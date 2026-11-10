# Create: Aeronautics / Sable — Offline Java API Reconnaissance

Target: Minecraft 1.21.1, NeoForge 21.1.233
Source: local jars only (no network). All class/method signatures below were obtained with
`javap -p` / `javap -v` from `C:\Program Files\Java\jdk-21\bin\javap.exe`, or by a raw
constant-pool byte scan of the `.class` entries.

Legend:
- **[V]** = verified by javap / constant-pool scan (exact output quoted).
- **[I]** = inferred (from naming, annotations, or usage patterns; not directly proven).

---

## 0. Tooling / repro notes

Working mods directory (note: some filenames carry Chinese display prefixes):

```
D:\CITY\.minecraft\versions\1.21.1-NeoForge_21.1.233CRA6月20号物理火车包\mods\
```

Gotchas hit during recon (worth reusing):

1. The primary jar is **not** named `create-aeronautics-bundled-1.21.1-1.3.0.jar`. Its real name is
   `[机械动力：航空学] create-aeronautics-bundled-1.21.1-1.3.0.jar`.
2. Create is `[机械动力] create-1.21.1-6.0.10.jar` (Chinese prefix).
3. PowerShell 5.1 only. `pwsh` here is Windows PowerShell 5.1 — `.ps1` files **must have a UTF-8 BOM**
   or the Chinese path literals are decoded as GBK and every `ZipFile.OpenRead` fails with
   "illegal characters in path". Script execution policy must be bypassed per-process:
   `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force`.
4. `[System.IO.Compression.ZipFile]` needs `Add-Type -AssemblyName System.IO.Compression.FileSystem`.
5. `jar.exe tf <jar>` works and is the most robust way to list entries.

