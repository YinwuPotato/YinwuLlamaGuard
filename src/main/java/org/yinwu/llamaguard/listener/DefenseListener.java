package org.yinwu.llamaguard.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.LlamaSpit;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.yinwu.llamaguard.YinwuLlamaGuardPlugin;
import org.yinwu.llamaguard.config.LlamaGuardConfig;
import org.yinwu.llamaguard.data.LlamaStatsStore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统计与伤害调优。
 *
 * <p>默认**完全不改动原版数值**（倍率 1.0 / 附加 0 / 不点燃），只有配置被主动改大才会介入，
 * 这样"不影响羊驼默认吐口水机制和伤害"这条要求是默认成立的。
 */
public class DefenseListener implements Listener {

    /** 击杀归属的有效期：这一发打中后多久内幻翼死亡还算这只羊驼的。 */
    private static final long ATTRIBUTION_WINDOW_MILLIS = 10_000L;

    /** 记录幻翼最近一次是被哪只羊驼吐中的。 */
    private record Hit(UUID llamaId, long at) {
    }

    private final YinwuLlamaGuardPlugin plugin;
    private final LlamaGuardConfig config;
    private final LlamaStatsStore stats;
    private final Map<UUID, Hit> lastHits = new ConcurrentHashMap<>();

    public DefenseListener(YinwuLlamaGuardPlugin plugin, LlamaGuardConfig config, LlamaStatsStore stats) {
        this.plugin = plugin;
        this.config = config;
        this.stats = stats;
    }

    /** 发射计数：原版机制发射的与插件补发的都会走到这里（单一计数来源）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof LlamaSpit spit)) {
            return;
        }
        ProjectileSource shooter = spit.getShooter();
        if (!(shooter instanceof Llama llama)) {
            return;
        }
        LlamaStatsStore.Entry entry = stats.entry(llama.getUniqueId());
        entry.spits++;
        entry.lastSeen = System.currentTimeMillis();
        stats.markDirty();
    }

    /** 命中幻翼：记录归属 + 可选的伤害调优（默认不改任何数值）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof LlamaSpit spit)) {
            return;
        }
        ProjectileSource shooter = spit.getShooter();
        if (!(shooter instanceof Llama llama)) {
            return;
        }
        Entity victim = event.getEntity();
        if (!(victim instanceof Phantom)) {
            return;
        }

        lastHits.put(victim.getUniqueId(), new Hit(llama.getUniqueId(), System.currentTimeMillis()));

        double multiplier = config.spitDamageMultiplier();
        double bonus = config.bonusDamage();
        if (multiplier != 1.0 || bonus > 0.0) {
            event.setDamage(event.getDamage() * multiplier + bonus);
        }
        if (config.fireTicks() > 0) {
            victim.setFireTicks(config.fireTicks());
        }
    }

    /** 幻翼死亡：归属到最近吐中它的羊驼，统计击杀数。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Phantom phantom)) {
            return;
        }
        Hit hit = lastHits.remove(phantom.getUniqueId());
        if (hit == null || System.currentTimeMillis() - hit.at() > ATTRIBUTION_WINDOW_MILLIS) {
            return;
        }

        LlamaStatsStore.Entry entry = stats.entry(hit.llamaId());
        entry.kills++;
        entry.lastSeen = System.currentTimeMillis();
        stats.markDirty();

        if (config.notifyOwner()) {
            notifyOwner(hit.llamaId());
        }
        plugin.debug("幻翼被羊驼 " + hit.llamaId() + " 击杀（累计 " + entry.kills + "）");
    }

    /** 给羊驼主人发一条提示（主人 UUID 由管理器在羊驼线程里缓存，避免跨区域取实体）。 */
    private void notifyOwner(UUID llamaId) {
        if (plugin.defense() == null) {
            return;
        }
        UUID ownerId = plugin.defense().ownerOf(llamaId);
        if (ownerId == null) {
            return;
        }
        Player owner = plugin.getServer().getPlayer(ownerId);
        if (owner == null) {
            return;
        }
        String message = config.prefix() + config.message("notify-owner").replace('&', '§');
        plugin.scheduler().atEntity(owner, () -> {
            if (owner.isOnline()) {
                owner.sendMessage(message);
            }
        });
    }
}
