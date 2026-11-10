# Create 导航 / 进路 / 道岔 —— 由原始 javap 转储整理

> 说明：这一份本该由侦察子代理产出，但它在写报告前就结束了（原始转储见同目录 `nav_*.txt` / `rw_*.txt`）。
> 以下内容**全部来自那些转储**，未使用的推断一律标注。

## 1. 进路的形式：`DiscoveredPath`

```java
public class com.simibubi.create.content.trains.graph.DiscoveredPath {
  public java.util.List<net.createmod.catnip.data.Couple<TrackNode>> path;   // 有序的"节点对"序列
  public GlobalStation destination;
  public double distance;
  public double cost;
}
```

每个 `Couple<TrackNode>` 就是**一条边**（从哪个节点到哪个节点）。
因此**"岔路口走哪条出边"由下一对节点直接给出** —— 这正是扳道岔需要的判据，不需要另发明表示法。
我的 `CreateTrackNetwork` 现在产出 `List<TrackNode>`，转成**相邻节点对**即与原版同构。

## 2. 反向区分：原版就是一个布尔

```java
public class ...entity.Navigation {
  public boolean destinationBehindTrain;    // 目的地在车身后 = 需要反向运行
  public boolean announceArrival;
  java.util.List<Couple<TrackNode>> currentPath;   // 非 public
  public boolean isActive();
  public GlobalStation findNearestApproachable(boolean);   // 参数很可能就是 forward（未验证）
  public void search(double, boolean, ArrayList<GlobalStation>, Navigation$StationTest);
  public void search(double, double, boolean, ArrayList<GlobalStation>, Navigation$StationTest);
}
```

**要点**：原版**不判断"信号箭头朝向"**，而是判断**目的地是否在身后**，由此决定正/反向运行。
这比我原本打算的"点积求顺向"更简单，也更贴近原版语义。
`findNearestApproachable(boolean)` 与"顺向列车站"是同一件事，且是原版实现（**参数语义未验证**）。

## 3. 道岔：状态读写与"该往哪边扳"的映射（**齐了**）

```java
public class com.railwayteam.railways.content.switches.TrackSwitch
        extends com.simibubi.create.content.trains.signal.SingleBlockEntityEdgePoint {
  public TrackSwitchBlock.SwitchState getTargetState(TrackNodeLocation);  // ★ 我要去的节点 → 所需状态
  public boolean trySetSwitchState(TrackSwitchBlock.SwitchState);         // 可能被拒（锁定/自动）
  public boolean setSwitchState(TrackSwitchBlock.SwitchState);
  public TrackSwitchBlock.SwitchState getSwitchState();
  public TrackNodeLocation getSwitchTarget();
  public boolean hasStraightExit();  hasLeftExit();  hasRightExit();
  public boolean isAutomatic();  isLocked();  shouldAutoTrainsSwitch();
}

public class ...switches.TrackSwitchBlockEntity extends SmartBlockEntity
        implements TransformableBlockEntity, IHaveGoggleInformation {
  public TrackTargetingBehaviour<TrackSwitch> edgePoint;   // ★ public 字段
  public TrackSwitch getSwitch();
  public TrackSwitchBlock.SwitchState getState();
  public boolean isAutomatic();  isLocked();
  public boolean isNormal();  isReverseLeft();  isReverseRight();
  public boolean hasExit(TrackSwitchBlock.SwitchState);
}

public class ...switches.TrackSwitchBlock {
  public static final BooleanProperty LOCKED;
  protected boolean isAutomatic();
  public InteractionResult useWithoutItem(...);
}
```

### 由此得出的道岔驱动算法

```
对进路上的每一处岔路口（path 里 from 节点的出边多于一条）：
  1. 找到位于该岔路口的 TrackSwitch
     （可经 TrackSwitchBlockEntity.edgePoint.getEdgePoint()，或按图的 SWITCH 点位枚举）
  2. state = trackSwitch.getTargetState( 下一对节点里的"到达节点".getLocation() )   ← ★ 原版给的映射
  3. trackSwitch.trySetSwitchState( state )    // 返回 false 表示被拒（锁定/自动），据此记录日志
```

**关键**：`getTargetState(TrackNodeLocation)` 正是"我要去哪个节点 → 道岔该是什么状态"的**权威映射**。
我先前认为"只能靠几何猜哪条出边对应哪个状态"—— **那个顾虑现在消除了**。

## 4. 待验证