Scratch artifacts produced (all under `D:\DS_WORKSPACE\raildispatch\_recon\`):
`lists/classes-*.txt`, `raw/*.txt` (javap dumps), `refs/*.refs.tsv` (constant-pool xrefs),
`all-mods-toml.txt`, `all-mixins.json.txt`, `all-lang.txt`, `sable-apistatus.tsv`.

---

## 1. Mod identity, entry points, metadata

### 1.1 The bundled Aeronautics jar is a jarjar wrapper only

`[机械动力：航空学] create-aeronautics-bundled-1.21.1-1.3.0.jar` contains **10 entries, zero classes**:

```
META-INF/MANIFEST.MF
aeronautics_bundled.png
META-INF/neoforge.mods.toml
META-INF/jarjar/dev.eriksonn.aeronautics.aeronautics-neoforge-1.21.1-1.3.0.jar
META-INF/jarjar/dev.ryanhcode.offroad.offroad-neoforge-1.21.1-1.3.0.jar
META-INF/jarjar/dev.simulated_team.simulated.simulated-neoforge-1.21.1-1.3.0.jar
META-INF/jarjar/metadata.json
LICENSE.md
```
**[V]**

* `modId = "aeronautics_bundled"`, version `1.3.0`, displayName `Create Aeronautics`,
  **`modLoader = "lowcodefml"`**, authors `Simulated Team`, requires `neoforge >= 21.1.228`, `minecraft [1.21.1]`.
  **[V]** — no `@Mod` class of its own; it only nests three mods.

### 1.2 Full mod table

`@Mod(value=...)` values below are read straight out of the `RuntimeVisibleAnnotations` of the named
class with `javap -v` — all **[V]**.

| key | modId | version | loader | `@Mod` class (verified) | depends on |
|---|---|---|---|---|---|
| bundle | `aeronautics_bundled` | 1.3.0 | lowcodefml | (none — jarjar wrapper) | neoforge, minecraft |
| aeronautics | `aeronautics` | 1.3.0 | javafml | **`dev.eriksonn.aeronautics.neoforge.AeronauticsNeoForge`** — `@Mod("aeronautics")` | create `[6.0.10,)`, **sable `[2.0.0,3.0.0)`**, **simulated `[1.3.0,)`** |
| simulated | `simulated` | 1.3.0 | javafml | **`dev.simulated_team.simulated.neoforge.SimulatedNeoForge`** — `@Mod("simulated")` | create `[6.0.10,)`, flywheel `[1.0.6,)` (CLIENT), **sable `[2.0.0,3.0.0)`** |
| offroad | `offroad` | 1.3.0 | javafml | **`dev.ryanhcode.offroad.neoforge.OffroadNeoForge`** — `@Mod("offroad")` | create, **sable `[2.0.0,3.0.0)`**, **simulated `[1.3.0,)`** |
| sable | `sable` | 2.0.3 | javafml | `dev.ryanhcode.sable.Sable` (no `@Mod`; `Sable.init()`) **[V]** | neoforge `>=21.1.228`, minecraft; optional flywheel, optional create `[6.0.10,6.1.0)` CLIENT |
| sable_schematic_api | `sable_schematic_api` | 0.4.0 | javafml | **`dev.rew1nd.sableschematicapi.SableSchematicApi`** — `@Mod("sable_schematic_api")` | neoforge, minecraft, **sable `[1.1.3,)` required**, `ldlib2 [2.2.6,)` required; optional create, simulated, copycats, drivebywire |
| drivebywire | `drivebywire` | 0.2.9 | javafml | **`edn.stratodonut.drivebywire.DriveByWireMod`** — `@Mod("drivebywire")` | neoforge `>=21.1.219`, minecraft, **create `[6.0.9,)` required**, **sable `[1.0.6,)` required**, optional create_tweaked_controllers |
| simurail | `simurail` | 0.0.0 | javafml | **`com.crystaelix.simurail.Simurail`** — `@Mod("simurail")` | minecraft `[1.21,1.21.2)`, **sable `[2,)` required**, **simulated `[1,)` required** |
| create_train_parts | `create_train_parts` | 0.4.0 | javafml | **`com.tiestoettoet.create_train_parts.CreateTrainParts`** — `@Mod("create_train_parts")` | neoforge `>=21.1.0`, **create `[6.0.4,6.1.0)`**, minecraft. **No sable/aeronautics dependency.** |
| tracks (not requested, found adjacent) | `tracks` | 1.0.1 | javafml | **`dev.qwxon.tracks.neoforge.TracksNeoForge`** — `@Mod("tracks")` | create, simulated, sable, offroad **[V — constant-pool refs]** |

Note the three `*NeoForge` classes are the actual `@Mod` entry points; the same-package-name
`Aeronautics` / `Simulated` / `Tracks` / `OffroadNeoForgeClient` classes are **not** annotated
(`dev.eriksonn.aeronautics.Aeronautics`, `dev.simulated_team.simulated.Simulated`,
`dev.qwxon.tracks.Tracks` are plain static-init holders with a `public static void init()`) **[V]**.
Confirmed real usage of the container-ready hook, straight from `Aeronautics`'s lambda bootstrap:
`dev.eriksonn.aeronautics.events.AeronauticsCommonEvents.onSubLevelContainerReady(Level, SubLevelContainer)` **[V]**.

**Ownership answer:**
- **Physics engine = `sable`** (`dev.ryanhcode.sable.*`, author RyanHCode). "Interactive moving block
  structures with physics." It bundles its own native backend:
  `META-INF/jarjar/dev.ryanhcode.sable.sable-sable_rapier-1.21.1-2.0.3.jar` (Rapier physics) **[V]**.
- **Aeronautics content = `aeronautics`** (`dev.eriksonn.aeronautics.*`) — hot air balloons, propellers,
  levitite. **Ground vehicles = `offroad`** (`dev.ryanhcode.offroad.*`) — wheel mounts, tires.
  **Shared interaction/content base = `simulated`** (`dev.simulated_team.simulated.*`) — physics assembler,
  steering wheel, throttle lever, swivel bearing, ropes, springs.

### 1.3 Nested jars discovered

- `sable-neoforge-1.21.1-2.0.3.jar` contains `META-INF/jarjar/`:
  `dev.ryanhcode.sable.sable-sable_rapier-1.21.1-2.0.3.jar`,
  **`sable-companion-common-1.21.1-1.6.0.jar`**, `veil-neoforge-1.21.1-4.1.4.jar` **[V]**
  → `dev.ryanhcode.sable.companion.**` (including `Pose3d`/`Pose3dc`, `BoundingBox3d*`, `JOMLConversion`,
  `SubLevelAccess`, `SableCompanion`) lives **in the companion jar, not in the sable mod jar**.
  Many other mods (`createaddition`, `create_connected`, `createdieselgenerators`, `electroenergetics`,
  `vista`, `create_power_loader`, `aeronauticadditions`) also nest a copy of
  `sable-companion-common-1.21.1-1.5.0.jar` / `-1.6.0.jar` **[V]** — i.e. the companion API is the
  intended cross-mod surface.

---

## 2. The physics core API (Sable)

### 2.1 Representation of a player-built moving structure

A moving structure is a **`SubLevel`** living inside a **`SubLevelContainer`** (a "plot grid" of
reserved chunk slots). Three concrete kinds:

| fqcn | notes | [V] |
|---|---|---|
| `dev.ryanhcode.sable.companion.SubLevelAccess` | minimal read-only view: `logicalPose()`, `lastPose()`, `boundingBox()`, `getUniqueId()`, `getName()` | V |
| `dev.ryanhcode.sable.sublevel.SubLevel` | `abstract`, implements `SubLevelAccess`. `getLevel()`, `logicalPose()`, `lastPose()`, `boundingBox()`, `getPlot()`, `isRemoved()`, `markRemoved()`, `getUniqueId()`, `getName()`, `setName(String)`, `tick()`, `onRemove()`, `updateLastPose()`, `forceUpdateGlobalBounds()` | V |
| `dev.ryanhcode.sable.sublevel.ServerSubLevel` | `extends SubLevel implements PhysicsPipelineBody`. Adds `latestLinearVelocity`, `latestAngularVelocity` (public `Vector3d`), `getRuntimeId()`, `getMassTracker()`, `getSelfMassTracker()`, `buildMassTracker()`, `getQueuedForceGroups()`, `getOrCreateQueuedForceGroup(ForceGroup)`, `enableIndividualQueuedForcesTracking(boolean)`, `getUserDataTag()/setUserDataTag(CompoundTag)`, `applyQueuedForces(...)`, `prePhysicsTick(...)`, `getReactionWheelManager()`, `getHeatMapManager()`, `getFloatingBlockController()` | V |
| `dev.ryanhcode.sable.sublevel.ClientSubLevel` | `renderPose()`, `renderPose(float)`, `getInterpolator()`, `isFinalized()` | V |

Sable does **not** use Create's `Contraption` for its own structures; it defines a separate
`dev.ryanhcode.sable.api.sublevel.KinematicContraption` interface for things that behave contraption-like.

```java
public interface dev.ryanhcode.sable.api.sublevel.KinematicContraption {   // [V]
  void sable$getLocalBounds(dev.ryanhcode.sable.companion.math.BoundingBox3i);
  net.minecraft.world.level.BlockGetter sable$blockGetter();
  dev.ryanhcode.sable.api.physics.mass.MassTracker sable$getMassTracker();
  org.joml.Vector3dc sable$getPosition(double);
  org.joml.Quaterniond sable$getOrientation(double);
  java.util.Map<net.minecraft.core.BlockPos, dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider$LiftProviderContext> sable$liftProviders();
  dev.ryanhcode.sable.physics.floating_block.FloatingClusterContainer sable$getFloatingClusterContainer();
  boolean sable$shouldCollide();
  boolean sable$isValid();
  // defaults: sable$getPosition(), sable$getOrientation(), sable$getLocalPose(Pose3d,double)
}
```

### 2.2 Getting the physics object for a world position  ← primary lookup recipe

**Step 1 — world position → SubLevel.** Two equivalent routes:

```java
// Route A: the companion (recommended; this is the "public" surface)
dev.ryanhcode.sable.companion.SableCompanion.INSTANCE     // static interface field  [V]
net.minecraft.world.level.Level level;
dev.ryanhcode.sable.companion.SubLevelAccess sl =
    SableCompanion.INSTANCE.getContaining(level, new net.minecraft.core.BlockPos(x,y,z));   // Vec3i overload
// overloads: (Level,int,int) (Level,ChunkPos) (Level,SectionPos) (Level,Vec3i)
//            (Level,Position) (Level,Vector3dc) (Level,double,double)
//            (Entity) (BlockEntity)
// also: getContainingClient(...) -> ClientSubLevelAccess
//       getAllIntersecting(Level, BoundingBox3dc) -> Iterable<? extends SubLevelAccess>
//       getTrackingSubLevel(Entity) / getLastTrackingSubLevel(Entity)
//       getVehicleSubLevel(Entity) / getTrackingOrVehicleSubLevel(Entity)
//       projectOutOfSubLevel(Level, Vec3) -> Vec3
//       isInPlotGrid(Level, ...) -> boolean
//       getVelocity(Level, ...)  and  getVelocityRelativeToAir(Level, ...)

// Route B: the concrete implementation (preferred by aeronautics/simulated)
dev.ryanhcode.sable.ActiveSableCompanion helper = dev.ryanhcode.sable.Sable.HELPER;  // static final field [V]
dev.ryanhcode.sable.sublevel.SubLevel sl2 =
    helper.getContaining(level, pos);      // returns SubLevel (concrete)
```
`dev.ryanhcode.sable.Sable` **[V]**: `public static final String MOD_ID; public static final ActiveSableCompanion HELPER;`
`dev.ryanhcode.sable.ActiveSableCompanion` **[V]** implements `SableCompanion` and widens return types to
`SubLevel` / `ClientSubLevel`.

Third route (container-oriented, used by drivebywire and sableschem):

```java
dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer c =
    dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(serverLevel);   // static  [V]
// class SubLevelContainer: getSubLevel(int,int), getSubLevel(UUID), getAllSubLevels(),
//   getPlot(int,int), getPlot(ChunkPos), getPlot(BlockPos) [on subclass], inBounds(BlockPos|ChunkPos|Vector3dc|int,int),
//   queryIntersecting(BoundingBox3dc) -> Iterable<SubLevel>, getContainer(Level|ServerLevel|ClientLevel)
// ServerSubLevelContainer: physicsSystem() -> SubLevelPhysicsSystem, trackingSystem(),
//   getHoldingChunkMap(), addForceLoadTicket(...), removeForceLoadTicket(...)
// SubLevelContainer declares public net.minecraft.world.level.Level getLevel()  [V, covariant in subclasses]

// Position -> plot -> sublevel:
// SubLevelContainer exposes ONLY these two plot getters (verified):
//   public LevelPlot getPlot(int x, int z)          <-- NOTE: (int,int) is the *plot grid* index,
//   public LevelPlot getPlot(ChunkPos pos)              not a world block coordinate
// There is NO getPlot(BlockPos) overload. To go from a world BlockPos to a plot, use
// getChunk(new ChunkPos(blockPos)) / inBounds(blockPos) and the private getLocalPlot(int,int) path,
// or simply use SableCompanion.getContaining(level, blockPos) instead.   [V]
dev.ryanhcode.sable.sublevel.SubLevel sl3 = plot.getSubLevel();                    // [V]
```

**Step 2 — SubLevel → physics handle.**

```java
dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem system =
    dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem.get(level);      // nullable
// or .require(level)  — throws if absent                                  [V]
// static: getCurrentlySteppingSystem(), IN_PHYSICS_STEP (static boolean)
dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle handle =
    system.getPhysicsHandle(serverSubLevel);                                   // [V]
// equivalent: RigidBodyHandle.of(serverSubLevel)   or  RigidBodyHandle.of(ServerLevel, PhysicsPipelineBody)  [V]
```

### 2.3 Reading / writing position, rotation, velocity

```java
// --- Pose (position + orientation), via the companion math types -------------
public interface dev.ryanhcode.sable.companion.math.Pose3dc {                 // [V]
  org.joml.Vector3dc position();
  org.joml.Quaterniondc orientation();
  org.joml.Vector3dc rotationPoint();
  org.joml.Vector3dc scale();
  org.joml.Vector3d transformPosition(Vector3dc, Vector3d);
  net.minecraft.world.phys.Vec3 transformPosition(Vec3);
  net.minecraft.world.phys.Vec3 transformPositionInverse(Vec3);
  org.joml.Vector3d transformNormal(Vector3dc, Vector3d);
  org.joml.Vector3d transformNormalInverse(Vector3dc, Vector3d);
  Pose3d lerp(Pose3dc, double, Pose3d);
  org.joml.Matrix4d bakeIntoMatrix(Matrix4d);
  boolean withinTolerance(Pose3d, double, double);
}
public final class dev.ryanhcode.sable.companion.math.Pose3d implements Pose3dc {  // [V]
  public static com.mojang.serialization.Codec<Pose3d> CODEC;
  public Pose3d(Vector3d position, Quaterniond orientation, Vector3d rotationPoint, Vector3d scale);
  public Pose3d(); public Pose3d(Pose3dc);
  public Pose3d set(Pose3dc); public Pose3d lerp(Pose3dc, double);
  public Vector3d position(); public Quaterniond orientation();
  public Vector3d rotationPoint(); public Vector3d scale();
}
// Read:   SubLevel.logicalPose() -> Pose3d   (SubLevelAccess returns Pose3dc)
//         SubLevel.lastPose()    -> Pose3dc
// Write:  RigidBodyHandle.teleport(Vector3dc position, Quaterniondc orientation)
//         PhysicsPipeline.teleport(PhysicsPipelineBody, Vector3dc, Quaterniondc)

// --- Velocity --------------------------------------------------------------
public class dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle {        // [V]
  public static RigidBodyHandle of(net.minecraft.server.level.ServerLevel, PhysicsPipelineBody);
  public static RigidBodyHandle of(dev.ryanhcode.sable.sublevel.ServerSubLevel);
  public RigidBodyHandle(PhysicsPipelineBody, dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem);
  public org.joml.Vector3dc getLinearVelocity();
  public org.joml.Vector3dc getAngularVelocity();
  public org.joml.Vector3d  getLinearVelocity(org.joml.Vector3d dest);
  public org.joml.Vector3d  getAngularVelocity(org.joml.Vector3d dest);
  public boolean isValid();
  // ServerSubLevel also exposes public final Vector3d latestLinearVelocity / latestAngularVelocity  [V]
}
```

### 2.4 Applying force / torque / impulse

```java
public class dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle {        // [V]
  public void applyImpulseAtPoint(org.joml.Vector3dc position, org.joml.Vector3dc impulse);
  public void applyImpulseAtPoint(net.minecraft.world.phys.Vec3 position, net.minecraft.world.phys.Vec3 impulse);
  public void applyLinearAndAngularImpulse(org.joml.Vector3dc linear, org.joml.Vector3dc angular);
  public void applyLinearAndAngularImpulse(org.joml.Vector3dc linear, org.joml.Vector3dc angular, boolean wake);
  public void applyLinearImpulse(org.joml.Vector3dc linear);
  public void applyAngularImpulse(org.joml.Vector3dc angular);
  public void applyTorqueImpulse(org.joml.Vector3dc torque);
  public void addLinearAndAngularVelocity(org.joml.Vector3dc linear, org.joml.Vector3dc angular);
  public void teleport(org.joml.Vector3dc position, org.joml.Quaterniondc orientation);
  public void applyForcesAndReset(dev.ryanhcode.sable.api.physics.force.ForceTotal total);
}
```

Accumulator / force-group pattern (this is how `tracks` and `offroad` actually do it):

```java
public class dev.ryanhcode.sable.api.physics.force.ForceTotal {              // [V]
  public ForceTotal();
  public void reset();
  public void applyForces(RigidBodyHandle handle);          // <- flush accumulated force into the body
  public void applyForceTotal(ForceTotal other);
  public void applyLinearAndAngularImpulse(Vector3dc, Vector3dc);
  public void applyLinearImpulse(Vector3dc);
  public void applyAngularImpulse(Vector3dc);
  public void applyTorqueImpulse(Vector3dc);
  public void applyImpulseAtPoint(MassData mass, Vector3dc pos, Vector3dc impulse);
  public void applyImpulseAtPoint(ServerSubLevel sl, Vector3dc pos, Vector3dc impulse);
  public void applyImpulseAtPoint(MassTracker tracker, Vec3 pos, Vec3 impulse);
  public org.joml.Vector3d getLocalForce();
  public org.joml.Vector3d getLocalTorque();
}

public class dev.ryanhcode.sable.api.physics.force.QueuedForceGroup {        // [V]
  public QueuedForceGroup(ServerSubLevel);
  public ForceTotal getForceTotal();
  public void applyAndRecordPointForce(Vector3dc pos, Vector3dc force);
  public void recordPointForce(Vector3dc pos, Vector3dc force);
  public java.util.List<QueuedForceGroup$PointForce> getRecordedPointForces();
  public void reset();
}
// ServerSubLevel.getOrCreateQueuedForceGroup(ForceGroup) -> QueuedForceGroup          [V]
// ServerSubLevel.getQueuedForceGroups() -> Object2ObjectMap<ForceGroup, QueuedForceGroup> [V]
// ServerSubLevel.enableIndividualQueuedForcesTracking(boolean)                          [V]

public final class dev.ryanhcode.sable.api.physics.force.ForceGroup extends java.lang.Record {  // [V]
  ForceGroup(net.minecraft.network.chat.Component name, Component description, int color, boolean defaultDisplayed);
}
public class dev.ryanhcode.sable.api.physics.force.ForceGroups {             // [V]
  public static final ResourceKey<Registry<ForceGroup>> REGISTRY_KEY;
  public static final Registry<ForceGroup> REGISTRY;
  // RegistryObject<ForceGroup>: GRAVITY, DRAG, LEVITATION, BALLOON_LIFT, PROPULSION, LIFT, MAGNETIC_FORCE
}
```

Alternative raw-pipeline route:

```java
public interface dev.ryanhcode.sable.api.physics.PhysicsPipeline {           // [V]
  void init(org.joml.Vector3dc gravity, double dt);
  void dispose();
  void prePhysicsTicks(); void physicsTick(double dt); void postPhysicsTicks(); void tick();
  void add(dev.ryanhcode.sable.sublevel.ServerSubLevel, dev.ryanhcode.sable.companion.math.Pose3dc pose);
  void remove(dev.ryanhcode.sable.sublevel.ServerSubLevel);
  void add(dev.ryanhcode.sable.api.sublevel.KinematicContraption);
  void remove(dev.ryanhcode.sable.api.sublevel.KinematicContraption);
  Pose3d readPose(ServerSubLevel, Pose3d dest);
  RopeHandle addRope(RopePhysicsObject);  BoxHandle addBox(BoxPhysicsObject);
  void teleport(PhysicsPipelineBody, Vector3dc, Quaterniondc);
  void applyImpulse(PhysicsPipelineBody, Vector3dc, Vector3dc);
  void applyLinearAndAngularImpulse(PhysicsPipelineBody, Vector3dc, Vector3dc, boolean);
  void addLinearAndAngularVelocity(PhysicsPipelineBody, Vector3dc, Vector3dc);   // default
  void resetVelocity(PhysicsPipelineBody);                                      // default
  Vector3d getLinearVelocity(PhysicsPipelineBody, Vector3d);                    // default
  Vector3d getAngularVelocity(PhysicsPipelineBody, Vector3d);                   // default
  void wakeUp(PhysicsPipelineBody);
  <T extends PhysicsConstraintHandle> T addConstraint(PhysicsPipelineBody a, PhysicsPipelineBody b,
                                                      PhysicsConstraintConfiguration<T> config); // default
  void updateConfigFrom(dev.ryanhcode.sable.physics.config.PhysicsConfigData);  // default
  int getNextRuntimeID();
  void handleChunkSectionAddition(net.minecraft.world.level.chunk.LevelChunkSection, int,int,int, boolean);
  void handleChunkSectionRemoval(int,int,int);
  void handleBlockChange(SectionPos, LevelChunkSection, int,int,int, BlockState old, BlockState now);
  void onStatsChanged(ServerSubLevel);                                          // default
}
// Obtain: SubLevelPhysicsSystem.getPipeline()  [V]
// Provider SPI: dev.ryanhcode.sable.api.physics.PhysicsPipelineProvider.INSTANCE (ServiceLoader)  [V]
public interface dev.ryanhcode.sable.api.physics.PhysicsPipelineBody {        // [V]
  int NULL_RUNTIME_ID = ...;
  int getRuntimeId();
  dev.ryanhcode.sable.api.physics.mass.MassData getMassTracker();
  boolean isRemoved();
}
```

### 2.5 Mass

```java
public interface dev.ryanhcode.sable.api.physics.mass.MassData {              // [V]
  double getMass(); double getInverseMass();
  org.joml.Matrix3dc getInertiaTensor(); org.joml.Matrix3dc getInverseInertiaTensor();
  org.joml.Vector3dc getCenterOfMass();
  default boolean isInvalid();
  default double getInverseNormalMass(org.joml.Vector3dc, org.joml.Vector3dc);
}
public class dev.ryanhcode.sable.api.physics.mass.MassTracker implements MassData {  // [V]
  public static java.util.function.BiFunction<BlockGetter, BlockState, Vector3dc> BLOCK_CENTER_OF_MASS;
  public static MassTracker build(BlockGetter, BoundingBox3ic);
  public void addBlockMass(BlockGetter, BlockState, BlockPos, double mass, Vec3 center);
  public void moveCenterOfMass(org.joml.Vector3d);
}
public class dev.ryanhcode.sable.api.physics.mass.MergedMassTracker implements MassData { // [V]
  public MergedMassTracker(ServerSubLevel, MassTracker);
  public void update(float partialTick);
  public MassTracker getSelfMassTracker();
}
// ServerSubLevel.getMassTracker() -> MergedMassTracker, getSelfMassTracker() -> MassTracker  [V]
```

### 2.6 Constraints (joints) — the mechanism behind bogies/axles/bearings

```java
public interface dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle {  // [V]
  void getJointImpulses(org.joml.Vector3d outLin, org.joml.Vector3d outAng);
  void setContactsEnabled(boolean);
  void setMotor(dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis axis,
                double targetVel, double targetPos, double stiffness, boolean, double damping);
  void remove();
  boolean isValid();
}
public interface dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle extends PhysicsConstraintHandle { // [V]
  void setFrame1(org.joml.Vector3dc pos, org.joml.Quaterniondc rot);
  void setFrame2(org.joml.Vector3dc pos, org.joml.Quaterniondc rot);
  void setLimit(ConstraintJointAxis axis, double min, double max);
  void lockAxes(ConstraintJointAxis... axes);
}
public interface dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintHandle extends PhysicsConstraintHandle {  // [V]
  ConstraintJointAxis DEFAULT_AXIS = ...;
  default void setServoCoefficients(double, double, double);
}
public interface dev.ryanhcode.sable.api.physics.constraint.FixedConstraintHandle  extends PhysicsConstraintHandle {}  // [V]
public interface dev.ryanhcode.sable.api.physics.constraint.FreeConstraintHandle   extends PhysicsConstraintHandle {}  // [V]

public enum dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis {  // [V]
  LINEAR_X, LINEAR_Y, LINEAR_Z, ANGULAR_X, ANGULAR_Y, ANGULAR_Z;
  static ConstraintJointAxis[] ALL, LINEAR, ANGULAR;
}

// Config records (all implement PhysicsConstraintConfiguration<H>):
//   GenericConstraintConfiguration(Vector3dc pos1, Vector3dc pos2, Quaterniondc orientation1,
//                                  Quaterniondc orientation2 [, Set<ConstraintJointAxis> lockedAxes])
//   FreeConstraintConfiguration(Vector3dc pos1, Vector3dc pos2, Quaterniondc orientation)
//   FixedConstraintConfiguration(Vector3dc pos1, Vector3dc pos2, Quaterniondc orientation)
//   RotaryConstraintConfiguration(Vector3dc pos1, Vector3dc pos2, Vector3dc normal1, Vector3dc normal2)
//   static void PhysicsConstraintConfiguration.validateAnchors(ServerSubLevelContainer,
//                       PhysicsPipelineBody a, PhysicsPipelineBody b, Vector3dc anchorA, Vector3dc anchorB);
// Create with: PhysicsPipeline.addConstraint(bodyA, bodyB, config)   [V]
```
Note: `ServerSubLevel implements PhysicsPipelineBody`, so two sub-levels can be joined directly.

### 2.7 Detecting that a block is part of an assembled structure

```java
// (a) Position-based, cheapest:
dev.ryanhcode.sable.companion.SubLevelAccess sl =
    dev.ryanhcode.sable.companion.SableCompanion.INSTANCE.getContaining(level, blockPos);
boolean assembled = (sl != null);

// (b) Container-based write path (is this chunk section a sub-level plot slot?):
dev.ryanhcode.sable.api.sublevel.SubLevelContainer c = SubLevelContainer.getContainer(level);
boolean inPlot = c.inBounds(blockPos);          // also inBounds(ChunkPos|Vector3dc|int,int)
LevelPlot plot = c.getPlot(new net.minecraft.world.level.ChunkPos(blockPos));  // getPlot(ChunkPos), NOT BlockPos
SubLevel sl2 = (plot == null) ? null : plot.getSubLevel();

// (c) From a BlockEntity:
SubLevel sl3 = Sable.HELPER.getContaining(blockEntity);

// (d) Client side:
ClientSubLevelAccess csl = SableCompanion.INSTANCE.getContainingClient(level, pos);

// (e) Inside a BlockEntity that already knows it is on a sub-level:
//     implement dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor and you receive
//     sable$tick(ServerSubLevel) / sable$physicsTick(ServerSubLevel, RigidBodyHandle, double).
```
`LevelPlot` **[V]** exposes `getSubLevel()`, `contains(Vec3|Vector3dc|ChunkPos)`, `getBoundingBox()`,
`getBlockEntityActors() -> Iterable<BlockEntitySubLevelActor>`,
`getBlockEntityReactionWheels()`, `getBlockEntityReactionWheelMap()`.

### 2.8 Tick / assembly / disassembly hooks

**Physics tick events — these are NOT NeoForge bus events.** They are single-method listener interfaces
registered through Sable's own platform SPI. **[V, from `Tracks.init` bytecode]:**

```java
// registration (verified bytecode: getstatic SableEventPlatform.INSTANCE ; invokeinterface onPhysicsTick)
dev.ryanhcode.sable.platform.SableEventPlatform.INSTANCE.onPhysicsTick(myListener);
dev.ryanhcode.sable.platform.SableEventPlatform.INSTANCE.onPostPhysicsTick(myListener);
dev.ryanhcode.sable.platform.SableEventPlatform.INSTANCE.onSubLevelContainerReady(myListener);

public interface dev.ryanhcode.sable.platform.SableEventPlatform {          // [V]
  SableEventPlatform INSTANCE = ...;                                        // ServiceLoader-backed
  void onSubLevelContainerReady(dev.ryanhcode.sable.api.event.SableSubLevelContainerReadyEvent);
  void onPhysicsTick(dev.ryanhcode.sable.api.event.SablePrePhysicsTickEvent);
  void onPostPhysicsTick(dev.ryanhcode.sable.api.event.SablePostPhysicsTickEvent);
}
@java.lang.FunctionalInterface
public interface dev.ryanhcode.sable.api.event.SablePrePhysicsTickEvent {   // [V]
  void prePhysicsTick(dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem system, double dt);
}
public interface dev.ryanhcode.sable.api.event.SablePostPhysicsTickEvent {  // [V]
  void postPhysicsTick(dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem system, double dt);
}
public interface dev.ryanhcode.sable.api.event.SableSubLevelContainerReadyEvent {  // [V]
  void onSubLevelContainerReady(net.minecraft.world.level.Level level,
                                dev.ryanhcode.sable.api.sublevel.SubLevelContainer container);
}
```
Real usage example (verified): `dev.qwxon.tracks.events.TracksCommonEvents.physicsTick(SubLevelPhysicsSystem, double)`
is registered from `dev.qwxon.tracks.Tracks.init()`.

**Per-block hooks (interfaces your block / block entity implements):**

```java
public interface dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor {    // [V]  (all default)
  default void sable$tick(dev.ryanhcode.sable.sublevel.ServerSubLevel);
  default void sable$physicsTick(dev.ryanhcode.sable.sublevel.ServerSubLevel,
                                 dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle, double dt);
  default java.lang.Iterable<dev.ryanhcode.sable.sublevel.SubLevel> sable$getLoadingDependencies();
  default java.lang.Iterable<dev.ryanhcode.sable.sublevel.SubLevel> sable$getConnectionDependencies();
}
public interface dev.ryanhcode.sable.api.sublevel.SubLevelObserver {         // [V]  (all default)
  default void onSubLevelAdded(dev.ryanhcode.sable.sublevel.SubLevel);
  default void onSubLevelRemoved(dev.ryanhcode.sable.sublevel.SubLevel,
                                 dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason);
  default void tick(dev.ryanhcode.sable.api.sublevel.SubLevelContainer);
}
// register via SubLevelContainer.addObserver(SubLevelObserver)   [V]

public interface dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener { // [V]
  default  void beforeMove(net.minecraft.server.level.ServerLevel from, ServerLevel to,
                           BlockState state, net.minecraft.core.BlockPos fromPos, BlockPos toPos);
  abstract void afterMove (net.minecraft.server.level.ServerLevel from, ServerLevel to,
                           BlockState state, net.minecraft.core.BlockPos fromPos, BlockPos toPos);
}
```

**Assembly / disassembly helpers:**

```java
public class dev.ryanhcode.sable.api.SubLevelAssemblyHelper {                // [V]
  public static dev.ryanhcode.sable.sublevel.ServerSubLevel assembleBlocks(
      net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos anchor,
      java.lang.Iterable<net.minecraft.core.BlockPos> blocks,
      dev.ryanhcode.sable.companion.math.BoundingBox3ic bounds);
  public static void kickFromContainingSubLevel(ServerLevel, SubLevelPhysicsSystem, PhysicsPipeline,
                                                ServerSubLevel, SubLevel);
  public static SubLevelAssemblyHelper$GatherResult gatherConnectedBlocks(
      BlockPos start, ServerLevel, int limit, SubLevelAssemblyHelper$FrontierPredicate predicate);
  public static void moveBlocks(ServerLevel, SubLevelAssemblyHelper$AssemblyTransform,
                                java.lang.Iterable<BlockPos>);
  public static void moveOtherStuff(ServerLevel, AssemblyTransform, Iterable<BlockPos>, BoundingBox3ic);
  public static void moveTrackingPoints(ServerLevel, BoundingBox3ic, ServerSubLevel, AssemblyTransform);
}
public class SubLevelAssemblyHelper$AssemblyTransform {                      // [V]
  AssemblyTransform(BlockPos anchorPos, BlockPos resultingAnchorPos, int angle,
                    net.minecraft.world.level.block.Rotation, ServerLevel resultingLevel);
  Vec3 apply(Vec3); BlockPos apply(BlockPos); BlockState apply(BlockState);
  ServerLevel getLevel(); net.minecraft.world.level.block.Rotation getRotation();
}
// Removal: SubLevelContainer.removeSubLevel(SubLevel, dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason)
//                                  or removeSubLevel(int,int,SubLevelRemovalReason)     [V]

// Higher-level (simulated mod, NOT sable):
public class dev.simulated_team.simulated.util.SimAssemblyHelper {           // [V]
  public static SimAssemblyHelper$AssemblyResult assembleFromSingleBlock(
      net.minecraft.world.level.Level, BlockPos anchor, BlockPos, boolean, boolean)
      throws com.simibubi.create.content.contraptions.AssemblyException;
  public static void disassembleSubLevel(net.minecraft.world.level.Level, SubLevel, BlockPos, BlockPos,
                                         net.minecraft.world.level.block.Rotation, boolean);
  public static void register();
}
// record SimAssemblyHelper$AssemblyResult(SubLevel subLevel, BlockPos offset)
```

**Block-behaviour SPIs for physics participation:**

```java
public interface dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider {   // [V]
  static final Direction[] DIRECTIONS; static final Vector3d LIFT_FORCE, LIFT_POS, LIFT_NORMAL,
                                          LIFT_VELO, DRAG, TEMP;
  static void resetVectors();
  static java.util.List<BlockSubLevelLiftProvider$LiftProviderGroup> groupLiftProviders(
      java.util.Collection<LiftProviderContext>);
  net.minecraft.core.Direction sable$getNormal(net.minecraft.world.level.block.state.BlockState);
  default float sable$getParallelDragScalar();
  default float sable$getDirectionlessDragScalar();
  default float sable$getLiftScalar();
  default void  sable$contributeLiftAndDrag(LiftProviderContext ctx, ServerSubLevel, Pose3d,
                       double, Vector3dc, Vector3dc, Vector3d outForce, Vector3d outTorque, LiftProviderGroup);
}
// record LiftProviderContext(BlockPos pos, BlockState state, net.minecraft.world.phys.Vec3 dir)

public interface dev.ryanhcode.sable.api.block.BlockEntitySubLevelReactionWheel {  // [V]
  void sable$getAngularVelocity(org.joml.Vector3d out);
  BlockState getBlockState();
}
public interface dev.ryanhcode.sable.api.block.BlockSubLevelCustomCenterOfMass {   // [V]
  org.joml.Vector3dc getCenterOfMass(BlockGetter, BlockState);
}
public interface dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape {       // [V]
  net.minecraft.world.phys.shapes.VoxelShape getSubLevelCollisionShape(BlockGetter, BlockState);
}
public interface dev.ryanhcode.sable.api.block.BlockSubLevelDynamicCollider {      // [V]
  void buildBoxes(dev.ryanhcode.sable.api.physics.collider.VoxelColliderData);
}
public interface dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback { // [V]
  static dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback sable$getCallback(BlockState);
  static boolean hasCallback(BlockState);
  dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback sable$getCallback();
}
public interface dev.ryanhcode.sable.api.block.propeller.BlockEntityPropeller {    // [V]
  Direction getBlockDirection(); double getAirflow(); double getThrust(); boolean isActive();
  default double getScaledThrust(); default double getCurrentAirPressure(); default double getAirflowScaling();
  Level getLevel(); BlockPos getBlockPos();
}
public interface dev.ryanhcode.sable.api.block.propeller.BlockEntitySubLevelPropellerActor
        extends dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor {           // [V]
  static final Vector3d THRUST_VECTOR, THRUST_POSITION;
  BlockEntityPropeller getPropeller();
  default void sable$physicsTick(ServerSubLevel, RigidBodyHandle, double);
  default void applyForces(ServerSubLevel, net.minecraft.world.phys.Vec3, double);
}
```

**Physics material properties (mass/friction/restitution) of blocks:**
```java
public class dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper {  // [V]
  public static double getMass(BlockGetter, BlockPos, BlockState);
  public static net.minecraft.world.phys.Vec3 getInertia(BlockGetter, BlockPos, BlockState);
  public static double getFriction(BlockState);
  public static double getVolume(BlockState);
  public static double getRestitution(BlockState);
  public static double getFloatingScale(BlockState);
  public static dev.ryanhcode.sable.physics.floating_block.FloatingBlockMaterial getFloatingMaterial(BlockState);
}
```
Sable tags found in data (simurail ships them): `data/sable/tags/block/heavy.json`, `.../super_light.json` **[V]**.

### 2.9 Other useful Sable API

```java
public class dev.ryanhcode.sable.api.SubLevelHelper {                        // [V]
  static void pushEntityLocal(SubLevel, net.minecraft.world.entity.Entity);
  static void popEntityLocal(SubLevel, Entity);
  static void pushEntityLocal(SubLevel, Entity, net.minecraft.commands.arguments.EntityAnchorArgument$Anchor);
  static void popEntityLocal(SubLevel, Entity, EntityAnchorArgument$Anchor);
  static org.joml.Vector3d getVelocityRelativeToAir(Level, Vector3dc, Vector3d dest);
  static void registerWindProvider(java.util.function.BiFunction<Vector3dc, Level, Vector3dc>);
  static java.util.Collection<ServerSubLevel> getLoadingDependencyChain(ServerSubLevel);
  static java.util.Collection<SubLevel> getConnectedChain(SubLevel);
}
public class dev.ryanhcode.sable.api.entity.EntitySubLevelUtil {             // [V]
  static void setOldPosNoMovement(Entity);
  static void kickEntity(SubLevel, Entity);
  static boolean shouldKick(Entity);
  static Quaterniondc getCustomEntityOrientation(Entity, float);
  static boolean hasCustomEntityOrientation(Entity);
}
public class dev.ryanhcode.sable.api.schematic.SubLevelSchematicSerializationContext {  // [V]
  SubLevelSchematicSerializationContext(SubLevelSchematicSerializationContext$Type, BoundingBox3i);
  static SubLevelSchematicSerializationContext getCurrentContext();
  static void setCurrentContext(SubLevelSchematicSerializationContext);
  SubLevelSchematicSerializationContext$Type getType();     // enum { PLACE, SAVE }
  BoundingBox3i getBoundingBox();
  SchematicMapping getMapping(SubLevel); getMapping(java.util.UUID);
  java.util.Map<java.util.UUID, SchematicMapping> getMappings();
  it.unimi.dsi.fastutil.Function<BlockPos,BlockPos> getPlaceTransform()/getSetupTransform()/setters
}
public class dev.ryanhcode.sable.api.math.OrientedBoundingBox3d { /* OBB + SAT overlap tests */ }  // [V]
public class dev.ryanhcode.sable.companion.math.JOMLConversion {             // [V]
  static final Vector3dc ZERO, ONE, HALF; static final Quaterniondc QUAT_IDENTITY;
  static Vector3d toJOML(net.minecraft.core.Position[, Vector3d]);
  static Vector3d atCenterOf(net.minecraft.core.Vec3i[, Vector3d]);
  static Vector3d atLowerCornerOf(Vec3i[, Vector3d]);
  static Vector3d atBottomCenterOf(Vec3i[, Vector3d]);
  static Vector3d upFromBottomCenterOf(Vec3i, double[, Vector3d]);
  static net.minecraft.world.phys.Vec3 toMojang(Vector3dc);
  static Vector3i toJOML(Vec3i[, Vector3i]);
  static Vector3d getAABBCenter(net.minecraft.world.phys.AABB[, Vector3d]);
}
public interface dev.ryanhcode.sable.companion.math.BoundingBox3dc { /* minX..maxZ, contains, intersects,
  expandTo, expand, move, intersect, transform(Pose3dc), transformInverse, center, size, toMojang */ }  // [V]
