# 方向感知路由与提示（待确认后实施）

## 0. 需求原文要点

1. **像机械动力原版一样，在"找不到进路"和"脱轨"时给出提示。**
2. **能探测"后方顺向"的列车站与信号机，并倒退至目的地。**
3. **顺向的定义：信号机/列车站在轨道上的箭头指向与行进方向一致。**
4. **若这段进路上没有顺向的信号机/列车站，直接返回"获取进路失败"。**

## 1b. 探针（probe）：需要开启、且有距离范围 —— **实测为空**

实测：未开启探针时，`probeAhead()` 的每个字段都返回 `-1` / 空：

```
probe: signal d=-1.0 aligned=false names=[] | station d=-1.0 'null' | discontinuity d=-1.0
```

用户确认：**必须在转向架中开启探针**，而且**探针有距离范围**。

### 由此确定的两条设计规则

**规则一：探针不是唯一来源，而是"更准的来源"。**
它需要玩家开启、且看得有限远。因此：

```
探针报了东西        -> 用它（引擎沿轨算的，比我的直线几何准）
探针什么都没报      -> 回退到自建几何（见 §1 公式 2）
```

**规则二（关键）："探针沉默"绝不能被当成"没有顺向目标"。**
需求第 4 条要求"没有顺向目标 → 立即失败"。如果拿探针的沉默去触发它，
而探针默认是**关闭**的，那么结果就是**每一次申请进路都立即失败、车永远出不了库**。

这正是本轮实测暴露出来的：如果当时直接把判定接上探针，"立即失败"会**恒定触发**，
而症状会表现为"进路全部失败"，几乎不可能反推到"探针没开"。

因此"没有顺向目标"只能由**自建几何**判定（它不依赖任何运行时开关），
探针只用于**在它说话时提高精度**。

## 1. "顺向"的定义 —— **已用字节码核实**


Create 的约定（三处独立证据：`TrackTargetingBehaviour.createEdgePoint`、`TrackGraphHelper.getBezierGraphLocationAt`、
`Navigation` 的比较式）：

```
TrackEdgePoint.position  = 从 edgeLocation.getFirst() 沿边朝向 getSecond() 的**方块距离**
getLocationOn(edge)      = distance in blocks from edge.node1
isPrimary(node)          = node.getLocation().equals(edgeLocation.getSecond())
TrackEdge.getPosition(graph, t) 的 t 是归一化 0..1，t=0 → node1，t=1 → node2
```

**判定顺向的两条可用路径：**

**路径 A（Create 原生，权威）** —— `nodeAhead` = 我在当前这条边上正驶向的那个节点：

```java
顺向(信号机) = signalBoundary.canNavigateVia(nodeAhead)
顺向(列车站) = station.canApproachFrom(nodeAhead)      // = isPrimary(nodeAhead) && !assembling
```

**路径 B（纯几何，不依赖朝向约定）**：设 A/B 为 `edgeLocation.getFirst()/getSecond()` 两节点的世界坐标，

```java
顺向 <=> dot(行进方向, normalize(B - A)) > 0
```

**决定采用路径 A** —— 它能直接读，不需要我用几何去重建语义（这是我在"换向器"上犯过的错）。
路径 B 作为交叉校验保留。

**额外发现（最省事的一条）**：**Simurail 自己已经在算信号的顺向**，并且对外可读：

```java
bogie.getAxle(boolean).getProbeData().isSignalAligned()   // 内部即 signal.canNavigateVia(ahead)
```

所以信号机的顺向**不必自己算**；只有**列车站**需要我自己调 `GlobalStation.canApproachFrom`（Simurail 没有车站的顺向标志）。

**修正一处先前的猜测**：`SignalBoundary.blockEntities` 里的 `Boolean` **不是"哪一侧"**，而是
`SignalBlockEntity.getReportedPower()`（逐方块的红石供电标志，用于强制红）。**方向在 `Couple` 的下标里**
（`getFirst()` = POSITIVE 方向，`getSecond()` = NEGATIVE 方向）—— 我原来的猜测是错的，幸好在写代码前核实了。

