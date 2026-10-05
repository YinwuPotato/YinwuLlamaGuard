# YinwuLlamaGuard — 羊驼防卫

## 项目信息
- **功能**: 玩家 32 格范围内的羊驼，自动攻击范围内的幻翼
- **技术栈**: Java 21, Maven, Paper API 1.21.4
- **打包**: `mvn -pl YinwuLlamaGuard -am clean package` → `target/YinwuLlamaGuard-<version>.jar`
- **本机编译校验**: `自动化\compile-check.bat YinwuLlamaGuard`（父 POM 不在时的替代方案）
- **Folia 兼容**: 是
- **前置**: [YinwuPluginLib](https://github.com/YinwuPotato/YinwuPluginLib)（必需）
- **仓库**: [YinwuPotato/YinwuLlamaGuard](https://github.com/YinwuPotato/YinwuLlamaGuard)（待建）

## 三条不可动摇的设计约束（改代码前先读）
1. **两个半径的中心不同**：参战判定以【玩家】为中心 32 格；搜索幻翼以【羊驼】为中心（水平 24 / 垂直 48）。别写反。
2. **不改原版数值**：原版 `setTarget` + `RangedAttackGoal` 完整保留；伤害保持原版（约 1 点）。插件只是**额外补发**投射物来提高频率。`spit-damage-multiplier` / `bonus-damage` 默认 1.0 / 0.0，不主动改。
3. **线程模型**：全局定时器 → `atEntity(玩家)` 派发 → `atEntity(羊驼)` 内查询与操作。**绝不跨区域** `setTarget` / `getNearbyEntities` / 读写实体；落盘走 `async`。

## 共享规则（适用于所有 Yinwu 插件）

### 调度规范（Folia）
- ✅ 用 `plugin.scheduler()`（即 `SchedulerUtil`）：`global` / `atEntity` / `atRegion` / `atChunk` / `async`
- ✅ 也可以直接用 `RegionScheduler` / `GlobalRegionScheduler` / `EntityScheduler`
- ❌ 禁止 `Bukkit.getScheduler()`、`runTask`、`runTaskAsynchronously`
- ❌ 初始延迟禁止为 `0L`（必须 ≥ `1L`）

### 生命周期
- 主类 `extends net.yinwu.lib.plugin.YinwuPlugin`，只实现 `enable()` / `disable()` / `name()`
- 基类已负责：`saveDefaultConfig` → `reloadConfig` → Folia 检测 → `SchedulerUtil.init` → 禁用时取消全局任务 + 解注册监听器

### 配置
- 继承 `net.yinwu.lib.config.BaseConfigManager`，在 `reload()` 里对每个键调用 `cache(path, value)`
- 读取用 `getInt/getDouble/getBoolean/getString/getLong`（走 `ConcurrentHashMap`，线程安全）
- 数值一律做兜底（`Math.max(1, …)`），别让配置写出 0 或负数把调度搞崩

### 代码风格
- 注释极简，无废话
- 仅使用 Paper / Folia API，禁止 NMS
