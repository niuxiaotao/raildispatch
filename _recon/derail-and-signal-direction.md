# Offline API recon: Simurail/Sable derailment state + Create signal/station "governs-this-way" direction

Scope: static analysis only (javap bytecode). No game was launched, no runtime probing.
Tooling: `C:\Program Files\Java\jdk-21\bin\javap.exe` (21.0.8), `jar.exe tf`, Windows PowerShell 5.1
(`powershell.exe -NoProfile -File`, ASCII-only scripts). Every CJK/bracketed path used `-LiteralPath`.

## 0. Working copies created (side effect you should know about)

Because `javap -cp <path>` passes the jar path as a native argv, and two source jars have CJK +
bracket names, ASCII copies were made **inside** `_recon` and all bytecode work was done against them:

| copy in `D:\DS_WORKSPACE\raildispatch\_recon` | source |
|---|---|
| `simurail.jar`   | `...\mods\simurail-0.1.0+mc1.21.1+8368e22.jar` |
| `sable.jar`      | `...\mods\sable-neoforge-1.21.1-2.0.5.jar` |
| `create.jar`     | `...\mods\[机械动力] create-1.21.1-6.0.10.jar` |
| `aeronautics.jar`| `...\mods\[机械动力：航空学] create-aeronautics-bundled-1.21.1-1.3.0.jar` |
| `nested\*`       | the 6 `META-INF/jarjar/*.jar` bundles extracted out of `create.jar` + `aeronautics.jar` |

Raw `javap -p -c` dumps for every class examined are also in `_recon\*.txt`. Nothing was written
outside `_recon`.

Aeronautics nested jars: `aeronautics`, `offroad`, `simulated` (all `dev.*`), plus Create's
`flywheel`, `ponder`, `Registrate`. **catnip (`net.createmod.catnip`) ships inside
`create__ponder-neoforge-1.0.82+mc1.21.1.jar`** — needed for `Couple` semantics, see §2.3.

---

# QUESTION 1 — Simurail / Sable derailment state

## 1.1 Verdict (short)

A **public, exact, name-literal API exists**. `derail` is a real identifier in Simurail:

```java
// com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity
public boolean isDerailed();     // <-- literally named "derailed"
public boolean hasTrack();
```

Nothing named `derail` exists in **Sable** at all (0 hits), and nothing named `derail` exists in Create.
There is **no `derailed` field, no `derail` enum constant, and no `DerailState` type** anywhere —
the state is derived on demand from a single nullable field.

## 1.2 Exact signatures and visibility (all `javap -p`)

`com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity`
```java
protected final PhysicsBogeyAxle axleFront;          // field, protected
protected final PhysicsBogeyAxle axleBack;           // field, protected
public PhysicsBogeyAxle getAxle(boolean);            // arg = logical/front axle selector
public boolean hasTrack();                           // PUBLIC
public boolean isDerailed();                         // PUBLIC
public double  getMovementSpeed();
public double  getLateralCurvature();
public boolean isInverted();
```

`com.crystaelix.simurail.content.bogey.PhysicsBogeyAxle`
```java
protected TrackSegment                      trackSegment;   // <-- the ONLY on-track state
protected ServerSubLevel                    trackSubLevel;
protected Pose3dc                           trackSubLevelPose;
protected TrackGraph                        trackGraph;     // NOTE: may be non-null while derailed
protected TravellingPoint                   trackPoint;     // created in ctor, never null
protected TravellingPoint                   probe;          // created in ctor, never null
protected List<Couple<TrackNode>>           currentPath;
protected double                            trackRecheckTime;
public    TrackGraph                        getTrackGraph();
public    TravellingPoint                   getTrackPoint();
public    boolean                           hasTrack();          // PUBLIC
public    PhysicsBogeyProbeData              getProbeData();      // PUBLIC
public    PhysicsBogeyAxle                  other();
```

## 1.3 The decision rule (quoted bytecode)

`PhysicsBogeyAxle.hasTrack()` — **a single null check on the cached `TrackSegment`**:
```
public boolean hasTrack();
  Code:
     0: aload_0
     1: getfield      #308   // Field trackSegment:Lcom/crystaelix/simurail/content/track/TrackSegment;
     4: ifnull        11
     7: iconst_1
     8: goto          12
    11: iconst_0
    12: ireturn
```
⇒ `axle.hasTrack() == (axle.trackSegment != null)`. **No `TravellingPoint` null check, no `TrackGraph`
null check, no distance threshold is involved** — `trackPoint`/`probe` are allocated once in the
constructor (`new TravellingPoint` at ctor offsets 65 and 76) and are never nulled.

`PhysicsBogeyBlockEntity.hasTrack()` = `axleFront.hasTrack() || axleBack.hasTrack()`:
```
public boolean hasTrack();
  Code:
     0: aload_0
     1: getfield      #302   // Field axleFront:...PhysicsBogeyAxle;
     4: invokevirtual #1364  // Method ...PhysicsBogeyAxle.hasTrack:()Z
     7: ifne          20
    10: aload_0
    11: getfield      #304   // Field axleBack:...PhysicsBogeyAxle;
    14: invokevirtual #1364  // Method ...PhysicsBogeyAxle.hasTrack:()Z
    17: ifeq          24
    20: iconst_1
    21: goto          25
    24: iconst_0
    25: ireturn
```

`PhysicsBogeyBlockEntity.isDerailed()` = `!(front && back)` — i.e. **derailed if EITHER axle has
lost its track** (and also if both have):
```
public boolean isDerailed();
  Code:
     0: aload_0
     1: getfield      #302   // Field axleFront:...PhysicsBogeyAxle;
     4: invokevirtual #1364  // Method ...PhysicsBogeyAxle.hasTrack:()Z
     7: ifeq          20
    10: aload_0
    11: getfield      #304   // Field axleBack:...PhysicsBogeyAxle;
    14: invokevirtual #1364  // Method ...PhysicsBogeyAxle.hasTrack:()Z
    17: ifne          24
    20: iconst_1
    21: goto          25
    24: iconst_0
    25: ireturn
```
Note the asymmetry to be aware of: `isDerailed()` is **not** `!hasTrack()`. For a 2-axle bogie
`hasTrack()` is true when at least **one** axle is on rail, so the pair
(`hasTrack()==true`, `isDerailed()==true`) is reachable ("one axle off").

## 1.4 How `trackSegment` is set / cleared (the closest readable "no longer on track" signal)

`PhysicsBogeyAxle.findTrack(ServerSubLevel)` — the only writer of `trackSegment` in the normal path:
```
3049-3052: ... getfield PhysicsBogeyOptions.type ; BogeySubtype.trackTypes()
3052: invokestatic  #1290   // ...TrackSegmentHelper.findTrackSegment:(Level;SubLevel;Vector3dc;Vector3dc;Vector3dc;Z;Set;)L...TrackSegment;
3053: putfield      #308    // Field trackSegment:...TrackSegment;      <-- may store null
3055: getfield      #308 ; 78: ifnull 106
          ... (on track)  trackRecheckTime = SimurailPhysicsConfig.axleTrackRecheckTime
    106:  ... (no track)  trackRecheckTime = SimurailPhysicsConfig.axleTrackCheckTime
```
So `findTrack` **assigns the raw return of `TrackSegmentHelper.findTrackSegment(...)`**, which is
`null` when nothing was found. That null assignment *is* the derailment event.

`PhysicsBogeyAxle.updateTrack(ServerSubLevel, double)` re-runs it:
```
571: invokevirtual #705  // Method findTrack:(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)V   // when the current segment chain ran out on a curve
575: getfield      #189  // Field trackRecheckTime:D
580: ifgt          588
585: invokevirtual #705  // findTrack(...)                                                   // when the recheck timer expired
```