public interface dev.ryanhcode.sable.companion.math.BoundingBox3ic { /* int variant, toAABB() */ }      // [V]
public interface dev.ryanhcode.sable.platform.SableAssemblyPlatform {        // [V]
  SableAssemblyPlatform INSTANCE = ...;
  void setIgnoreOnPlace(net.minecraft.world.level.Level, boolean);
}
// Other platform SPIs seen in javap: SableChunkEventPlatform, SableEventPublishPlatform,
// SableLoaderPlatform, SablePlatform, SableSubLevelRenderPlatform (+ *Impl classes in
// dev.ryanhcode.sable.neoforge.platform.*)                                                    [V]
```

---

## 3. Bogie / wheel / steering / 转向架

There is **no class literally named `Bogie` in Aeronautics/Sable itself.** Bogie-like behaviour is split
across three places, plus the Create-train system for real rails.

### 3.1 Offroad wheel mount (cars) — the wheel + steering block

```java
public class dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlock
    extends com.simibubi.create.content.kinetics.base.HorizontalKineticBlock
    implements com.simibubi.create.foundation.block.IBE<WheelMountBlockEntity>,
               com.simibubi.create.api.schematic.requirement.SpecialBlockItemRequirement        // [V]
// BlockEntityType registered; getBlockEntityClass/getBlockEntityType; getRotationAxis();
// hasShaftTowards(...); getStateForPlacement(...); useItemOn(...) (switches tire stacks)

