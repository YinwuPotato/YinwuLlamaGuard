# YinwuLlamaGuard —— 设计计划（实现前的方案，未写代码）

> 功能：**让玩家 32 格范围内的羊驼自动攻击幻翼。**
> 目标平台：Sur（Paper 1.21.4 / Folia 兼容）｜前置：YinwuPluginLib｜持久化：YAML｜界面：无｜软依赖：无

---

## 1. 行为定义（可验收，不含歧义）

| 编号 | 规则 |
|---|---|
| B1 | 玩家 P 在线时，与其距离 ≤ `player-radius`（默认 **32.0** 格）的羊驼进入**防卫模式**；**所有**羊驼都算（野生 / 已驯服 / 商队羊驼） |
| B2 | 每只羊驼**各自独立**判定：在水平 **24** 格、垂直 **48** 格内寻找**距离自己最近**的幻翼作为目标（不与其他羊驼共享目标、不做统一分配） |
| B3 | **原版攻击机制完整保留**：`llama.setTarget(phantom)` 让原版 `RangedAttackGoal` 照常工作，羊驼该吐就吐、该有的音效动画都在，**伤害仍是原版（约 1 点）** |
| B3b | **额外发射（提高频率）**：插件按更短冷却（默认 **5 tick = 0.25 秒**）直接 `launchProjectile(LlamaSpit.class, 预判方向)` 再补一发 —— 投射物走原版逻辑，**伤害仍是原版**，只是"吐得更勤" |
| B4 | 玩家离开 32 格 → 该羊驼**退出防卫模式**；插件不主动清空它已有的非幻翼目标（可配） |
| B5 | 羊驼原有行为（被狼/玩家激怒后回击、商队羊驼跟随商人）**不被破坏**；原版攻击间隔（40 tick）也不改 |
| B6 | 每只羊驼可被单独开关（`/ylg toggle`），状态**持久化**，重启后保留 |
| B7 | 统计每只羊驼的 `spits`（发射次数）与 `kills`（击杀幻翼数），存 `data.yml` |
| B8 | 无幻翼时不产生任何额外动作（不做无用扫描、不发投射物） |
| B9 | **全局发射上限**：所有羊驼合计每秒最多 `max-spits-per-second`（默认 **200**）发，超出则本轮丢弃 —— 防止多羊驼时投射物把 TPS 拖垮 |

### 两个半径的中心（必须分清，实现时容易搞混）

| 判定 | 中心 | 半径 | 作用 |
|---|---|---|---|
| 羊驼是否参战 | **玩家**（等价说法：羊驼 32 格内有玩家） | 32 格 | 决定"哪些羊驼进入防卫模式" |
| 打哪只幻翼 | **羊驼** ⬅ **不是玩家** | 水平 24 / 垂直 48 | 决定"这只羊驼打多远内的幻翼" |

- 两个判定的距离是**对称的**，所以"玩家 32 格内的羊驼"与"羊驼 32 格内有玩家"完全等价；
  但**搜索幻翼那一条必须以羊驼自身坐标为原点**，与玩家站哪无关。
- 扫描为何从玩家起步：Folia 没有全局实体列表，只能从某个实体的区域线程发起查询。
  迭代方向不影响判定结果，只影响性能（先按玩家剪枝，省掉大量羊驼判定）。

**默认不做**：不给羊驼加自定义寻路/AI 目标选择器、不改繁殖/驯服/商队逻辑、不改幻翼的生成与行为。

---

## 2. 命名与代码结构

