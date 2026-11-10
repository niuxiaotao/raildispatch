# 自动驾驶台（Autopilot Console）

> 方块：`raildispatch:autopilot_console`
> 代码：`block/AutopilotConsoleBlock.java`、`block/entity/AutopilotConsoleBlockEntity.java`、
> `control/SpeedController.java`、`control/RouteProgress.java`、`autopilot/AutopilotState.java`

---

## 1. 它解决什么问题

物理驾驶台（`physics_cab`）的起点是**一张 Create 时刻表**：要先编辑条目、再放进驾驶台。
自动驾驶台把这件事缩成一句话：**输入目的地，按「启程」。**

它不做时刻表的条目推进、不做连挂，只做一件事——**把车从当前位置开到目的地**：

| 能力 | 做法 |
|---|---|
| 寻路 | **复刻 Create `Navigation.search`**（A* + 逐边访问 + 信号/车站/列车惩罚 + `canTravelTo`）；车站目的地走这条，地址/车辆标签走几何兜底 |
| 沿进路行驶 | 按**节点序列**逐点跟随，而不是对着目的地直线开 |
| 道岔 | 进路授予时全扳一遍；行驶中**在接近岔路口前再扳**（到达新节点时 / 至少每 5 秒） |
| 信号 | 只沿**本台自己的行驶方向**（进路节点序列）读取前方信号机，红灯按匀减速曲线停 |
| 牵引 / 制动 | Drive By Wire **两个**命名红石通道（前进、驻车制动），没有后退通道 |
| 调速 | 前馈 + **PID**（积分限幅、测量微分、条件积分抗饱和） |
| 方向 | **单向**：只朝屏幕背面（操作员看屏幕的方向）开；目的地在背后就拒绝启程，要双向就装两台 |

---

## 2. 使用

1. 把方块装在物理车上，**随结构一起组装**；站在车尾、**让屏幕朝向你**放置——车会朝你放置时看的方向
   （屏幕的反方向）开。它只朝这个方向开，装反了会提示 `WRONG WAY`。
2. 空手右键打开界面：

```
目的地 (车站 / 地址标签 / 车辆标签)   [____________________]
[启程] [停车] [清除]
状态        自动行驶中
速度/目标   8.2 / 12.0 m/s
前方信号    绿灯通行
进路        locked, 342 blocks, 5/12
牵引/制动   68% / 0%
```

3. 输入目的地后按「启程」（或按回车）。潜行 + 空手右键 = 清除并停车。
4. 目的地在本台背面时，界面标 `WRONG WAY`、日志写明原因，**不会倒车过去**；换一台朝那个方向的
   控制台即可。双向运行 = 车头车尾各装一台。

目的地解析顺序与时刻表一致（`TrackNetwork.resolveDestination`）：

| 写法 | 解析为 |
|---|---|
| 车站名（支持 `货场*` 之类的通配） | 车站**在轨道上的定位点**（不是车站方块） |
| 地址标签名 | 最近的同名地址牌 |
| 车辆标签名 | 那辆车**当前的位置** |

---

## 2.1 机械动力时刻表（Create Train Schedule）

控制台**不自建时刻表**：它执行 Create 的 **Train Schedule** 物品。

- 控制台左侧有时刻表槽。把 Create 的 Train Schedule 放进去，或手持时刻表**潜行右键**控制台；
  空手潜行右键（槽里有表时）把表取回。
- 装入后按「启程」，控制台执行表里的**当前条目**，而不是目的地输入框；目的地输入框只用于
  没有装表时的一次性单目的地。
- 读取的条目类型：
  - `DestinationInstruction`：目的地名交给本模组的车站/地址/车辆标签解析；
  - `CoupleVehicleInstruction`：车辆标签；
  - `ShuntByAddressInstruction`：地址标签。
- 定时等待（`TimedWaitCondition`）按 Create 的等待条件执行：到站后 `Holding`，等待结束再
  `Schedules.advance` 前进到下一条。
- 循环由时刻表自身的 `cyclic` 标志决定；非循环表跑完最后一条后显示 `schedule complete` 并停车。
- 条目进度写回时刻表物品的 `savedProgress`（和 Create 自己编辑的是同一个数据组件），所以取出
  时刻表时它停在当前条目。
- 其它条目类型（改油门、包裹、改名等）当前不执行：控制台会停下并显示
  `schedule entry has no supported destination`，不会猜一个目的地继续开。