public class dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity
    extends com.simibubi.create.content.kinetics.base.KineticBlockEntity
    implements dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor,
               net.minecraft.world.Clearable,
               com.simibubi.create.content.equipment.clipboard.ClipboardCloneable,
               com.simibubi.create.api.schematic.requirement.SpecialBlockEntityItemRequirement {  // [V]
  // state (private/protected):
  private   int clientSteeringSignal;
  protected int clientSteeringSignalLeft;
  protected int clientSteeringSignalRight;
  private   int lastServerSteeringSignal, lastServerSteeringSignalLeft, lastServerSteeringSignalRight;
  private   double extension, lastExtension, chasingYaw, lastChasingYaw, lastAngle, angle, angularVelocity;
  private   double touchingFriction;
  private   boolean liftedUp;
  private final org.joml.Vector3d queuedForcePos, queuedForce;
  private final dev.ryanhcode.sable.api.physics.force.ForceTotal forceTotal;

  // public API:
  public static void applyAllBatchedForces(net.minecraft.server.level.ServerLevel, double dt);
  public static double fudgeFriction(double);
  public void sable$physicsTick(dev.ryanhcode.sable.sublevel.ServerSubLevel,
                                dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle, double dt);  // <- the hook
  public float  getLerpedAngle(float partialTick);
  public double getLerpedExtension(float partialTick);
  public net.minecraft.world.item.ItemStack getHeldItem();
  public dev.simulated_team.simulated.multiloader.inventory.SingleSlotContainer getInventory();
  // protected / private (NOT usable from outside):
  protected double getChasingYaw();
  protected double getLerpedYaw(double);
  protected double computeYaw();
  protected int    getSteeringSignal();
  private void applyBatchedForces();
  private double computeMaxExtension(float);
  private org.joml.Vector3dc getRotatedWheelAxis(net.minecraft.core.Vec3i);
  private WheelMountBlockEntity$TerrainCastResult computeMaxExtensionToTerrain(Vector3dc, Pose3dc);
}
```
**How steering is set:** there is **no public setter.** Steering is driven by *redstone signal reads*
into the private `clientSteeringSignal` / `clientSteeringSignalLeft` / `clientSteeringSignalRight` and
`lastServerSteeringSignal*` fields. `getSteeringSignal()`, `computeYaw()`, `getChasingYaw()`,
`getLerpedYaw(double)` are `protected`/`private`. **[V]**

**Proof of the intended extension route (this is a real third-party addon doing it):**
`drivebywire` ships

```
edn.stratodonut.drivebywire.mixin.compat.aeronautics.MixinWheelMountBlockEntity
  @Mixin(targets = ["dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity"])
  @Pseudo
  private int drivebywire$useWireForBrakeSignal(Level, BlockPos, Direction);
  private int drivebywire$useWireForClientBrakeSignal(Level, BlockPos, Direction);
  private int drivebywire$useWireForLeftSteeringSignal(Level, BlockPos, Direction);
  private int drivebywire$useWireForRightSteeringSignal(Level, BlockPos, Direction);
  // all @Redirect(at=@At("INVOKE"))  -> intercepts the redstone lookups
```
**[V — javap -v on the mixin shows the `targets=` value and the `@Redirect` annotations.]**
So: the sanctioned-ish way to control a wheel mount from another mod is
**a `@Pseudo` mixin with `@Redirect` over the redstone-signal calls**, exactly as drivebywire does.

`dev.ryanhcode.offroad.content.components.TireLike` **[V]** is a data-component record describing a wheel:
`TireLike(float radius, Vec3 rotation, Vec3 offset, Optional<ResourceLocation> model)`, registered as
`dev.ryanhcode.offroad.index.OffroadDataComponents.TIRE`. Presets: `SMALL_TIRE, TIRE, LARGE_TIRE,
MONSTROUS_TIRE, CRUSHING_WHEEL, WATER_WHEEL, FLYWHEEL, LARGE_WATER_WHEEL, ROCKCUTTING_WHEEL,
MECHANICAL_ROLLER`. A wheel mount does nothing physics-wise without a tire item inserted.

### 3.2 `tracks` mod (`dev.qwxon.tracks`) — a *real* Sable bogie/track implementation

This is the closest thing in the pack to a "转向架" and it is **the best reference implementation
available offline**. Not in the original target list; found in the same mods directory.

```java
public class dev.qwxon.tracks.content.blocks.sable_track.SableTrackBlockEntity
    extends com.simibubi.create.content.kinetics.base.KineticBlockEntity
    implements dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor,
               net.minecraft.world.Clearable {                                     // [V]
  private final dev.ryanhcode.sable.api.physics.force.ForceTotal forceTotal;
  private final org.joml.Vector3d queuedForcePos, queuedForce;
  private double extension, lateralOffset, longitudinalOffset, heightOffset, angle, angularVelocity;
  private double touchingFriction, springMultiplier, dampingMultiplier, bumpClearanceMultiplier,
                 bumpForceMultiplier, maxImpulseMultiplier, driveMultiplier, gripMultiplier;
  private boolean liftedUp, visualSuspensionHidden;
  private net.minecraft.world.item.DyeColor beltColor;
  private net.minecraft.world.item.ItemStack heldItem;

  public static void applyAllBatchedForces(net.minecraft.server.level.ServerLevel, double dt);
  public void sable$physicsTick(dev.ryanhcode.sable.sublevel.ServerSubLevel,
                                dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle, double dt);  // [V] hook
  public static double fudgeFriction(double);

  // --- programmatically usable tuning API (all public) ---
  public double adjustLateralOffset(int delta);
  public double adjustLongitudinalOffset(int delta);
  public double adjustHeightOffset(int delta);
  public double adjustTuning(java.lang.String key, int delta);
  public double getTuning(java.lang.String key);
  public void   selectScrollTuningMode(java.lang.String key);
  public double getLerpedExtension(float);  getLerpedLateralOffset(float);
  public double getLerpedLongitudinalOffset(float); getLerpedHeightOffset(float);
  public float  getLerpedAngle(float);
  public float  getSharedTrackAngle(net.minecraft.core.Direction, float);
  public float  getSharedTrackSpeed(net.minecraft.core.Direction);
  public boolean hasTrackNeighbor(net.minecraft.core.Direction);
  public double getNeighborExtensionDelta(net.minecraft.core.Direction, float);
  public net.minecraft.world.item.ItemStack getHeldItem(); public void setHeldItem(ItemStack);
  public SableTrackRole baseRole(); public SableTrackPart effectivePart(); public SableTrackRole effectiveRole();
  public net.minecraft.world.item.DyeColor getBeltColor();
  public boolean applyBeltColorToConnectedTrack(DyeColor);
  public void resetTuningToConnectedTrack();
  public boolean isVisualSuspensionHidden(); public void toggleVisualSuspensionHidden();
  public float calculateStressApplied();
}
// registered as a BlockEntitySubLevelActor -> Sable calls sable$physicsTick each physics step.

public enum dev.qwxon.tracks.content.blocks.sable_track.SableTrackRole {      // [V]
  MOUNT, SUSPENSION, DRIVE;   radius(), suspensionTravel(), driveMultiplier(),
                              sideGripMultiplier(), contactSamples(), appliesPhysics()
}
public enum dev.qwxon.tracks.content.blocks.sable_track.SableTrackPart {      // [V]
  NONE, SMALL_SUSPENSION, SUSPENSION, LARGE_SUSPENSION,                         // role = SUSPENSION
  SMALL_DRIVE, DRIVE, LARGE_DRIVE;                                             // role = DRIVE
  role(), appliesPhysics(), radius(), suspensionTravel(), driveMultiplier(),
  sideGripMultiplier(), contactSamples(), visualScale();
  public static SableTrackPart fromStack(net.minecraft.world.item.ItemStack);
}
public class dev.qwxon.tracks.content.items.SuspensionKeyItem extends net.minecraft.world.item.Item { // [V]
  public static SuspensionKeyItem$TuningMode cycleMode(ItemStack, int);
  public static SuspensionKeyItem$TuningMode getMode(ItemStack);
}
public enum SuspensionKeyItem$TuningMode {                                     // [V]
  STRENGTH, SPRING, DAMPING, BUMP_CLEARANCE, BUMP_FORCE, MAX_IMPULSE, DRIVE, GRIP, RESET, POSITION;
  public final String key; public Component title(); public boolean supportsWheel();
}
public class dev.qwxon.tracks.events.TracksCommonEvents {                      // [V]
  public static void physicsTick(dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem, double);
}
public interface dev.qwxon.tracks.mixin_interface.WheelMountOffsetAccess {     // [V]  (mixin duck on offroad)
  double tracks$adjustLateralOffset(int);      double tracks$adjustLongitudinalOffset(int);
  double tracks$adjustHeightOffset(int);       double tracks$adjustTuning(String, int);
  void   tracks$resetTuning();                 double tracks$getTuning(String);
  double tracks$getLerpedLateralOffset(float); double tracks$getLerpedLongitudinalOffset(float);
  double tracks$getLerpedHeightOffset(float);  double tracks$getLerpedYaw(float);
  boolean tracks$isVisualSuspensionHidden();   void tracks$toggleVisualSuspensionHidden();
  int    tracks$getClientSteeringSignalLeft(); int tracks$getClientSteeringSignalRight();
}
```
`tracks` registers its physics hook exactly like this (verified bytecode of `dev.qwxon.tracks.Tracks.init()`):
```
getstatic  dev/ryanhcode/sable/platform/SableEventPlatform.INSTANCE
invokeinterface  SableEventPlatform.onPhysicsTick:(Ldev/ryanhcode/sable/api/event/SablePrePhysicsTickEvent;)V
```
It also registers its blocks/items through `dev.simulated_team.simulated.registrate.SimulatedRegistrate`
**[V]** — i.e. the third-party addon convention is to reuse *simulated*'s registrate, not to roll your own.

### 3.3 Simurail `PhysicsBogey*` — Create-train bogie welded onto Sable physics

`Create Simurail` (`com.crystaelix.simurail`, "An experimental train mod", author TheLMiffy1111)
requires `sable [2,)` and `simulated [1,)`. Its whole point is a Sable-physics **rail bogie**.

```java
public class com.crystaelix.simurail.content.bogey.PhysicsBogeyBlock
    extends com.simibubi.create.content.kinetics.base.HorizontalKineticBlock
    implements com.simibubi.create.foundation.block.IBE<PhysicsBogeyBlockEntity>,
               dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener,
               com.simibubi.create.foundation.block.ProperWaterloggedBlock {   // [V]
  public static final BooleanProperty INVERTED;
  public static final VoxelShape SHAPE, INVERTED_SHAPE;
  public void afterMove(net.minecraft.server.level.ServerLevel, ServerLevel, BlockState, BlockPos, BlockPos);
  public net.minecraft.world.InteractionResult onSneakWrenched(BlockState, UseOnContext);
}