```
Sur\YinwuLlamaGuard-羊驼防卫\
├─ pom.xml                     # parent YinwuPlugins:1.0.1 + paper-api + YinwuPluginLib
├─ README.md  CLAUDE.md  LICENSE(LGPL-3.0)  .gitignore
├─ PLAN.md                     # 本文件（实现完成后可删）
└─ src\main\
   ├─ java\org\yinwu\llamaguard\
   │  ├─ YinwuLlamaGuardPlugin.java      # extends YinwuPlugin；组装各组件
   │  ├─ config\LlamaGuardConfig.java    # extends BaseConfigManager
   │  ├─ command\LlamaGuardCommand.java  # /yinwullama（别名 /ylg）
   │  ├─ defense\LlamaDefenseManager.java# 核心：定时扫描 + 目标分配（Folia 线程模型见 §4）
   │  ├─ listener\DefenseListener.java   # 投射物/伤害/死亡事件：战斗调优 + 统计
   │  └─ data\LlamaStatsStore.java       # data.yml 读写（异步落盘）
   └─ resources\
      ├─ plugin.yml                      # main / api-version 1.21 / folia-supported / 命令 / 权限
      └─ config.yml                      # 全量配置（见 §3）
```

- **主类名**：`YinwuLlamaGuardPlugin`，`name()` 返回 `YinwuLlamaGuard`
- **包名**：`org.yinwu.llamaguard`（与 `org.yinwu.flightblock` 等一致）
- **命令**：`/yinwullama`，别名 `/ylg`
- **权限**：`yinwu.llamaguard.use`（默认 true）、`yinwu.llamaguard.admin`（默认 op）

---

## 3. 配置设计（`config.yml`）

```yaml
enabled: true                 # 总开关
debug: false                  # 基类 YinwuPlugin 读取

defense:
  player-radius: 32.0         # B1：玩家附近的羊驼才参战（立方体判定，见 use-sphere-check）
  use-sphere-check: false     # false = 立方体（Bukkit 原生，快）；true = 额外做一次真实距离过滤
  target-radius-horizontal: 24.0  # B2：羊驼搜索幻翼的水平半径
  target-radius-vertical: 48.0    # B2：垂直半径单独放大 —— 幻翼在高空，否则会出现"头顶打不到"
  check-interval-ticks: 5     # 扫描间隔（tick）；与额外发射冷却对齐
  only-tamed: false           # 已定：false（所有羊驼都参战）
  include-trader-llamas: true # 已定：true（商队羊驼也参战）
  keep-vanilla-targets: true  # 羊驼已有非幻翼目标（如狼）时不抢目标
  clear-when-no-phantom: false# 无幻翼时是否清空插件设置的幻翼目标
  max-llamas-per-player: 0    # 0 = 不限；限制每个玩家带动的参战羊驼数（性能保护）
  max-assign-per-tick: 0      # 0 = 不限；限制单轮 setTarget 次数

combat:
  mode: hybrid                # hybrid（默认）= 原版 AI 照常 + 插件额外补吐
                              # vanilla-ai    = 只让原版机制工作（不额外发射）
                              # extra-spit    = 只由插件发射（不建议，会改变观感）
  spit-damage-multiplier: 1.0 # 保持 1.0 = 完全不改原版 spit 伤害（已定）
  bonus-damage: 0.0           # 额外固定伤害（默认 0 = 不动平衡）
  fire-ticks: 0               # 命中点燃 tick（0 = 不点燃）
  knockback-multiplier: 1.0   # 击退倍率（1.0 = 原版）
  extra-spit:
    cooldown-ticks: 5         # 已定：0.25 秒一发（原版 40 tick 的 8 倍频率）
    speed: 1.5                # 与原版羊驼一致
    inaccuracy: 0.0           # 0 = 精准（命中率更高；原版是 10）
    lead-ticks: 10            # 预判提前量：按幻翼当前速度外推多少 tick
    max-distance: 24.0        # 超过水平此距离不发射（与搜索半径一致）
    play-sound: true          # 手动补播原版 LLAMA_SPIT 音效（launchProjectile 不自带）
  max-spits-per-second: 200   # B9：全局发射上限，保护 TPS

stats:
  enabled: true
  notify-owner: false         # 击杀时是否给附近主人提示（默认关，防刷屏）
  save-interval-seconds: 300  # 定时落盘
  purge-after-days: 30        # 超过该天数没再出现的羊驼记录会被清理（0 = 不清理）

messages:
  prefix: "&8[&eLlamaGuard&8] &r"
  no-permission: "&c你没有权限。"
  no-llama: "&c请先看着一只羊驼（10 格内）。"
  toggled-on: "&a已开启这只羊驼的自动防卫。"
  toggled-off: "&7已关闭这只羊驼的自动防卫。"
  stats: "&e这只羊驼的战绩：吐出 &f%spits% &e次，击杀幻翼 &f%kills% &e只。"
  reloaded: "&a配置已重载。"
```

