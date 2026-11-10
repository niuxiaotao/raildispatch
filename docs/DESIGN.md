# 航空学：轨道调度（Rail Dispatch）— 设计说明

给 **Create: Aeronautics / Simurail 物理车辆**补上 Create 蒸汽火车那套自动化的附属模组。

> 一句话：**让"会飞的/会跑的车"能读时刻表、看信号机、申请进路、按标签找车与找股道。**

---

## 1. 目标与范围

用户提出的四个方块/物品，本模组都实现了：

| 需求 | 实现 | 类 |
|---|---|---|
| 物理驾驶台：接受原版时刻表、接收信号机信号、据信号停车、通知地面道岔/集中调度 | `physics_cab` | `PhysicsCabBlock` / `PhysicsCabBlockEntity` |
| 换向器：接入物理转向架的转向装置，受驾驶台或红石控制 | `reverser` | `ReverserBlock` / `ReverserBlockEntity` |
| 车辆标签：识别车辆，供时刻表「连挂某车辆」按标签寻址 | `vehicle_tag` | `VehicleTagItem` + `RailTags` |
| 地址标签：驼峰调车场识别去向 | `address_tag` + `address_plate` | `AddressTagItem` / `AddressPlateBlock` |
| （附加）集中调度 | `dispatch_center` | `DispatchCenterBlock` / `DispatchCenterBlockEntity` |

**混合式调度**（用户选择）：没有调度中心时，驾驶台自己规划并锁闭进路；放了调度中心就由它统一仲裁。

---

## 2. 目标环境

| 项 | 值 |
|---|---|
| Minecraft | 1.21.1 |
| 模组加载器 | NeoForge 21.1.233 |
| 实例 | `1.21.1-NeoForge_21.1.233CRA6月20号物理火车包` |
| 必需 | Create 6.0.10、Simurail（物理转向架 + 自动车钩）、Sable 2.0.5+、Drive By Wire（牵引/制动通道） |
| 可选 | Create: Railways 0.3.0（**道岔方块在这里**，软依赖） |

**重要事实（决定了设计）：**

1. **Create 6.0.10 没有道岔方块。** `TrackGraph` 里只有节点与边；岔路口走哪条支线是由**车辆自己的进路**决定的。真正的道岔方块在 **Create: Railways** 里（`TrackSwitch` / `TrackSwitchBlockEntity`）。
2. **Create 没有运行时"连挂两列车"的 API。** `Train` 的车厢列表在车站组装时就固定了，只有整车 `disassemble`。所以连挂必须在**物理层**做：Simurail 的 `AutomaticCouplerBlockEntity` 用 Sable 的约束把两个 sub-level 连起来，把两个车钩互设为 partner 才是真正的"挂上"。
3. **Simurail 的物理转向架没有公开的转向 setter。** 唯一的数值控制通道是 `PhysicsBogeyControlOverrides`（公开字段：`brakeStrength` / `stressMultiplier` / `steerValue`），而它的实例字段 `computerOverrides` 是 `protected`。见 §7。

---

## 3. 分层架构

```
            ┌──────────────────────────────────────────────┐
            │  block/ , block/entity/ , item/ , menu/       │  业务层：不 import 任何第三方类
            │  （驾驶台状态机、换向器、标签、调度中心）      │
            └───────────────┬──────────────────────────────┘
                            │  只依赖 api/ 里的接口
            ┌───────────────▼──────────────────────────────┐
            │  api/  PhysicsBridge, VehicleHandle,          │  契约层
            │        TrackNetwork, ScheduleView/Schedules,  │
            │        CouplerBridge, SwitchDriver,           │
            │        RailServices（服务定位器）             │
            └───────────────┬──────────────────────────────┘
                            │  compat/RailCompat 在模组构造时装配
        ┌───────────────────▼───────────────┐
        │  physics/  Sable + Simurail       │
        │  train/    Create + Railways      │
        └───────────────────────────────────┘
```