- `findNearestApproachable(boolean)` 的参数语义（是否为 forward）。
- `trySetSwitchState` 与 `setSwitchState` 的区别（推测前者遵守锁定/自动检查，后者不检查）。
- 如何**按位置**找到一个岔路口对应的 `TrackSwitch`：`TrackSwitch` 继承 `SingleBlockEntityEdgePoint`，
  所以它有 `getBlockEntityPos()` / `getBlockEntityDimension()` —— **可用方块位置匹配**，
  但注意驾驶台自身的坐标是 plot 坐标，匹配时必须与同处 plot 空间的转向架/方块实体比较（见 DESIGN §7.4）。

## 5. `TrackSwitch` 的内部结构（字节码已核实）——它决定了接入方式

```java
private TrackNodeLocation                                  switchPoint;
private final java.util.List<TrackNodeLocation>            exits;
private TrackNodeLocation                                  straightExit, leftExit, rightExit;
private TrackSwitchBlock.SwitchState                       switchState;

private TrackNodeLocation getExit(SwitchState);                                   // private
private java.util.Map<SwitchState, Optional<TrackNodeLocation>> getExistingExits(); // private
TrackNodeLocation getSwitchPoint();                                               // 包级私有
Collection<TrackNodeLocation> getExits();                                         // 包级私有

public SwitchState getTargetState(TrackNodeLocation);   // ★ = "找到通向该节点的状态"
public boolean      trySetSwitchState(SwitchState);     // → setSwitchState
public boolean      setSwitchState(SwitchState);        // 内部先 isStateValid(state) 再写入
public SwitchState  getSwitchState();
public TrackNodeLocation getSwitchTarget();             // 当前状态对应的出边节点
public boolean hasStraightExit(); hasLeftExit(); hasRightExit();
```

`getTargetState(node)` 的字节码：构建 `getExistingExits()`（`SwitchState → Optional<TrackNodeLocation>`），
返回**其出边节点与传入节点 `equals` 的那个状态**。

**接入结论（这是本文件里最重要的一条）**：出边字段与 `getExits()` / `getSwitchPoint()` 都是
**private / 包级私有**，**外部模组无法自行枚举出边**。所以"该往哪边扳"**只能**通过
`getTargetState(TrackNodeLocation)` 得到 —— 这也意味着**必须传入一个 Create 的 `TrackNodeLocation`**。

而我的进路（`path` 是 `List<Couple<TrackNode>>`）里的节点正是 `TrackNode`，
`TrackNode.getLocation()` 直接给出所需的 `TrackNodeLocation` ✅ —— **进路与道岔接口天然对得上**。

待定的只是一个分层问题：我的 `api/SwitchDriver` 目前是
`boolean drive(ServerLevel, BlockPos, boolean alternateBranch)`，不含任何 Create 类型。
要接入 `getTargetState`，需要把"目标节点"传进驱动。可选的两种做法：
1. 传目标节点的**世界坐标 `Vec3`**，由 railways 驱动用 `TrackGraph.locateNode(...)` 换回 `TrackNodeLocation`；
2. 让 `SwitchDriver` 接口直接暴露一个 Create 类型（破坏"api 不依赖 Create"的分层）。

**倾向做法 1**：保住分层，且目标节点本来就是一个真实节点，`locateNode` 在这个场景下是"精确命中"，
不存在之前"拿车体位置去找节点"那种容差问题。

---

## 6. `Navigation.findPathTo` 为什么不能独立调用（决定自动驾驶台的做法）

`findPathTo(GlobalStation, double)` 与 `findPathTo(ArrayList<GlobalStation>, double)` 是
**`Navigation` 的实例方法**，构造函数只接受 `Train`。对 `Navigation.findPathTo(ArrayList, double)`
的 `javap -c` 显示它读取以下字段：

```
train.graph
train.carriages.get(0).getLeadingPoint()
train.carriages.get(size-1).getTrailingPoint()
train.hasForwardConductor()
train.hasBackwardConductor()
train.runtime.paused
train.doubleEnded
```

调用链末端是 `search(double, double, boolean, ArrayList<GlobalStation>, StationTest)`，
同样以 `train` 的车厢为起点。

**结论**：Create 6.0.10 **没有**一个"给任意起点/终点做图搜索"的公开静态 API。可复用的只有：

- 数据结构：`TrackGraph` / `TrackNode` / `TrackEdge` / `TrackNodeLocation` / `DiscoveredPath`；
- 代价模型：`TrackEdge.getLength()`、`EdgeData.getEffectiveEdgeGroupId()`；
- 邻接：`graph.getConnectionsFrom(node)`；
- 道岔的权威映射：Railways `TrackSwitch.getTargetState(TrackNodeLocation)`。

自动驾驶台因此复刻 `DiscoveredPath` 的**节点序列**语义（`TrackNetwork.grantedRoute`），
而不是伪造一个 `Train` 去喂 `Navigation`——伪造会把它注册进 `Create.RAILWAYS.trains`，
副作用远大于收益。

