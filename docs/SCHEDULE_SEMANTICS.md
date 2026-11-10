# Create 时刻表语义 —— 忠实实现规格

依据：`_recon/create-schedule-api.md`（对 `[机械动力] create-1.21.1-6.0.10.jar` 的 javap 字节码核对）。

## 0. 为什么必须自己重写，而不能调用原版

- `Schedule` **只有数据与序列化，没有任何推进方法**；推进全在 `ScheduleRuntime`。
- `ScheduleRuntime` **硬绑定 `Train`**：构造函数 `ScheduleRuntime(Train)`、公共字段 `public Train train`，
  内部解引用 `train.navigation` / `train.carriages` / `train.status` / `train.getCurrentStation()`。
- 每条条件的判定入口是 `boolean tickCompletion(Level, Train, CompoundTag)`，`getWaitingStatus(Level, Train, CompoundTag)`
  同样要 `Train`。**没有 Train 就无法调用任何条件。**
- 结论：**数据模型可复用**（`Schedule.fromTag` / `ScheduleEntry` / `IScheduleInput` / `TRAIN_SCHEDULE` 组件），
  **运行时不可插拔**，必须按下面的语义重写。

## 1. 数据结构与序列化

```
Schedule            { List<ScheduleEntry> entries; boolean cyclic; int savedProgress; }
   NBT: Entries(ListTag), Cyclic(bool), Progress(int, 仅 >0 时写入)
ScheduleEntry       { ScheduleInstruction instruction; List<List<ScheduleWaitCondition>> conditions; }
   NBT: Instruction(CompoundTag), Conditions(ListTag<ListTag<CompoundTag>>, 仅当 instruction.supportsConditions())
每条指令/条件的信封: { Id: String("create:xxx"), Data: CompoundTag }
```

`conditions` 的语义：**外层是"或"（column），内层是"与"且按顺序**。
条目在**任意一列被完整满足**时即完成。

## 2. 需要复刻的状态机（ScheduleRuntime.tick 的次序）

```
enum State { PRE_TRANSIT, IN_TRANSIT, POST_TRANSIT }

tick(level):
  若 schedule 为空 / paused / （我们的车判定脱轨） -> 返回
  若 已有导航目标(destination != null) -> ticksInTransit++; 返回
  若 checkEndOfScheduleReached() 为真 -> 返回
      （currentEntry >= entries.size() 时 currentEntry = 0；非 cyclic 则 paused = completed = true）
  若 --cooldown > 0 -> 返回
  若 state == IN_TRANSIT -> 返回
  若 state == POST_TRANSIT -> tickConditions(level); 返回
  // PRE_TRANSIT
  path = startCurrentInstruction(level)      // 先 checkEndOfScheduleReached()，再 instruction.start(runtime, level)
  若 path == null -> 返回
  若 path.destination == 当前车站 -> state = IN_TRANSIT; destinationReached(); 返回
  否则 startNavigation(path) 成功 -> state = IN_TRANSIT; ticksInTransit = 0

destinationReached():
  state = POST_TRANSIT
  清空 conditionProgress / conditionContext
  建 conditionProgress（每列一个 0）+ conditionContext（每列一个 new CompoundTag）

tickConditions(level):
  若 !instruction.supportsConditions() -> state = PRE_TRANSIT; currentEntry++; 返回
  对每一列 i:
      若 progress[i] >= conditions[i].size() -> state = PRE_TRANSIT; currentEntry++; 返回
      ctx = context[i]
      若 conditions[i].get(progress[i]).tickCompletion(level, train, ctx) 为真:
          context[i] = new CompoundTag(); progress[i]++
```

要点：**cooldown 初值 40 刻**；`progress`/`context` 是**每列一份**；某列满足后**不重置该列**，是**整列满足才推进条目**。

## 3. 可实现的条件（不依赖 Train / 车厢）

| id | 类 | NBT 键 | 判定 |
|---|---|---|---|
| `create:delay` | `ScheduledDelay` | `Value`(int), `TimeUnit`(int) | `time >= Value * ticksPer`，`time` 存于 ctx 的 `Time`，每刻 +1。**TimeUnit: 0=TICKS(1), 1=SECONDS(20), 2=MINUTES(1200)**。缺键 → 立即完成 |
| `create:time_of_day` | `TimeOfDayCondition` | `Hour`, `Minute`, `Rotation` | 见下 |
| `create:redstone_link` | `RedstoneLinkCondition` | `Frequency`(2×ItemStack), `Inverted`(int) | `hasAnyLoadedPower(freq) != (Inverted == 1)` |
| `create:powered` | `StationPoweredCondition` | 无 | 当前车站方块 `level.hasNeighborSignal(pos)` |
| `create:unloaded` | `StationUnloadedCondition` | 无 | `!level.isPositionEntityTicking(车站方块位置)` |

`time_of_day` 的精确公式（窗口 40 刻）：

```
rotation = [24000,12000,6000,4000,3000,2000,1000,750,500,250][Rotation]
time     = (int)(level.getDayTime() % rotation)
target   = (int)(((Hour + 18) % 24) * 1000.0 + ceil(Minute/60.0 * 1000.0)) % rotation
delta    = time - target
完成     = delta >= 0 && delta <= 40
```

`redstone_link` 的两个频率从 `Frequency` 的 2 个 ItemStack 反序列化（`Couple.deserializeEach`），
再用 `Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(freq)` 查询。

## 4. 明确不实现的条件（需要车厢或乘客）

| id | 原因 |
|---|---|
| `create:item_threshold` | 需要 `Carriage.storage.getAllItems()` |
| `create:fluid_threshold` | 需要 `Carriage.storage.getFluids()` |
| `create:idle` | 需要 `storage.getTicksSinceLastExchange()` |
| `create:player_count` | 需要 `Train.countPlayerPassengers()` |

**行为约定**：遇到这些条件时，**不假装满足**。应停在等待状态，并在日志与界面中说明
"该条件需要车厢/乘客，本模组不支持"。这与"读不出来就按停车处理"是同一个原则。

同理，`supportsConditions() == false` 的指令（`create:rename`、`create:throttle`）**应直接推进**，
且副作用（改标题、改油门）可按能力尽力实现或记录为未实现。

## 5. 目的地指令

`create:destination` 的 `Text` 是**glob**（不是精确名），匹配 `GlobalStation.name`：
`Glob.toRegexPattern(getFilter(), "")`。这意味着我目前的"精确名匹配"**窄于原版** ——
原版支持 `货场*` 之类的通配。应改为 glob 匹配。

## 6. 与我当前实现的差异（待改）

1. 现在只评估 `TimedWaitCondition`，其余条件**一律忽略并立即推进** → 应改为"可实现则评估，不可实现则停等并说明"。
2. 现在没有 PRE/IN/POST_TRANSIT 状态与 **40 刻 cooldown** → 应补上。
3. 现在是全局一个等待计时 → 应改为**每列一份 progress + context**。
4. 目的地匹配用精确名 → 应改为 **glob**。
5. `Schedule.savedProgress` 是原版持久化续跑点（`Progress`），我只写在物品里 → 应确认与 `currentEntry` 的关系。