## 3. 站到站：复刻原版 `Navigation.search`

目标解析出来是**车站**时，`CreateTrackNetwork.planRoute` 先交给 `train/CreatePathfinder`，它按
`Navigation.search` 的字节码逐条复刻：

| 原版行为 | 复刻方式 |
|---|---|
| 前沿按 `distance + penalty + remaining` 排序 | 同样的 `Frontier` 比较器；`remaining` 是原版的加权曼哈顿启发（车站 `position` + 三个轴向系数） |
| 每条边只访问一次 | 同样的 `Set<TrackEdge>` 访问表 |
| 信号：强制红 `+400`；占用区间 `+signalPenalty` 并减半；`canNavigateVia` 不通过则不可走 | 同样的 `SignalBoundary`/`SignalEdgeGroup` 逻辑 |
| 车站：有别的车 `+300`、不可接近 `+50`、`canApproachFrom` | 同样的判定 |
| 其他列车端点边加各自 `getNavigationPenalty()` | 同样的 `getEndpointEdges` + `graph.getConnection` |
| 轨道兼容 `edge.canTravelTo(next)` | 同样检查（道岔非活动支线因此走不通） |
| 只到车站为止，终点取车站边上的 `position` | 搜索只接受 `GlobalStation`，终点仍用车站定位点 |
| `DestinationInstruction` 传 `Double.MAX_VALUE` 作为距离上限 → 启用惩罚、按 cost 选方向 | 同样用 `MAX_VALUE`；所有候选起点里取 cost 最小 |

**不可避免的适配**（物理车没有 `Train`/`Carriage`/`TravellingPoint`）：

- 没有车厢，所以轨道类型过滤放宽为“所有类型”（原版取各车厢转向架的合法寻路类型交集）；
- 起点边与边内位置由车辆位置投影到最近节点的各条邻边得到；正好站在道岔上时会得到多条同样近的
  边，**每条都按两个方向各搜一次，取 cost 最小**，对应原版从一个确定边出发的自由度；
- 不知道 `ticksWaitingForSignal`，信号惩罚从原版下限 25 起算。

找不到车站（地址牌 / 车辆标签）或原版搜索无解时，回退到原来的几何规划器，保证控制台不会因
不支持的图形态而完全失效。配置 `pathfinding.nativeStationRouting = false` 可以整体关掉复刻搜索、
只用几何兜底——用于 A/B 对比或图上出现异常时的排障。

---

### 3.1 为什么不是直接调 `Navigation.findPathTo`

用户要求"使用机械动力寻路 API"，而 Create 真正公开的寻路入口只有一个：

```java
public DiscoveredPath findPathTo(GlobalStation destination, double distanceBias);
public DiscoveredPath findPathTo(ArrayList<GlobalStation> destinations, double distanceBias);
```

它是 `com.simibubi.create.content.trains.entity.Navigation` 的**实例方法**，而 `Navigation`
的构造函数只接受 `Train`。对 `javap -c` 的字节码核对（见
[`_recon/create-navigation.md`](../_recon/create-navigation.md) §6）显示，`findPathTo` 的方法体读取：

```
train.graph
train.carriages.get(0).getLeadingPoint() / getTrailingPoint()
train.hasForwardConductor() / hasBackwardConductor()
train.runtime.paused
train.doubleEnded
```

也就是说，**没有 `Train` 就没有寻路**；而本项目操纵的是 Sable 物理刚体，不是 Create 的 `Train`。
伪造一个 `Train` 会把它注册进 `Create.RAILWAYS.trains`，副作用远大于收益。

因此实现上**复刻了 Create 的路由语义**：同样的 `TrackGraph`、同样的 `TrackNode`/`TrackEdge`、
同样的"一条边 = 一对节点"的 `DiscoveredPath` 形状，用带权 Dijkstra 求出节点序列，
并把节点序列暴露成 `TrackNetwork.grantedRoute(...)` 供驾驶台/自动驾驶台**跟随**。
这是在不伪造 `Train` 的前提下，对 Create 寻路 API 能做到的最接近的对接。

> 结论不是"没用 Create 的 API"，而是"用了 Create 公开的图数据结构与道岔/信号语义；
> 唯一没有复用的，是与 `Train` 硬绑定的那个方法"。

---

## 4. 沿进路行驶（`RouteProgress`）

进路是**有序节点序列**。跟随它要回答三个问题：

