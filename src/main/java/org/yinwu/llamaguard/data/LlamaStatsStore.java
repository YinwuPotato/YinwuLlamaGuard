package org.yinwu.llamaguard.data;

import net.yinwu.lib.scheduler.SchedulerUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yinwu.llamaguard.YinwuLlamaGuardPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 羊驼统计与开关的持久化（data.yml）。
 *
 * <p>内存里用 ConcurrentHashMap（会被多个区域线程访问）；落盘走异步调度器，
 * 不在区域线程里做文件 IO。
 */
public class LlamaStatsStore {

    /** 一只羊驼的记录。 */
    public static final class Entry {
        public int spits;
        public int kills;
        public boolean enabled = true;
        public long lastSeen = System.currentTimeMillis();
    }

    private final YinwuLlamaGuardPlugin plugin;
    private final File file;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private Object saveTask;

    public LlamaStatsStore(YinwuLlamaGuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    /** 取记录，没有就新建。 */
    public Entry entry(UUID id) {
        return entries.computeIfAbsent(id, k -> new Entry());
    }

    /** 只读查询，可能为 null。 */
    public Entry peek(UUID id) {
        return entries.get(id);
    }

    public int size() {
        return entries.size();
    }

    public void markDirty() {
        dirty.set(true);
    }

    // ---- 读写 ----

    public void load() {
        entries.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yml.getConfigurationSection("llamas");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Entry entry = new Entry();
                entry.spits = section.getInt(key + ".spits", 0);
                entry.kills = section.getInt(key + ".kills", 0);
                entry.enabled = section.getBoolean(key + ".enabled", true);
                entry.lastSeen = section.getLong(key + ".last-seen", System.currentTimeMillis());
                entries.put(id, entry);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("data.yml 里有非法的羊驼 UUID，已忽略: " + key);
            }
        }
        plugin.getLogger().info("已载入 " + entries.size() + " 条羊驼记录");
    }

    public void saveNow() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录，data.yml 未保存");
            return;
        }
        try {
            YamlConfiguration yml = new YamlConfiguration();
            yml.set("version", 1);
            for (Map.Entry<UUID, Entry> pair : entries.entrySet()) {
                String base = "llamas." + pair.getKey();
                Entry entry = pair.getValue();
                yml.set(base + ".spits", entry.spits);
                yml.set(base + ".kills", entry.kills);
                yml.set(base + ".enabled", entry.enabled);
                yml.set(base + ".last-seen", entry.lastSeen);
            }
            yml.save(file);
            dirty.set(false);
            if (plugin.isDebug()) {
                plugin.debug("data.yml 已保存（" + entries.size() + " 条）");
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("保存 data.yml 失败: " + ex.getMessage());
        }
    }

    /** 定时异步落盘（只有脏了才写）。 */
    public void startAutoSave(int intervalSeconds) {
        stopAutoSave();
        long period = Math.max(1L, intervalSeconds) * 20L;
        saveTask = plugin.scheduler().globalTimer(
                () -> plugin.scheduler().async(this::saveIfDirty), 1L, period);
    }

    public void stopAutoSave() {
        if (saveTask != null) {
            SchedulerUtil.cancel(saveTask);
            saveTask = null;
        }
    }

    private void saveIfDirty() {
        if (dirty.get()) {
            saveNow();
        }
    }

    /** 清理超过 days 天没再出现的记录，返回清理条数。 */
    public int purge(int days) {
        if (days <= 0) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        int before = entries.size();
        entries.entrySet().removeIf(pair -> pair.getValue().lastSeen < cutoff);
        int removed = before - entries.size();
        if (removed > 0) {
            markDirty();
        }
        return removed;
    }
}