所有数值都做**兜底**（例如 `Math.max(1, check-interval-ticks)`、`Math.max(1.0, radius)`），与现有插件风格一致。

---

## 4. 核心算法与 Folia 线程模型（最关键的一节）

```
SchedulerUtil.globalTimer(..., 初始延迟 1L, 周期 check-interval-ticks=5)   ← 全局区域线程
  └─ 只做一件事：拿 getOnlinePlayers() 快照（读集合是安全的）
     └─ 对每个玩家 P：scheduler().atEntity(P, …)                       ← P 的所属区域线程
        ├─ P.getNearbyEntities(playerRadius×3)   ← 在拥有线程上取，安全
        ├─ 过滤出羊驼（only-tamed=false / include-trader-llamas=true → 基本全收）
        ├─ 若范围内没有幻翼 → 直接返回（B8：不做无用功、不发投射物）
        └─ 对每只羊驼 L：scheduler().atEntity(L, …)                     ← L 的所属区域线程
           ├─ L.getNearbyEntities(水平24 / 垂直48)  ← 在 L 自己的线程上取，避免跨区域读实体
           ├─ 选【离 L 最近】的幻翼（每只羊驼独立判定，不共享目标）
           ├─ llama.setTarget(phantom)          ← 原版机制照常（B3）
           ├─ 额外发射：若距上次发射 ≥ extra-spit.cooldown-ticks(5)
           │    ├─ 目标点 = 幻翼位置 + 幻翼速度 × lead-ticks(10)   ← 预判
           │    ├─ llama.launchProjectile(LlamaSpit.class, 方向×speed)  ← 伤害仍是原版
           │    ├─ 手动补播 Sound.LLAMA_SPIT（launchProjectile 不自带音效）
           │    └─ spits++（统计）
           ├─ 全局限流：本秒累计发射数 ≥ max-spits-per-second(200) → 本轮丢弃（B9）
           └─ 记录本轮分配（内存计数，供 /ylg info 显示）
```

**为什么要"预判"**：`launchProjectile` 是直线发射，而幻翼在俯冲时速度很快（水平可达 ~0.6 格/tick），不做提前量几乎必空。`lead-ticks` 默认 10 是按"投射物飞行时间"估的初值，实测可调。

**硬性规则**
- 任何实体操作都在**该实体自己的调度器**上执行；**绝不跨区域** `setTarget` / `getNearbyEntities` / 读写实体状态
- 不在任务里跨线程保存 `Entity` 引用后延迟使用；需要时用 UUID + 在自己的线程内重新 `getNearbyEntities` 解析
- `data.yml` 落盘走 `scheduler().async(...)`（纯文件 IO）或后台线程，**不在区域线程写盘**
- 初始延迟一律 ≥ `1L`（规范要求）；不使用 `Bukkit.getScheduler()` / `runTask`

---

## 5. 数据结构与持久化

**内存**（并发容器，供多区域线程读写）
```
Map<UUID, LlamaState>  states
  LlamaState { boolean enabled = true; int spits; int kills; long lastSeen; }
Map<UUID, UUID>        lastSpitOwner   // 幻翼 UUID → 最近一次吐中它的羊驼 UUID（击杀归属，10s 过期）
```

**`data.yml`**
```yaml
version: 1
llamas:
  3f1c0e3a-....:            # 羊驼 UUID
    spits: 42
    kills: 3
    enabled: true
    last-seen: 2026-09-29T12:00:00Z
```
- 启动：`enable()` 时加载
- 落盘：每 `save-interval-seconds` 一次；`disable()` 时再存一次；`/ylg toggle` 后延迟 1 秒存（合并写入）
- 清理：`purge-after-days` 到期删除（0 = 关闭）