1. **下一个节点是哪个**——它一定在轨道上。车站方块在轨道**旁边**，目的地若是一座环线尽头的站，
   对着它直线开就是切弯/出轨。
2. **还剩多远**——沿节点**累加弧长**，而不是直线距离。这是减速曲线在弯道和回头线上仍然正确的原因。
3. **下一个弯有多急**——用进入腿与离开腿的夹角决定限速，并在**到岔路口前**就减速。

索引**单调前进**：只在"已到达当前节点"或"离下一个节点更近了"时前进一位。
若用"最近节点"每刻重算，车会在道岔辙叉处来回跳，并被要求开回已经过的节点。

限速：

```
目标速度 = min(巡航速度,
               曲线限速(到岔路口距离 <= 40 格才生效，<12° 不限，<35° 9 m/s，<70° 6 m/s，否则 4 m/s),
               停车曲线(剩余弧长),
               信号曲线(信号距离 - brakeMargin))
```

停车曲线：`v = sqrt(2 × a × 可用距离)`，`a = 1.0 m/s²`（比实测可达的 1.46 保守，留出控制滞后余量）。

---

## 5. 道岔

`SwitchDriver` 扩展点（见 `api/SwitchDriver.java`）：

- 进路**授予时**：`CreateTrackNetwork.requestRoute` 调 `alignSwitches`，把整条进路上的岔路口都扳到位。
- 行驶中：`TrackNetwork.alignSwitchesAhead(level, vehicle, from, 48)` 只重扳**路线顺序上还没过去**、
  且距离在 48 格内的岔路口（`CreateTrackNetwork.alignSwitchesAhead`）。
- 每 20 tick 最多一次，且只在**路线索引变化**或**距上次 5 秒**时真正执行——道岔驱动会问遍范围内的
  Railways 道岔，且驱动本身会打日志，逐 tick 调用既贵又吵。

**目标节点必须是进路里的“下一个”节点**，不是岔路口任意一个“也在进路上”的邻接节点：
上一个节点同样在进路上，若把它交给道岔，道岔就会扳回**来车方向**——道岔动了、日志说成功，车却走了另一条路。
实现上：

- 先用 `indexOfNodeFrom(...)` 在路线里**向前**找这个岔路口的序号（重复经过同一岔路口也能区分），
  目标就是 `waypoints[index + 1]`；
- 岔路口的 `TrackNode` 用**进路节点精确 `locateNode`** 得到，不再扫“4 格内最近的节点”
  （并列/立交处会拿到别的轨道上的节点）；
- 图上道岔对齐只接受 `getTargetState(目标节点)` 的答案；**没有出口通往目标就什么都不做**，
  不再“探测 8 格内任意节点”后拿第一个答案充数——那正是扳错支线的来源；
- 兜底的方块扫描也使用同一个目标节点，驱动仍会先查 `isLocked()`。

Railways 的驱动（`train/railways/RailwaysSwitchDriver.java`）用的是它自己的权威映射：

```java
TrackSwitch trackSwitch = be.getSwitch();
SwitchState state = trackSwitch.getTargetState(nodeLocation);  // "哪个状态通向这个节点"
if (trackSwitch.isLocked()) return false;                      // 玩家锁定的道岔绝不覆盖
trackSwitch.trySetSwitchState(state);
```

**被玩家锁定的道岔会被拒绝驱动并记日志**——锁闭是玩家的明确决定，路由必须绕行或失败，
不能被静默推翻。

---

## 6. 信号与安全

- 信号读取用 `TrackNetwork.signalAheadAlong`：**沿进路自己的节点序列逐边走**，找第一个治理本车的
  信号边界；侧别由"进路从哪个节点进入该边界"决定（`boundarySide`），距离按进路弧长累加。
- **为什么不能按"航向"找**：到下一个节点的直线在道岔处可能对准另一条支线，在弯道上可能哪条边都对不齐。
  按方向走图就会读到**隔壁线路的信号机**，或者明明前方有一盏红灯却报"无信号"——这是本方块
  早期版本实际出现过的"信号机探测错误"。进路已经知道走哪条边，信号就必须沿那些边问。
- 已经过去的信号（距离 < 1.5 格）会被跳过，不会被当成"很近的红灯"。
- 红灯：按 `brakeMargin` 提前量停车；距离未知（区间被占但看不到信号机）→ **直接停车**。
- 黄灯：限速 4 m/s 爬行。
- 红灯一直不灭且本车就压在信号自己的区间里？本方块**不做 call-on**：它只停。
  需要"压着信号爬出去"的场合请用驾驶台的 `creepAfterTicks` 配置。