这样做的**理由**：物理引擎与 Create 的内部类会随版本变动，而"驾驶台该在红灯前多远开始制动""没有调度中心时怎么办"这类业务规则不该跟着变。`RailServices` 是唯一的装配点，装配失败只会退化成 `NONE` 实现并打一条日志，不会让游戏崩溃。

`api/` 里两个默认实现（`PhysicsBridge.NONE`、`TrackNetwork.NONE`）是有意设计的：缺物理模组时驾驶台会停在 `IDLE` 并告诉你原因，而不是抛异常。

---

## 4. 物理驾驶台

### 4.1 控制周期

`serverTick()` **每 5 tick 跑一次控制环**（4 Hz）。物理转向架积分的是力，4 Hz 下发指令已经足够平滑，同时把开销压到 1/5。其余 tick 直接返回。

每轮依次做五件事：

1. **认车**：先按绑定的 `vehicleId`；再按驾驶台的车辆标签；最后按坐标在 3 格内找物理车体。找到后写入 `CabRegistry`，声明"这辆车归我控制"。
2. **读时刻表**：`Schedules.read()` 返回当前条目的目的地 / 车辆标签 / 去向地址 / 等待 tick。
3. **看信号**：`RailServices.track().signalAhead()` 返回 `SignalInfo`（**灯色 + 距离**）。距离是必需的——没有距离就没法算制动曲线。
4. **要进路**：`requestRoute()`。有调度中心就交给它仲裁；否则按配置自行锁闭。
5. **下指令**：算出目标速度与转向，写进车体的转向架。

### 4.2 状态机

`IDLE → REQUESTING_ROUTE → RUNNING ⇄ APPROACHING → HOLDING → ARRIVED/WAITING →（下一条目）`

| 状态 | 含义 | 比较器输出 |
|---|---|---|
| `IDLE` | 没有车 / 没有可用时刻表 | 1 |
| `REQUESTING_ROUTE` | 等进路 | 4 |
| `RUNNING` | 绿灯正常运行 | 8 |
| `APPROACHING` | 减速接近（黄灯 / 目的地 / 红灯前） | 11 |
| `HOLDING` | 红灯前停稳 | 15 |
| `WAITING` | 目的地停车等点 | 6 |
| `ARRIVED` | 条目完成 | 2 |
| `COUPLING` | 接近目标车辆准备连挂 | 10 |

### 4.3 制动曲线

```
可用距离 = 信号距离 − brakeMargin
目标速度 = sqrt(2 × 1.5 m/s² × 可用距离)
```

若目标速度 ≤ 0.08 m/s 就直接抱闸（`HOLDING`）。这是教科书式的匀减速曲线，好处是**不会冲过信号机**，而且距离越近减速度越自然地变大。配置项 `signalBrakeMargin` 是安全余量。

### 4.4 牵引/制动/转向输出

```
current = speed × 20            // 格/tick → 格/秒
current < 目标 − 0.2  → drive = min(1, (目标−current)/4)
current > 目标 + 0.2  → brake = min(1, (current−目标)/4)
否则                   → 两个都 0（惰行）
```

转向用叉积求航向偏差：`cross(前进方向, 目标方向) × 2`，截断到 ±1。对在轨的 Simurail 转向架来说转向基本不需要（它自己跟着轨道走），主要给离轨的物理车用。

---

## 5. 信号、进路与道岔

### 5.1 信号读取

直接读**信号机自己的灯**，而不是推断区间状态：

1. 遍历所有轨道图，取 `graph.getPoints(EdgePointType.SIGNAL)` 上的 `SignalBoundary`；
2. `boundary.blockEntities` 给出这两台信号机的**真实方块坐标**，于是 `boundary.getStateFor(pos)` 就是玩家看到的那盏灯（`GREEN`/`YELLOW`/`RED`/`INVALID`）；
3. 只考虑 `lookahead` 以内、且与航向点积为正（即在前方）的信号机，取最近的；
4. **距离取到信号机方块的距离**——这正是制动曲线需要的量。

