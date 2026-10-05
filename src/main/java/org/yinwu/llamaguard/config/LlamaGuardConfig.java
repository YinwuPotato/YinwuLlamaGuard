package org.yinwu.llamaguard.config;

import net.yinwu.lib.config.BaseConfigManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 配置读取。所有键在 {@link #reload()} 里缓存一次，读取走并发缓存（线程安全）。
 *
 * <p>两个半径的中心不同，别搞混：
 * · player-radius —— 以【玩家】为中心，决定哪些羊驼参战
 * · target-radius-* —— 以【羊驼】为中心，决定这只羊驼打多远内的幻翼
 */
public class LlamaGuardConfig extends BaseConfigManager {

    /** 消息键（缓存用）。 */
    private static final String[] MESSAGE_KEYS = {
            "prefix", "no-permission", "no-llama", "toggled-on", "toggled-off",
            "stats", "stats-empty", "reloaded", "purged", "notify-owner"
    };

    public LlamaGuardConfig(JavaPlugin plugin) {
        super(plugin);
    }

    @Override
    public void reload() {
        super.reload();

        cache("enabled", raw().getBoolean("enabled", true));

        cache("defense.player-radius", raw().getDouble("defense.player-radius", 32.0));
        cache("defense.use-sphere-check", raw().getBoolean("defense.use-sphere-check", false));
        cache("defense.target-radius-horizontal", raw().getDouble("defense.target-radius-horizontal", 24.0));
        cache("defense.target-radius-vertical", raw().getDouble("defense.target-radius-vertical", 48.0));
        // 注意：这两个键用 getLong 读取，缓存时必须按 long 存 ——
        // 库的 BaseConfigManager.get(p,f) 是 (T) 强转，存 Integer 再按 Long 读会 ClassCastException
        cache("defense.check-interval-ticks", raw().getLong("defense.check-interval-ticks", 5L));
        cache("defense.only-tamed", raw().getBoolean("defense.only-tamed", false));
        cache("defense.include-trader-llamas", raw().getBoolean("defense.include-trader-llamas", true));
        cache("defense.keep-vanilla-targets", raw().getBoolean("defense.keep-vanilla-targets", true));
        cache("defense.clear-when-no-phantom", raw().getBoolean("defense.clear-when-no-phantom", false));
        cache("defense.max-llamas-per-player", raw().getInt("defense.max-llamas-per-player", 0));

        cache("combat.mode", raw().getString("combat.mode", "hybrid"));
        cache("combat.spit-damage-multiplier", raw().getDouble("combat.spit-damage-multiplier", 1.0));
        cache("combat.bonus-damage", raw().getDouble("combat.bonus-damage", 0.0));
        cache("combat.fire-ticks", raw().getInt("combat.fire-ticks", 0));
        cache("combat.max-spits-per-second", raw().getInt("combat.max-spits-per-second", 200));
        cache("combat.extra-spit.cooldown-ticks", raw().getLong("combat.extra-spit.cooldown-ticks", 5L));
        cache("combat.extra-spit.speed", raw().getDouble("combat.extra-spit.speed", 1.5));
        cache("combat.extra-spit.inaccuracy", raw().getDouble("combat.extra-spit.inaccuracy", 0.0));
        cache("combat.extra-spit.lead-ticks", raw().getInt("combat.extra-spit.lead-ticks", 10));
        cache("combat.extra-spit.max-distance", raw().getDouble("combat.extra-spit.max-distance", 24.0));
        cache("combat.extra-spit.play-sound", raw().getBoolean("combat.extra-spit.play-sound", true));

        cache("stats.enabled", raw().getBoolean("stats.enabled", true));
        cache("stats.notify-owner", raw().getBoolean("stats.notify-owner", false));
        cache("stats.save-interval-seconds", raw().getInt("stats.save-interval-seconds", 300));
        cache("stats.purge-after-days", raw().getInt("stats.purge-after-days", 30));

        for (String key : MESSAGE_KEYS) {
            cache("messages." + key, raw().getString("messages." + key, ""));
        }
    }

    // ---- 总开关 ----

    public boolean enabled() {
        return getBoolean("enabled");
    }

    // ---- 参战判定与搜索 ----

    /** 以玩家为中心：羊驼进入防卫模式的距离。 */
    public double playerRadius() {
        return Math.max(1.0, getDouble("defense.player-radius"));
    }

    public boolean useSphereCheck() {
        return getBoolean("defense.use-sphere-check");
    }

    /** 以羊驼为中心：搜索幻翼的水平半径。 */
    public double targetRadiusHorizontal() {
        return Math.max(1.0, getDouble("defense.target-radius-horizontal"));
    }

    /** 以羊驼为中心：搜索幻翼的垂直半径（幻翼在高空，通常比水平大）。 */
    public double targetRadiusVertical() {
        return Math.max(1.0, getDouble("defense.target-radius-vertical"));
    }

    /** 扫描间隔，规范要求调度延迟 ≥ 1 tick。 */
    public long checkIntervalTicks() {
        return Math.max(1L, getLong("defense.check-interval-ticks"));
    }

    public boolean onlyTamed() {
        return getBoolean("defense.only-tamed");
    }

    public boolean includeTraderLlamas() {
        return getBoolean("defense.include-trader-llamas");
    }

    public boolean keepVanillaTargets() {
        return getBoolean("defense.keep-vanilla-targets");
    }

    public boolean clearWhenNoPhantom() {
        return getBoolean("defense.clear-when-no-phantom");
    }

    /** 0 = 不限。 */
    public int maxLlamasPerPlayer() {
        return Math.max(0, getInt("defense.max-llamas-per-player"));
    }

    // ---- 战斗 ----

    public String combatMode() {
        String mode = getString("combat.mode");
        return mode == null ? "hybrid" : mode.toLowerCase();
    }

    /** 原版机制（setTarget + RangedAttackGoal）是否保留。 */
    public boolean modeUsesVanillaAi() {
        String mode = combatMode();
        return mode.equals("hybrid") || mode.equals("vanilla-ai");
    }

    /** 插件是否额外补发投射物。 */
    public boolean modeUsesExtraSpit() {
        String mode = combatMode();
        return mode.equals("hybrid") || mode.equals("extra-spit");
    }

    public double spitDamageMultiplier() {
        return Math.max(0.0, getDouble("combat.spit-damage-multiplier"));
    }

    public double bonusDamage() {
        return Math.max(0.0, getDouble("combat.bonus-damage"));
    }

    public int fireTicks() {
        return Math.max(0, getInt("combat.fire-ticks"));
    }

    public int maxSpitsPerSecond() {
        return Math.max(0, getInt("combat.max-spits-per-second"));
    }

    public long extraSpitCooldownTicks() {
        return Math.max(1L, getLong("combat.extra-spit.cooldown-ticks"));
    }

    /** 冷却换算成毫秒（用于按时间戳做冷却，避免依赖 tick 计数）。 */
    public long extraSpitCooldownMillis() {
        return extraSpitCooldownTicks() * 50L;
    }

    public double extraSpitSpeed() {
        return Math.max(0.1, getDouble("combat.extra-spit.speed"));
    }

    public double extraSpitInaccuracy() {
        return Math.max(0.0, getDouble("combat.extra-spit.inaccuracy"));
    }

    public int extraSpitLeadTicks() {
        return Math.max(0, getInt("combat.extra-spit.lead-ticks"));
    }

    public double extraSpitMaxDistance() {
        return Math.max(1.0, getDouble("combat.extra-spit.max-distance"));
    }

    public boolean extraSpitPlaySound() {
        return getBoolean("combat.extra-spit.play-sound");
    }

    // ---- 统计 ----

    public boolean statsEnabled() {
        return getBoolean("stats.enabled");
    }

    public boolean notifyOwner() {
        return getBoolean("stats.notify-owner");
    }

    public int saveIntervalSeconds() {
        return Math.max(1, getInt("stats.save-interval-seconds"));
    }

    public int purgeAfterDays() {
        return Math.max(0, getInt("stats.purge-after-days"));
    }

    // ---- 消息 ----

    public String message(String key) {
        String value = getString("messages." + key);
        return value == null ? "" : value;
    }

    /** 把 & 颜色代码转成 §，并替换 %k% 占位符。 */
    public String format(String key, String... replacements) {
        String text = message(key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return text.replace('&', '§');
    }

    public String prefix() {
        return message("prefix").replace('&', '§');
    }
}