---

## 7. Drive By Wire 通道

方块实现 `edn.stratodonut.drivebywire.wire.MultiChannelWireSource`，所以拿 **Drive By Wire 的线**
（`drivebywire:wire`）右键本方块再滚动鼠标，就能在两个通道间切换：

| 通道 | 含义 | 取值 |
|---|---|---|
| `raildispatch:autopilot_forward` | 前进牵引（本台驱动方向，即屏幕背面） | 0..15 |
| `raildispatch:autopilot_brake` | 转向架自身制动（停车保持） | 0..15 |

**没有后退通道**：本台是单向控制台，不提供反向推力。服务制动由
`VehicleHandle.command(..., brake, ...)` 施加**刚体减速冲量**完成；`autopilot_brake` 是转向架自己的
驻车/保持制动，在停稳或未启程时给 15。

发射走 `WireNetworkManager.trySetSignalAt(level, pos, channel, value)`（Drive By Wire 的公开静态入口）。
通道名与物理驾驶台的**故意不同**：

- 两个方块可以同时装在一辆车上，互不覆盖；
- 玩家也可以把两台方块接到不同的传动箱上。

约定：

- 只有正向牵引走 `forward`；`brake` 与 `forward` 永不同时非零；
- 完全停稳或未启程时（`HOLDING` / `ARRIVED` / `PLANNING` / 待命）`forward = 0`、`brake = 15`，
  避免"牵引顶着停车制动"；
- 方块被拆除时会把两个通道**清零**，不会留下一个还在给油的幽灵信号。

> ⚠️ **牵引必须自己接线。** 本模组不直接驱动转向架（`VehicleHandle.command` 只施加上层制动冲量）。
> 拿 `drivebywire:wire` 把 `autopilot_forward` 接到车上的传动箱/驱动源，`autopilot_brake` 接到转向架刹车。
> 不接线时：**制动有效、牵引为零**，车不会动。这与物理驾驶台一致。

> 通道值不做 0..15 以外的编码；Drive By Wire 本身不限制上限，但 0..15 足以表达 16 档。

---

## 8. 单向行驶

本台**只朝屏幕背面开**（站在屏幕前看的方向），没有后退通道，也没有"倒车/换向"逻辑。方向判定在
启程时做一次：

```
驱动方向 = -transform(localToWorld, FACING)          // 屏幕朝你，车朝你看的方向；必须过车辆姿态
进路第一条边 = normalize(waypoints[1] - waypoints[0])
若 dot(驱动方向, 第一条边) < -0.1：
    释放进路、解除启程、界面 WRONG WAY、日志写明 → 停住，不倒退
否则：正常行驶
```

要点：

- 比较的是**进路的第一条边**，不是"到目的地的直线"：绕环线去一个背后的目的地时，第一边仍是向前的，
  本台可以正常绕过去；只有第一边就指向背面时才拒绝。
- 方块放置时屏幕朝向玩家，所以**驱动方向 = FACING 的反方向**（你站在车尾看屏幕的那个方向），
  与驾驶台实测的"前进通道驱动屏幕背面"一致；要反方向就换另一头的控制台。
- `FACING` 是结构内局部方向，必须经 `vehicle.localToWorld(...)` 转成世界方向再比较；直接比较是错的。
- 选择"拒绝"而不是"倒车"：反向推力、反向信号侧、以及取决于玩家接线的极性，是三处独立的错向来源；
  而车头车尾各装一台控制台就能覆盖双向运行，语义也更接近现实中的双端司机室。
- **已知限制**：进路中途若要求折返（同一段轨道原路返回），本台无法执行；需要折返的作业请用两台控制台
  或驾驶台。

---

## 9. PID（`control/SpeedController.java`）

```
前馈 = 目标速度 / fullSignalSpeed
误差 = 目标速度 - 实测速度
积分 += 误差 × dt           （限幅 ±pidIntegralLimit；输出饱和且误差同向时不再累加）
微分 = -d(实测速度)/dt      （对**测量**求导，目标阶跃不会产生微分冲击）
输出 = 前馈 + Kp·误差 + Ki·积分 + Kd·微分      （截断到 -1..1）
```