**回退**：该段轨道没有信号机方块时，用区间占用判断——`edge.getEdgeData().getEffectiveEdgeGroupId(graph)` → `Create.RAILWAYS.signalEdgeGroups.get(id)` → 若 `group.trains` 非空则视为红灯。此时**距离未知**（-1），驾驶台按"前方有车但看不见多远"处理：直接抱闸停车，这是对未知信息的保守解读。

### 5.2 进路

- **规划**：Create 没有公开的路径查找（`Navigation.findPathTo` 只认车站），所以在 `TrackGraph` 上自己跑 **Dijkstra**，代价用 `TrackEdge.getLength()`；邻接只能通过 `graph.getConnectionsFrom(node)` 拿。路径上连接数 > 2 的节点即岔路口，记入 `junctions`。
- **锁闭**：按"车 → 占用区间集合"记账。区间 id 优先用**信号区间组 UUID**；没有分组的边用**岔路口坐标的稳定哈希**，这样两类 id 能放进同一个集合比较。这就是防止两辆车被排进同一段铁路的机制。
- **仲裁**：调度中心持有 `UUID → HeldRoute` 表，两车进路的 `junctions` 相交或路径点距离 < 4 格即判为冲突，拒绝并给出可读原因（显示在 GUI 里）。
- **释放**：车辆出清、被卸载或超过 `routeTimeoutTicks` 未移动 → 自动释放。因为释放是从车这一侧发起的、而车并不知道是哪台调度中心仲裁的，`DispatchCenters.release()` 会通知该维度内**所有**已加载的调度中心。

### 5.4 调度中心怎么"贴在铁轨上"放（与信号机同一套机制）

Create 的信号机不是随便放的：它的物品是 `TrackTargetingBlockItem`，方块实现 `IBE`，方块实体是 `SmartBlockEntity` 并挂一个 `TrackTargetingBehaviour<SignalBoundary>`。调度中心**完整复刻了这套**，所以操作手势与信号机一致：

| 组成 | 本模组的对应物 |
|---|---|
| 物品 | `TrackTargetingBlockItem`，绑定到我们注册的点位类型 |
| 点位类型 | `RDPoints.DISPATCH_CENTER`，用公开的 `EdgePointType.register(...)` 注册 |
| 点位实现 | `DispatchCenterPoint extends SingleBlockEntityEdgePoint` —— 空子类即可，`TrackEdgePoint` 的整套契约它已经实现 |
| 方块 | `DispatchCenterBlock extends Block implements IBE<...>`，`newBlockEntity`/`getTicker` 由 `IBE` 默认提供 |
| 方块实体 | `SmartBlockEntity` + `TrackTargetingBehaviour<DispatchCenterPoint>`（在 `addBehaviours` 里挂上） |

从字节码核对出的**真实手势**（`TrackTargetingBlockItem.useOn`）：

1. 右键**铁轨**（`ITrackBlock`）→ 服务端用 `ITrackBlock.getNearestTrackAxis` 定出方向，`withGraphLocation` 校验是否与已有点位重叠；通过后把选中的位置/方向/贝塞尔**存进物品的数据组件**（`TRACK_TARGETING_ITEM_SELECTED_POS` 等），失败则给红色提示并返回 `FAIL`。
2. 右键**别的可放置位置** → 若物品上没有选中记录，提示 `track_target.missing` 并 `FAIL`；有记录则放置方块并把绑定写进它的 `TrackTargetingBehaviour`。
3. 潜行 + 右键 → 清除物品上的选择。

**两个必须照抄的细节**（否则会留下幽灵点位或行为错误）：

- `onRemove` 只调 `IBE.onRemove(state, level, pos, newState)`，**不调 super** —— 这正是 Create 的 `SignalBlock` 的写法（我用字节码确认过）。少了它，方块被拆后轨道图上会永远留着一个不存在的调度中心。
- `IBE.getTicker` 的默认实现会在 `SmartBlockEntity.class.isAssignableFrom(getBlockEntityClass())` 时返回 `SmartBlockEntityTicker`，所以**不要**自己覆盖 `getTicker`；改为覆盖 `SmartBlockEntity#tick()`，并在里面先 `super.tick()` 再跑自己的逻辑（并用 `level instanceof ServerLevel` 把逻辑限制在服务端）。

