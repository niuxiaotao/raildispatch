# 轨道设备都是图上的点（项目规则）

> 这条规则不是"建议"，是**本项目最强的一条约束**。到目前为止，违反它的每一次都产生了
> 同一类 bug：设备被按"方块"处理 → 用位置/距离/半径去猜 → 猜错 → 表现为"读到了别的
> 信号机"、"道岔从不动作"、"目的地指到隔壁股道"。

## 规则

**任何放到轨道上的设备，都是 `TrackEdgePoint`（图上的点），并注册一个 `EdgePointType`。**

因此：

| 要问的事 | 只能这样问 | 绝不能这样问 |
|---|---|---|
| 这是哪个设备 | 用 `edgeLocation`（一对 `TrackNodeLocation`）与图上的节点比对 | 离某个方块多远 |
| 设备在哪里 | `edgeLocation` 两端节点的世界坐标（或其连线） | `getBlockEntityPos()` |
| 沿线有多远 | `TrackEdge.getLength()` 累加（**弧长**） | 直线距离 |
| 它是否在我前方 | 沿图遍历，**第一个**遇到的即是 | `dot(方向, 位置差)` |
| 它朝向哪一侧 | `isPrimary(我进入的节点)` / 设备自己的出口节点 | 几何点积 |
| 枚举所有设备 | `graph.getPoints(EdgePointType)` | 以某点为中心扫方块半径 |

**方块位置只是实体恰好站着的地方**：它可以偏离轨道、可以不存在，也**不是**设备的位置。

## 已核实的 API（本项目用到的）

| 设备 | 类型常量 | 备注 |
|---|---|---|
| 信号边界 | `EdgePointType.SIGNAL`（Create） | `blockEntities` 是 `Couple<Map<BlockPos,Boolean>>`，两侧 = 两个方向 |
| 车站 | `EdgePointType.STATION`（Create） | `edgeLocation` 即站台所占的那段轨道 |
| 观测器 | `EdgePointType.OBSERVER`（Create） | |
| **道岔** | `com.railwayteam.railways.registry.CREdgePointTypes.SWITCH` | **Create 里没有 SWITCH**；道岔来自 Railways |

`TrackSwitch extends SingleBlockEntityEdgePoint`（因此**有 `edgeLocation`**）：

```java
public SwitchState getTargetState(TrackNodeLocation)     // 哪个状态通向该节点
public boolean     trySetSwitchState(SwitchState)
public TrackNodeLocation getSwitchTarget()
// 包内可见：getSwitchPoint(), getExits() —— 需要时再考虑反射或换路
```

## 我们往后自己做的轨道设备

**如果它是一个放在轨道上的设备（无论外观是方块还是别的），也必须是图上的点**：

1. 定义一个 `TrackEdgePoint` 子类 + 一个 `EdgePointType`（用 `EdgePointType.register(...)`）；
2. 设备的位置由 `edgeLocation` 决定，**不要**把方块位置当作它的逻辑位置；
3. 任何"判断它是否属于某条进路/某段轨道"的代码，都应与节点做**相等比较**，不做距离比较。

**反例（本项目真实踩过）**：地址标签 / 车辆标签是**方块设备**（不放在轨道上），
它们按"绑定关系"工作 —— 那是另一类，不要混进来。

## 症状对照表（违反规则时你会看到什么）

| 症状 | 真实原因 |
|---|---|
| 读到别的线路的信号机 | 按方块位置/距离筛选，或按"共享端点"匹配边 |
| 道岔从不动作 | 扫方块半径找道岔；或把 `Vec3` 交给需要 `TrackNodeLocation` 的接口 |
| 目的地指向隔壁股道 | 解析出坐标后按"最近的站点"反推车站 |
| 停车点/减速点偏早或偏晚 | 用直线距离而不是沿图弧长 |

## 一句话

**轨道上的东西，问图；方块上的东西，问方块。** 两者混用时，谜题会以"时好时坏"的形式出现。