- 输出 > 0 → 牵引，< 0 → 制动，**永不同时**；
- 牵引按 `0.22/s` 的速率上升、可瞬时切断；制动可缓加、**瞬时释放**
  （"起步时还咬着闸"比"松闸慢一点"明显得多）；
- 实测速度用 `VehicleHandle.speed()`（位移法），对 Sable 的刚体抖动免疫；
- 控制周期 5 tick（4 Hz），`dt` 用真实游戏刻计算，改周期不会改变整定含义。

可调项（`config/raildispatch-common.toml`，`[autopilot]`）：

| 键 | 默认 | 说明 |
|---|---|---|
| `fullSignalSpeed` | 12.0 | 满信号稳态速度（前馈分母，按车设定） |
| `pidKp` / `pidKi` / `pidKd` | 0.22 / 0.05 / 0.06 | PID 增益 |
| `pidIntegralLimit` | 3.0 | 积分限幅 |

> 这些值在方块实体构造时读入 PID。改配置后，**已有的自动驾驶台仍用旧增益**；重新放置方块
> （或重新进世界）才会用新值。`fullSignalSpeed` 每次控制周期现读，不受此限。

> `SpeedController` 是独立类，不依赖 Minecraft 类型，可离线用纯 Java 驱动测试。
> 开发期用一段临时程序验过：目标 8 m/s 收敛、零目标停车不反向；
> `RouteProgress` 的节点推进/剩余弧长/到达判定符合预期。该程序不在 jar 里。

---

## 10. 红石与比较器

- **红石输入 = 紧急停车**：方块被充能时立即解除自动驾驶、释放进路、抱闸；
  红石撤销后**不会自动恢复**，必须重新按「启程」。这样一根拉杆不会被误当成第二套油门。
- 比较器输出：

| 状态 | 输出 |
|---|---|
| 空闲（未启程） | 0 |
| 规划进路中 | 4 |
| 自动行驶中 | 8 |
| 减速接近 | 11 |
| 停车等待（红灯 / 脱轨 / 进路失败） | 15 |
| 已到达 | 2 |

方块 `POWERED` 状态在真正驱动时点亮（模型换成亮屏）。

---

## 11. 与物理驾驶台共存

- 物理驾驶台装了**时刻表** → 自动驾驶台让位（`detail = schedule cab in charge`），
  且把 `engaged` 置回 false。摘掉时刻表后需要重新启程。
- 自动驾驶台**已启程** → 驾驶台让位：它不再下发任何指令（不是"下发停车"，那会和自动驾驶台对打）。
- 同一辆车上**两台自动驾驶台**：先声明者通过 `CabRegistry` 获胜，另一台只待命、不发指令、
  也不碰按车辆记账的进路表（否则会把正在行驶那台的进路释放掉）。
- **双向运行**就是两台控制台、一头一台：每台只负责自己的方向、只探测自己方向的信号，
  不会出现"一台想前进、一台想后退"的争夺。需要跑哪一头，就在朝向那一头的那台上按启程。

---

## 12. 已知限制（诚实清单）

1. **未在游戏内实测。** 本机无外网、无 Gradle 缓存，无法启动客户端。代码对着真实 jar 编译通过，
   `SpeedController` / `RouteProgress` 有离线数值自测；运行时行为需要按
   [`IN_GAME_CHECKLIST.md`](IN_GAME_CHECKLIST.md) §12 验证。
2. **不支持倒车 / 中途折返**：没有后退通道，目的地或进路第一边在背面时会拒绝启程（`WRONG WAY`）；
   进路中途要求原路折返的作业本台无法执行，请用另一头的控制台或驾驶台。
3. **道岔只在有 Railways 时能被扳**：本模组不自带道岔方块；没有 `SwitchDriver` 时进路仍会锁闭，
   但地面道岔不会动。
4. **进路丢失即重规划**：`grantedRoute` 是会话状态，重启/被别的控制器释放后自动驾驶台会回到
   `PLANNING`，重新申请，而不是继续沿着一条没人保留的进路开。
5. **不做 call-on**：红灯不灭时只停车，不爬行。
6. **不做连挂**：要连挂请用驾驶台的 `连挂车辆` 指令。
7. **站点到达半径**（`driving.arriveRadius`，默认 5 格）同时是"算到达"的容差；
   如果地址牌离轨道太远，可能永远进不了该半径。此时会触发越过后 3 格内的兜底停车。
