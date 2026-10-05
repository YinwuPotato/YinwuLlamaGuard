package org.yinwu.llamaguard.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.yinwu.llamaguard.YinwuLlamaGuardPlugin;
import org.yinwu.llamaguard.config.LlamaGuardConfig;
import org.yinwu.llamaguard.data.LlamaStatsStore;
import org.yinwu.llamaguard.defense.LlamaDefenseManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 命令：/yinwullama &lt;help|stats|toggle|info|reload|purge&gt;（别名 /ylg）
 *
 * <p>命令回调运行在发送者自己的区域线程上；涉及实体读取都在这里直接做是安全的。
 */
public class LlamaGuardCommand implements CommandExecutor, TabCompleter {

    /** 用来看/选羊驼的射线距离。 */
    private static final int TARGET_DISTANCE = 10;

    private final YinwuLlamaGuardPlugin plugin;
    private final LlamaGuardConfig config;
    private final LlamaStatsStore stats;
    private final LlamaDefenseManager defense;

    public LlamaGuardCommand(YinwuLlamaGuardPlugin plugin, LlamaGuardConfig config,
                             LlamaStatsStore stats, LlamaDefenseManager defense) {
        this.plugin = plugin;
        this.config = config;
        this.stats = stats;
        this.defense = defense;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "toggle" -> toggle(sender);
            case "stats" -> stats(sender);
            case "info" -> info(sender);
            case "reload" -> reload(sender);
            case "purge" -> purge(sender, args);
            default -> sender.sendMessage(config.prefix() + "§c未知子命令，用 §e/" + label + " help §c查看");
        }
        return true;
    }

    // ---- 子命令 ----

    private void toggle(CommandSender sender) {
        Llama llama = lookingAtLlama(sender);
        if (llama == null) {
            return;
        }
        LlamaStatsStore.Entry entry = stats.entry(llama.getUniqueId());
        entry.enabled = !entry.enabled;
        entry.lastSeen = System.currentTimeMillis();
        stats.markDirty();

        String message = entry.enabled ? config.message("toggled-on") : config.message("toggled-off");
        sender.sendMessage(config.prefix() + message.replace('&', '§'));
        plugin.debug("羊驼 " + llama.getUniqueId() + " 的防卫开关 → " + entry.enabled);
    }

    private void stats(CommandSender sender) {
        Llama llama = lookingAtLlama(sender);
        if (llama == null) {
            return;
        }
        LlamaStatsStore.Entry entry = stats.peek(llama.getUniqueId());
        if (entry == null) {
            sender.sendMessage(config.prefix() + config.message("stats-empty").replace('&', '§'));
            return;
        }
        sender.sendMessage(config.prefix() + config.format("stats",
                "%spits%", String.valueOf(entry.spits),
                "%kills%", String.valueOf(entry.kills)));
        sender.sendMessage("§7（自动防卫：" + (entry.enabled ? "§a开" : "§c关") + "§7）");
    }

    private void info(CommandSender sender) {
        if (!sender.hasPermission("yinwu.llamaguard.admin")) {
            sender.sendMessage(config.prefix() + config.message("no-permission").replace('&', '§'));
            return;
        }
        sender.sendMessage("§8§m                                                  ");
        sender.sendMessage("§eYinwuLlamaGuard §7v" + plugin.getPluginMeta().getVersion());
        sender.sendMessage("§7玩家半径 §f" + config.playerRadius() + " §8(中心=玩家)");
        sender.sendMessage("§7搜索幻翼 §f水平 " + config.targetRadiusHorizontal()
                + " §7/ §f垂直 " + config.targetRadiusVertical() + " §8(中心=羊驼)");
        sender.sendMessage("§7扫描间隔 §f" + config.checkIntervalTicks() + " tick"
                + "§7，模式 §f" + config.combatMode());
        sender.sendMessage("§7额外发射冷却 §f" + config.extraSpitCooldownTicks() + " tick"
                + "§7，全局上限 §f" + (config.maxSpitsPerSecond() == 0 ? "不限" : config.maxSpitsPerSecond() + "/秒"));
        sender.sendMessage("§7本秒已发射 §f" + defense.currentSpitRate()
                + "§7，累计 §f" + defense.extraSpitsTotal()
                + "§7，被限流 §f" + defense.rateLimitedTotal());
        sender.sendMessage("§7上轮参战羊驼 §f" + defense.lastHandledLlamas()
                + "§7，有冷却记录的 §f" + defense.trackedLlamas());
        sender.sendMessage("§7本轮派发耗时 §f" + defense.lastDispatchMicros() + " µs"
                + "§7，记录数 §f" + stats.size());
        sender.sendMessage("§8§m                                                  ");
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("yinwu.llamaguard.admin")) {
            sender.sendMessage(config.prefix() + config.message("no-permission").replace('&', '§'));
            return;
        }
        plugin.scheduler().global(plugin::reloadAll);
        sender.sendMessage(config.prefix() + config.message("reloaded").replace('&', '§'));
    }

    private void purge(CommandSender sender, String[] args) {
        if (!sender.hasPermission("yinwu.llamaguard.admin")) {
            sender.sendMessage(config.prefix() + config.message("no-permission").replace('&', '§'));
            return;
        }
        int days = config.purgeAfterDays();
        if (args.length >= 2) {
            try {
                days = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(config.prefix() + "§c天数必须是数字，例如 §e/ylg purge 30");
                return;
            }
        }
        if (days <= 0) {
            sender.sendMessage(config.prefix() + "§c天数必须大于 0");
            return;
        }
        int removed = stats.purge(days);
        stats.saveNow();
        sender.sendMessage(config.prefix() + config.format("purged", "%count%", String.valueOf(removed)));
    }

    // ---- 工具 ----

    /** 取发送者正看着的羊驼；失败时已给出提示并返回 null。 */
    private Llama lookingAtLlama(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(config.prefix() + "§c该子命令只能由玩家执行");
            return null;
        }
        Entity target = player.getTargetEntity(TARGET_DISTANCE);
        if (!(target instanceof Llama llama)) {
            sender.sendMessage(config.prefix() + config.message("no-llama").replace('&', '§'));
            return null;
        }
        return llama;
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage("§8§m                                                  ");
        sender.sendMessage("§e/" + label + " help   §7查看帮助");
        sender.sendMessage("§e/" + label + " stats  §7看着羊驼执行：查看它的战绩");
        sender.sendMessage("§e/" + label + " toggle §7看着羊驼执行：开关它的自动防卫");
        if (sender.hasPermission("yinwu.llamaguard.admin")) {
            sender.sendMessage("§e/" + label + " info   §7运行状态与当前配置");
            sender.sendMessage("§e/" + label + " reload §7重载配置与数据");
            sender.sendMessage("§e/" + label + " purge [天数] §7清理过期记录");
        }
        sender.sendMessage("§8§m                                                  ");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> subs = new ArrayList<>(List.of("help", "stats", "toggle"));
        if (sender.hasPermission("yinwu.llamaguard.admin")) {
            subs.add("info");
            subs.add("reload");
            subs.add("purge");
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String sub : subs) {
            if (sub.startsWith(prefix)) {
                out.add(sub);
            }
        }
        return out;
    }
}