---

## 6. 事件监听（战斗调优 + 统计）

| 事件 | 条件 | 动作 |
|---|---|---|
| `ProjectileLaunchEvent` | 投射物是 `LlamaSpit` 且 shooter 是羊驼，且该羊驼在防卫模式 | `spits++` |
| `EntityDamageByEntityEvent` | damager 是 `LlamaSpit`、shooter 是羊驼、victim 是幻翼 | 应用 `spit-damage-multiplier` / `bonus-damage` / `fire-ticks` / `knockback-multiplier`；记录击杀归属（10s） |
| `EntityDeathEvent` | 死亡的是幻翼，且最近一次伤害来自某羊驼 | 该羊驼 `kills++`；`notify-owner` 开启时给附近主人一行提示 |

（可选，第 4 步再加）`EntityTargetEvent` 不监听 —— 保留原版行为。

---

## 7. 命令清单

| 命令 | 说明 | 权限 |
|---|---|---|
| `/ylg help` | 帮助 | `yinwu.llamaguard.use` |
| `/ylg toggle` | 开关**你正看着的**羊驼（10 格内），写入 `data.yml` | `yinwu.llamaguard.use` |
| `/ylg stats` | 查看正看着的羊驼的战绩 | `yinwu.llamaguard.use` |
| `/ylg info` | 当前配置摘要 + 本轮参战羊驼数 + 扫描耗时 | `yinwu.llamaguard.admin` |
| `/ylg reload` | 重载 `config.yml` 并重读 `data.yml` | `yinwu.llamaguard.admin` |
| `/ylg purge <天数>` | 清理旧记录 | `yinwu.llamaguard.admin` |

Tab 补全：子命令 + 权限过滤（管理员才补 `reload`/`purge`）。

---

## 8. 风险与对策

| # | 风险 | 对策 |
|---|---|---|
| 1 | **原版会清目标**：`Mob#setTarget` 设的目标可能被原版 AI/目标选择器重置 | 每 5 tick 重新分配；`keep-vanilla-targets` 避免抢走原有目标 |
| 2 | **投射物数量**：0.25 秒一发 × N 只羊驼 → 50 只 = 每秒 200 发，是真实的 TPS 风险 | B9 全局上限 `max-spits-per-second: 200`；`check-interval-ticks` 与冷却对齐（5）；`/ylg info` 报告实际发射速率 |
| 3 | **不缩放伤害**（已定）→ 幻翼 20 HP 需要约 20 次命中才死 | 靠**频率**取胜（原版 40 tick + 插件 5 tick ≈ 8 倍）；多羊驼叠加即可快速清空；不改动原版 spit 伤害 |
| 4 | **`launchProjectile` 不带原版音效/动画** | 手动 `world.playSound(..., Sound.LLAMA_SPIT, ...)`（默认开启）；观感与原版一致 |
| 5 | **直线弹道打不中高空目标** | `lead-ticks: 10` 预判 + `inaccuracy: 0` 精准；实测后可调 |
| 6 | **性能**：玩家 × 羊驼 × 附近实体扫描 | 无幻翼直接剪枝；提供 `check-interval-ticks` / `max-llamas-per-player` / `max-assign-per-tick`；`/ylg info` 报扫描耗时 |
| 7 | **Folia 线程安全** | §4 的线程模型 + 只用 `SchedulerUtil`；验收时专门检查日志有无 "Thread failed main thread check" |
| 8 | **多只羊驼打同一只幻翼** | **按设计如此**（每只羊驼独立选最近的，用户已确认），不做统一分配 |

---

## 9. 验收清单（实现后逐条跑）