另外：信号方块/车站方块**都没有朝向属性**（`SignalBlock` 只有 `TYPE`/`POWERED`）。
视觉上的箭头来自方块实体的 `TrackTargetingBehaviour.targetDirection`（`Direction.AxisDirection`，
NBT 键 `TargetDirection`）—— 这解释了为什么你在游戏里"看不到 edgeLocation"：**它本来就不是一个可见概念**。


## 2. 目标行为

```
获取进路时：
  1. 先按原逻辑向前寻找目的地（顺向、在前方）；
  2. 若前方没有，则寻找"后方顺向"的列车站/信号机作为**倒车目标**；
  3. 找到 -> 进路目标设为该点，并以**倒退**方式前往；
  4. 两者都没有 -> 立即返回失败，并提示"这段进路上没有顺向的信号机/列车站"。
```

关键：第 4 条要求**立即失败**，而不是像现在这样"退回申请进路并持续重试" —— 重试对"这段轨道上压根没有顺向目标"这种情况永远不会成功，
应当一次性明确报错（与第 1 条的提示需求是同一件事）。

## 3. 提示（对齐原版）

Create 的提示走 `TrainStatus`（`missingConductor()` / `failedNavigation()` / `failedNavigationNoTarget(String)` 等），
在驾驶台上以文字+音效呈现。本模组不是 `Train`，无法直接调用，因此**自行实现等价提示**：

| 情况 | 提示 |
|---|---|
| 这段轨道上没有顺向目标 | "此段轨道上没有顺向的信号机或列车站" |
| 有目标但搜不到路径 | "找不到通往 <目的地> 的进路" |
| 调度中心拒绝 | "进路被调度中心拒绝：<原因>" |
| 脱轨 | "车辆已脱轨" |

载体：动作栏消息（已有机制）+ 日志。原版是常驻文字，动作栏会淡出；
若要更接近原版，需要一个客户端常驻显示（即 HUD 那条网络通道，见 `CAB_INTERFACE.md` §4）。

## 4. 脱轨检测 —— **已核实，有现成的公共 API**

```java
com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity
    public boolean isDerailed();   // == !(axleFront.hasTrack() && axleBack.hasTrack())
    public boolean hasTrack();     // == axleFront.hasTrack() || axleBack.hasTrack()
    public PhysicsBogeyAxle getAxle(boolean);   // 选前/后轴

com.crystaelix.simurail.content.bogey.PhysicsBogeyAxle
    public boolean hasTrack();     // == (trackSegment != null)，唯一的状态来源
```

**判定：任一转向架 `isDerailed()` 即为脱轨。**

**必须注意的陷阱**：`isDerailed()` **不等于** `!hasTrack()` ——
一个轴脱轨时 `hasTrack()` 为真（另一轴还在轨上）而 `isDerailed()` 已为真。
所以两者语义不同，不能互相替代；提示用 `isDerailed()`，判断"完全离轨"才用 `hasTrack()`。

另外 `TravellingPoint.blocked` 表示"信号禁止进入"，**不是**脱轨，不要误用；`trackGraph` 在脱轨时也**不会**变 null。


## 5. 与现有实现的冲突点

1. 现在"找不到路"会 **10 秒重试一次**（`retryTimer`）。第 4 条要求**立即失败并提示**，需要区分
   "暂时失败（区间占用，值得重试）"与"永久失败（本段没有顺向目标，重试无意义）"。
2. **倒退**需要 `backward` 通道有值 + 制动仍由本模组施加。当前自动化只会产生正油门，
   需要让目标在后方时产生负油门。
3. 目的地解析（`destinationPosition`）现在是"无障碍的全局查找"，必须加上**同向性过滤**。
