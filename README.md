# YinwuLlamaGuard — 羊驼防卫

**玩家 32 格范围内的羊驼，会自动攻击范围内的幻翼。**

不需要玩家做任何事：只要你身边有羊驼、天上有幻翼，羊驼就会朝幻翼吐口水。
**原版机制完全保留**（羊驼该怎么吐还怎么吐、伤害也是原版的约 1 点），插件只是让它**吐得更勤**。

> ⚡ 完全兼容 Folia 区域线程；全部实体操作都在该实体自己的线程上执行。

---

## 1. 行为规则（一张表说清）

| 规则 | 说明 |
|---|---|
| **谁参战** | 与玩家距离 ≤ **32 格**（`player-radius`）的羊驼 —— 野生 / 已驯服 / 商队羊驼**全都算** |
| **打谁** | 以**羊驼自己**为中心，水平 **24** 格、垂直 **48** 格内，**离它最近**的那只幻翼 |
| **多只羊驼** | **各自独立**选目标，不共享、不统一分配（可能出现几只打同一只，属预期） |
| **怎么打** | ① 原版 `setTarget` + `RangedAttackGoal` 照常工作；② 插件按 **0.25 秒**冷却**额外补发**一发 `LlamaSpit`（带弹道预判） |
| **伤害** | **改动为 0** —— 默认与原版完全一致（约 1 点），插件不缩放、不附加 |
| **没有幻翼时** | 什么都不做（不扫描、不发投射物） |
| **玩家走远** | 超过 32 格后该羊驼退出防卫模式 |
| **原版行为** | 不影响羊驼回击狼/玩家、不影响商队跟随、不改原版攻击间隔（40 tick） |

### ⚠️ 两个半径的"中心"不一样（最容易搞错的地方）

```
玩家 ──32 格──> 羊驼 是否参战          （中心 = 玩家）
羊驼 ──水平24/垂直48──> 幻翼 打哪只     （中心 = 羊驼）  ← 不是玩家！
```

垂直半径单独放大到 48 是因为**幻翼在高空俯冲**，如果三轴都用 24，会出现"幻翼就在头顶却打不到"。

---

## 2. 命令与权限

| 命令 | 说明 | 权限 | 默认 |
|---|---|---|---|
| `/yinwullama help`（别名 `/ylg`） | 查看帮助 | — | — |
| `/ylg stats` | **看着一只羊驼**执行：查看它的战绩（吐出次数 / 击杀幻翼数） | `yinwu.llamaguard.use` | 所有人 |
| `/ylg toggle` | **看着一只羊驼**执行：开关它的自动防卫（写入 `data.yml`，重启保留） | `yinwu.llamaguard.use` | 所有人 |
| `/ylg info` | 运行状态：当前配置、本秒发射数、累计发射、被限流次数、上轮参战羊驼数、派发耗时 | `yinwu.llamaguard.admin` | OP |
| `/ylg reload` | 重载 `config.yml` 并重读 `data.yml` | `yinwu.llamaguard.admin` | OP |
| `/ylg purge [天数]` | 清理超过 N 天没再出现的记录（默认取 `stats.purge-after-days`） | `yinwu.llamaguard.admin` | OP |

> "看着"= 准星 10 格内的羊驼（`getTargetEntity`）。

---

## 3. 配置（`plugins/YinwuLlamaGuard/config.yml`）

### 参战与搜索

| 键 | 默认 | 说明 |
|---|---|---|
| `defense.player-radius` | `32.0` | **以玩家为中心**：羊驼进入防卫模式的距离 |
| `defense.use-sphere-check` | `false` | `false` = 立方体判定（Bukkit 原生，快）；`true` = 再按真实距离过滤（球体） |
| `defense.target-radius-horizontal` | `24.0` | **以羊驼为中心**：搜索幻翼的水平半径 |
| `defense.target-radius-vertical` | `48.0` | 同上，垂直半径（幻翼在高空，建议别调小） |
| `defense.check-interval-ticks` | `5` | 扫描间隔，与额外发射冷却对齐 |
| `defense.only-tamed` | `false` | 已定：所有羊驼参战 |
| `defense.include-trader-llamas` | `true` | 商队羊驼是否参战 |
| `defense.keep-vanilla-targets` | `true` | 羊驼正忙着打狼时不抢它的目标 |
| `defense.clear-when-no-phantom` | `false` | 没幻翼时是否清空插件设过的目标 |
| `defense.max-llamas-per-player` | `0` | 每个玩家最多带动多少只羊驼（`0` = 不限，性能保护用） |

