package org.yinwu.llamaguard.defense;

import net.yinwu.lib.scheduler.SchedulerUtil;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.LlamaSpit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.TraderLlama;
import org.bukkit.util.Vector;
import org.yinwu.llamaguard.YinwuLlamaGuardPlugin;
import org.yinwu.llamaguard.config.LlamaGuardConfig;
import org.yinwu.llamaguard.data.LlamaStatsStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 防卫调度核心。线程模型（Folia 关键，别改错）：
 *
 * <pre>
 * 全局定时器（初始延迟 1L，周期 check-interval-ticks）
 *   └─ 只取在线玩家快照并派发
 *      └─ atEntity(玩家)  ← 玩家所属区域线程
 *         ├─ 取附近实体，筛出羊驼、判断有没有幻翼（没有就直接返回）
 *         └─ atEntity(羊驼) ← 羊驼所属区域线程
 *            ├─ 在羊驼自己的线程里取附近实体 → 选最近的幻翼（不跨区域读实体）
 *            ├─ setTarget（保留原版机制）
 *            └─ 额外补发 LlamaSpit（带预判），受冷却与全局上限约束
 * </pre>
 *
 * 绝不跨区域调用 setTarget / getNearbyEntities，也不在别的线程里碰实体。
 */
public class LlamaDefenseManager {

    private final YinwuLlamaGuardPlugin plugin;
    private final LlamaGuardConfig config;
    private final LlamaStatsStore stats;

    private Object task;

    /** 每只羊驼上次额外发射的时间戳（毫秒）。 */
    private final Map<UUID, Long> lastExtraSpit = new ConcurrentHashMap<>();

    /** 羊驼 → 主人 UUID。在羊驼自己的区域线程里刷新，供击杀提示用（避免跨区域取实体）。 */
    private final Map<UUID, UUID> llamaOwners = new ConcurrentHashMap<>();

    /** 当前这一秒内插件发射了多少发（全局限流用）。 */
    private final AtomicInteger spitsThisWindow = new AtomicInteger();
    private volatile long windowStart = System.currentTimeMillis();
    private volatile int rateLimitedTotal;
    private volatile int extraSpitsTotal;

    /** 上一轮扫描的统计（供 /ylg info）。 */
    private final AtomicInteger handledLlamas = new AtomicInteger();
    private volatile int lastHandled;
    private volatile long lastDispatchMicros;

    public LlamaDefenseManager(YinwuLlamaGuardPlugin plugin, LlamaGuardConfig config, LlamaStatsStore stats) {
        this.plugin = plugin;
        this.config = config;
        this.stats = stats;
    }

    // ---- 生命周期 ----

    public void start() {
        start(config.checkIntervalTicks());
    }

    public void restart(long intervalTicks) {
        stop();
        start(intervalTicks);
    }

    private void start(long intervalTicks) {
        long period = Math.max(1L, intervalTicks);
        task = plugin.scheduler().globalTimer(this::tick, 1L, period);
        plugin.debug("防卫扫描已启动：周期 " + period + " tick");
    }

    public void stop() {
        if (task != null) {
            SchedulerUtil.cancel(task);
            task = null;
            plugin.debug("防卫扫描已停止");
        }
    }

    // ---- 全局区域线程 ----

    private void tick() {
        long begin = System.nanoTime();

        // 上一轮派发出去的羊驼处理量（异步执行，取下一轮开头读到的值）
        lastHandled = handledLlamas.get();

        long now = System.currentTimeMillis();
        if (now - windowStart >= 1000L) {
            windowStart = now;
            spitsThisWindow.set(0);
        }

        handledLlamas.set(0);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            plugin.scheduler().atEntity(player, () -> scanForPlayer(player));
        }