因此调度中心**知道它管的是哪条铁路**（`edgePoint.getGlobalPosition()`），而不是靠半径猜；右键时会显示"已锁定轨道 X"。

另外：`SmartBlockEntity.setRemoved()` 是 **final**，所以"从 `DispatchCenters` 注册表里摘掉自己"改用 `invalidate()` 与 `onChunkUnloaded()` 两个钩子；`DispatchCenters.nearest` 还会拿 `level.getBlockEntity(pos) != authority` 做一次存活校验，让注册表**自愈**，即使某个移除钩子漏了也不会让一个已消失的调度中心继续霸占进路。

### 5.5 道岔

Create 没有道岔方块，所以 `setSwitch()` 走 **`SwitchDriver` 扩展点**：

- **本模组自带 Create: Railways 的驱动**（`RailwaysSwitchDriver`）：`TrackSwitchBlockEntity.getSwitch()` → `trySetSwitchState(NORMAL / REVERSE_LEFT / REVERSE_RIGHT)`，按 `hasLeftExit()/hasRightExit()` 决定分歧方向。**被玩家锁定的道岔会拒绝驱动并记一条日志**——锁闭是玩家的明确决定，不该被静默推翻。
- 该类被刻意放在独立包 `train/railways/` 中，只在 `ModList.isLoaded("railways")` 为真时实例化，因此**不装 Railways 的整合包里这个类及其引用的所有 Railways 类型都不会被解析**（真·软依赖）。
- 其它整合包要实现 `SwitchDriver` 注册到 `RailServices` 即可接入，无需改本模组。

---

## 6. 标签系统（本模组的实体面）

`RailTags` 是挂在主世界 `DimensionDataStorage` 上的 `SavedData`（`raildispatch_tags`），全局共享：

| 映射 | 用途 |
|---|---|
| `车辆标签名 → 车辆 UUID` | 时刻表按名字寻址一辆车：去它那儿、连挂它 |
| `车辆 UUID → 去向编码` | 驼峰场：这辆车要去哪条股道 |
| `去向编码 → 股道坐标列表` | 地址牌登记的目的地 |

- **车辆标签**（`vehicle_tag`）：铁砧改名 → 右键车辆或驾驶台完成绑定。名字直接来自物品的 `CUSTOM_NAME`，不做隐藏 NBT，玩家看得见、可复制、可交易。
- **地址标签**（`address_tag`）：改名后右键**车辆**表示"这车的去向是 X"，右键**地址牌**表示"这条股道承接去向 X"。驼峰调度就是拿两者做匹配。

之所以这样分：真实驼峰场里**去向编码挂在车上**（车知道自己去哪），而**股道有自己的编号**；两者匹配才决定扳哪一边。所以 `address_tag` 既能写车、也能登记股道。

---

## 7. 控制通道（关键技术决策）

### 7.1 结论：主通道是**直接给 Sable 刚体施加质量标定的冲量**

最初的设计是写 Simurail 转向架的 `PhysicsBogeyControlOverrides`（公开字段：`brakeStrength` / `stressMultiplier` / `steerValue`）。但对本实例的 Simurail jar 做了**全 jar 常量池扫描**后发现：

- 这三个字段名只出现在 `PhysicsBogeyControlOverrides` 自身；
- `computerOverrides` 只出现在 `PhysicsBogeyBlockEntity`，且仅被**字段初始化**与 `tick()` 里的 `reset()` 使用；
- **没有任何类读取这些字段**；
- 而 `getControlStrength()` / `getSteerValue()` 是**直接读红石**的，且 `tick()` 在没有连接计算机时**每 tick 调 `reset()`**。

也就是说，这条"文档化通道"在这个版本里很可能是**死的**，而且玩家红石才是转向架的原生输入——但一个不在每个转向架旁边的驾驶台无法提供红石。于是控制方案改为：

```
冲量 = 质量 × 加速度 × dt
```