### 战斗

| 键 | 默认 | 说明 |
|---|---|---|
| `combat.mode` | `hybrid` | `hybrid` = 原版 + 额外补发（推荐）；`vanilla-ai` = 只走原版；`extra-spit` = 只由插件发射 |
| `combat.extra-spit.cooldown-ticks` | `5` | **额外发射冷却 = 0.25 秒**（原版羊驼是 40 tick） |
| `combat.extra-spit.speed` | `1.5` | 投射物速度（与原版一致） |
| `combat.extra-spit.inaccuracy` | `0.0` | 散布；`0` = 精准瞄准，原版是 `10` |
| `combat.extra-spit.lead-ticks` | `10` | 弹道预判提前量（幻翼俯冲很快，不预判基本打空） |
| `combat.extra-spit.max-distance` | `24.0` | 超过这个水平距离不发射 |
| `combat.extra-spit.play-sound` | `true` | 补播原版吐口水音效 |
| `combat.max-spits-per-second` | `200` | **全局限流**：所有羊驼合计每秒最多发射多少发（`0` = 不限） |
| `combat.spit-damage-multiplier` | `1.0` | **默认不动原版伤害**；只有你改它才会生效 |
| `combat.bonus-damage` / `combat.fire-ticks` | `0.0` / `0` | 同上，默认都不介入 |

> ⚠️ **`max-spits-per-second` 别删**：0.25 秒一发 × 50 只羊驼 = 200 发/秒，
> 没有这道闸门投射物会把 TPS 拖下来。被限流时 `/ylg info` 会显示累计限流次数。

### 统计

| 键 | 默认 | 说明 |
|---|---|---|
| `stats.enabled` | `true` | 是否记录并持久化战绩 |
| `stats.notify-owner` | `false` | 击杀幻翼时是否提示羊驼主人 |
| `stats.save-interval-seconds` | `300` | 定时异步落盘 |
| `stats.purge-after-days` | `30` | 超过该天数没再出现的记录会被清理（`0` = 不清理） |

消息全部在 `messages:` 段，支持 `&` 颜色代码。

---

## 4. 工作原理与线程模型（Folia 关键）

```
全局定时器（初始延迟 1L，周期 check-interval-ticks=5）
  └─ 只取在线玩家快照并派发
     └─ atEntity(玩家)                       ← 玩家所属区域线程
        ├─ 取附近实体，筛出羊驼、判断有没有幻翼
        ├─ 没有幻翼 → 直接返回（不做无用功）
        └─ atEntity(羊驼)                    ← 羊驼所属区域线程
           ├─ 在羊驼自己的线程里取附近实体 → 选最近的幻翼
           ├─ setTarget(幻翼)                 ← 原版机制照常
           └─ 冷却到了就 launchProjectile(LlamaSpit, 预判方向)
                ├─ 全局限流闸门
                ├─ 补播 ENTITY_LLAMA_SPIT 音效
                └─ 统计由 ProjectileLaunchEvent 统一计数
```

**绝不**跨区域调用 `setTarget` / `getNearbyEntities` / 读写实体；`data.yml` 落盘走 `async` 调度器。
所以本插件在 Folia / Canvas 上不会出现线程违规。

**为什么用 `launchProjectile` 而不是 OP 改原版 AI**：原版羊驼的攻击间隔（40 tick）写在 AI goal 里，
Bukkit API 改不了；而"额外补发"既不动原版机制、又能把频率提到 8 倍。

---

## 5. 安装

1. 把 `YinwuLlamaGuard-1.0.0.jar` 放进 `plugins/`（先装 `YinwuPluginLib`）
2. 重启服务器（Bukkit 插件只在启动时加载）
3. 启动日志应出现：
   `[YinwuLlamaGuard] 已启用：玩家半径=32.0，搜索=水平 24.0 / 垂直 48.0，模式=hybrid，额外发射冷却=5 tick，全局上限=200/秒`

---

## 6. 验收（怎么确认它真的在工作）