        lastDispatchMicros = (System.nanoTime() - begin) / 1000L;
    }

    // ---- 玩家所属区域线程 ----

    private void scanForPlayer(Player player) {
        if (!player.isOnline() || player.isDead()) {
            return;
        }

        double radius = config.playerRadius();
        List<Entity> nearby = player.getNearbyEntities(radius, radius, radius);

        boolean anyPhantom = false;
        List<Llama> llamas = new ArrayList<>();
        for (Entity entity : nearby) {
            if (entity instanceof Phantom) {
                anyPhantom = true;
            } else if (entity instanceof Llama llama && isEligible(llama, player, radius)) {
                llamas.add(llama);
            }
        }

        // B8：附近没有幻翼就什么都不做（不做无用扫描、不发投射物）
        if (!anyPhantom || llamas.isEmpty()) {
            return;
        }

        int max = config.maxLlamasPerPlayer();
        int count = 0;
        for (Llama llama : llamas) {
            if (max > 0 && count >= max) {
                break;
            }
            count++;
            handledLlamas.incrementAndGet();
            plugin.scheduler().atEntity(llama, () -> handleLlama(llama));
        }
    }

    /** 参战条件：类型、驯服、以及（可选的）真实球体距离。 */
    private boolean isEligible(Llama llama, Player player, double radius) {
        if (llama.isDead() || !llama.isValid()) {
            return false;
        }
        if (llama instanceof TraderLlama && !config.includeTraderLlamas()) {
            return false;
        }
        if (config.onlyTamed() && !llama.isTamed()) {
            return false;
        }
        if (config.useSphereCheck()
                && llama.getWorld().equals(player.getWorld())
                && llama.getLocation().distance(player.getLocation()) > radius) {
            return false;
        }
        return true;
    }

    // ---- 羊驼所属区域线程 ----

    private void handleLlama(Llama llama) {
        if (llama.isDead() || !llama.isValid()) {
            return;
        }

        LlamaStatsStore.Entry entry = stats.entry(llama.getUniqueId());
        if (!entry.enabled) {
            return;
        }

        // 顺手刷新主人缓存（此刻在羊驼自己的线程上，读它是安全的）
        if (llama instanceof Tameable tameable && tameable.getOwner() instanceof Player owner) {
            llamaOwners.put(llama.getUniqueId(), owner.getUniqueId());
        } else {
            llamaOwners.remove(llama.getUniqueId());
        }

        Phantom target = findNearestPhantom(llama);
        if (target == null) {
            if (config.clearWhenNoPhantom() && llama.getTarget() instanceof Phantom) {
                llama.setTarget(null);
            }
            return;
        }

        entry.lastSeen = System.currentTimeMillis();

        // 保留原版机制：羊驼正忙着打别的东西（比如狼）时不抢
        if (config.modeUsesVanillaAi()) {
            LivingEntity current = llama.getTarget();
            if (config.keepVanillaTargets() && current != null && !(current instanceof Phantom)
                    && !current.isDead() && current.isValid()) {
                return;
            }
            llama.setTarget(target);
        }

        if (config.modeUsesExtraSpit()) {
            maybeExtraSpit(llama, target);
        }
    }

    /** 在自己（羊驼）的区域线程里查询附近实体并选最近的幻翼。 */
    private Phantom findNearestPhantom(Llama llama) {
        double h = config.targetRadiusHorizontal();
        double v = config.targetRadiusVertical();
        Location origin = llama.getLocation();

        Phantom best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : llama.getNearbyEntities(h, v, h)) {
            if (!(entity instanceof Phantom phantom) || phantom.isDead() || !phantom.isValid()) {
                continue;
            }
            double distance = origin.distanceSquared(phantom.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = phantom;
            }
        }
        return best;
    }

    /**
     * 额外补发一发 LlamaSpit —— 只提高频率，不改变伤害（投射物走原版逻辑）。
     * 距离、冷却、全局限流三道闸门。
     */
    private void maybeExtraSpit(Llama llama, Phantom target) {
        UUID id = llama.getUniqueId();
        long now = System.currentTimeMillis();

        Long last = lastExtraSpit.get(id);
        if (last != null && now - last < config.extraSpitCooldownMillis()) {
            return;
        }

        if (config.maxSpitsPerSecond() > 0 && spitsThisWindow.get() >= config.maxSpitsPerSecond()) {
            rateLimitedTotal++;
            return;
        }

        Location eye = llama.getEyeLocation();
        Location aimPoint = target.getLocation().add(0.0, target.getHeight() * 0.5, 0.0);

        double dx = aimPoint.getX() - eye.getX();
        double dz = aimPoint.getZ() - eye.getZ();
        if (Math.sqrt(dx * dx + dz * dz) > config.extraSpitMaxDistance()) {
            return;
        }

        // 预判：按幻翼当前速度外推 lead-ticks
        Vector direction = aimPoint.toVector()
                .add(target.getVelocity().multiply(config.extraSpitLeadTicks()))
                .subtract(eye.toVector());
        if (direction.lengthSquared() < 1.0E-6) {
            return;
        }

        double inaccuracy = config.extraSpitInaccuracy();
        if (inaccuracy > 0.0) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            direction.add(new Vector(
                    random.nextGaussian() * inaccuracy * 0.01,
                    random.nextGaussian() * inaccuracy * 0.01,
                    random.nextGaussian() * inaccuracy * 0.01));
        }
        direction.normalize().multiply(config.extraSpitSpeed());

        LlamaSpit spit = llama.launchProjectile(LlamaSpit.class, direction);
        if (spit == null) {
            return;
        }

        lastExtraSpit.put(id, now);
        spitsThisWindow.incrementAndGet();
        extraSpitsTotal++;

        if (config.extraSpitPlaySound()) {
            llama.getWorld().playSound(eye, Sound.ENTITY_LLAMA_SPIT, 1.0f, 1.0f);
        }
        plugin.debug("额外发射：羊驼 " + id + " → 幻翼 " + target.getUniqueId()
                + "（本轮已发 " + spitsThisWindow.get() + " 发）");
    }

    // ---- 供 /ylg info ----

    public int lastHandledLlamas() {
        return lastHandled;
    }

    public long lastDispatchMicros() {
        return lastDispatchMicros;
    }

    public int extraSpitsTotal() {
        return extraSpitsTotal;
    }

    public int rateLimitedTotal() {
        return rateLimitedTotal;
    }

    public int currentSpitRate() {
        return spitsThisWindow.get();
    }

    public int trackedLlamas() {
        return lastExtraSpit.size();
    }

    /** 击杀提示用：取这只羊驼的主人 UUID（可能为 null）。 */
    public UUID ownerOf(UUID llamaId) {
        return llamaOwners.get(llamaId);
    }
}