- **牵引**：沿车体前进方向施加，`MAX_ACCELERATION = 2.0` 格/秒²（满驱动）。
- **制动**：沿速度反方向，`MAX_BRAKE = 3.0`，且冲量上限为 `质量 × 当前速度`——**绝不允许刹到倒退**。
- **转向**：绕世界 +Y 的角冲量（`steer > 0` 表示右转，故取负号）；惯量用 `质量 × 4.0` 的**标量估计**，因为惯量张量的轴向约定无法离线确认，用错轴会让车朝荒唐方向自旋，质量比例偏航力矩至少是可预测、可调的。
- **dt 有缓存**：驾驶台每 5 tick 下一次指令、换向器每 tick 下一次，如果不缓存，同一 tick 里的第二次、第三次调用会看到零间隔而只施加象征性冲量。实现里按游戏刻缓存 dt（下限 0.05 s、上限 0.5 s），**加速度因此与调用频率无关**。

### 7.2 `computerOverrides` 仍然会写，但只是尽力而为

反射写它是为了**向前兼容**：将来某个 Simurail 版本开始读取时，本模组无需改动即可生效。字段是 `protected final`，**访问转换器（AT）只改运行时不改 javac 的可见性判断**，跨包访问 protected 成员在编译期就会被拒，所以只能反射。失败只记一条 WARN，绝不上抛。

### 7.3 两条通道的分工被写进接口契约

`PhysicsBridge.commandBogey` 的 javadoc 明确写着：**它只写转向架自身的状态，实现不得在此施加车体受力**。车体级别的牵引/制动/转向归属于 `VehicleHandle`。这条约定是"换向器与驾驶台不会同时推同一辆车"的保证：

- **换向器**同时走两条道：`vehicle.setSteer()`（保证有物理效果）+ 逐转向架 `commandBogey`（尽力写覆写字段）。
- **驾驶台接管同一辆车时**（`CabRegistry` 查询），换向器自动转为被动，不再下达任何指令。
- `releaseBogey()` 调 `reset()`，把控制权还给红石与动力学网络。

### 7.4 车辆的坐标空间：最容易踩的坑

Sable 组装一辆车时，会把结构方块**搬进一个远离原点的 plot**（`LevelPlot`：`plotPos`、`getCenterBlock()`、`toGlobal/toLocal`、`getBlockEntityActors()`）。这意味着：

> **结构里的方块实体报告的是 plot 坐标，不是这辆车在世界里看起来所在的位置。**

第一版实现因此在两处同时出错：

1. **在世界坐标附近扫方块找转向架 → 必然一个都找不到。** 正确做法是**根本不做扫描**：`plot.getBlockEntityActors()` 直接给出结构里所有 `BlockEntitySubLevelActor`，而 `PhysicsBogeyBlockEntity` 正好实现了这个接口。于是物理层现在**持有转向架的对象引用**，全类没有任何坐标运算。
2. **用世界坐标查"这个方块属于哪辆车" → 结构内部的方块查不到。** `SableCompanion` 有 `getContaining(BlockEntity)` 重载；而最**精确**的判据是"哪个结构的 actor 列表里正好有这个方块实体"（`ownerOfActor`）。两者都保留：companion 查询还额外覆盖"方块在世界里、位于车顶上但没被组装进结构"的情况。

同样的坑也影响车钩：两辆车在**不同的 plot** 里，它们的局部坐标**不可比较**。所以 `SimurailCouplers` 里所有距离都在用 `logicalPose().transformPosition(...)` 映射到世界空间之后才计算——否则会挑出一对毫不相干的最近车钩。

### 7.5 另一个被否决的方案

不自己模拟红石输出：转向架的**一个**红石信号按 `PhysicsBogeyControlMode` 被解释为"刹车"**或**"驱动强度"，而 `getSteerValue()` 据说也读红石。用一个信号同时表达"转向"极可能被误读成刹车，风险大于收益，故放弃。

---

## 8. 持久化与同步

