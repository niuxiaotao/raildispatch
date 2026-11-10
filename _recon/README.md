# `_recon` — 开发期 API 侦察记录

这个目录**不是**模组的一部分，也不会打进 jar。它是开发过程中对目标实例里真实 jar 做逆向核对的记录，
留着是为了让以后维护的人知道"某个签名当时是从哪个类、怎么确认的"，而不是重新猜一遍。

## 报告（值得保留）

| 文件 | 内容 |
|---|---|
| `create-train-api.md` | Create 6.0.10 铁路 API 全图：时刻表指令扩展点、`ScheduleEntries`、信号占用链、`TrackGraph`、车站、`railways` 的道岔 API，以及"Create 没有运行时连挂 API"的结论与证据 |
| `create-navigation.md` | 进路/道岔的字节码核对，以及 §6 `Navigation.findPathTo` 为何必须绑定 `Train`（决定自动驾驶台复刻节点序列而非调用它） |
| `drivebywire-api.md` | Drive By Wire 0.2.9 的红石通道 API：通道即字符串、`trySetSignalAt` 发射、接收端靠 `getSignal` 轮询、无订阅/无注册表 |
| `aeronautics-sable-api.md` | Sable 2.0.3 / Aeronautics 1.3.0 / Simurail 的物理 API：`SubLevel` 查找、`RigidBodyHandle`、约束、转向架、驾驶台类方块，以及整个生态里"哪些类真正被第三方 mod 用过"的常量池交叉引用结果 |
| `build-feasibility.md` | 离线构建可行性实测：为什么 Gradle 不可用、javac 直编的 classpath 组成、以及三个 argfile 陷阱 |

## 其余文件

`*.txt`、`lists/`、`raw/`、`extract/`、`*_bundle/` 都是**可再生的临时产物**（jar 清单、`javap` 输出、
从模组里抽出来的内嵌 jar），已被 `.gitignore` 忽略，随时可以删。

## 关键结论（如果只想看三条）

1. **Create 6.0.10 没有道岔方块。** 道岔在 Create: Railways 里（`TrackSwitch`），所以本模组把它做成软依赖 +
   `SwitchDriver` 扩展点。
2. **Create 没有运行时连挂两列车的 API。** 连挂只能在物理层做：Simurail 的 `AutomaticCouplerBlockEntity`
   用 Sable 约束把两个 sub-level 连起来，把两个车钩互设 partner 才是真的挂上。
3. **物理转向架没有公开的转向 setter。** 唯一的数值控制面是 `PhysicsBogeyControlOverrides`，而它的实例字段
   `computerOverrides` 是 `protected`——访问转换器只改运行时不改 javac 的可见性判断，所以必须走反射。
   详见 [`../docs/DESIGN.md`](../docs/DESIGN.md) §7。

## 怎么重新核对一个签名

```powershell
# 单个类的完整签名
& "C:\Program Files\Java\jdk-21\bin\javap.exe" -p -cp "<某个 mod 的 jar>" <全限定类名>

# 某 jar 里有哪些类
& "C:\Program Files\Java\jdk-21\bin\jar.exe" tf "<某个 mod 的 jar>" | Select-String "关键词"
```

注意：目标实例的 mod jar 文件名带中文前缀（例如 `[机械动力] create-1.21.1-6.0.10.jar`），
而 Windows PowerShell 5.1 会把无 BOM 的脚本按 ANSI 解码，所以脚本里**不要**直接写这些中文路径，
用 `-like '*6.0.10*'` 之类的模式去匹配。