| # | 验收项 | 期望 |
|---|---|---|
| 1 | 玩家旁放羊驼 + 召唤幻翼 | 5 tick 内 `/ylg info` 显示参战羊驼数 ≥ 1，羊驼开始吐口水 |
| 2 | **发射频率** | 单只羊驼约每 **0.25 秒**一发（额外发射），叠加原版 40 tick 机制 |
| 3 | **伤害未被改动** | 命中幻翼的伤害与**原版 spit 相同**（约 1 点），插件不缩放 |
| 4 | **原版机制未被破坏** | 不替换/禁用原版攻击；羊驼被狼或玩家激怒后仍能正常回击 |
| 5 | **中心正确性** | 玩家离羊驼 > 32 格 → 该羊驼停止参战；**玩家站在 24 格外但羊驼 20 格内有幻翼 → 仍然要打**（证明搜索以羊驼为中心） |
| 6 | **垂直判定** | 幻翼在羊驼正上方 40 格 → 仍被锁定（验证 48 的垂直半径，而水平 24 不会漏） |
| 7 | **全局限流** | 把 `max-spits-per-second` 调到 20 并放 20 只羊驼 → 实际发射速率被压到 ~20/秒，日志/`info` 可见丢弃计数 |
| 8 | **单只开关持久化** | `/ylg toggle` 关掉某只 → 不再参战；**重启后依然关闭**（`data.yml` 生效） |
| 9 | **统计** | 击杀幻翼后 `/ylg stats` 数字 +1，`data.yml` 有对应记录 |
| 10 | **热重载** | `/ylg reload` 后改动的半径/冷却立即生效 |
| 11 | **性能** | 50 只羊驼 + 20 只幻翼时 TPS 无明显下降；`/ylg info` 显示扫描耗时与发射速率 |
| 12 | **Folia 合规** | 全程无 "Thread failed main thread check" 类报错 |

---

## 10. 实现顺序（每步都能编译 + 单独验收）

| 步骤 | 内容 | 编译验证 |
|---|---|---|
| 1 | 配置类 + 命令框架（help/info/reload）+ plugin.yml/config.yml | `自动化\compile-check.bat YinwuLlamaGuard` |
| 2 | `LlamaDefenseManager` 的 §4 线程模型 + 目标分配（先不调伤害） | 同上 |
| 3 | 战斗调优 + 统计 + `data.yml` 持久化 + `toggle/stats` | 同上 |
| 4 | README/CLAUDE 定稿；可选 `mode: direct-spit`；验收脚本 | 同上 |

---

## 11. 已确认的决策（2026-09-29 逐条问答确定）

| # | 问题 | **决定** |
|---|---|---|
| 1 | 哪些羊驼参战 | **范围内所有羊驼**（野生 / 已驯服 / 商队羊驼全含） |
| 2 | 伤害与频率 | **伤害保持原版不变**（不缩放、不禁用原版机制）；靠**额外发射**提高频率：冷却 **5 tick = 0.25 秒**，全局上限 **200 发/秒** |
| 3 | 攻击实现 | **hybrid**：原版 `setTarget` + `RangedAttackGoal` 照常工作，插件再按 5 tick 补发 `LlamaSpit`（预判弹道，手动补音效） |
| 4 | 打哪些幻翼 | **范围内所有幻翼**；**每只羊驼各自独立**选**离自己最近**的那只（不共享目标、不做统一分配） |
| 5 | 搜索范围 | 以**羊驼**为中心：水平 **24** 格 / 垂直 **48** 格 |
| 6 | 参战判定 | 以**玩家**为参照：**32** 格（等价于"羊驼 32 格内有玩家"） |

配置项与默认值已按这 6 条写进 §3；行为定义见 §1；线程模型见 §4。

---

## 12. 下一步

按 §10 的四步实现，每步用 `自动化\compile-check.bat YinwuLlamaGuard` 验到零错误：
1. 配置类 + 命令框架（help/info/reload）+ `plugin.yml` / `config.yml`
2. `LlamaDefenseManager`（§4 线程模型 + 最近目标选择 + 5 tick 额外发射 + 全局限流）
3. 统计 + `data.yml` 持久化 + `toggle` / `stats`
4. `README.md` / `CLAUDE.md` 定稿 + 验收脚本