| # | 操作 | 期望 |
|---|---|---|
| 1 | 在羊驼旁召唤幻翼 | 5 tick 内羊驼开始吐口水；`/ylg info` 显示参战羊驼 ≥ 1 |
| 2 | 观察吐口水频率 | 约 **每 0.25 秒**一发（叠加原版机制后更密） |
| 3 | 打幻翼看掉血 | 与原版 spit 一致（约 1 点）—— 插件没改伤害 |
| 4 | 玩家站 24 格外、但羊驼 20 格内有幻翼 | **仍然会打**（证明搜索以羊驼为中心） |
| 5 | 幻翼飞到羊驼正上方 40 格 | **仍被锁定**（验证垂直 48 的作用） |
| 6 | 把 `max-spits-per-second` 临时改成 `20`，放 20 只羊驼 | `/ylg info` 的"本秒已发射"被压到 ~20，限流计数增长 |
| 7 | `/ylg toggle` 关掉一只 | 它不再参战；**重启后依然关闭**（`data.yml`） |
| 8 | 击杀幻翼后 `/ylg stats` | 数字 +1，`plugins/YinwuLlamaGuard/data.yml` 有记录 |
| 9 | 狼去咬羊驼 | 羊驼仍能正常回击狼（`keep-vanilla-targets` 生效，插件不去抢） |
| 10 | 50 只羊驼 + 20 只幻翼 | TPS 无明显下降；日志无 "Thread failed main thread check" |

---

## 7. 已知限制

- **羊驼不会飞**：它只对范围内的幻翼吐口水；幻翼飞出 24/48 范围就不再打。想扩大范围改配置即可。
- **命中靠预判**：`lead-ticks` 是估值（默认 10），幻翼机动剧烈时会有空发 —— 属于正常现象，
  但"空发也要吐"正是原版行为，所以观感上没有问题。
- **多只羊驼可能打同一只幻翼**：这是"每只独立选最近"的必然结果（有意如此）。
- **`extra-spit` 模式**（只由插件发射、不用原版 AI）会改变观感，除非有特殊需求，建议保持 `hybrid`。
- **不处理跨世界/跨区域追踪**：离开范围即停手。

---

## 8. 排查

| 现象 | 检查点 |
|---|---|
| 完全不吐 | 是不是没装 `YinwuPluginLib`；`enabled` 是否为 true；`/ylg info` 里"上轮参战羊驼"是否为 0 |
| 参战数为 0 | 玩家 32 格内到底有没有羊驼（注意是**以玩家为中心**）；`only-tamed` 是否被改成 true |
| 有参战但不吐 | `combat.mode` 是否被改成 `vanilla-ai`；冷却是否被改得很大；`max-spits-per-second` 是否为 0 以外的极小值 |
| 投射物太多/卡 | 调大 `combat.extra-spit.cooldown-ticks`，或调小 `combat.max-spits-per-second`、`defense.max-llamas-per-player` |
| 打不到高空的幻翼 | `defense.target-radius-vertical` 是否被调小；`combat.extra-spit.lead-ticks` 是否偏小 |
| 记录的羊驼不是你想的 | `/ylg info` 看记录数；`data.yml` 里 key 是羊驼 UUID |

---

## 9. 构建

```bash
# 1) 首次构建必需：共享库不在 Maven 中央仓库，先装进本地仓库
git clone https://github.com/YinwuPotato/YinwuPluginLib.git
cd YinwuPluginLib && mvn clean install && cd ..

# 2) 构建本插件
git clone https://github.com/YinwuPotato/YinwuLlamaGuard.git
cd YinwuLlamaGuard && mvn clean package
```

产出：`target/YinwuLlamaGuard-1.0.0.jar`（shade 已把 YinwuPluginLib 打进去）

> 父 POM（`net.yinwu:YinwuPlugins:1.0.1`）已随仓库提供在 `parent/pom.xml`，无需额外操作。

**本机快速编译校验**（不走 Maven，用 `.buildcache` 里的依赖直接 javac）：

```bat
自动化\compile-check.bat YinwuLlamaGuard
```

**依赖**：`YinwuPluginLib`（必需）、Paper API 1.21+（provided）。**无任何软依赖。**

---

## 10. 链接

- 组织：[github.com/YinwuPotato](https://github.com/YinwuPotato)
- 前置：[YinwuPluginLib](https://github.com/YinwuPotato/YinwuPluginLib)
- 设计文档：[PLAN.md](PLAN.md)（行为定义、决策记录、线程模型、验收清单）
- 作者：Qumingjam