`PhysicsBogeyAxle.updateJoint(ServerSubLevel)` physically drops the rail constraint:
```
 7: getfield PhysicsBogeyOptions.enabled
10: ifeq 20
13: getfield      #308   // Field trackSegment
17: ifnonnull     25
20: invokevirtual #733   // Method removeJoint:()V
24: return
```
⇒ **when `trackSegment == null` (or the bogey is disabled) the track joint is removed.** That is the
mechanical meaning of "off the rails" in this mod.

`updatePivotLimits` also branches on it:
```
 1: invokevirtual #1165  // hasTrack:()Z          -> local4
 7: invokevirtual #1168  // isDerailed:()Z        -> local5 = !isDerailed
```

## 1.5 The closest-signal ranking (all verified)

1. **Best / canonical:** `PhysicsBogeyBlockEntity.isDerailed()` (public, literally named).
2. Per-axle: `PhysicsBogeyAxle.hasTrack()` (public) — `false` means that axle is off.
3. Raw cause: `PhysicsBogeyAxle.trackSegment == null` — **`protected`**, so not reachable from another
   mod without reflection/an accessor mixin. Reach it via (1)/(2) instead.
4. `PhysicsBogeyAxle.trackGraph` is **NOT** a valid signal: it is left non-null across derailment
   (only `updateGraph(boolean)` touches it), so "graph reference became null" is **not** the marker.
   UNVERIFIED whether `trackGraph` is ever nulled on world unload.