| 数据 | 位置 | 理由 |
|---|---|---|
| 时刻表 | 驾驶台的 `ItemStackHandler`（1 格）+ NBT | 玩家能把时刻表取出来带走 |
| 车辆/去向标签 | `SavedData`（主世界） | 跨维度、跨区块一致 |
| 驾驶台状态 | 方块实体 NBT | 重载后继续跑 |
| GUI 状态 | 原版 `ContainerData`（9 个整型） | **零自定义网络包**，用原版菜单同步 |
| 地址牌去向 | 方块实体 NBT + `SavedData` 索引 | 方块自身是权威，索引用于快速查找 |
| **进路/锁闭** | **不持久化** | 跨存档重生的进路会锁住不存在的车；1 秒内可从活着的车体重建 |

---

## 9. 已知限制（诚实清单）

1. **未在游戏内实测。** 本机无外网、无 Gradle 依赖缓存，无法启动开发客户端，也无法跑 `runClient`。所有代码是**对着真实 jar 编译通过**（javac 零错误）并对签名逐一 `javap` 核对过的，但**运行时行为需要你进游戏验证**。
2. **`create_train_parts` 与转向架无关**（已核实：0 个相关类、0 处 sable/aero 引用），不要指望它。
3. **物理车的"连接"依赖 Simurail 车钩**。没有车钩的车体无法连挂；此时驾驶台会推进到对齐位置、给出"no coupler available"而不是假装成功。
4. **转向只对离轨物理车有意义**。在轨的 Simurail 转向架自己跟着轨道走。
5. **道岔需要 Create: Railways**（或自己实现 `SwitchDriver`）。
6. **转向的惯量是标量估计**（`质量 × 4.0`）。真实惯量张量的轴向约定无法离线确认，用错轴会让车朝荒唐方向自旋；质量比例的偏航力矩是可预测、可调的折中。若实测手感不对，调 `SableVehicle.STEER_INERTIA_FACTOR`。
7. **"前进方向"的推断有两级**：优先用当前速度的水平分量（无歧义），静止时用 `logicalPose().orientation()` 变换局部 +Z（沿用 Minecraft 实体的朝向约定），两者都不可用时退回上一次记住的方向。若你的车体在静止时朝错误方向起步，问题在这里。
8. **转向架来自结构自己的 actor 列表**（`plot.getBlockEntityActors()`），不依赖任何坐标扫描。因此边界情况是：如果某个物理模组的转向架**没有实现 `BlockEntitySubLevelActor`**，就枚举不到它。此时日志会打出 `... lists N sub-level actor(s) and 0 Simurail physics bogie(s)`，一眼能区分是"结构里真的没有转向架"还是"枚举不到"。
9. **道岔需要 Create: Railways**（或自己实现 `SwitchDriver`）。被玩家锁定的道岔会被拒绝驱动。
10. **`create_train_parts` 与转向架无关**（已核实：0 个相关类、0 处 sable/aero 引用）。

---

## 10. 构建

**不能用 Gradle**：本机无外网，`~/.gradle/caches` 与 `~/.m2` 都是空的，`moddevgradle`、`foojay` 插件与 neoform 管线全都下载不了（已实测）。

因此工程里**刻意没有 `build.gradle` / `settings.gradle`**：写了也跑不起来，只会误导。`gradle.properties` 保留，因为它是版本与元数据的唯一来源，也是离线脚本做 `${占位符}` 替换的数据源。

