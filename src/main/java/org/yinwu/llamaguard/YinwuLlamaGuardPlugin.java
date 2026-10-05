package org.yinwu.llamaguard;

import net.yinwu.lib.plugin.YinwuPlugin;
import org.yinwu.llamaguard.command.LlamaGuardCommand;
import org.yinwu.llamaguard.config.LlamaGuardConfig;
import org.yinwu.llamaguard.data.LlamaStatsStore;
import org.yinwu.llamaguard.defense.LlamaDefenseManager;
import org.yinwu.llamaguard.listener.DefenseListener;

/**
 * YinwuLlamaGuard —— 玩家 32 格内的羊驼会自动攻击范围内的幻翼。
 *
 * <p>设计要点（详见 PLAN.md）：
 * · 参战判定以【玩家】为中心 32 格；搜索幻翼以【羊驼】为中心（水平 24 / 垂直 48）
 * · 原版攻击机制完整保留（setTarget + RangedAttackGoal），插件只"额外补吐"以提高频率，不改伤害
 * · 全部实体操作都在该实体自己的区域线程上执行（Folia），只用 SchedulerUtil 调度
 */
public class YinwuLlamaGuardPlugin extends YinwuPlugin {

    private LlamaGuardConfig config;
    private LlamaStatsStore stats;
    private LlamaDefenseManager defense;

    @Override
    public String name() {
        return "YinwuLlamaGuard";
    }

    @Override
    public void enable() {
        config = new LlamaGuardConfig(this);
        config.reload();

        if (!config.enabled()) {
            getLogger().info(name() + " 已在 config.yml 中禁用（enabled: false）");
            return;
        }

        stats = new LlamaStatsStore(this);
        if (config.statsEnabled()) {
            stats.load();
        }

        defense = new LlamaDefenseManager(this, config, stats);
        getServer().getPluginManager().registerEvents(new DefenseListener(this, config, stats), this);

        LlamaGuardCommand command = new LlamaGuardCommand(this, config, stats, defense);
        if (getCommand("yinwullama") != null) {
            getCommand("yinwullama").setExecutor(command);
            getCommand("yinwullama").setTabCompleter(command);
        } else {
            getLogger().warning("plugin.yml 里没有 yinwullama 命令，命令功能不可用");
        }

        defense.start();
        if (config.statsEnabled()) {
            stats.startAutoSave(config.saveIntervalSeconds());
        }

        getLogger().info(name() + " 已启用"
                + "：玩家半径=" + config.playerRadius()
                + "，搜索=水平 " + config.targetRadiusHorizontal() + " / 垂直 " + config.targetRadiusVertical()
                + "，模式=" + config.combatMode()
                + "，额外发射冷却=" + config.extraSpitCooldownTicks() + " tick"
                + "，全局上限=" + (config.maxSpitsPerSecond() == 0 ? "不限" : config.maxSpitsPerSecond() + "/秒")
                + (isFolia() ? "（Folia 区域线程）" : "（Paper 主线程）"));
    }

    @Override
    public void disable() {
        if (defense != null) {
            defense.stop();
        }
        if (stats != null) {
            stats.stopAutoSave();
            if (config != null && config.statsEnabled()) {
                stats.saveNow();
            }
        }
    }

    /** 命令 /yinwullama reload 调用。 */
    public void reloadAll() {
        config.reload();
        if (defense != null) {
            defense.restart(config.checkIntervalTicks());
        }
        if (stats != null) {
            stats.stopAutoSave();
            if (config.statsEnabled()) {
                stats.load();
                stats.startAutoSave(config.saveIntervalSeconds());
            }
        }
    }

    public LlamaGuardConfig config() {
        return config;
    }

    public LlamaStatsStore stats() {
        return stats;
    }

    public LlamaDefenseManager defense() {
        return defense;
    }
}