5. `TravellingPoint.blocked` (public field on Create's `TravellingPoint`) is read by the axle
   (`getfield TravellingPoint.blocked:Z` at axle offsets 133/430) but means "the probe hit a signal
   that forbids entry", **not** "off track". Do not use it for derailment.
6. No distance-to-track threshold exists as a public value. `updateTrack` does test geometric
   containment (`TrackSegment.inSegmentRange(Vector3dc,Vector3dc,Z)`,
   `TrackSegment.inProjectionRange(Vector3dc,Vector3dc)`), but the outcome is only ever folded back
   into `trackSegment` being reassigned or nulled. UNVERIFIED: the exact numeric tolerance, because it
   lives inside `TrackSegment`/`CurvedTrackSegment` internals not inspected here.

## 1.6 Config surface (spells out the intended semantics)

`com.crystaelix.simurail.config.SimurailPhysicsConfig`:
```java
public final ConfigBase.ConfigFloat axleDerailFrictionFactor;  // default 0.5f, range [0,1]
public final ConfigBase.ConfigFloat axleTrackCheckTime;        // default 0.1f, range [0,5]
public final ConfigBase.ConfigFloat axleTrackRecheckTime;      // default 3.0f, range [0,60]
```
Comment strings (from `SimurailPhysicsConfig$Comments`, read out of the constant pool):
- `axleDerailFrictionFactor`: *"Factor of effective friction between an axle of the Physics Bogie and the ground when derailed."*
- `axleTrackCheckTime`: *"Inverval to find nearest track when derailed for an axle of the Physics Bogie."*
- `axleTrackRecheckTime`: *"Inverval to re-find nearest track for an axle of the Physics Bogie."*

NBT keys confirmed: `PhysicsBogeyAxle.write()` stores `"segment"` (`String segment`) only when
`trackSegment != null`; `read` restores it via `TrackSegment.read(CompoundTag)`.

## 1.7 Sable: nothing derail-related, but here is what does exist

`jar tf sable.jar | Select-String 'derail'` → **0 matches** (verified against decompressed class
bytes, not the raw zip, via a full `ZipFile` entry walk — the raw byte scan of a jar is meaningless
because entries are deflated).

The Sable classes matching `state|status|sublevel` that are relevant to "is this vehicle OK" are:
```java
dev.ryanhcode.sable.sublevel.SubLevel
dev.ryanhcode.sable.sublevel.ServerSubLevel
dev.ryanhcode.sable.api.sublevel.SubLevelContainer / ServerSubLevelContainer / ClientSubLevelContainer
dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem
dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason      // enum-ish; removal, not derailment
dev.ryanhcode.sable.api.SubLevelAssemblyHelper$GatherResult$State
```
`ServerSubLevel` contains **no** `track*`/`rail*` member that means off-rails — the only `track*`
identifiers are unrelated (`trackingPlayers`, `sessionTrackingPlayers`, `trackIndividualQueuedForces`,
`isTrackingIndividualQueuedForces()`, `massTracker`). Sable is the rigid-body layer; it has no concept
of rails. **Sable cannot answer "are you derailed".**

## 1.8 Enums checked for an "off-track" value — none found

- `PhysicsBogeyControlMode`: `BRAKING, BRAKING_INVERTED, STRENGTH, STRENGTH_INVERTED, NONE` — throttle, not track.
- `PhysicsRollerMode`: `TUNNEL_PAVE, STRAIGHT_FILL, WIDE_FILL` — paving, not track.
- `TrackSwitchState`: `STRAIGHT, LEFT, RIGHT` — switch blade position.
- `ProbeReaderMode`: `OCCUPIED_SIGNAL, ALIGNED_SIGNAL, OPPOSITE_SIGNAL, SIGNAL, UNPOWERED_STATION, POWERED_STATION, STATION, DISCONTINUITY`
  — **`DISCONTINUITY` is the only "track ends / no continuation" value, and it is a probe-reader display
  mode, not the bogie's own state.** See §1.9.
- No enum anywhere in simurail/sable/create has a constant whose name contains `derail`, `offRail`,
  `offTrack`, or `onTrack` (case-insensitive byte scan of every decompressed class in all four jars).

## 1.9 Two ready-made bridges you probably want instead of computing this yourself

**(a) ComputerCraft peripheral** — `com.crystaelix.simurail.compat.computercraft.peripheral.PhysicsBogeyPeripheral`
```java
public final boolean hasTrack(java.util.Optional<java.lang.Boolean>);
public final boolean isDerailed(java.util.Optional<java.lang.Boolean>);
public final double  getLateralCurvature(java.util.Optional<java.lang.Boolean>);
public final double  getVerticalCurvature(java.util.Optional<java.lang.Boolean>);
```
Each delegates as `optional.isPresent() ? be.getAxle(optional.get()).<m>() : be.<m>()` — i.e. **the
`Optional<Boolean>` selects one axle** (`true` = front/logical-front), empty = whole bogie. Quoted for
`isDerailed`:
```
public final boolean isDerailed(java.util.Optional<java.lang.Boolean>);
  Code:
     0: aload_1
     1: invokevirtual #213   // Optional.isPresent
     4: ifeq          39
     7: ... checkcast PhysicsBogeyBlockEntity
    14: aload_1
    15: invokevirtual #217   // Optional.get
    21: invokevirtual #222   // Boolean.booleanValue
    24: invokevirtual #226   // PhysicsBogeyBlockEntity.getAxle:(Z)L...PhysicsBogeyAxle;
    27: invokevirtual #230   // PhysicsBogeyAxle.hasTrack:()Z
    30: ifne          37
    33: iconst_1
    34: goto          38
    37: iconst_0
    38: ireturn
    39: ... PhysicsBogeyBlockEntity.isDerailed:()Z
    49: ireturn
```

**(b) `PhysicsBogeyProbeData`** — `public PhysicsBogeyAxle.getProbeData()` returns a public class with
public getters (see §2.7; this is also the `顺向` goldmine):
```java
public double getStationDistance(); public String getStationName(); public UUID getStationId();
public Vec3 getStationPos(); public BlockPos getStationBlockPos(); public boolean isStationPowered();
public double getSignalDistance(); public List<String> getSignalNames(); public Vec3 getSignalPos();
public boolean isSignalAligned(); public boolean isSignalBidirectional();
public double getOccupiedSignalDistance(); public List<String> getOccupiedSignalNames();
public Vec3 getOccupiedSignalPos(); public boolean isOccupiedSignalAligned();
public boolean isOccupiedSignalBidirectional(); public boolean isOccupiedSignalForced();
public double getDiscontinuityDistance();
```
There is **no** `isDerailed`/`isOnTrack` member on `PhysicsBogeyProbeData` — use the axle/blockentity.

## 1.10 Q1 UNVERIFIED list

- Whether `PhysicsBogeyAxle.trackGraph` is ever explicitly nulled (I only read `updateGraph(boolean)`'s
  callers, not its whole body for the nulling path).
- The geometric tolerance used by `TrackSegment.inSegmentRange` / `inProjectionRange` before
  `findTrack` is re-run (internals of `TrackSegment`/`CurvedTrackSegment`/`TrackSegmentHelper` were
  dumped but not fully decompiled).
- Whether `isDerailed()` is used by any Simurail GUI/HUD element (I did not enumerate UI call sites).
- `PhysicsBogeyBlockEntity.getAxle(boolean)`'s argument name/semantics are inferred from its body
  (`axleFront` vs `axleBack` selection); the parameter is not named in bytecode. The inference is
  solid — the body is a straight `a == axleFront ? axleBack : axleFront`-style select — but the
  *which-is-front* convention is taken from field names only.

---

# QUESTION 2 — how to compute which way a Create signal / station GOVERNS (顺向)

## 2.1 `TrackEdgePoint` — fields and the two convention methods

`com.simibubi.create.content.trains.signal.TrackEdgePoint` (abstract; superclass of `SignalBoundary`,
`GlobalStation`, `TrackObserver`):
```java
public  java.util.UUID id;
public  net.createmod.catnip.data.Couple<com.simibubi.create.content.trains.graph.TrackNodeLocation> edgeLocation;
public  double position;
private com.simibubi.create.content.trains.graph.EdgePointType<?> type;

public  void setId(UUID);                     public UUID getId();
public  void setType(EdgePointType<?>);       public EdgePointType<?> getType();
public  abstract boolean canMerge();
public  boolean canCoexistWith(EdgePointType<?>, boolean);
public  abstract void invalidate(net.minecraft.world.level.LevelAccessor);
protected void invalidateAt(net.minecraft.world.level.LevelAccessor, BlockPos);
public  abstract void blockEntityAdded(BlockEntity, boolean);
public  abstract void blockEntityRemoved(BlockPos, boolean);
public  void onRemoved(TrackGraph);
public  void setLocation(Couple<TrackNodeLocation>, double);
public  double getLocationOn(TrackEdge);
public  boolean canNavigateVia(TrackNode);
public  boolean isPrimary(TrackNode);
public  void read(...); public void write(...);        // NBT + FriendlyByteBuf overloads
public  void tick(TrackGraph, boolean);
protected void removeFromAllGraphs();
```

`setLocation` — a **plain assignment**, no normalisation, no clamping:
```
public void setLocation(net.createmod.catnip.data.Couple<...TrackNodeLocation>, double);
  Code:
     0: aload_0
     1: aload_1
     2: putfield      #51   // Field edgeLocation:Lnet/createmod/catnip/data/Couple;
     5: aload_0
     6: dload_2
     7: putfield      #55   // Field position:D
    10: return
```

`getLocationOn(TrackEdge)` — **the mirroring branch**:
```
public double getLocationOn(com.simibubi.create.content.trains.graph.TrackEdge);
  Code:
     0: aload_0
     1: aload_1
     2: getfield      #59   // Field com/simibubi/create/content/trains/graph/TrackEdge.node1:...TrackNode;
     5: invokevirtual #65   // Method isPrimary:(L...TrackNode;)Z
     8: ifeq          23
    11: aload_1
    12: invokevirtual #69   // Method com/simibubi/create/content/trains/graph/TrackEdge.getLength:()D
    15: aload_0
    16: getfield      #55   // Field position:D
    19: dsub
    20: goto          27
    23: aload_0
    24: getfield      #55   // Field position:D
    27: dreturn
```
⇒ `getLocationOn(edge) = isPrimary(edge.node1) ? edge.getLength() - position : position`

`isPrimary(TrackNode)`:
```
public boolean isPrimary(net.minecraft.core.BlockPos...);   // (TrackNode)
  Code:
     0: aload_0
     1: getfield      #51   // Field edgeLocation:Couple;
     4: invokevirtual #73   // Couple.getSecond
     7: checkcast     #79   // TrackNodeLocation
    10: aload_1
    11: invokevirtual #81   // TrackNode.getLocation
    14: invokevirtual #87   // TrackNodeLocation.equals
    17: ireturn
```
⇒ `isPrimary(node) == edgeLocation.getSecond().equals(node.getLocation())`

`Couple` semantics (verified against `net/createmod/catnip/data/Couple.class` inside
`create__ponder-neoforge-1.0.82+mc1.21.1.jar`):
```
public T get(boolean);
  Code:
     0: iload_1
     1: ifeq          11
     4: aload_0
     5: invokevirtual #77   // getFirst
     8: goto          15
    11: aload_0
    12: invokevirtual #80   // getSecond
    15: areturn
```
⇒ `couple.get(true) == getFirst()`, `couple.get(false) == getSecond()`.
`Couple.create(a,b)` stores `first=a, second=b`; `Couple.swap() == create(second, first)`;
`Couple.both(p) == p.test(first) && p.test(second)` (all three bytecode-verified).

## 2.2 THE CONVENTION — `position` is measured from `edgeLocation.getFirst()`

**Result:** as `position` increases, you move along the edge **from the node at
`edgeLocation.getFirst()` toward the node at `edgeLocation.getSecond()`**. Equivalently:

> `TrackEdgePoint.position` = distance in **blocks** along the edge, measured from
> `locateNode(edgeLocation.getFirst())`, positive toward `locateNode(edgeLocation.getSecond())`.

And `getLocationOn(edge)` converts that into **distance in blocks from `edge.node1`** (toward
`edge.node2`).

Three independent bytecode proofs:

**(A) The creation site mirrors `position` exactly when it swaps the Couple.**
`TrackTargetingBehaviour.createEdgePoint()` (this is where `setLocation` is called):
```
189: getTargetDirection()
193: getstatic AxisDirection.POSITIVE
196: if_acmpne 203
199: iconst_1 / 200: goto 204 / 203: iconst_0
204: istore 9                       // local9 = (getTargetDirection() == POSITIVE)
...
575: iload 9
577: ifne 588
580: aload 8 ; 582: instanceof SingleBlockEntityEdgePoint
585: ifeq 592
588: iconst_1 / 589: goto 593 / 592: iconst_0
593: istore 14                      // local14 = local9 || (edgePoint instanceof SingleBlockEntityEdgePoint)
595: aload 8                        // edgePoint
597: iload 14
599: ifeq 609
602: aload_3 ; 603: getfield TrackGraphLocation.edge            // Couple as-is
606: goto 616
609: aload_3 ; 610: getfield TrackGraphLocation.edge
613: invokevirtual Couple.swap()
616: (couple on stack)
616: iload 14
618: ifeq 628
621: aload_3 ; 622: getfield TrackGraphLocation.position        // position as-is
625: goto 635
628: dload 10                      // local10 = edge.getLength()
630: aload_3 ; 631: getfield TrackGraphLocation.position
634: dsub                          // length - position
635: invokevirtual TrackEdgePoint.setLocation:(LCouple;D)V
```
i.e.
```java
boolean primary = (targetDirection == POSITIVE) || (edgePoint instanceof SingleBlockEntityEdgePoint);
edgePoint.setLocation(primary ? loc.edge : loc.edge.swap(),
                      primary ? loc.position : edge.getLength() - loc.position);
```
Swapping the endpoint pair is compensated by `position → length - position`. That is only coherent if
`position`'s origin is `edgeLocation.getFirst()`.

**(B) The very same swap+mirror idiom appears independently in `TrackGraphHelper.getBezierGraphLocationAt`.**
```
475: aload 15 ; 477: Couple.create(local10, local8) ; 479: putfield graphLocation.edge
480: aload 15
481: aload_3 ; 482: BezierTrackPointLocation.segment()
483: iconst_1 ; 484: iadd ; 485: i2f ; 486: fconst_2 ; 487: fdiv ; 488: f2d
489: putfield graphLocation.position
490: aload_2 ; 491: getstatic AxisDirection.POSITIVE
492: if_acmpne 343
493: aload 15 ; 495: getfield graphLocation.edge ; 496: Couple.swap() ; 497: putfield graphLocation.edge
498: aload 15
499: aload 14 ; 500: TrackEdge.getLength()
501: aload 15 ; 502: getfield graphLocation.position
503: dsub
504: putfield graphLocation.position
```
Again: `edge = edge.swap(); position = length - position;` — same invariant, different call site.

**(C) Consumers treat `getLocationOn(edge)` as "blocks from `edge.node1`".**
`TravellingPoint.edgeTraversedFrom(...)`:
```
 60: edge.getEdgeData() -> local13
 69: local13.getPoints() -> local14
 76: edge.getLength() -> local15
 85: i = 0
 loop:
 100: if (forward) local18 = i ; else local18 = points.size() - i - 1
 123: TrackEdgePoint point = points.get(local18)          // local19
 137: double locationOn = point.getLocationOn(edge)       // local20
 148: if (forward) local22 = locationOn ; else local22 = local15 - locationOn
 164: if (forward) { if (locationOn < local9) continue; if (locationOn < local11) accept; }
 187: else         { if (locationOn <= local9) continue; if (locationOn <= local11) accept; }
```
with `local9/local11` derived from the travel parameters and `local15 == edge.getLength()`, and
`local22` reaching the listener as `Pair.of(Double.valueOf(local7 + local22), point, forward ? Couple(node1,node2) : Couple(node1,node2).swap())`.
The explicit `length - locationOn` mirror plus the direct comparison against a travel distance proves
`getLocationOn` is in **blocks**, in the same coordinate as `TravellingPoint.position`.

`Navigation` uses it the same way (path planning over the edge the train is on):
```
1425: edge18.getEdgeData() -> local23
1431: local23.getPoints()
1442: local24... point -> local25
1442: aload 25 ; 1444: point.getLocationOn(edge18)          // local20-equivalent
1446: edge18.getLength()
1448: dload 19                                              // local19
1449: dcmpg ; 1450: ifge 596                                // process only if locationOn >= length - local19
```
and `local19` is set at:
```
1366: iload 5 (forward)
1368: ifeq 399 -> local16 = tp.node2 ... (see below)
```
```
1366: if (forward) local16 = travellingPoint.node1 ; else local16 = travellingPoint.node2
1374: if (forward) local17 = travellingPoint.node2 ; else local17 = travellingPoint.node1
1382: local18 = graph.getConnectionsFrom(local16).get(local17)          // = the travelled TrackEdge
1392: if (forward) local19 = local18.getLength() - travellingPoint.position
1400: else          local19 = travellingPoint.position
```
⇒ `length - local19` reproduces exactly `travellingPoint.position`, and the comparison is
`getLocationOn(edge) >= travellingPoint.position`. **Confirmed: `getLocationOn` and
`TravellingPoint.position` share the origin `edge.node1`.**

**And `TravellingPoint.position` is measured from `TravellingPoint.node1`** — `reverse()`:
```
689: public void reverse(TrackGraph);
 692: local2 = node1
 696: putfield node1 = node2
 700: putfield node2 = local2
 701: aload_0 ; 703: getfield edge ; 704: TrackEdge.getLength() ; 706: getfield position ; 707: dsub
 708: putfield position                                  // position = length - position
 709: aload_0 ; 713: graph.getConnectionsFrom(node1) ; 716: Map.get(node2) ; 718: putfield edge
```
and `getPositionWithOffset`:
```
740: getfield position ; 742: dload_2 ; 744: dadd
746: getfield edge ; 747: TrackEdge.getLength() ; 748: ddiv     // t = (position + offset) / edge.getLength()
753: TrackEdge.getPosition(graph, t)
758: TrackEdge.getNormal(graph, t)
```
So `position` is in blocks and feeds `t = position / edge.getLength()`.

## 2.3 `TrackEdge` — parameter ranges and orientation (all bytecode-verified)

```java
public TrackNode node1;                       // public
public TrackNode node2;                       // public
       BezierConnection turn;                 // package-private
       EdgeData edgeData;                     // package-private
       boolean interDimensional;              // package-private
       TrackMaterial trackMaterial;            // package-private

public TrackMaterial getTrackMaterial();
public boolean isTurn();                      // turn != null
public boolean isInterDimensional();
public EdgeData getEdgeData();
public BezierConnection getTurn();
public Vec3     getDirection(boolean);
public Vec3     getDirectionAt(double);
public boolean  canTravelTo(TrackEdge);
public double   getLength();
public double   incrementT(double, double);
public Vec3     getPosition(TrackGraph, double);
public Vec3     getNormal(TrackGraph, double);
public Vec3     getPositionSmoothed(TrackGraph, double);
public Vec3     getNormalSmoothed(TrackGraph, double);
```

### `getPosition(TrackGraph, double t)` — **`t` is normalised 0..1**, t=0 at `node1`, t=1 at `node2`
```
237: public Vec3 getPosition(TrackGraph, double);
 239: aload_0 ; 240: isTurn() ; 241: ifeq 21
 242: getfield turn ; 245: dload_2 ; 246: dconst_0 ; 247: dconst_1 ; 248: Mth.clamp(t,0,1)
 249: BezierConnection.getPosition(D) ; 250: areturn            // turns: clamped Bezier t
  21: aload_1 ; 251: ifnull 67                                    // (world-space y-offset smoothing)
 ...
  67: dload_2 ; 68: d2f                                          // (float) t
  69: getfield node1 ; 73: TrackNode.location ; 76: TrackNodeLocation.getLocation()   // Vec3 A
  79: getfield node2 ; 83: TrackNode.location ; 86: TrackNodeLocation.getLocation()   // Vec3 B
  89: invokestatic VecHelper.lerp:(FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;
  92: areturn
```
⇒ for straight edges `getPosition(g, t) == lerp(t, node1.location.getLocation(), node2.location.getLocation())`.
`VecHelper.lerp(t, from, to)` is the standard `from + t*(to-from)`, so **`t=0 → node1`, `t=1 → node2`**.
For turns the parameter is `Mth.clamp(t, 0, 1)` into `BezierConnection.getPosition` — also normalised.
**`t` is 0..1, NOT 0..length.** UNVERIFIED: the direction of `BezierConnection.getPosition(t)`
(increasing t from the `node1` end) — I did not decompile `BezierConnection.getPosition`; the
`clamp(t,0,1)` proves the *range* but not the *sense*. Every consumer (see below) uses it as
node1→node2.

### `getLength()` — the world length in **blocks**
```
172: public double getLength();
 174: isInterDimensional() ; 175: ifeq 11
 177: dconst_0 ; 178: goto 51                                    // inter-dimensional => 0.0
  11: isTurn() ; 15: ifeq 28
  18: getfield turn ; 22: BezierConnection.getLength() ; 25: goto 51
  28: getfield node1 ; 32: TrackNode.location ; 35: TrackNodeLocation.getLocation()
  38: getfield node2 ; 42: TrackNode.location ; 45: TrackNodeLocation.getLocation()
  48: Vec3.distanceTo(Vec3)
  51: dreturn
```

### `getDirectionAt(double tBlocks)` — **input in blocks**, returns node1→node2 unit tangent
```
108: public Vec3 getDirectionAt(double);
 111: getLength() ; 114: dstore_3                                 // L = length
 113: ldc2_w 0.5d ; 118: dload_3 ; 119: ddiv ; 120: dstore 5       // step = 0.5 / L
 117: dload_1 ; 118: dload_3 ; 119: ddiv ; 120: dstore_1           // t = t / L      <-- normalise
 121: getPosition(null, Math.min(1.0d, t + step)) -> local7
 130: getPosition(null, Math.max(0.0d, t - step)) -> local8
 139: local7.subtract(local8).normalize()
```
⇒ `getDirectionAt` takes a **block distance from `node1`** and differentiates `getPosition` in the
increasing-t direction. The returned unit vector points **`node1` → `node2`**.

### `getDirection(boolean)` — the same tangent sampled at one end of the edge
```
 86: public Vec3 getDirection(boolean);
 88: this ; aconst_null ; (b ? 0.25 : 1.0) ; getPosition(null, x)
 95: this ; aconst_null ; (b ? 0.0 : 0.75) ; getPosition(null, x)
103: subtract ; normalize
```
⇒ `getDirection(true)  = normalize(pos(0.25) - pos(0.00))`  (tangent near the **node1** end)
⇒ `getDirection(false) = normalize(pos(1.00) - pos(0.75))`  (tangent near the **node2** end)
**Both point node1→node2.** They are *not* opposite. (This is why `canTravelTo` compares
`other.getDirection(true) · this.getDirection(false) > 0.875` — a smooth head-to-tail join.)

### `getNormal(TrackGraph, double)` — same normalised range
```
284: public Vec3 getNormal(TrackGraph, double);
 286: isTurn() ; 288: ifeq 21
 289: getfield turn ; 292: dload_2 ; 293: dconst_0 ; 294: dconst_1 ; 295: Mth.clamp(t,0,1)
 296: BezierConnection.getNormal(D) ; 297: areturn
  21: ... (straight: node1 getNormal / node2 getNormal, lerped)      // same shape as getPosition
```
⇒ `t` is 0..1. `TravellingPoint.getPositionWithOffset` calls it with the same `position/length` value.

### `incrementT(double t, double increment)` — reveals the units of `t`
```
197: public double incrementT(double, double);
 199: Math.abs(increment) ; 4: ldc2_w 5.0d ; 7: dcmpl ; 8: ifle 15
 11: iconst_1 ; 12: goto 16 / 15: iconst_0
 16: istore 5                                                    // flag = |increment| > 5.0
 18: getLength() ; 22: dstore 6
 24: dload_3 ; 25: dload 6 ; 27: dconst_0 ; 28: dcmpl ; 29: ifne 36
 32: dconst_1 ; 33: goto 38 / 36: dload 6
 38: ddiv ; 39: dstore_3                                          // increment /= (length==0 ? 1 : length)
 40: if (!flag && isTurn()) return turn.incrementT(t, increment)
 64: return t + increment
```
⇒ a step **> 5 blocks** is treated as a block distance and divided by `getLength()` to become a Δt.
This is corroborating evidence that `t` is normalised and `getLength()` is in blocks.

## 2.4 The directional-edge duality — and why `edge.node1` is always "behind you"

`TrackGraph.connectNodes(LevelAccessor, DiscoveredLocation a, DiscoveredLocation b, BezierConnection turn)`
creates **two** `TrackEdge` objects per physical connection:
```
 42: nodes.get(a) -> local5
 48: nodes.get(b) -> local6
 61: new TrackEdge(local5, local6, turn, material)                        -> local9
 78: new TrackEdge(local6, local5, turn != null ? turn.secondary() : null, material) -> local10
```
and `TrackGraph.putConnection(node1, node2, edge)` inserts only `connectionsByNode[node1][node2] = edge`
(single `Map.put` at offset 55 of `putConnection`). Therefore:

> **`graph.getConnectionsFrom(a).get(b).node1 == a`** — always. Verified by construction, and relied on
> by three independent consumers: `TravellingPoint.migrateTo` (positions the TP from
> `graphLocation.edge.getFirst()/getSecond()`), `TravellingPoint.reverse` (`position = length - position`
> + re-fetch edge from the swapped node pair), and `Navigation` (computes the remaining distance as
> `edge.getLength() - tp.position` for forward travel, which is only correct if `edge.node1 == tp.node1`).

Each of the two directional edges has its **own** `EdgeData` (created in the `TrackEdge` constructor,
`new EdgeData(this)`), and `EdgeData` holds the edge-point list. `TrackEdgePoint` objects are therefore
registered on **both** directions:

`EdgePointManager.onEdgePointAdded(TrackGraph graph, T point, EdgePointType<T> type)`:
```
 12: point.edgeLocation -> local3
 14-20: local4 = edgeLocation.map(graph::locateNode)                     // nodes
 22-27: local5 = local4.mapWithParams((n1,n2) -> graph.getConnectionsFrom(n1).get(n2), local4.swap())
 29: for (boolean first : Iterate.trueAndFalse) {
 43:   TrackNode nodeA = nodes.get(first);  TrackNode nodeB = nodes.get(!first);
 57:   TrackEdge edge = edges.get(first);
 62:   edge.getEdgeData().addPoint(graph, point);        // <-- BOTH directions
```
with `lambda$onEdgePointAdded$0(graph, n1, n2) = graph.getConnectionsFrom(n1).get(n2)`.
`EdgeData.addPoint` insertion-sorts by `point.getLocationOn(edge)` ascending — yet another independent
confirmation that `getLocationOn` is a monotone "distance along the edge from `node1`":
```
340: EdgeData.addPoint(TrackGraph, TrackEdgePoint);
 342: point.getType() == EdgePointType.SIGNAL -> setSingleSignalGroup(graph, null)
 350: double d = point.getLocationOn(this.edge)
 355: i = 0
 357: while (i < points.size()) {
 362:    if (points.get(i).getLocationOn(edge) > d) break;
 374:    i++ }
 376: points.add(i, point)
```

## 2.5 `SignalBoundary` — `blockEntities` type and the meaning of the Boolean

```java
public class com.simibubi.create.content.trains.signal.SignalBoundary extends TrackEdgePoint {
  public Couple<Map<BlockPos, Boolean>>                 blockEntities;
  public Couple<SignalBlock.SignalType>                 types;
  public Couple<UUID>                                   groups;
  public Couple<Boolean>                                sidesToUpdate;
  public Couple<SignalBlockEntity.SignalState>          cachedStates;
  public Couple<Map<UUID, Boolean>>                     chainedSignals;   // (present in the class)
  // no direction field of any kind
}
```

### The `Boolean` = **"this signal block currently reports redstone power"**. It is NOT a side/direction marker.
`blockEntityAdded(BlockEntity, boolean)`:
```
168: public void blockEntityAdded(BlockEntity, boolean);
 170: getfield blockEntities ; 172: iload_2 ; 173: Couple.get(Z) ; 174: checkcast Map
 176: Map.isEmpty() ; 178: ifeq 41
 179: blockEntity.getBlockState()
 181: getstatic SignalBlock.TYPE
 182: BlockState.getOptionalValue(Property)
 185: invokedynamic #133 accept:(SignalBoundary, Z) -> Consumer   // lambda sets types.set(first, type)
 186: Optional.ifPresent(Consumer)
  41: aload_3 (the map)
  42: aload_1 ; 43: BlockEntity.getBlockPos()
  46: aload_1 ; 47: instanceof SignalBlockEntity
  50: ifeq 71
  53: checkcast SignalBlockEntity
  61: SignalBlockEntity.getReportedPower() ; 64: ifeq 71
  67: iconst_1 ; 68: goto 72 / 71: iconst_0
  72: Boolean.valueOf(Z)
  75: Map.put(BlockPos, Boolean)
```
⇒ `map.put(be.getBlockPos(), (be instanceof SignalBlockEntity sbe) && sbe.getReportedPower())`
and `SignalBlockEntity.getReportedPower()` is literally `return lastReportedPower;`.

### The **Couple index** is what encodes the side, and it equals the block's own target direction
`TrackTargetingBehaviour.createEdgePoint` calls `edgePoint.blockEntityAdded(this.blockEntity, local9)`
where `local9 == (getTargetDirection() == AxisDirection.POSITIVE)`. Therefore:

> `blockEntities.getFirst()`  = the map of signal blocks whose `targetDirection == POSITIVE`
> `blockEntities.getSecond()` = the map of signal blocks whose `targetDirection == NEGATIVE`

The read side confirms the pairing (`Couple.get(isPrimary(node))` everywhere):
```
canNavigateVia(TrackNode):
 287: public boolean canNavigateVia(TrackNode);
 289: aload_0 ; 290: getfield blockEntities ; 292: aload_1 ; 293: isPrimary(node) ; 294: Couple.get(Z)
 295: checkcast Map ; 296: Map.isEmpty() ; 297: ifne 27 ; 298: iconst_1 ; 299: goto 28 ; 300: iconst_0 ; 301: ireturn
     => !blockEntities.get(isPrimary(node)).isEmpty()

getGroup(TrackNode):
 276: groups.get(isPrimary(node))

queueUpdate(TrackNode):
 264: sidesToUpdate.set(isPrimary(node), Boolean.TRUE)

isForcedRed(TrackNode) = isForcedRed(isPrimary(node)):
 561: public boolean isForcedRed(TrackNode);
 563: aload_0 ; 564: aload_0 ; 566: isPrimary(node) ; 567: isForcedRed(Z)
 570: public boolean isForcedRed(boolean);
 572: getfield blockEntities ; 574: iload_1 ; 575: Couple.get(Z) ; 576: checkcast Map
 577: Map.values() ; iterate ; if any Boolean is TRUE -> return true ; else false
```
`isForcedRed` returning "any `true` in that side's map" is decisive proof that the `Boolean` is a
per-block **power** flag, not a side flag.

`getTypeFor(BlockPos)`, `getStateFor(BlockPos)`, `getOverlayFor(BlockPos)` all iterate
`Iterate.trueAndFalse` over both `blockEntities` sides and look the `BlockPos` up in whichever map
contains it — i.e. they recover the side from the map, again proving the index is the side.

### `resolveSignalChain(TrackGraph, boolean)` — the per-side state resolution
```
598: private SignalState resolveSignalChain(TrackGraph, boolean);
 601: types.get(first)
 604: if (== SignalType.CROSS_SIGNAL) return SignalState.GREEN;
 609: chainedSignals.get(first) ; 612: ifnonnull 43
 613: chainedSignals.set(first, SignalPropagator.collectChainedSignals(graph, this, first))
 621: ... iterate chainedSignals.get(first).entrySet() ... (RED/GREEN resolution)
```

### Is there a public "which side is primary" accessor?
Yes, inherited and public: **`TrackEdgePoint.isPrimary(TrackNode)`**. On `SignalBoundary` there is no
extra accessor; you derive the side from `isPrimary(node)` and read `blockEntities.get(isPrimary(node))`.
`SignalBoundary` also has `public boolean canNavigateVia(TrackNode)`, `public boolean isForcedRed(TrackNode)`,
`public boolean isForcedRed(boolean)`, `public UUID getGroup(TrackNode)`,
`public SignalBlock.SignalType getTypeFor(BlockPos)`, `public SignalBlockEntity.SignalState getStateFor(BlockPos)`,
`public SignalBlockEntity.OverlayState getOverlayFor(BlockPos)`, `public void cycleSignalType(BlockPos)`,
`public void setGroup(boolean, UUID)`, `public void setGroupAndUpdate(TrackNode, UUID)`,
`public void updateBlockEntityPower(SignalBlockEntity)`, `public boolean canMerge()`,
`public boolean canCoexistWith(EdgePointType<?>, boolean)`.

**No direction field at all.** `SignalBoundary` stores no facing; directionality is fully encoded in
`(edgeLocation, position)` + the `blockEntities` Couple index + `isPrimary`.

## 2.6 `GlobalStation` — no direction field; directionality is `canApproachFrom(TrackNode)`

```java
public class com.simibubi.create.content.trains.station.GlobalStation extends SingleBlockEntityEdgePoint {
  public String name;
  public WeakReference<Train> nearestTrain;
  public boolean assembling;
  public Map<BlockPos, GlobalPackagePort> connectedPorts;
  // inherited from SingleBlockEntityEdgePoint: public ResourceKey<Level> blockEntityDimension; public BlockPos blockEntityPos;
  // inherited from TrackEdgePoint: public UUID id; public Couple<TrackNodeLocation> edgeLocation; public double position; private EdgePointType<?> type;
  // *** NO direction / facing / arrow field ***
  public boolean canApproachFrom(TrackNode);
  public boolean canNavigateVia(TrackNode);
  public Train getPresentTrain(); / getImminentTrain(); / getNearestTrain();
  public void reserveFor(Train); public void cancelReservation(Train); public void trainDeparted(Train);
  public void runMailTransfer();
}
```
```
public boolean canApproachFrom(net.minecraft...TrackNode);
 187: aload_0 ; 188: aload_1 ; 189: isPrimary(node) ; 190: ifeq 19
 191: getfield assembling ; 192: ifne 19
 194: iconst_1 ; 195: goto 20 / 197: iconst_0 ; 198: ireturn
     => isPrimary(node) && !assembling

public boolean canNavigateVia(TrackNode);
 201: aload_0 ; 202: aload_1 ; 203: SingleBlockEntityEdgePoint.canNavigateVia(node) ; 204: ifeq 19
 205: getfield assembling ; 206: ifne 19 ; 208: iconst_1 ; 209: goto 20 ; 211: iconst_0 ; 212: ireturn
```
`SingleBlockEntityEdgePoint.canNavigateVia(TrackNode)` (inherited default) is `TrackEdgePoint`'s
`return true`. `SingleBlockEntityEdgePoint` adds:
```java
public ResourceKey<Level> blockEntityDimension;  public BlockPos blockEntityPos;
public BlockPos getBlockEntityPos();  public ResourceKey<Level> getBlockEntityDimension();
public void blockEntityAdded(BlockEntity, boolean);   // stores pos + dimension, ignores the boolean
public void blockEntityRemoved(BlockPos, boolean);    // removeFromAllGraphs()
public void invalidate(LevelAccessor);                // invalidateAt(level, blockEntityPos)
public boolean canMerge();                            // false
```
⇒ **`GlobalStation` has no `direction`; `canApproachFrom(node)` is the whole story.**

`Navigation` uses it as `canApproachFrom(local17)` where (see the §2.2(C) quote) `local17` is
`travellingPoint.node2` when travelling forward, i.e. **the node ahead on the edge the train is
currently on**. So:

> `GlobalStation.canApproachFrom(nodeAhead) == true` means "this station is oriented for a train that
> is arriving at `nodeAhead`", i.e. the train is running **from `edgeLocation.getFirst()` toward
> `edgeLocation.getSecond()`**.

`Navigation` also calls `trackEdgePoint.canNavigateVia(local17)` (same `nodeAhead`) for every point on
the edge, so **signals and stations are both evaluated against "the node I am heading to".**

## 2.7 The signal BLOCK: blockstate properties, and where the arrow actually lives

`com.simibubi.create.content.trains.signal.SignalBlock`:
```java
public class com.simibubi.create.content.trains.signal.SignalBlock
        extends net.minecraft.world.level.block.Block
        implements IBE<SignalBlockEntity>, IWrenchable {
  public static final EnumProperty<SignalBlock.SignalType> TYPE;
  public static final BooleanProperty POWERED;
  // createBlockStateDefinition adds EXACTLY [TYPE, POWERED]
  // defaultBlockState: TYPE = ENTRY_SIGNAL, POWERED = false
}
public enum SignalBlock.SignalType implements StringRepresentable { ENTRY_SIGNAL, CROSS_SIGNAL }
```
`createBlockStateDefinition` bytecode (2-element array):
```
 32: iconst_2 ; 36: anewarray Property
 40: getstatic TYPE ; 44: getstatic POWERED ; 46: StateDefinition$Builder.add([LProperty;)
```
⇒ **NO `HORIZONTAL_FACING`, no `DirectionProperty`, no `AxisDirection` property, and `SignalBlock` is a
plain `Block` (no `AbstractTrackBlock` superclass that could contribute a facing).** The only two
properties are `TYPE` (`entry_signal` | `cross_signal`) and `POWERED`.

`com.simibubi.create.content.trains.station.StationBlock`:
```java
public class StationBlock extends Block implements IBE<StationBlockEntity>, IWrenchable, ProperWaterloggedBlock {
  public static final BooleanProperty ASSEMBLING;
  // createBlockStateDefinition adds EXACTLY [ASSEMBLING, WATERLOGGED]
}
```
⇒ **Station also has NO facing property.**

### Where the visual arrow does live: the block entity behaviour
`com.simibubi.create.content.trains.track.TrackTargetingBehaviour<T extends TrackEdgePoint>`:
```java
private BlockPos targetTrack;
private BezierTrackPointLocation targetBezier;
private Direction.AxisDirection targetDirection;   // <-- THE ARROW
private UUID id;
private Vec3 prevDirection;
private Vec3 rotatedDirection;
private CompoundTag migrationData;
private EdgePointType<T> edgePointType;
private T edgePoint;
private boolean orthogonal;
public Direction.AxisDirection getTargetDirection();
```
It is persisted in the **block entity NBT** (key `"TargetDirection"`, a boolean), not the blockstate:
```
TrackTargetingBehaviour.write, 77: getfield targetDirection ... (written as the selected-direction component by TrackTargetingBlockItem)
TrackTargetingBehaviour.read:
 169: NBTHelper.readBlockPos(tag, "TargetTrack") -> targetTrack
 169: tag.getBoolean("TargetDirection") ; 173: ifeq 51
 174: AxisDirection.POSITIVE / 176: AxisDirection.NEGATIVE
 177: putfield targetDirection
 178: tag.getBoolean("Ortho") -> orthogonal
```
and it is also flipped in-place by `createEdgePoint` when the stored arrow disagrees with the actual
track (`prevDirection · rotatedDirection < -0.85` ⇒ `targetDirection = targetDirection.opposite()`,
then retry) — offsets 254-280.

It is set at placement through the item's component
`AllDataComponents.TRACK_TARGETING_ITEM_SELECTED_DIRECTION` (`TrackTargetingBlockItem.useOn`), and read
by the renderers: `SignalRenderer`, `SignalVisual`, `StationRenderer`, `TrackObserverRenderer`,
`TrackObserverVisual`, `StationBlockEntity` all reference `getTargetDirection()`.

`SignalBlockEntity` (public API):
```java
public TrackTargetingBehaviour<SignalBoundary> edgePoint;     // public field
public SignalBoundary getSignal();                            // returns (SignalBoundary) edgePoint.getEdgePoint()
public boolean isPowered();                                   // state == SignalState.RED
public boolean getReportedPower();                            // returns lastReportedPower
public SignalState getState(); public OverlayState getOverlay(); public void setOverlay(OverlayState);
public void enterState(SignalState); public void invalidate();
```
```
public SignalBoundary getSignal();
  Code:
     0: aload_0 ; 1: getfield #107 // Field edgePoint:...TrackTargetingBehaviour;
     4: invokevirtual #111 // TrackTargetingBehaviour.getEdgePoint:()...TrackEdgePoint;
     7: checkcast     #117 // class ...SignalBoundary
    10: areturn

public boolean isPowered();
  Code:
     0: getfield #13  // Field state:...SignalBlockEntity$SignalState;
     4: getstatic #119 // SignalState.RED
     7: if_acmpne 14 ; 10: iconst_1 ; 11: goto 15 ; 14: iconst_0 ; 15: ireturn
```
Direction flow, end to end:
1. player places the block; the item picks an `AxisDirection` from the player's horizontal facing →
   `TrackTargetingBehaviour.targetDirection` (BE NBT `TargetDirection`).
2. `TrackTargetingBehaviour.determineGraphLocation()` =
   `TrackGraphHelper.getGraphLocationAt(level, getGlobalPosition(), getTargetDirection(), track.getTrackAxes(level,pos,state).get(0))`
   (or the `getBezierGraphLocationAt` variant when `targetBezier != null`).
3. `createEdgePoint()` folds `targetDirection` into `edgeLocation` + `position` and into the
   `blockEntities` side index, then registers the point.
4. `SignalBlockEntity` reads state back through `edgePoint` / `getSignal()` → `SignalBoundary`; the
   arrow is drawn from `edgePoint.getTargetDirection()`.

## 2.8 THE BOTTOM LINE

### Setup
Let the vehicle be on `TrackEdge edge` (as returned by
`graph.getConnectionsFrom(tp.node1).get(tp.node2)` from its `TravellingPoint tp`), and let
`travelDir` be its world-space unit travel direction (e.g. `tp.getPosition(graph).subtract(prevPos).normalize()`,
or `edge.getDirectionAt(tp.position)` if you trust the graph).

Define the travel-oriented node pair, exactly as `Navigation`/`TravellingPoint` do:
```java
TrackNode nodeBehind = forward ? tp.node1 : tp.node2;   // the node you are leaving
TrackNode nodeAhead  = forward ? tp.node2 : tp.node1;   // the node you are heading to
```
and note the verifiable invariant `edge.node1 == nodeBehind` (because Create keeps one directed
`TrackEdge` per direction — §2.4). So `tp.position` and `point.getLocationOn(edge)` are both distances
in blocks measured from `nodeBehind`, increasing toward `nodeAhead`.

### Formula 1 — the Create-native predicate (authoritative, exact, no trigonometry)
```java
boolean sameWay(TrackEdgePoint p, TrackNode nodeAhead) { return p.isPrimary(nodeAhead); }
// isPrimary(node) == p.edgeLocation.getSecond().equals(node.getLocation())
```
```
顺向(signal)  ==  signalBoundary.canNavigateVia(nodeAhead)
               ==  !signalBoundary.blockEntities.get(signalBoundary.isPrimary(nodeAhead)).isEmpty()

顺向(station) ==  station.canApproachFrom(nodeAhead)
               ==  station.isPrimary(nodeAhead) && !station.assembling
```
and `isPrimary(nodeAhead)` is equivalent to (and cheaper to compute via)
`nodeAhead.getLocation().equals(p.edgeLocation.getSecond())`.

### Formula 2 — purely geometric, given `(edge, travelDir)`
Because `edgeLocation`'s two endpoints are exactly `edge`'s two endpoints as a set, and the vehicle
moves `nodeBehind → nodeAhead`, we get `isPrimary(nodeAhead) ⟺ edgeLocation.getSecond() == nodeAhead`.
So with
```java
Vec3 A = graph.locateNode(p.edgeLocation.getFirst()).getLocation().getLocation();   // node at getFirst()
Vec3 B = graph.locateNode(p.edgeLocation.getSecond()).getLocation().getLocation();  // node at getSecond()
```
```
顺向  <==>  dot(travelDir, B.subtract(A).normalize()) > 0
```
i.e. **"the thing points the same way I am travelling" iff your travel direction has a positive dot
with the vector from `edgeLocation.getFirst()` to `edgeLocation.getSecond()`.** This form is
orientation-independent (it does not care how your `TrackEdge` object happens to be oriented) and needs
no `position` at all.

### Formula 3 — the original `(position, edge)` form, if you want "am I approaching it"
```java
double d      = p.getLocationOn(edge);        // blocks from edge.node1 (== nodeBehind)
double signed = forward ? d - tp.position     // > 0 => the point is still ahead of me
                        : tp.position - d;
boolean reachableInMyDirection = signed >= 0; // Navigation's test, verbatim
```
`Navigation` computes precisely this: skip the point unless `getLocationOn(edge) >= edge.getLength() - local19`
where `local19 == edge.getLength() - tp.position` for forward travel — i.e. `getLocationOn(edge) >= tp.position`.
Combining: the point is in your path **and** `顺向` iff `reachable && p.isPrimary(nodeAhead)`.

### Formula 4 — `TrackTargetingBehaviour.targetDirection`, the raw arrow
```java
Direction.AxisDirection arrow = ((SignalBlockEntity) be).edgePoint.getTargetDirection();  // or from NBT "TargetDirection"
```
and in `createEdgePoint`, `prevDirection = edge.getDirectionAt(loc.position).scale(arrow == POSITIVE ? -1 : +1)`.
So, at creation time:
```
POSITIVE  =>  the signal's governed track direction is OPPOSITE to edge.getDirectionAt(position)
NEGATIVE  =>  it is ALONG edge.getDirectionAt(position)
```
> **UNVERIFIED:** that `prevDirection` is the same vector as the visible arrow. `prevDirection` is used
> for change-detection (`prevDirection · rotatedDirection < -0.85` triggers the auto-flip), and the
> actual rendered arrow pose comes from `TrackTargetingBehaviour.render(level, pos, targetDirection, ...)`
> which I did not decompile. Treat Formula 4 as "how the code stores/uses the arrow", not as a
> pixel-accurate statement about the on-screen arrow.

## 2.9 Simurail already computes 顺向 — use this if you can

`PhysicsBogeyAxle.lambda$updateOuterProbe$4(Double, Pair)` is the probe callback; the `Pair` is
`(TrackEdgePoint, Couple<TrackNode>)` and (per §2.2(C)) `pair.getSecond()` is the **travel-oriented**
node couple `(nodeBehind, nodeAhead)`:
```
4194: private boolean lambda$updateOuterProbe$4(Double, Pair);
 4196: pair.getFirst()  -> TrackEdgePoint        (local6)
 4200: pair.getSecond() -> Couple<TrackNode>     (local4)
 4204: trackGraph
 4206: aload 4 ; 4207: TrackGraph.getConnection(Couple) ; 4208: astore 5     // local5 = getConnectionsFrom(local4.getFirst()).get(local4.getSecond())
 4209: local6 -> local7 (typeSwitch)
 4215: invokedynamic typeSwitch
 4216: tableswitch { -1: 531, 0: 68, 1: 191, default: 534 }
  68: -> GlobalStation branch
 191: -> SignalBoundary branch
```
GlobalStation branch (offsets 102-188):
```
 105: probeData.stationName  = station.name
 117: probeData.stationId    = station.id
 129: probeData.stationPos   = edge.getPosition(trackGraph, station.getLocationOn(edge) / edge.getLength())
 158: probeData.stationBlockPos = station.blockEntityPos
 170: probeData.stationPowered   = level.hasNeighborSignal(station.blockEntityPos)
 188: goto 610
```
SignalBoundary branch (offsets 198-311), guarded by `signalDistance < 0` (first signal wins):
```
 214: probeData.signalDistance  = abs(distance)
 228: probeData.signalNames     = getSignalNames(signal, local4.getSecond())        // <-- nodeAhead
 249: probeData.signalPos       = edge.getPosition(trackGraph, signal.getLocationOn(edge) / edge.getLength())
 278: probeData.signalAligned   = signal.canNavigateVia(local4.getSecond())          // <-- *** 顺向 ***
 298: probeData.signalBidirectional = signal.blockEntities.both(lambda$updateOuterProbe$2)
```
with
```
4459: private static boolean lambda$updateOuterProbe$2(Map m) { return !m.isEmpty(); }
4449: private static boolean lambda$updateOuterProbe$3(Map m) { return !m.isEmpty(); }
```
(bootstrap #16 → `lambda$updateOuterProbe$2`, resolved via `-v` `BootstrapMethods`; #17 → `$3`.)
so `signalBidirectional == blockEntities.getFirst().isEmpty() == false && blockEntities.getSecond().isEmpty() == false`
= **there is at least one signal block on BOTH sides.**

And `getSignalNames` uses the very same side selection:
```
674: protected List<String> getSignalNames(SignalBoundary, TrackNode);
 676: boundary.blockEntities
 677: aload_1 ; 680: boundary.isPrimary(node) ; 681: Couple.get(Z) ; 682: checkcast Map
 683: Map.keySet()  -> stream -> flatMap(SignalNameExtractor) -> filter -> toList
```

**Conclusion:** Simurail's `PhysicsBogeyProbeData.isSignalAligned()` **is exactly**
`signal.canNavigateVia(nodeAhead)`, i.e. the `顺向` you asked for, already computed for you and
reachable publicly as
`PhysicsBogeyBlockEntity#getAxle(boolean)#getProbeData()#isSignalAligned()`.
There is **no** station-alignment flag in `PhysicsBogeyProbeData`; for stations you must call
`GlobalStation.canApproachFrom(nodeAhead)` yourself (or compare
`station.edgeLocation.getSecond()` with your ahead-node).

Related: `ProbeReaderMode.ALIGNED_SIGNAL` vs `OPPOSITE_SIGNAL` are the mode names Simurail's probe
reader uses for this distinction (verified as enum constants; **UNVERIFIED** which of
`isSignalAligned()` / `isOccupiedSignalAligned()` each maps to inside `ProbeReaderBlockEntity`).

## 2.10 Q2 UNVERIFIED list

- **Sense of `BezierConnection.getPosition(double)` / `getNormal(double)` / `incrementT(double,double)`**
  relative to `node1`/`node2`. The `Mth.clamp(t,0,1)` proves the range; the node1→node2 sense is
  inferred from every consumer (`getPosition` straight branch, `getDirection`, `getDirectionAt`,
  `TravellingPoint.getPositionWithOffset`, `TrackEdgePoint.getLocationOn`), not read from
  `BezierConnection` itself. For curved (bezier) track, treat the sense as high-confidence but not
  bytecode-proven.
- **`prevDirection` vs the rendered arrow** (Formula 4), as noted above.
- **The exact `Vec3` that `TrackTargetingBehaviour.determineGraphLocation()` passes** is
  `ITrackBlock.getTrackAxes(level, pos, state).get(0)` (verified: `List.get(0)`), but I did not
  decompile `getTrackAxes` for each track material, so "which world direction is `axes.get(0)`" is
  per-block-shape dependent and not enumerated here. It does **not** affect Formulas 1–3.
- **`EdgeData.get(EdgePointType, double)`'s exact comparison** was not decompiled (it is not needed for
  the formulas) — only `addPoint`'s insertion sort was read.
- **The mid-edge / "orthogonal" branch of `getGraphLocationAt`** computes
  `graphLocation.position = node13.location.distanceTo(node14.location) / 2.0` (bytecode quoted in
  §2.1(B) context and at offsets 718-746). Because it is a midpoint it is symmetric and therefore
  cannot disambiguate the `position` origin — no contradiction with §2.2, but it means I did not
  independently confirm the origin from that branch. The origin is confirmed by (A) and (C).
- **`SingleBlockEntityEdgePoint` stations never take the swap branch** (`primary` is forced true by
  `|| (edgePoint instanceof SingleBlockEntityEdgePoint)`), so for stations `edgeLocation == graphLocation.edge`
  and `position == graphLocation.position` unconditionally. The arrow still influences
  `graphLocation.edge` because it is passed into `getGraphLocationAt`/`getBezierGraphLocationAt` — I
  verified the *mechanism* (`neighbour` chosen by matching `-axes.get(0) * direction.getStep()`) but did
  not verify the resulting geometry in-game.
- No Simurail/Sable class computes a **station** `顺向` flag; that is a gap you will need to fill with
  `GlobalStation.canApproachFrom` / `isPrimary`.