**改用**：直接对着启动器已有的 jar 用 `javac` + `jar` 编译打包。

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
cd D:\DS_WORKSPACE\raildispatch
& .\tools\build-offline.ps1
```

脚本做四件事：自动探测"装了物理转向架的实例" → 拼 classpath（117 个启动器库 + MC 官方映射 jar + NeoForge + 实例全部 mod + **模组内嵌 `META-INF/jarjar` jar**）→ 编译 → 打包。

三个**必须知道**的坑（都已在脚本里处理）：

1. **内嵌 jarjar 必须抽取**。Create 的 Catnip（`net.createmod.catnip.data.Pair` 出现在 Create 自己的公开签名里！）、Flywheel、Ponder、Registrate，以及整个 Aeronautics 真身，都在内嵌 jar 里。
2. **argfile 里 classpath 必须用正斜杠**。javac 的 argfile 解析器把引号内的 `\` 当转义符，会静默解析失败。
3. **Windows PowerShell 5.1 把无 BOM 的 UTF-8 脚本按 ANSI 读**，所以 `tools/*.ps1` 一律纯 ASCII，中文实例名靠 `*21.1.233*` 之类的模式去匹配、不写进脚本。

---

## 11. 自动驾驶台

### 11.1 定位

驾驶台的入口是**时刻表**，自动驾驶台的入口是**一个目的地**。两者共用同一套地面层（信号、进路、道岔）
和同一套物理层（`VehicleHandle`、DBW 通道），但状态机不同：

```
IDLE → PLANNING → RUNNING ⇄ APPROACHING → HOLDING → ARRIVED
```

状态机在 `autopilot/AutopilotState`，控制律在 `control/`：

| 类 | 职责 | 为什么单独拿出来 |
|---|---|---|
| `control/SpeedController` | 前馈 + PID（积分限幅、测量微分、条件积分） | 纯数学，不依赖 Minecraft，可离线跑数值自测 |
| `control/RouteProgress` | 沿进路的节点索引、剩余弧长、下一节点、转角 | 路线跟随的正确性全在这里，与方块解耦后可以单独验 |

### 11.2 Create 寻路 API 的现实

用户要求"使用机械动力寻路 API"。核对结果（`_recon/create-navigation.md` §6）：

- Create 唯一公开的寻路入口 `Navigation.findPathTo(...)` 是**实例方法**，且方法体读取
  `train.graph` / `train.carriages` / `train.runtime` / `train.doubleEnded`；
- 没有 `Train` 就没有寻路；为物理车伪造 `Train` 会把它注册进 `Create.RAILWAYS.trains`。

所以复刻它的**数据形状与搜索本身**：

- `train/CreatePathfinder` 按 `Navigation.search` 的字节码复刻前沿队列（A*，
  `distance + penalty + remaining`）、逐边访问一次、信号/车站/其他列车的惩罚、
  `canTravelTo`/`canNavigateVia`/`canApproachFrom` 判定、车站边的终点位置；
- `TrackNetwork.grantedRoute` 仍暴露同一形状的节点序列，供控制台跟随；
- 唯一不能照搬的是车厢决定的轨道类型过滤（物理车没有 `Carriage`），放宽为所有类型；
- 起点由车辆位置投影到最近节点的邻边（道岔处多条边都试，两个方向都搜，取 cost 最小）。

这不是绕开 API，而是在没有 `Train` 的前提下，对原版寻路语义能做到的最接近的复刻。

### 11.3 为什么必须"沿进路跟随"

原先驾驶台是"对着目的地开"：转向与减速都用直线上到目的地的距离。对一条直线成立，对下列情况不成立：

- 车站方块在轨道**旁边**——直线距离在进站时不再代表剩余路程；
- 环线/回头线——直线方向与行进方向相反；
- 岔路口——最近节点可能是**已经过去的**节点。

`RouteProgress` 用**单调前进的节点索引 + 沿节点累加的弧长**解决这三点；转角限速则在到岔路口前
就把速度降下来，而不是在辙叉上才刹车。

### 11.4 信号必须沿进路问，不能沿"航向"问

自动驾驶台最初复用驾驶台的 `signalAhead(level, from, heading, lookahead)`：给一个世界方向，
让图搜索按对齐程度挑分支。这在直线上成立，在**道岔**和**弯道**上不成立：

- 到下一个节点的直线在岔路口可能更对齐**另一条支线**，于是读到隔壁线路的信号；
- 在弯道上这条弦可能和两条出边都不对齐，搜索干脆返回"无信号"，而前方明明有红灯。

`TrackNetwork.signalAheadAlong` 改为**沿进路自己的节点逐边遍历**：走哪条边由进路决定，信号侧别由
"从哪个节点进入该边界"决定，距离按弧长累加，已过去的信号跳过。这与
[`TRACK_DEVICES_ARE_GRAPH_POINTS.md`](TRACK_DEVICES_ARE_GRAPH_POINTS.md) 的规则一致：
**轨道上的东西，问图；而"问图的哪条边"，问进路。**

### 11.5 道岔要"再扳一次"

`requestRoute` 授予进路时已经扳过一遍（§5.5）。但道岔是**世界状态**，不是进路的一部分：
别的车、玩家、自动道岔都可能在授予之后改动它。于是 `TrackNetwork.alignSwitchesAhead` 在行驶中
按**路线顺序仍在前面**、距离 48 格内的岔路口重新驱动一次；频率是"路线索引变化或每 5 秒"，
因为每次驱动都要问遍 Railways 道岔并打日志。

被玩家锁定的道岔永远拒绝（`RailwaysSwitchDriver` 先查 `isLocked()`），这是"玩家的决定不静默覆盖"
这条规则的延续。

**目标节点只说一个答案：进路的下一个节点。** 岔路口的所有邻接节点里，上一个节点同样"在进路上"，
早先的实现用"任意一个在进路上的邻接节点"作目标，于是经常选中来车方向，道岔被扳回原支线——
道岔确实动了、日志也写了成功，车却走了另一条路。现在改为在路线序列里**向前**定位岔路口序号，
取 `waypoints[index + 1]`；重复经过同一岔路口时，`indexOfNodeFrom` 保证每次拿到本趟的下一节点。
图上道岔对齐也只接受"通往该节点"的状态，没有就返回 0，不再探测附近节点后拿第一个答案充数。

### 11.6 单向输出与通道分离

自动驾驶台是**单向**的：只朝屏幕背面（操作员看屏幕的方向）驱动、只探测这一方向的信号，输出只有
`autopilot_forward` 与 `autopilot_brake` 两个通道，**没有后退通道**。

- 服务制动走 `VehicleHandle.command(..., brake, ...)` 的刚体减速冲量，不需要反向推力；
- `autopilot_brake` 是转向架自身的驻车/保持制动，停稳或未启程时给 15；
- 启程时把 FACING 经 `localToWorld` 转成世界方向并取反（屏幕朝操作员，驱动方向是屏幕背面，
  与驾驶台实测约定一致），与**进路第一条边**做点积；点积为负就拒绝启程（界面 `WRONG WAY`），
  而不是倒车。比较"第一条边"而不是"到目的地的直线"，是为了让绕环线去背后车站的进路仍然合法；
- 双向运行 = 两台控制台，一头一台，每台只管自己那一头。

通道仍**不复用**驾驶台的：DBW 的接收端对同一面取 `max`，两个方块同时在线时，一个的"0"不会覆盖
另一个的"10"，但一旦两个都在驱动，谁大谁赢——通道分开后，两个方块可以各管一套传动箱，也能并存调试。

### 11.7 与驾驶台的互斥

- 驾驶台有时刻表 → 自动驾驶台让位；
- 自动驾驶台已启程 → 驾驶台让位，且**停止下发指令**（不是下发停车——`hold()` 会施加制动冲量，
  两个控制器就会对打）；
- 同车两台自动驾驶台 → `CabRegistry` 先到先得，输的那台只待命，不碰按车辆记账的进路表。

### 11.8 未验证

与整个项目一样：编译通过、数值逻辑离线自测通过，但**没有进游戏**。单向拒绝、道岔重扳频率、
菜单在 plot 坐标下的可用性，都需要按 `IN_GAME_CHECKLIST.md` §12 实测。

---

## 12. 后续可做

- 驼峰调车自动化（推峰、溜放、减速器）目前只做了"去向识别 + 按地址寻址"的地基。
- 驾驶台 GUI 可加时刻表编辑（现在依赖把 Create 时刻表物品放进槽位，用 Create 自己的界面编辑）。
- 支持更多道岔模组：实现 `SwitchDriver` 即可。
- 若 Simurail 未来让转向架真正读取 `computerOverrides`，或提供公开的转向 setter，可以去掉 §7.2 的反射、并把转向改为真实惯量张量。
- 若 Sable 暴露"结构包含哪些方块"的查询，可以把 §9.8 的有界扫描换成精确查询。