public class com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity
    extends com.simibubi.create.content.kinetics.base.KineticBlockEntity
    implements net.minecraft.world.Nameable, net.minecraft.world.MenuProvider,
               dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor,
               com.crystaelix.simurail.content.steering_connector.SteeringConnectable {   // [V]
  public static final double LINEAR_Y_LIMIT, LINEAR_Z_LIMIT,
                             ANGULAR_X_LIMIT, ANGULAR_Y_LIMIT, ANGULAR_Z_LIMIT;
  protected final PhysicsBogeyOptions options;
  protected final PhysicsBogeyControlOverrides computerOverrides;   // <- programmatic override bag
  protected com.crystaelix.simurail.api.physics.AttachableBoxPhysicsObject pivot;
  protected final dev.ryanhcode.sable.companion.math.Pose3d pivotPose;
  protected dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle pivotJoint;
  protected final PhysicsBogeyAxle axleFront, axleBack;

  public com.crystaelix.simurail.content.bogey.PhysicsBogeyOptions getOptions();
  public void setOptions(com.crystaelix.simurail.content.bogey.PhysicsBogeyOptions);
  public net.minecraft.core.Direction getFacing();
  public boolean isInverted();
  public org.joml.Vector3dc getDirection();  public org.joml.Vector3dc getLateral();
  public org.joml.Quaterniondc getJointOrientation();
  public PhysicsBogeyAxle getAxle(boolean front);
  public PhysicsBogeyBlockEntity getConnected(boolean front);
  public boolean getConnectedToFront(boolean front);
  public void init();
  public void sable$physicsTick(dev.ryanhcode.sable.sublevel.ServerSubLevel,
                                dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle, double dt);  // [V] hook
  public double getControlStrength();
  public double getBrakeStrength();
  public double getSteerValue();                     // <-- READ-ONLY steer readout
  public boolean hasTrack();  public boolean isDerailed();
  public double getLateralCurvature();  public double getVerticalCurvature();
  public float getWheelAngle(float partialTick);
  public boolean canConnectSteeringTo(SteeringConnectable);
  public double connectionRange(SteeringConnectable);
  public void connectSteering(boolean front, SteeringConnectable other, boolean otherFront);
  public void disconnectSteering(boolean front);
  public void afterMove();
  public com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour<?> ...
  public static void registerCapabilities(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent);
}

public class com.crystaelix.simurail.content.bogey.PhysicsBogeyControlOverrides {   // [V]  ALL PUBLIC FIELDS
  public boolean overrideBrakeStrength;  public double brakeStrength;
  public boolean overrideSteerValue;     public double steerValue;      // <-- programmatic steer target
  public boolean overrideStressMultiplier; public double stressMultiplier;
  public boolean hasOverrides();  public void reset();
}
// NOTE: the instance lives in `protected final PhysicsBogeyControlOverrides computerOverrides;`
//       -> reachable only from a subclass, an access transformer, or a mixin/accessor.  [V]

public class com.crystaelix.simurail.content.bogey.PhysicsBogeyOptions {      // [V]  ALL PUBLIC FIELDS
  public BogeyRenderedType type;  public boolean enabled;
  public boolean allowYawOffset, allowPitchOffset, allowVerticalOffset, allowLateralOffset,
                 allowVerticalMovement, renderFrontConnector, renderBackConnector;
  public PhysicsBogeyControlMode controlMode;  public float stress;
  public static final StreamCodec<ByteBuf, PhysicsBogeyOptions> STREAM_CODEC;
  public PhysicsBogeyOptions set(PhysicsBogeyOptions); setNonComputer(...); setStress(float);
  public int getAngularType()/setAngularType(int); getLinearType()/setLinearType(int);
  public int getConnectorType()/setConnectorType(int);
  public short getFlags()/setFlags(short);
  public CompoundTag write(); public PhysicsBogeyOptions read(CompoundTag);
  public void encode(ByteBuf); public PhysicsBogeyOptions decode(ByteBuf);
}
public enum com.crystaelix.simurail.content.bogey.PhysicsBogeyControlMode {    // [V]
  BRAKING, BRAKING_INVERTED, STRENGTH, STRENGTH_INVERTED;
  public static final IntFunction<PhysicsBogeyControlMode> BY_ID;
  public static final StreamCodec<ByteBuf, PhysicsBogeyControlMode> STREAM_CODEC;
}
public class com.crystaelix.simurail.api.physics.AttachableBoxPhysicsObject
    extends dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject {      // [V]
  public AttachableBoxPhysicsObject(dev.ryanhcode.sable.sublevel.ServerSubLevel parent,
                                    Pose3dc pose, org.joml.Vector3dc halfExtents, double mass);
  public void getBoundingBox(dev.ryanhcode.sable.companion.math.BoundingBox3d);
  public dev.ryanhcode.sable.api.physics.mass.MassData getMassTracker();
  public void wakeUp();
}
public class com.crystaelix.simurail.api.physics.BoxMassData implements MassData { /* box inertia */ }  // [V]
public class com.crystaelix.simurail.api.physics.SimurailJoints {              // [V]
  public static final java.util.Set<ConstraintJointAxis> LOCKED_PIVOT_JOINT_AXES,
      LOCKED_RAIL_JOINT_AXES, LOCKED_COUPLER_JOINT_AXES;
  public static GenericConstraintConfiguration pivotJoint(Vector3dc, Vector3dc, Quaterniondc, Quaterniondc);
  public static GenericConstraintConfiguration railJoint (Vector3dc, Vector3dc, Quaterniondc, Quaterniondc);
  public static GenericConstraintConfiguration couplerJoint(Vector3dc, Vector3dc, Quaterniondc, Quaterniondc);
}
public class com.crystaelix.simurail.content.bogey.PhysicsBogeyAxle {          // [V]  (protected members)
  protected final PhysicsBogeyBlockEntity bogey;  protected final boolean logicalFront;
  protected com.simibubi.create.content.trains.graph.TrackGraph trackGraph;
  protected com.simibubi.create.content.trains.entity.TravellingPoint trackPoint, probe;
  protected dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle joint;
  protected dev.ryanhcode.sable.api.physics.constraint.FixedConstraintHandle axleBoxJoint;
  protected AttachableBoxPhysicsObject axleBox;
  protected double kLateral, kVertical, speed, visualSpeed;
  protected void updateForces(ServerSubLevel, RigidBodyHandle, double);
  protected void updateTrackForces(ServerSubLevel, RigidBodyHandle, double);
  protected void updateWorldForces(ServerSubLevel, RigidBodyHandle, double);
  protected com.simibubi.create.content.trains.entity.TravellingPoint$ITrackSelector steer(TravellingPoint);
  protected com.simibubi.create.content.trains.entity.TravellingPoint$ITrackSelector follow(TravellingPoint, BooleanConsumer);
  protected com.simibubi.create.content.trains.entity.TravellingPoint$ITrackSelector followOrSteer(TravellingPoint);
  public boolean hasTrack();
}
```
**Programmatic steering for simurail (verified options):**
- `PhysicsBogeyBlockEntity.computerOverrides` (public fields `overrideSteerValue` / `steerValue`) — but the
  field is `protected`.
- **ComputerCraft route (fully public):**
  ```java
  public class com.crystaelix.simurail.compat.computercraft.peripheral.PhysicsBogeyPeripheral
      extends com.simibubi.create.compat.computercraft.implementation.peripherals.SyncedPeripheral<PhysicsBogeyBlockEntity> { // [V]
    boolean isPhysicsEnabled(); void setPhysicsEnabled(boolean);
    boolean allowsYawOffset(); void setAllowYawOffset(boolean);
    boolean allowsPitchOffset(); void setAllowPitchOffset(boolean);
    boolean allowsVerticalOffset(); void setAllowVerticalOffset(boolean);
    boolean allowsLateralOffset(); void setAllowLateralOffset(boolean);
    boolean allowsVerticalMovement(); void setAllowVerticalMovement(boolean);
    float getMaxStress(); void setMaxStress(float);
    int getControlMode(); void setControlMode(int);
    boolean hasTrack(); boolean isDerailed();
    double getLateralCurvature(); double getVerticalCurvature();
  }
  ```
  registered via `PhysicsBogeyBlockEntity.registerCapabilities(RegisterCapabilitiesEvent)`.
- **Network route:** `PhysicsBogeySetOptionsPacket(BlockPos pos, PhysicsBogeyOptions options)` (record,
  `CustomPacketPayload`) — send a fully-built `PhysicsBogeyOptions` to the server. **[V]**
- **Item/interaction route:** `SteeringConnectorItem` + `SteeringConnectable`:
  ```java
  public interface com.crystaelix.simurail.content.steering_connector.SteeringConnectable {  // [V]
    BlockPos getBlockPos(); Direction getFacing(); net.minecraft.world.phys.AABB getOutline(Direction);
    boolean canConnectSteeringTo(SteeringConnectable);
    double connectionRange(SteeringConnectable);
    void connectSteering(boolean front, SteeringConnectable other, boolean otherFront);
    void disconnectSteering(boolean front);
  }
  ```
  → **`connectSteering(boolean, SteeringConnectable, boolean)` is the only real public "steering" setter
  in the whole pack**, and it links two bogies rather than setting an angle numerically.

### 3.4 Create's own bogie API (for real Create trains, used by `create_train_parts`… actually NOT)

```java
com.simibubi.create.content.trains.bogey.AbstractBogeyBlock
com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity
com.simibubi.create.content.trains.bogey.StandardBogeyBlock
com.simibubi.create.content.trains.bogey.StandardBogeyBlockEntity
com.simibubi.create.content.trains.bogey.BogeySizes / BogeySizes$BogeySize
com.simibubi.create.content.trains.bogey.BogeyStyle / BogeyRenderer / BogeyVisual / BogeyVisualizer
com.simibubi.create.content.trains.entity.Carriage / CarriageBogey / CarriageContraption /
    CarriageContraptionEntity / Train / Navigation / TravellingPoint / TrainStatus
com.simibubi.create.content.trains.graph.TrackGraph / TrackNodeLocation / TrackGraphLocation
com.simibubi.create.content.trains.track.TrackBlock / TrackBlockEntity / ITrackBlock /
    TrackMaterial / TrackMaterial$TrackType / BezierConnection / TrackShape
```
**[V — all present in `[机械动力] create-1.21.1-6.0.10.jar`.]** javap signatures dumped to
`_recon/raw/create-bogey.txt`.

**IMPORTANT NEGATIVE RESULT:** `create_train_parts-0.4.0` has **no bogie, wheel, steering, sable or
physics classes at all**, and its constant pools contain **zero** references to
`dev/ryanhcode/sable`, `dev/eriksonn/aeronautics`, or `dev/simulated_team/simulated` **[V, full-jar scan]**.
It is purely Create decorative train content:
`content/trains/crossing/{CrossingBlock, ArmExtenderBlock, CrossingContraption, CrossingRenderer, ...}`,
`content/decoration/**` (52 classes), `content/contraptions/behaviour/{Slide,Step,Window}MovingInteraction`.
It has no relevance to a physics bogie.

---

## 4. Cockpit / helm / control desk / 驾驶台

**There is no block named `cockpit`, `helm`, `control desk`, `pilot`, or `joystick` in Aeronautics,
Simulated, Offroad or Sable.** A full keyword grep of all class lists for
`cockpit|helm|throttle|joystick|controller|control|pilot|input|steer|wheel|bogie|seat|lever` returned
only the classes below. **[V]**

### 4.1 The closest thing to a cockpit: Simulated's control blocks

| role | fqcn | key API | [V] |
|---|---|---|---|
| **Steering wheel** | `dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlock` / `SteeringWheelBlockEntity` / `SteeringWheelGenerator` / `SteeringWheelHandler` / `SteeringWheelRenderer` / `SteeringWheelVisual` | see below | V |
| **Throttle lever** | `dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlock` / `ThrottleLeverBlockEntity` / `ThrottleLeverHandler` / `ThrottleLeverRenderer` / `ThrottleLeverVisual` | see below | V |
| **Swivel bearing (rotary joint)** | `dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock` / `SwivelBearingBlockEntity` | see below | V |
| Assembly / disassembly lever | `dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlock` / `PhysicsAssemblerBlockEntity` / `PhysicsAssemblerGUIHandler` | `assembleOrDisassemble()`, `jerkLever()`, `updateControlledByPlayer(float)`, `clientFlickLeverTo(boolean)`, `setClientHoldLeverInPlace(boolean)`, `stopControllingPlayer()`, `isPrimaryAssembler()` | V |
| Grab handle | `dev.simulated_team.simulated.content.blocks.handle.HandleBlock` / `HandleBlockEntity` / `HandleBlockEntity$HandleConstraint` | `HandleConstraint` | V |
| Docking | `...docking_connector.DockingConnectorBlockEntity` / `PairedDockingConnectorBlock` / `DockingConnectorPair` | — | V |
| Sensors | `...velocity_sensor.VelocitySensorBlockEntity`, `...gimbal_sensor.GimbalSensorBlockEntity`, `...altitude_sensor.AltitudeSensorBlockEntity` | — | V |
| Redstone I/O | `...redstone.linked_typewriter.**` (21 classes), `...redstone.directional_receiver.**`, `...redstone.modulating_receiver.**`, `...redstone.redstone_accumulator.**`, `...redstone.redstone_inductor.**`, `...redstone_magnet.RedstoneMagnetBlockEntity` | — | V |

**SteeringWheelBlockEntity — verified signature (this is the input device):**
```java
public class dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlockEntity
    extends com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity {   // [V]
  public static final int RPM;
  public boolean held;
  private int inUse;
  public com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour angleInput;
  public float targetAngle;          // <-- public, writable
  public float targetAngleToUpdate;  // <-- public, writable
  private float angle, clientAngle, oldClientAngle;
  private double sequencedAngleLimit;
  float generatedSpeed, logicalSpeed;
  public net.minecraft.world.level.block.state.BlockState material;

  public void addBehaviours(List<BlockEntityBehaviour>);
  public static net.minecraft.network.chat.MutableComponent holdTipGetter(Player, BlockPos, BlockState);
  public void startHolding();  public void stopHolding();
  public float directionConvert(float);
  public void updateTargetAngle(float targetAngle);   // <-- the intended entry point  [V]
  public void tick();
  public float getAngle();  public float getAngularSpeed();
  public float getLogicalSpeed();  public float getGeneratedSpeed();
  public float getRenderAngle(float);  public float getInteractionAngle(float);
  public boolean isMaterialValid(ItemStack);  public ItemInteractionResult applyMaterialIfValid(ItemStack);
}
// Client -> server packet:
public final record dev.simulated_team.simulated.network.packets.SteeringWheelPacket(
        boolean shouldStop, float targetAngle, net.minecraft.core.BlockPos pos)
        implements CustomPacketPayload {                                // [V]
  public static CustomPacketPayload$Type<SteeringWheelPacket> TYPE;
  public static StreamCodec<RegistryFriendlyByteBuf, SteeringWheelPacket> CODEC;
  public void handle(foundry.veil.api.network.handler.ServerPacketContext);
}
// Client-side handler (held interaction):
public class ...steering_wheel.SteeringWheelHandler
    extends dev.simulated_team.simulated.util.hold_interaction.BlockHoldInteraction {  // [V]
  public void startHold(Level, Player, BlockPos);
  public void setTargetAngle(float);
  public boolean activeOnMouseMove(double, double);
  public boolean activeTick(Level, LocalPlayer);
  public static float wrapDegrees(float);
}
```
**How inputs reach physics: they do NOT, directly.** **[V, from the class hierarchy]**
`SteeringWheelBlockEntity extends GeneratingKineticBlockEntity` — it *generates Create kinetic rotation*.
Its `output` shaft speed is consumed by whatever it is geared to (`AngleInput` scroll behaviour sets the
target angle; `targetAngle` is a real writable public field). It never touches `RigidBodyHandle`.
The bridge from Create rotation → Sable physics is the **propeller bearing (aeronautics)** or the
**wheel mount / track drive wheel (offroad / tracks)**, which read kinetic speed and convert it into
forces inside `sable$physicsTick`.

**ThrottleLeverBlockEntity — verified signature:**
```java
public class dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity
    extends com.simibubi.create.foundation.blockEntity.SmartBlockEntity
    implements com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation {   // [V]
  protected int state;  protected int lastChange;
  protected net.createmod.catnip.animation.LerpedFloat clientAngle;
  public final net.createmod.catnip.animation.LerpedFloat clientPressedLerp;
  public void write(CompoundTag, HolderLookup$Provider, boolean); protected void read(...);
  public void tick();  public void initialize();
  public void changeState(boolean);
  public int getState();
  public void setSignal(int);        // <-- programmatic throttle set   [V]
  public boolean addToGoggleTooltip(List<Component>, boolean);
}
// The block itself is a redstone source: ThrottleLeverBlock implements
// com.simulated_team.simulated.multiloader.CommonRedstoneBlock and overrides
// getSignal / getDirectSignal / isSignalSource  -> outputs 0..15 analog redstone.  [V]
// Client -> server:
public final record ...network.packets.ThrottleLeverSignalPacket(BlockPos pos, int signal)
    implements CustomPacketPayload { ... }                                        // [V]
```
So the throttle is a **redstone emitter**, and steering/throttle ultimately reach physics through
redstone-driven blocks (wheel mount redstone reads, propeller bearing speed, track drive wheels).

**SwivelBearingBlockEntity — the richest physics-control block:**
```java
public class dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity
    extends com.simibubi.create.content.kinetics.base.KineticBlockEntity
    implements dev.simulated_team.simulated.util.extra_kinetics.ExtraKinetics,
               com.simibubi.create.content.contraptions.IDisplayAssemblyExceptions,
               dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor {           // [V]
  private java.util.UUID subLevelID;
  private net.minecraft.core.BlockPos swivelPlatePos;
  private dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintHandle handle;   // <-- REAL physics joint
  private double lastTargetAngleDegrees, targetAngleDegrees, sequencedAngleLimit;
  private boolean assembling;

  public void assemble();  public void disassemble();
  public boolean isAssembled();
  public void reattachConstraint(dev.ryanhcode.sable.sublevel.ServerSubLevel, boolean);
  public void updateServoCoefficients();
  public void associatePlateWithParent();
  public double getTargetAngleDegrees();
  public net.minecraft.core.BlockPos getPlatePos();  public void setPlatePos(BlockPos);
  public java.util.UUID getSubLevelID();             public void setSubLevelID(UUID);
  public void beforeAssembly();  public void invalidate();  public void remove();
  public float propagateRotationTo(KineticBlockEntity, BlockState, BlockState, BlockPos, boolean, boolean);
  public java.util.List<BlockPos> addPropagationLocations(IRotate, BlockState, List<BlockPos>);
  public java.lang.Iterable<dev.ryanhcode.sable.sublevel.SubLevel> sable$getConnectionDependencies();
}
```
This is the block that demonstrates how a Create kinetic block creates and drives a Sable rotary
constraint (`RotaryConstraintHandle` + `ServerSubLevel` + `setSubLevelID`).

### 4.2 Aeronautics content blocks (no cockpit)

```
dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlock/BlockEntity
dev.eriksonn.aeronautics.content.blocks.propeller.bearing.gyroscopic_propeller_bearing.GyroscopicPropellerBearingBlock/BlockEntity
dev.eriksonn.aeronautics.content.blocks.propeller.small.{BasePropellerBlock*, wooden, andesite, smart_propeller}
dev.eriksonn.aeronautics.content.blocks.hot_air.{balloon(15), hot_air_burner, lifting_gas, steam_vent, gust, envelope}
dev.eriksonn.aeronautics.content.blocks.levitite.*
dev.eriksonn.aeronautics.content.blocks.mounted_potato_cannon.MountedPotatoCannonBlock/BlockEntity
dev.eriksonn.aeronautics.content.blocks.propeller.behaviour.PropellerActorBehaviour
```
**[V]**

```java
public class ...propeller_bearing.PropellerBearingBlockEntity
    extends com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity
    implements MechanicalBearingTileEntityExtension,
               dev.ryanhcode.sable.api.block.propeller.BlockEntitySubLevelPropellerActor,
               com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation,
               dev.ryanhcode.sable.api.block.propeller.BlockEntityPropeller {     // [V]
  public final org.joml.Vector3d thrustDirection, facingDirection;
  public float totalSailPower;  public boolean disassemblySlowdown;  public float prevAngle;
  public dev.simulated_team.simulated.api.BearingSlowdownController slowdownController;
  protected PropellerActorBehaviour behavior;
  protected java.util.List<net.minecraft.core.BlockPos> sailPositions;

  public float getThrust();  public double getThrust();  public double getAirflow();
  public boolean isActive();  public net.minecraft.core.Direction getBlockDirection();
  public float getRotationSpeed();  public void setRotationSpeed(float);          // <-- writable speed [V]
  public float getClampedRotationRate();  public float getDirectionIndependentSpeed();
  public void setAssembleNextTick(boolean);  public void assemble();  public void disassemble();
  public void startDisassemblySlowdown();  public void contraptionInitialize();
  public void onDirectionChanged();  public void findSails();  public float getSailPower(StructureBlockInfo);
  public com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour<
      PropellerBearingBlockEntity$ThrustDirection> getThrustDirectionOption();
  public dev.ryanhcode.sable.api.block.propeller.BlockEntityPropeller getPropeller();
}

public class ...gyroscopic_propeller_bearing.GyroscopicPropellerBearingBlockEntity
    extends PropellerBearingBlockEntity {                                        // [V]
  public void sable$physicsTick(dev.ryanhcode.sable.sublevel.ServerSubLevel,
                                dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle, double);   // [V]
  public void setTilt(org.joml.Vector3d axis, org.joml.Vector3d target, double speed);   // [V] writable tilt
  public void setStrictTilt(org.joml.Vector3d target, double a, double b);               // [V]
  public void applyTilt();  public void forceTilt(BlockState);  public void updateSignal();
}

public class ...propeller.behaviour.PropellerActorBehaviour
    extends com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour {   // [V]
  public static final BehaviourType<PropellerActorBehaviour> TYPE;
  public void setThrustDirection(org.joml.Vector3dc);                            // [V] writable thrust dir
  public void addSimpleLayer(double, double);  public void addPropellerLayer(PropellerLayer);
  public double getParticleRange();  public int getParticleCount();
}
```

**Verdict for #4:** the "驾驶台" role is filled by a *set* of Simulated blocks
(`steering_wheel` + `throttle_lever` + `physics_assembler` + `swivel_bearing`), driven by
Create kinetic rotation and analog redstone, **not** by a single cockpit block and **not** by a direct
"write input into the physics body" API. The only physics-input surfaces that are truly public are
`SwivelBearingBlockEntity.assemble()/disassemble()/setSubLevelID(UUID)`,
`GyroscopicPropellerBearingBlockEntity.setTilt(...)/setStrictTilt(...)`,
`PropellerActorBehaviour.setThrustDirection(Vector3dc)`, `PropellerBearingBlockEntity.setRotationSpeed(float)`,
and `SteeringWheelBlockEntity.updateTargetAngle(float)` / public `targetAngle`.

---

## 5. Public / stable addon API surface

### 5.1 There *is* a dedicated API namespace

`dev.ryanhcode.sable.api.**` — 79 classes **[V]**. Full list in `_recon/lists/classes-sable.txt` lines 1–81.
Other `*.api.*` packages:
- `dev.simulated_team.simulated.api.**`: `BearingSlowdownController(+$ContraptionSymmetry)`,
  `ConditionalDisplayTarget`, `CustomStressImpactTooltipProvider`, `IDirectionalAnalogOutput`,
  `SimpleResourceManager(+$Registry)`, `dev.simulated_team.simulated.api.sound.**` (8) **[V]**
- `dev.eriksonn.aeronautics.api.levitite_blend_crystallization.**` (7) **[V]**
- `dev.rew1nd.sableschematicapi.api.blueprint.**` (58 + `survival` sub-package) **[V]** — this is a
  *third-party* API mod layering on top of Sable.
- `com.crystaelix.simurail.api.**` (bogey, coupler, extension, math, network, physics, track, util) **[V]**

Also, **platform SPIs** (`dev.ryanhcode.sable.platform.**`) are the intended injection points:
`SableEventPlatform`, `SableAssemblyPlatform`, `SableChunkEventPlatform`, `SableEventPublishPlatform`,
`SableLoaderPlatform`, `SablePlatform`, `SableSubLevelRenderPlatform` — each with a
`public static final X INSTANCE` and a `*Impl` in `dev.ryanhcode.sable.neoforge.platform.**`. **[V]**
These are `ServiceLoader`-backed (`PhysicsPipelineProvider` and `SableEventPlatform` both show
`lambda$static$0(java.util.ServiceLoader$Provider)` in javap). **[V]**

### 5.2 Obfuscation

**Nothing is obfuscated.** Every class, field and method keeps its source name; all the internal
mixin targets (`@Mixin(targets = ["dev.ryanhcode.offroad...WheelMountBlockEntity"])`) resolve by
literal name, and third-party mods compile against them. **[V]**

### 5.3 Stability annotations — mixed, and notable

`org.jetbrains.annotations.ApiStatus` (including `ApiStatus$Internal`) appears in the constant pools of
**55 Sable classes**; `org.jetbrains.annotations.Nullable` in 178. **[V]**

Confirmed **class-level `@ApiStatus.Internal`** (javap class attribute block):
```
dev.ryanhcode.sable.api.sublevel.SubLevelTicketLoadingSystem
dev.ryanhcode.sable.api.sublevel.ticket.SubLevelTicketInfo
dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer
dev.ryanhcode.sable.api.SubLevelHelper
dev.ryanhcode.sable.api.schematic.SubLevelSchematicSerializationContext
dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback
```
**[V]** — i.e. **being in `.api.` does not mean "stable"**. `SubLevelHelper` in particular is documented
as internal by its own annotation.

`@ApiStatus.Internal` is also applied at **member level** inside
`RigidBodyHandle`, `SubLevelContainer`, `ServerSubLevelContainer`, `ForceTotal`, `SubLevelAssemblyHelper`,
`SubLevelTicketLoadingSystem`. **[V — rendered annotation counts per class: RigidBodyHandle 1,
SubLevelContainer 2, ServerSubLevelContainer 5, ForceTotal 1, SubLevelAssemblyHelper 1, SubLevelHelper 1,
SubLevelSchematicSerializationContext 4, ClientSubLevelContainer 2, SubLevelTicketInfo 1,
BlockSubLevelCollisionCallback 1.]**

`RigidBodyHandle` specifically has **no class-level annotation at all** (`attributes: 2` =
`SourceFile` + `InnerClasses` only — verified from the attribute block after the closing `}`), but its
constant pool contains both `Lorg/jetbrains/annotations/ApiStatus$Internal;` and
`Ljava/lang/Deprecated;` alongside `RuntimeVisibleAnnotations` / `RuntimeInvisibleAnnotations`
attribute names → **one or more of its 18 methods is `@Deprecated` and/or `@ApiStatus.Internal`** **[V]**.

**Not fully determined:** the exact member(s) carrying `@ApiStatus.Internal` / `@Deprecated` inside
`RigidBodyHandle`, `ForceTotal`, `SubLevelContainer`, `ServerSubLevelContainer` (javap's
nested-annotation rendering made a per-member split unreliable). Treat the whole physics-handle layer as
"works, is used by 4 addons, but is not contractually public".

**No `@ApiStatus.Internal` and no class annotations at all on:**
`dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor` (only `@Nullable` members),
`dev.ryanhcode.sable.api.physics.PhysicsPipeline`, `PhysicsPipelineBody`, `SubLevelObserver`,
`BlockSubLevelAssemblyListener`, `dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem`,
`dev.ryanhcode.sable.sublevel.SubLevel`, `ServerSubLevel`. **[V]**
Those six-or-so are the safest bets.

`dev.ryanhcode.sable.api.event.SablePrePhysicsTickEvent` is `@java.lang.FunctionalInterface` with no
internal marking **[V]** → the most stable single hook.

### 5.4 What the four third-party addons actually reference (strongest evidence of the real API)

Full constant-pool cross-reference scan (raw byte scan of every `.class`, matching both
`CONSTANT_Class` internal names and `L…;` descriptors) — results in `_recon/refs/*.refs.tsv`.
Distinct `dev.ryanhcode.sable.**` classes referenced from **outside** sable:

```
dev.ryanhcode.sable.ActiveSableCompanion                      <= aeronautics, offroad, drivebywire, sableschem, simurail
dev.ryanhcode.sable.Sable                                     <= aeronautics, offroad, drivebywire, sableschem, simurail
dev.ryanhcode.sable.api.SubLevelAssemblyHelper                <= aeronautics, drivebywire, sableschem
dev.ryanhcode.sable.api.SubLevelAssemblyHelper$AssemblyTransform <= aeronautics, drivebywire
dev.ryanhcode.sable.api.SubLevelHelper                        <= sableschem
dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor        <= aeronautics, offroad, simurail
dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener   <= simurail
dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape     <= simurail
dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider(+$LiftProviderContext) <= aeronautics
dev.ryanhcode.sable.api.block.propeller.BlockEntityPropeller  <= aeronautics
dev.ryanhcode.sable.api.block.propeller.BlockEntitySubLevelPropellerActor <= aeronautics
dev.ryanhcode.sable.api.event.SablePrePhysicsTickEvent        <= aeronautics, offroad
dev.ryanhcode.sable.api.event.SableSubLevelContainerReadyEvent<= aeronautics, sableschem
dev.ryanhcode.sable.api.math.LevelReusedVectors               <= aeronautics
dev.ryanhcode.sable.api.math.OrientedBoundingBox3d            <= aeronautics, offroad
dev.ryanhcode.sable.api.particle.ParticleSubLevelKickable     <= aeronautics
dev.ryanhcode.sable.api.physics.PhysicsPipeline               <= sableschem, simurail
dev.ryanhcode.sable.api.physics.PhysicsPipelineBody           <= sableschem, simurail
dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis<= simurail
dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration/Handle <= sableschem, simurail
dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration/Handle <= simurail
dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintConfiguration/Handle  <= sableschem, simurail
dev.ryanhcode.sable.api.physics.force.ForceGroup / ForceGroups<= aeronautics
dev.ryanhcode.sable.api.physics.force.ForceTotal              <= aeronautics, offroad, simurail
dev.ryanhcode.sable.api.physics.force.QueuedForceGroup        <= aeronautics
dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle        <= aeronautics, offroad, simurail
dev.ryanhcode.sable.api.physics.mass.MassData                 <= offroad, sableschem, simurail
dev.ryanhcode.sable.api.physics.mass.MassTracker              <= sableschem
dev.ryanhcode.sable.api.physics.object.ArbitraryPhysicsObject <= simurail
dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject   <= simurail
dev.ryanhcode.sable.api.schematic.SubLevelSchematicSerializationContext(+$Type,$SchematicMapping) <= drivebywire, simurail
dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer      <= sableschem
dev.ryanhcode.sable.api.sublevel.KinematicContraption         <= aeronautics, offroad, sableschem
dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer      <= aeronautics, sableschem, simurail
dev.ryanhcode.sable.api.sublevel.SubLevelContainer            <= aeronautics, drivebywire, sableschem, simurail
dev.ryanhcode.sable.api.sublevel.SubLevelObserver             <= aeronautics, sableschem
dev.ryanhcode.sable.companion.math.BoundingBox3d/3dc/3i/3ic   <= aeronautics, offroad, drivebywire, sableschem, simurail
dev.ryanhcode.sable.companion.math.JOMLConversion             <= aeronautics, offroad, simurail
dev.ryanhcode.sable.companion.math.Pose3d / Pose3dc           <= aeronautics, offroad, drivebywire, sableschem, simurail
dev.ryanhcode.sable.index.SableTags                           <= aeronautics
dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension <= offroad, simurail
dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension / LevelExtension <= aeronautics
dev.ryanhcode.sable.mixinterface.particle.ParticleExtension   <= aeronautics
dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper <= aeronautics, offroad, simurail
dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData <= aeronautics
dev.ryanhcode.sable.physics.floating_block.FloatingBlockMaterial <= aeronautics
dev.ryanhcode.sable.platform.SableEventPlatform               <= aeronautics, offroad, sableschem
dev.ryanhcode.sable.render.region.SimpleCulledRenderRegionBuilder(+$Cube) <= aeronautics
dev.ryanhcode.sable.SableCommonEvents                         <= aeronautics
dev.ryanhcode.sable.sublevel.ClientSubLevel                   <= aeronautics, sableschem, simurail
dev.ryanhcode.sable.sublevel.ServerSubLevel                   <= aeronautics, offroad, sableschem, simurail
dev.ryanhcode.sable.sublevel.SubLevel                         <= aeronautics, offroad, drivebywire, sableschem, simurail
dev.ryanhcode.sable.sublevel.plot.LevelPlot / ServerLevelPlot <= aeronautics, drivebywire, sableschem, simurail
dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem     <= aeronautics, offroad, sableschem, simurail
dev.ryanhcode.sable.util.LevelAccelerator                     <= aeronautics, offroad, sableschem
dev.ryanhcode.sable.util.SableBufferUtils                     <= aeronautics, simurail
dev.ryanhcode.sable.util.SableNBTUtils                        <= aeronautics, sableschem, simurail
```
**[V — from `_recon/refs/*.refs.tsv`.]**

Note: `tracks` also references (**[V]**):
`RigidBodyHandle`, `ForceTotal`, `MassData`, `Pose3d/3dc`, `JOMLConversion`,
`BlockEntitySubLevelActor`, `OrientedBoundingBox3d`, `SablePrePhysicsTickEvent`, `SableEventPlatform`,
`SubLevelPhysicsSystem`, `ServerSubLevel`, `SubLevel`, `Sable`, `ActiveSableCompanion`,
`dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension`,
`dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper`,
and **`dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity`** (via mixins).

### 5.5 drivebywire integration summary (item 6a)

* modId `drivebywire`, v `0.2.9`, "Drive By Wire" / zh "线缆控制", author StratoDonut,
  license All Rights Reserved, description "Controller hub migration work for Create and Sable on NeoForge 1.21.1."
* **What it is:** redstone-over-cable networks that only function on Sable sub-levels, plus
  "Controller Hub" blocks that bind a Create Linked Controller (or Tweaked Controller) so you can drive
  a ship remotely. Blocks: `backup_block` (Network Backup Block), `controller_hub`, `tweaked_controller_hub`.
  Items: `wire` (Cable), `wire_cutter` (Cable Cutter).
* **Mixin targets [V]:**
  ```
  edn.stratodonut.drivebywire.mixin.MixinServerLevel
  ...mixin.compat.aeronautics.MixinWheelMountBlockEntity  -> dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity (@Pseudo)
  ...mixin.compat.tracks.MixinSableTrackBlockEntity       -> dev.qwxon.tracks.content.blocks.sable_track.SableTrackBlockEntity (@Pseudo)
  ...mixin.compat.MixinSubLevelAssemblyHelper             -> dev.ryanhcode.sable.api.SubLevelAssemblyHelper
  ...mixin.compat.MixinLecternControllerBlock             -> com.simibubi.create.content.redstone.link.controller.LecternControllerBlock
  ...mixin.compat.MixinLecternControllerBlockEntity       -> ...LecternControllerBlockEntity
  ...mixin.compat.MixinLinkedControllerInputPacket        -> ...LinkedControllerInputPacket
  ...mixin.compat.MixinLinkedControllerStopLecternPacket  -> ...LinkedControllerStopLecternPacket
  ...mixin.compat.tweaked.* (7 more, create_tweaked_controllers compat)
  ...mixin.client.MixinLinkedControllerClientHandlerAccessor
  ...mixin.client.MixinTweakedLinkedControllerClientHandlerAccessor
  ...mixin.client.MixinSchematicAndQuillHandler           -> uses Sable.HELPER, SubLevel, Pose3d
  ```
* **Duck/accessor interfaces it defines:** `edn.stratodonut.drivebywire.mixinducks.LecternControllerHubDuck`,
  `.TweakedControllerDuck` **[V]**.
* **Sable usage:** `SableEventPlatform` is *not* used; the only Sable touchpoints are
  `Sable.HELPER` / `ActiveSableCompanion`, `SubLevelContainer`, `SubLevel`, `LevelPlot`, `BoundingBox3i`,
  `SubLevelSchematicSerializationContext` (+`Type`,`SchematicMapping`), and `SubLevelAssemblyHelper`
  (+`AssemblyTransform`) **[V]**.
* **Takeaway:** drivebywire does **not** push forces. It rewires *redstone inputs* into
  offroad/tracks blocks by `@Redirect`-ing their redstone lookups, and it persists its network across
  Sable assembly/disassembly via `SubLevelSchematicSerializationContext` + `SubLevelAssemblyHelper`.
  This is the archetype for "addon that steers physics vehicles without touching the physics API".

### 5.6 simurail integration summary (item 6b)

* modId `simurail`, v `0.0.0`, displayName **"Create Simurail"**, description "An experimental train mod.",
  author `TheLMiffy1111`, MIT, issueTracker `github.com/Crystaelix/Create-Simurail` **[V]**.
* **Dependencies (mods.toml) [V]:** `minecraft [1.21,1.21.2)` required, **`sable [2,)` required**,
  **`simulated [1,)` required**. (Note: it does **not** declare a `create` dependency even though it
  uses Create classes heavily — it relies on `simulated` pulling Create in.)
* **What it does (from `assets/simurail/lang/en_us.json` / `zh_cn.json`) [V]:**
  blocks `physics_bogey` "Physics Bogie" and `automatic_coupler` "Automatic Coupler";
  items `inverted_physics_bogey` "Inverted Physics Bogie", `steering_connector` "Steering Connector".
  GUI options: Physics Behavior on/off, Frame Rotation (disallow / only yaw / only pitch),
  Frame Offset (only lateral / only vertical), Vertical Movement, Control Signal
  (Braking / Inverted Braking / Strength / Inverted Strength), Drive Strength, Connector Cable
  Visibility (front/back), "Controlled by Computer", bogie-type selector with axle counts
  (1–6 axle standard/trailing/Walschaerts driver trucks, S/L/XL sizes).
  It ships `data/sable/tags/block/heavy.json` and `super_light.json` **[V]**, and a
  `assets/simurail/simulated/sections/simurail.json` creative section.
* **Mixin config [V]:** `BogeyStyleAccessor`, `BezierConnectionMixin`, `GlobalRailwayManagerMixin`,
  `SignalEdgeGroupMixin`, `TrackBlockMixin`, `TrackBlockEntityMixin`,
  `compat.electroenergetics.PhysicsBogeyBlockMixin`, with plugin `SimurailMixinPlugin`.
* **Create classes it drives [V]:** `AbstractBogeyBlock`, `BogeySizes$BogeySize`, `BogeyStyle`,
  `AllBogeyStyles`, `AllBlocks`, `Create`, `TrackMaterial$TrackType`, `TrackGraph`, `TrackNodeLocation`,
  `TrackGraphLocation`, `TravellingPoint(+$ITrackSelector)`, `HorizontalKineticBlock`,
  `KineticBlockEntity`, `SmartBlockEntity`, `IBE`, `ProperWaterloggedBlock`, `CreateRegistrate`,
  `CreateBlockEntityBuilder`, `ValueBoxTransform`, `ValueSettingsBoard`, `ScrollValueBehaviour`,
  `AllIcons`, `SelectionScrollInput`, `Label`, `ArmInteractionPoint`.
* **Sable classes it uses [V]:** `Sable`, `ActiveSableCompanion`, `SubLevel`, `ServerSubLevel`,
  `ServerSubLevelContainer`, `plot.LevelPlot`, `plot.ServerLevelPlot`, `plot.EmbeddedPlotLevelAccessor`,
  `system.SubLevelPhysicsSystem`, `api.physics.PhysicsPipeline`, `PhysicsPipelineBody`,
  `api.physics.handle.RigidBodyHandle`, `api.physics.force.ForceTotal`, `api.physics.mass.MassData`,
  `api.physics.constraint.{GenericConstraintHandle,GenericConstraintConfiguration,
  FixedConstraintHandle,FixedConstraintConfiguration,ConstraintJointAxis}`,
  `api.physics.object.ArbitraryPhysicsObject`, `api.physics.object.box.BoxPhysicsObject`,
  `api.block.{BlockEntitySubLevelActor,BlockSubLevelAssemblyListener,BlockSubLevelCollisionShape}`,
  `api.util.*`-adjacent `companion.math.{Pose3d,Pose3dc}`, `mixinterface.clip_overwrite.ClipContextExtension`,
  `physics.config.block_properties.PhysicsBlockPropertyHelper`, `util.SableBufferUtils`, `util.SableNBTUtils`.
* **simulated classes it uses [V]:** `dev.simulated_team.simulated.registrate.SimulatedRegistrate`,
  `dev.simulated_team.simulated.util.SimColors`,
  `dev.simulated_team.simulated.util.click_interactions.InteractCallback(+$Input,$Result)`.
* **Takeaway:** simurail is *exactly* the "Aeronautics: Railway" the parent suspected — a
  Sable-physics bogie that also latches onto Create's `TrackGraph`/`TravellingPoint` for rail guidance.
  Its public steering surface is `SteeringConnectable.connectSteering(boolean, SteeringConnectable, boolean)`,
  `PhysicsBogeyOptions`/`PhysicsBogeySetOptionsPacket`, the CC:Tweaked `PhysicsBogeyPeripheral`, and the
  `PhysicsBogeyControlOverrides` (public fields, protected instance).

---

## 6. Recommended integration points (ranked)

### Tier 1 — safe, exercised by ≥3 independent addons

| # | fqcn | what you need |
|---|---|---|
| 1 | `dev.ryanhcode.sable.api.event.SablePrePhysicsTickEvent` | `void prePhysicsTick(SubLevelPhysicsSystem, double)`; register with `SableEventPlatform.INSTANCE.onPhysicsTick(...)` |
| 2 | `dev.ryanhcode.sable.platform.SableEventPlatform` | `INSTANCE`, `onPhysicsTick`, `onPostPhysicsTick`, `onSubLevelContainerReady` |
| 3 | `dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor` | implement; override `sable$physicsTick(ServerSubLevel, RigidBodyHandle, double)` |
| 4 | `dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem` | `get(Level)`, `require(Level)`, `getPhysicsHandle(ServerSubLevel)`, `getPipeline()`, `getPartialPhysicsTick()`, `getPaused()/setPaused(boolean)`, `queryIntersecting(BoundingBox3dc)` |
| 5 | `dev.ryanhcode.sable.sublevel.ServerSubLevel` / `SubLevel` | `logicalPose()`, `lastPose()`, `boundingBox()`, `getUniqueId()`, `getRuntimeId()`, `getMassTracker()` |
| 6 | `dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle` | `of(ServerSubLevel)`, `applyImpulseAtPoint`, `applyLinearImpulse`, `applyAngularImpulse`, `applyTorqueImpulse`, `addLinearAndAngularVelocity`, `getLinearVelocity()`, `getAngularVelocity()`, `teleport`, `applyForcesAndReset(ForceTotal)`, `isValid()` |
| 7 | `dev.ryanhcode.sable.api.physics.force.ForceTotal` | accumulate then `applyForces(RigidBodyHandle)` |
| 8 | `dev.ryanhcode.sable.companion.SableCompanion` / `dev.ryanhcode.sable.ActiveSableCompanion` | `getContaining(Level, BlockPos|Vec3i|Position|Vector3dc|int,int|Entity|BlockEntity)` |
| 9 | `dev.ryanhcode.sable.Sable` | `Sable.HELPER` (`ActiveSableCompanion`), `Sable.MOD_ID` |
| 10 | `dev.ryanhcode.sable.companion.math.Pose3d` / `Pose3dc` | `position()`, `orientation()`, `transformPosition`, `transformInverse`, `lerp` |
| 11 | `dev.ryanhcode.sable.api.sublevel.SubLevelContainer` / `ServerSubLevelContainer` | `getContainer(Level)`, `getSubLevel(UUID)`, `getAllSubLevels()`, `queryIntersecting`, `addObserver`, `physicsSystem()`, `removeSubLevel(...)` |
| 12 | `dev.ryanhcode.sable.sublevel.plot.LevelPlot` | `getSubLevel()`, `getBoundingBox()`, `contains(Vec3|Vector3dc)` |

### Tier 2 — useful, exercised by 1–2 addons, watch for churn

- `dev.ryanhcode.sable.api.physics.PhysicsPipeline` (`addConstraint`, `teleport`, `applyImpulse`, …)
- `dev.ryanhcode.sable.api.physics.constraint.*` — `GenericConstraintConfiguration/Handle`,
  `RotaryConstraintConfiguration/Handle`, `FixedConstraintConfiguration/Handle`,
  `FreeConstraintConfiguration/Handle`, `ConstraintJointAxis`, `PhysicsConstraintHandle.setMotor(...)`
- `dev.ryanhcode.sable.api.physics.mass.MassData` / `MassTracker` / `MergedMassTracker`
- `dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject` + `BoxHandle` (arbitrary rigid boxes)
- `dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener` (`afterMove(...)`)
- `dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider` (aerodynamic lift/drag — the wing/balloon hook)
- `dev.ryanhcode.sable.api.block.propeller.{BlockEntityPropeller,BlockEntitySubLevelPropellerActor}`
- `dev.ryanhcode.sable.api.schematic.SubLevelSchematicSerializationContext` (survive save/blueprint)
- `dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper` (mass/friction lookups)
- `dev.ryanhcode.sable.companion.math.BoundingBox3d/3dc/3i/3ic`, `JOMLConversion`
- `dev.ryanhcode.sable.util.SableNBTUtils`, `dev.ryanhcode.sable.util.SableBufferUtils`,
  `dev.ryanhcode.sable.util.LevelAccelerator`
- `dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension`

### Tier 3 — Gameplay control blocks (if the addon is a "cab")

- `dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlockEntity` →
  `updateTargetAngle(float)`, public `targetAngle`, `getAngle()`
- `dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity` →
  `setSignal(int)`, `getState()`, `changeState(boolean)`
- `dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity` →
  `assemble()`, `disassemble()`, `isAssembled()`, `getSubLevelID()/setSubLevelID(UUID)`,
  `getTargetAngleDegrees()`, `updateServoCoefficients()`
- `dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity` →
  `assembleOrDisassemble()`, `jerkLever()`
- `dev.eriksonn.aeronautics...PropellerBearingBlockEntity` → `setRotationSpeed(float)`, `getThrust()`,
  `assemble()`, `disassemble()`
- `dev.eriksonn.aeronautics...GyroscopicPropellerBearingBlockEntity` → `setTilt(...)`, `setStrictTilt(...)`
- `dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity` → **no public setters**;
  control via redstone signal, or a `@Pseudo @Redirect` mixin (drivebywire pattern)

### Tier 4 — reference implementations to copy wholesale

- **`dev.qwxon.tracks`** (`tracks-neoforge-1.21.1-1.0.1.jar`) →
  `SableTrackBlockEntity` is a complete, readable Sable bogie/track:
  `BlockEntitySubLevelActor` + `ForceTotal` + `RigidBodyHandle` + suspension tuning API.
- **`com.crystaelix.simurail`** → complete Sable-physics *rail bogie* welded to Create's `TrackGraph`.
- **`edn.stratodonut.drivebywire`** → complete example of `SubLevelAssemblyHelper` +
  `SubLevelSchematicSerializationContext` persistence across assembly/disassembly.
- **`dev.rew1nd.sableschematicapi`** → 58-class `api.blueprint` event/mapper registry on top of Sable.

---

## 7. What could NOT be determined

1. **Entry points are all resolved now** (see §1.2) — every `@Mod(value=...)` was read with `javap -v`.
   What remains open: `sable` itself has **no `@Mod` annotation** on `dev.ryanhcode.sable.Sable`
   (it is `lowcodefml`-bootstrap / `Sable.init()` driven) — the exact `modLoader` wiring for the
   `sable` mod was not traced further. Same question applies to whether `sable` is registered via a
   separate loader class.
2. **Which specific members of `RigidBodyHandle`, `ForceTotal`, `SubLevelContainer`,
   `ServerSubLevelContainer` carry `@ApiStatus.Internal` / `@Deprecated`.** javap's nested-annotation
   rendering was ambiguous. Presence and per-class counts are known; the exact member set is not.
   (`RigidBodyHandle` has **no class-level** annotation — confirmed.)
3. **What replaces the `@Deprecated` member(s) on `RigidBodyHandle`.** No obvious successor class
   exists in the sable jar; the only alternative surface is `PhysicsPipeline`'s `default` velocity
   methods and `ForceTotal.applyForces(...)`.
4. **How to numerically set an offroad wheel's steering angle from outside the mod.**
   No public setter, no accessor interface, no network packet was found. Only the redstone-input path
   (and mixin-redirecting it, as drivebywire does) is confirmed.
5. **Whether `Sable.EventPlatform`/`SablePlatform` are intended for third-party use** — they are
   `ServiceLoader`-backed and have `*Impl` classes in the `neoforge` package; the `INSTANCE` field is
   public, but I saw no mod (other than aeronautics/offroad/sableschem/tracks) using them, and no
   documentation. The `onPhysicsTick` path **is** [V]-confirmed working via `tracks`.
6. **The `mixin_interface.*` / `mixinterface.*` packages** are, by naming, duck-typing interfaces added by
   Sable's own mixins. Whether third parties may `implements` them was not determinable from bytecode.
7. **Offroad/simulated/aeronautics "intended extension" annotations** — I found no `@ApiStatus` or
   `@Internal` markers in `dev.ryanhcode.offroad`, `dev.simulated_team.simulated`, or
   `dev.eriksonn.aeronautics`; those packages appear not to be API at all (all content/impl).
8. **`create_train_parts` has no relevance** to bogies: 0 bogie/wheel/steer/sable/physics classes and
   0 sable/aeronautics/simulated constant-pool references **[V]**. If a bogie was expected there, it is not.
9. **Full method-level dependency graphs** were not built; the xref scan is class-level only.
   A method named in a descriptor of a *class you never reference* would be missed only if it appears
   in no constant pool entry — which cannot happen for a real call site.
10. **The `veil` (foundry.veil) API** used pervasively for networking
    (`foundry.veil.api.network.VeilPacketManager$PacketSink`, `ServerPacketContext`) was not analysed;
    a networking-heavy addon will need it. It is nested inside `sable-neoforge-...jar`
    (`veil-neoforge-1.21.1-4.1.4.jar`) **[V]**.
