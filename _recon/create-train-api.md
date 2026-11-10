# Create 6.0.10 (NeoForge 1.21.1) — Train / Schedule / Signal / Graph API Reconnaissance

Offline bytecode reconnaissance for a NeoForge 1.21.1 Create addon ("raildispatch").
All signatures below were printed by `javap` against the real jars. No sources, no internet.

## 0. Environment & method

| Item | Value |
|---|---|
| Create jar | `D:\CITY\.minecraft\versions\1.21.1-NeoForge_21.1.233CRA6月20号物理火车包\mods\[机械动力] create-1.21.1-6.0.10.jar` |
| Create version | `6.0.10`, modId `create`, requires `neoforge [21.1.219,)`, `minecraft [1.21.1]`, `flywheel [1.0.0,2.0)`, `ponder [1.0.82,)` |
| Binary-relevant libs | jarjar'd inside Create: `ponder-neoforge-1.0.82+mc1.21.1.jar` (contains **net.createmod.ponder** AND **net.createmod.catnip**), `flywheel-neoforge-1.21.1-1.0.6.jar`, `Registrate-MC1.21-1.3.0+67.jar` |
| Java | `C:\Program Files\Java\jdk-21\bin\javap.exe` (JDK 21.0.8) |
| Scratch helper | `_recon\j.ps1` — `& _recon\j.ps1 -Jar create -Sig <fqcn>` / `-Entries <regex>` / `-Code <fqcn>` |
| Raw dumps | `_recon\*.txt`, extracted jars under `_recon\extract\` |

**Sandbox note:** `[System.IO.Compression.ZipFile]` is unavailable in this PowerShell host — all archive access used `jar tf` / `jar xf`.

**Legend**
- **[V]** = verified by a javap-printed signature or by reading javap bytecode of the actual class.
- **[I]** = inferred from bytecode behaviour, naming, or an addon's usage pattern; not directly printed.
- **[U]** = undetermined / not checked.

---

## 1. Train schedule system

### 1.1 Package layout — `com.simibubi.create.content.trains.schedule`
```
Schedule                 ScheduleEntry           ScheduleDataEntry       IScheduleInput
ScheduleRuntime          ScheduleRuntime$State   ScheduleItem            ScheduleItemEntityInteraction
ScheduleMenu             ScheduleMenu$InactiveSlot  ScheduleMenu$InactiveItemHandlerSlot
ScheduleEditPacket       ScheduleScreen          ScheduleScreen$EditorSubWidgets
DestinationSuggestions
destination/ScheduleInstruction
destination/TextScheduleInstruction
destination/DestinationInstruction
destination/ChangeThrottleInstruction
destination/ChangeTitleInstruction
destination/DeliverPackagesInstruction
destination/FetchPackagesInstruction
condition/ScheduleWaitCondition
condition/LazyTickedScheduleCondition
condition/TimedWaitCondition          (+ TimedWaitCondition$TimeUnit)
condition/ScheduledDelay
condition/CargoThresholdCondition      (+ CargoThresholdCondition$Ops)
condition/ItemThresholdCondition
condition/FluidThresholdCondition
condition/IdleCargoCondition
condition/PlayerPassengerCondition
condition/RedstoneLinkCondition
condition/StationPoweredCondition
condition/StationUnloadedCondition
condition/TimeOfDayCondition
hat/TrainHatInfo  hat/TrainHatInfoReloadListener
```
There is **no** `AllScheduleInstructions` class. **[V]** (full `jar tf` scan of the Create jar; the only class matching `Schedule*` outside this package is nothing.)

### 1.2 `Schedule` **[V]**
```java
public class com.simibubi.create.content.trains.schedule.Schedule {
  public static final StreamCodec<RegistryFriendlyByteBuf, Schedule> STREAM_CODEC;

  // *** THE ADDON EXTENSION POINT ***
  public static List<Pair<ResourceLocation, Supplier<? extends ScheduleInstruction>>> INSTRUCTION_TYPES;
  public static List<Pair<ResourceLocation, Supplier<? extends ScheduleWaitCondition>>> CONDITION_TYPES;

  private static void registerInstruction(String path, Supplier<? extends ScheduleInstruction>);
  private static void registerCondition (String path, Supplier<? extends ScheduleWaitCondition>);

  public static <T> List<? extends Component> getTypeOptions(List<Pair<ResourceLocation, T>>);

  public List<ScheduleEntry> entries;
  public boolean cyclic;
  public int savedProgress;

  public Schedule();
  public Schedule(List<ScheduleEntry> entries, boolean cyclic, int savedProgress);
  public CompoundTag write(HolderLookup.Provider registries);
  public static Schedule fromTag(HolderLookup.Provider registries, CompoundTag tag);
}
```

**Registration mechanism — verified from `<clinit>` bytecode.**
```
38: new           java/util/ArrayList
45: putstatic     Schedule.INSTRUCTION_TYPES
48: new           java/util/ArrayList
55: putstatic     Schedule.CONDITION_TYPES
58: ldc  "destination" ; 65: invokestatic registerInstruction(String, Supplier)
68: ldc  "package_delivery"     78: "package_retrieval"     88: "rename"     98: "throttle"
108: ldc "delay" 118: "time_of_day" 128: "fluid_threshold" 138: "item_threshold"
148: "redstone_link" 158: "player_count" 168: "idle" 178: "unloaded" 188: "powered"
```
`registerInstruction` body **[V]**:
```java
Schedule.INSTRUCTION_TYPES.add(Pair.of(Create.asResource(path), supplier));
```
`registerCondition` body is identical against `CONDITION_TYPES`.

> **Both lists are `java.util.ArrayList` — mutable — and `public static`.** `registerInstruction`/`registerCondition` are `private`, but an addon does not need them: it appends directly to the public list. Registered ids are `<namespace>:<path>`; Create's own use namespace `create` via `Create.asResource`.

**Resolution on load — `ScheduleInstruction.fromTag` bytecode [V]:**
```java
ResourceLocation id = ResourceLocation.parse(tag.getString("Id"));
Supplier<? extends ScheduleInstruction> found = null;
for (Pair<ResourceLocation, Supplier<...>> p : Schedule.INSTRUCTION_TYPES)   // LINEAR SCAN
    if (p.getFirst().equals(id)) { found = p.getSecond(); }                  // NOTE: no break
if (found == null) {
    Create.LOGGER.warn("...");            // unresolvable id
    return new DestinationInstruction();  // FALLBACK
}
ScheduleInstruction i = found.get();
i.readAdditional(provider, tag);                       // outer tag
CompoundTag data = tag.getCompound("Data");
i.readAdditional(provider, data);                      // inner data (called twice — Create quirk)
i.data = data;
```
Consequences: (a) an addon type **must** be present in `INSTRUCTION_TYPES` before any save is loaded, i.e. register during common setup; (b) duplicate ids are last-one-wins (no `break`); (c) an unknown id silently degrades to a destination instruction.

`ScheduleWaitCondition.fromTag` uses the same pattern against `CONDITION_TYPES`. **[V]**

### 1.3 `ScheduleEntry` **[V]**
```java
public class ScheduleEntry {
  public static final StreamCodec<RegistryFriendlyByteBuf, ScheduleEntry> STREAM_CODEC;
  public ScheduleInstruction instruction;
  public List<List<ScheduleWaitCondition>> conditions;   // OR-groups of AND-conditions
  public ScheduleEntry();
  public ScheduleEntry(ScheduleInstruction, List<List<ScheduleWaitCondition>>);
  public ScheduleEntry clone(HolderLookup.Provider);
  public CompoundTag write(HolderLookup.Provider);
  public static ScheduleEntry fromTag(HolderLookup.Provider, CompoundTag);
}
```

### 1.4 `ScheduleDataEntry` + `IScheduleInput` **[V]**
```java
public abstract class ScheduleDataEntry implements IScheduleInput {
  protected CompoundTag data;
  public CompoundTag getData();
  public void setData(HolderLookup.Provider, CompoundTag);
  protected void writeAdditional(HolderLookup.Provider, CompoundTag);
  protected void readAdditional(HolderLookup.Provider, CompoundTag);
  protected <T> T enumData(String key, Class<T> type);
  protected String textData(String key);
  protected int intData(String key);
}

public interface IScheduleInput {
  Pair<ItemStack, Component> getSummary();
  ResourceLocation getId();
  CompoundTag getData();
  void setData(HolderLookup.Provider, CompoundTag);
  default int slotsTargeted();
  default List<Component> getTitleAs(String langKey);
  default ItemStack getSecondLineIcon();
  default void setItem(int slot, ItemStack);
  default ItemStack getItem(int slot);
  default List<Component> getSecondLineTooltip(int slot);
  default void initConfigurationWidgets(ModularGuiLineBuilder builder);
  default boolean renderSpecialIcon(GuiGraphics, int, int);
}
```

### 1.5 Instructions **[V]**
```java
public abstract class ScheduleInstruction extends ScheduleDataEntry {
  public static final StreamCodec<RegistryFriendlyByteBuf, ScheduleInstruction> STREAM_CODEC;
  public ScheduleInstruction();
  public abstract boolean supportsConditions();
  public abstract DiscoveredPath start(ScheduleRuntime runtime, Level level);
  public final CompoundTag write(HolderLookup.Provider);
  public static ScheduleInstruction fromTag(HolderLookup.Provider, CompoundTag);
}

public abstract class TextScheduleInstruction extends ScheduleInstruction {
  protected String getLabelText();
  public List<Component> getTitleAs(String);
  public void initConfigurationWidgets(ModularGuiLineBuilder);
  protected void modifyEditBox(EditBox);
}

public class DestinationInstruction extends TextScheduleInstruction {
  public DestinationInstruction();
  public Pair<ItemStack, Component> getSummary();
  public boolean supportsConditions();          // true
  public ResourceLocation getId();              // create:destination
  public ItemStack getSecondLineIcon();         // AllBlocks.TRACK_STATION.asStack()
  public String getFilter();
  public String getFilterForRegex();            // Glob.toRegexPattern(getFilter(), "")
  public List<Component> getSecondLineTooltip(int);
  protected void modifyEditBox(EditBox);
  public DiscoveredPath start(ScheduleRuntime, Level);
}
```
**`DestinationInstruction.start` bytecode [V]** — this is *exactly* the addressing logic a new "address" instruction must mirror:
```java
if (!runtime.train.hasForwardConductor() && !runtime.train.hasBackwardConductor()) {
    runtime.train.status.missingConductor();
    runtime.startCooldown();
}
List<GlobalStation> candidates = new ArrayList<>();
for (GlobalStation s : runtime.train.graph.getPoints(EdgePointType.STATION))
    if (s.name.matches(getFilterForRegex())) candidates.add(s);
DiscoveredPath path = runtime.train.navigation.findPathTo(candidates, <double distanceBias>);
if (path == null) {
    if (getFilter().isEmpty()) runtime.train.status.failedNavigation();
    else                       runtime.train.status.failedNavigationNoTarget(getFilter());
    runtime.startCooldown();
}
return path;
```
Note: **station lookup is a linear scan of the train's own `TrackGraph`** — there is no global name→station map (§5).

Other instructions (all no-arg ctors): `ChangeThrottleInstruction` (`create:throttle`, `float getThrottle()`), `ChangeTitleInstruction` (`create:rename`, `String getScheduleTitle()`), `DeliverPackagesInstruction` (`create:package_delivery`), `FetchPackagesInstruction` (`create:package_retrieval`, extends `TextScheduleInstruction`). **[V]**

### 1.6 Conditions **[V]**
```java
public abstract class ScheduleWaitCondition extends ScheduleDataEntry {
  public static final StreamCodec<RegistryFriendlyByteBuf, ScheduleWaitCondition> STREAM_CODEC;
  public abstract boolean tickCompletion(Level, Train, CompoundTag context);
  protected void requestStatusToUpdate(CompoundTag context);
  public final CompoundTag write(HolderLookup.Provider);
  public static ScheduleWaitCondition fromTag(HolderLookup.Provider, CompoundTag);
  public abstract MutableComponent getWaitingStatus(Level, Train, CompoundTag);
}

public abstract class LazyTickedScheduleCondition extends ScheduleWaitCondition {
  public LazyTickedScheduleCondition(int tickRate);
  protected abstract boolean lazyTickCompletion(Level, Train, CompoundTag);
}

public abstract class TimedWaitCondition extends ScheduleWaitCondition {
  protected void requestDisplayIfNecessary(CompoundTag, int);
  public int totalWaitTicks();
  protected Component formatTime(boolean);
  public int getValue();
  public TimedWaitCondition$TimeUnit getUnit();     // TimeUnit enum
}

public abstract class CargoThresholdCondition extends LazyTickedScheduleCondition {
  protected abstract boolean test(Level, Train, CompoundTag);
  protected abstract Component getUnit();
  protected abstract ItemStack getIcon();
  public int slotsTargeted();
  public CargoThresholdCondition$Ops getOperator();  // Ops enum
  public int getThreshold();
  public int getMeasure();
}
```
Concrete: `ScheduledDelay` (`create:delay`), `TimeOfDayCondition` (`create:time_of_day`), `FluidThresholdCondition` (`create:fluid_threshold`), `ItemThresholdCondition` (`create:item_threshold`), `RedstoneLinkCondition` (`create:redstone_link`), `PlayerPassengerCondition` (`create:player_count`), `IdleCargoCondition` (`create:idle`), `StationUnloadedCondition` (`create:unloaded`), `StationPoweredCondition` (`create:powered`). **[V]**

### 1.7 `ScheduleRuntime` — how a schedule advances **[V]**
```java
public class ScheduleRuntime {
  private static final int TBD, INVALID, INTERVAL;
  public Train train;
  public Schedule schedule;
  public boolean isAutoSchedule, paused, completed;
  public int currentEntry;
  public ScheduleRuntime$State state;       // PRE_TRANSIT, IN_TRANSIT, POST_TRANSIT
  public List<Integer> conditionProgress;
  public List<CompoundTag> conditionContext;
  public String currentTitle;
  public int ticksInTransit;
  public List<Integer> predictionTicks;
  public boolean displayLinkUpdateRequested;
  private int cooldown;

  public ScheduleRuntime(Train train);
  public void startCooldown();
  public void destinationReached();
  public void transitInterrupted();
  public void tick(Level level);
  public void tickConditions(Level level);
  public DiscoveredPath startCurrentInstruction(Level level);
  public void setSchedule(Schedule schedule, boolean isAutoSchedule);
  public Schedule getSchedule();
  public void discardSchedule();
  public Collection<GlobalTrainDisplayData$TrainDeparturePrediction> submitPredictions();
  public CompoundTag write(HolderLookup.Provider);
  public void read(HolderLookup.Provider, CompoundTag);
  public ItemStack returnSchedule(HolderLookup.Provider);
  public void setSchedulePresentClientside(boolean);
  public MutableComponent getWaitingStatus(Level);
}
```
Driving path **[V]** — `Train.tick(Level)` contains `getfield Train.runtime` + `invokevirtual ScheduleRuntime.tick(Level)` at three bytecode sites (offsets 79, 1078, 1117 of `Train#tick`), plus `Train.earlyTick` and the `runtime.paused` check. State machine: `Train.arriveAt(GlobalStation)` → `runtime.destinationReached()`; `Train#tick` on arrival calls `runtime.startCurrentInstruction(level)` and applies the returned `DiscoveredPath` via the `Navigation`.

`setSchedule` bytecode **[V]**:
```java
reset();                                                      // clears state, conditions, cooldown
this.schedule = schedule;
this.currentEntry = Mth.clamp(schedule.savedProgress, 0, schedule.entries.size() - 1);
this.paused = false;
this.isAutoSchedule = isAutoSchedule;
this.train.status.newSchedule();
this.predictionTicks = new ArrayList<>();
schedule.entries.forEach(<prime predictions>);
this.displayLinkUpdateRequested = true;
```
`discardSchedule()` bytecode **[V]**: `train.navigation.cancelNavigation(); reset();`
`setSchedulePresentClientside(boolean)` sets `schedule = flag ? new Schedule() : null` (client mirror only). **[V]**

`Schedule.savedProgress` is the persisted resume index — persisted through `ScheduleRuntime.write/read` inside `Train.write/read`. **[V]**

---

## 2. Trains, carriages, coupling

### 2.1 `Train` **[V]** — `com.simibubi.create.content.trains.entity.Train`
```java
public class Train {
  public static final StreamCodec<RegistryFriendlyByteBuf, Train> STREAM_CODEC;

  public double speed, targetSpeed;
  public Double speedBeforeStall;
  public int carriageWaitingForChunks;
  public double throttle;
  public boolean honk;
  public UUID id;                                     // train identity
  public UUID owner;
  public TrackGraph graph;
  public Navigation navigation;
  public ScheduleRuntime runtime;
  public TrainIconType icon;
  public int mapColorIndex;
  public Component name;                              // train name (mutable public field)
  public TrainStatus status;
  public boolean invalid;
  public TravellingPoint$SteerDirection manualSteer;
  public boolean manualTick;
  public UUID currentStation;
  public boolean currentlyBackwards, doubleEnded;
  public List<Carriage> carriages;
  public List<Integer> carriageSpacing;
  public boolean updateSignalBlocks;
  public Map<UUID, UUID> occupiedSignalBlocks;        // carriage/train uuid -> edge-group uuid
  public Set<UUID> reservedSignalBlocks;
  public Set<UUID> occupiedObservers;
  public Map<UUID, Pair<Integer, Boolean>> cachedObserverFiltering;
  public int migrationCooldown;
  public boolean derailed;
  public int fuelTicks, honkTicks;
  public Boolean lowHonk;
  public int honkPitch;
  public float accumulatedSteamRelease;
  public Player backwardsDriver;

  public Train(UUID id, UUID owner, TrackGraph graph, List<Carriage> carriages,
               List<Integer> carriageSpacing, boolean doubleEnded, int mapColorIndex);
  public Train(UUID id, UUID owner, TrackGraph graph, List<Carriage> carriages,
               List<Integer> carriageSpacing, boolean doubleEnded, Component name,
               TrainIconType icon, int mapColorIndex);

  public void earlyTick(Level);
  public void tick(Level);
  public TravellingPoint$IEdgePointListener frontSignalListener();
  public TravellingPoint$IEdgePointListener backSignalListener();
  public void cancelStall();
  public boolean hasForwardConductor();
  public boolean hasBackwardConductor();
  public Pair<Train, Vec3> findCollidingTrain(Level, Vec3, Vec3, ResourceKey<Level>);
  public void crash();
  public boolean disassemble(Direction, BlockPos);
  public boolean canDisassemble();
  public boolean isTravellingOn(TrackNode);
  public void detachFromTracks();
  public void forEachTravellingPoint(Consumer<TravellingPoint>);
  public void forEachTravellingPointBackwards(BiConsumer<TravellingPoint, Double>);
  public void reattachToTracks(Level);
  public int getTotalLength();
  public void leaveStation();
  public void arriveAt(GlobalStation);
  public void setCurrentStation(GlobalStation);
  public GlobalStation getCurrentStation();
  public LivingEntity getOwner(Level);
  public void approachTargetSpeed(float);
  public void collectInitiallyOccupiedSignalBlocks();
  public boolean shouldCarriageSyncThisTick(long, int);
  public Couple<Couple<TrackNode>> getEndpointEdges();
  public int getNavigationPenalty();
  public void burnFuel();
  public float maxSpeed(), maxTurnSpeed(), acceleration();
  public CompoundTag write(DimensionPalette, HolderLookup.Provider);
  public static Train read(CompoundTag, HolderLookup.Provider,
                           Map<UUID, TrackGraph>, DimensionPalette);
  public int countPlayerPassengers();
  public void determineHonk(Level);
  public float distanceToLocationSqr(Level, Vec3);
  public List<ResourceKey<Level>> getPresentDimensions();
  public Optional<BlockPos> getPositionInDimension(ResourceKey<Level>);
}
```
`Train#disassemble(Direction, BlockPos)` bytecode **[V]** ends with:
```java
Create.RAILWAYS.removeTrain(this.id);
CatnipServices.NETWORK.sendToAll(new RemoveTrainPacket(this));   // via RemoveTrainPacket
// also writes StationBlockEntity.lastDisassembledTrainName / lastDisassembledMapColorIndex
```

### 2.2 Coupling / decoupling — **there is no runtime couple/decouple API** **[V]**
Full `jar tf` scan for `Coupl` returns only:
```
content/trains/entity/CarriageCouplingRenderer          (render only)
content/contraptions/minecart/CouplingHandler
content/contraptions/minecart/CouplingHandlerClient
content/contraptions/minecart/CouplingPhysics
content/contraptions/minecart/CouplingCreationPacket
content/contraptions/minecart/MinecartCouplingItem
content/contraptions/minecart/CouplingRenderer
content/contraptions/minecart/capability/MinecartController$CouplingData
infrastructure/command/CouplingCommand
```
`CouplingCommand` is **minecart-only** — its error constants are `ONLY_MINECARTS_ALLOWED`, `SAME_DIMENSION`, `TWO_CARTS`. **[V]**

So in Create 6.0.10:
- A `Train` is a fixed `List<Carriage>` created at **station assembly**; it can only be torn down wholesale via `Train#disassemble(Direction, BlockPos)`.
- `TrainRelocator.carriageWrenched(Vec3, CarriageContraptionEntity)` **[V]** does *not* split a train: it only records `relocatingOrigin`, `relocatingTrain = train.id`, `relocatingEntityId` and returns `true` — it starts the **relocate-train** flow (`TrainRelocator.relocate(...)`). No carriage list mutation.
- Any "coupling by tag / hump shunting" behaviour must therefore be **implemented by the addon** (e.g. disassemble + reassemble via `StationBlockEntity.assemble`, or a custom edge-point/track-vehicle layer), not delegated to Create.

### 2.3 `GlobalRailwayManager` — server-side train registry **[V]**
```java
public class com.simibubi.create.content.trains.GlobalRailwayManager {
  public Map<UUID, TrackGraph> trackNetworks;
  public Map<UUID, SignalEdgeGroup> signalEdgeGroups;
  public Map<UUID, Train> trains;                       // *** train lookup by UUID ***
  public TrackGraphSync sync;
  public int version;

  public void addTrain(Train);
  public void removeTrain(UUID);
  public TrackGraph getOrCreateGraph(UUID, int);
  public void putGraphWithDefaultGroup(TrackGraph);
  public void putGraph(TrackGraph);
  public void removeGraphAndGroup(TrackGraph);
  public void removeGraph(TrackGraph);
  public void updateSplitGraph(LevelAccessor, TrackGraph);
  public TrackGraph getGraph(LevelAccessor, TrackNodeLocation);
  public List<TrackGraph> getGraphs(LevelAccessor, TrackNodeLocation);
  public void tick(Level);
  public void tickSignalOverlay();
  public void clientTick();
  public void markTracksDirty();
  public void cleanUp();
  public void playerLogin(Player);
  public void playerLogout(Player);
  public GlobalRailwayManager sided(LevelAccessor);     // client vs server instance
}
```
Access from anywhere: **`Create.RAILWAYS`** — `public static final GlobalRailwayManager RAILWAYS`. **[V]**
```java
public class com.simibubi.create.Create {
  public static final String ID;                       // "create"
  public static final String NAME;
  public static final org.slf4j.Logger LOGGER;
  public static final com.google.gson.Gson GSON;
  public static final java.util.Random RANDOM;
  private static final CreateRegistrate REGISTRATE;    // *** PRIVATE ***
  public static final ServerSchematicLoader SCHEMATIC_RECEIVER;
  public static final RedstoneLinkNetworkHandler REDSTONE_LINK_NETWORK_HANDLER;
  public static final TorquePropagator TORQUE_PROPAGATOR;
  public static final GlobalRailwayManager RAILWAYS;   // *** PUBLIC ***
  public static final GlobalLogisticsManager LOGISTICS;
  public static final ServerLagger LAGGER;
  public static ResourceLocation asResource(String);
  public static CreateRegistrate registrate();         // *** use this, REGISTRATE is private ***
  public static LangBuilder lang();
}
```
Persistence: `RailwaySavedData extends SavedData` with `TrackGraph`/`Train`/`SignalEdgeGroup` maps and `static RailwaySavedData load(MinecraftServer)` / `SavedData.Factory<RailwaySavedData> factory()`. **[V]**

### 2.4 `Carriage` / `CarriageBogey` / `TravellingPoint` **[V]**
```java
public class Carriage {
  public static final StreamCodec<RegistryFriendlyByteBuf, Carriage> STREAM_CODEC;
  public static final AtomicInteger netIdGenerator;
  public Train train;
  public int id;
  public boolean blocked, stalled;
  public Couple<Boolean> presentConductors;
  public int bogeySpacing;
  public Couple<CarriageBogey> bogeys;
  public TrainCargoManager storage;

  public Carriage(CarriageBogey front, CarriageBogey back, int bogeySpacing);   // creation ctor
  public void setTrain(Train);
  public TravellingPoint getLeadingPoint();
  public TravellingPoint getTrailingPoint();
  public CarriageBogey leadingBogey(), trailingBogey();
  public boolean isOnTwoBogeys();
  public CarriageContraptionEntity anyAvailableEntity();
  public Pair<ResourceKey<Level>, Carriage$DimensionalCarriageEntity> anyAvailableDimensionalCarriage();
  public void forEachPresentEntity(Consumer<CarriageContraptionEntity>);
  public void setContraption(Level, CarriageContraption);
  public Carriage$DimensionalCarriageEntity getDimensional(Level);
  public CompoundTag write(DimensionPalette, HolderLookup.Provider);
  public static Carriage read(CompoundTag, HolderLookup.Provider, TrackGraph, DimensionPalette);
  // + travel/manageEntities/alignEntity/updateConductors/updateContraptionAnchors/getPresentDimensions
}

public class CarriageBogey {
  public Carriage carriage; public CompoundTag bogeyData;
  public Couple<Vec3> couplingAnchors;
  public CarriageBogey(AbstractBogeyBlock<?> type, boolean upsideDown, CompoundTag bogeyData);
  public CarriageBogey(AbstractBogeyBlock<?> type, boolean upsideDown, CompoundTag bogeyData,
                       TravellingPoint leading, TravellingPoint trailing);
  public TravellingPoint leading(), trailing();
  public Vec3 getAnchorPosition(); public Vec3 getAnchorPosition(boolean);
  public double getStress();
  public BogeyStyle getStyle(); public BogeySizes$BogeySize getSize();
}

public class TravellingPoint {
  public TrackNode node1, node2; public TrackEdge edge;
  public double position; public boolean blocked, upsideDown;
  public double travel(TrackGraph, double, ITrackSelector);
  public double travel(TrackGraph, double, ITrackSelector, IEdgePointListener);
  public double travel(TrackGraph, double, ITrackSelector, IEdgePointListener, ITurnListener);
  public double travel(TrackGraph, double, ITrackSelector, IEdgePointListener, ITurnListener, IPortalListener);
  public Vec3 getPosition(TrackGraph[, boolean]);
  public Vec3 getPositionWithOffset(TrackGraph, double, boolean);
  public void reverse(TrackGraph);
  public void migrateTo(List<TrackGraphLocation>);
  // nested: IEdgePointListener, ITurnListener, IPortalListener, ITrackSelector, SteerDirection
}
```

### 2.5 `Navigation` **[V]**
```java
public class Navigation {
  public Train train;
  public GlobalStation destination;
  public double distanceToDestination, distanceStartedAt;
  public boolean destinationBehindTrain, announceArrival;
  java.util.List<Couple<TrackNode>> currentPath;          // package-private
  public Pair<UUID, Boolean> waitingForSignal;            // edge-group uuid -> ?
  public double distanceToSignal;
  public int ticksWaitingForSignal;

  public Navigation(Train);
  public void tick(Level);
  public boolean isActive();
  public TravellingPoint$ITrackSelector control(TravellingPoint);
  public TravellingPoint$ITrackSelector controlSignalScout();
  public void cancelNavigation();
  public double startNavigation(DiscoveredPath);
  public DiscoveredPath findPathTo(GlobalStation destination, double distanceBias);
  public DiscoveredPath findPathTo(ArrayList<GlobalStation> destinations, double distanceBias);
  public GlobalStation findNearestApproachable(boolean /*preferForward*/);
  public void search(double distance, boolean, ArrayList<GlobalStation>, Navigation$StationTest);
  public void search(double, double, boolean, ArrayList<GlobalStation>, Navigation$StationTest);
  public CompoundTag write(DimensionPalette);
  public void read(CompoundTag, TrackGraph, DimensionPalette);

  public interface StationTest { /* functional */ }
}
```
`findPathTo(ArrayList<GlobalStation>, double)` is **the** reusable route-calculation entry point an addon dispatcher can call. **[V]**

`TrainStatus` **[V]**: public booleans `navigation`, `track`, `conductor`; methods `failedNavigation()`, `failedNavigationNoTarget(String)`, `failedPackageNoTarget(String)`, `successfulNavigation()`, `foundConductor()`, `missingConductor()`, `missingCorrectConductor()`, `manualControls()`, `failedMigration()`, `highStress()`, `doublePortal()`, `endOfTrack()`, `crash()`, `successfulMigration()`, `trackOK()`, `tick(Level)`, `addMessage(StatusMessage)`, `newSchedule()`, `displayInformation(String, boolean, Object...)`.

`TrainMigration` **[V]**: `TrainMigration(TravellingPoint)`, `TrackGraphLocation tryMigratingTo(TrackGraph)`, `write(DimensionPalette)`, `static read(CompoundTag, DimensionPalette)` — used for moving a train between graphs on track edits.

`TrainIconType` **[V]**: `public static Map<ResourceLocation, TrainIconType> REGISTRY;` + `public static void register(ResourceLocation id, ResourceLocation sheet, int x, int y)`, `byId(ResourceLocation)`, `getDefault()`. *(A public registration hook — but it is a plain public static map, and this class is client-render oriented.)*

---

## 3. Signals

### 3.1 There is **no `TrackSignal` class in Create 6.0.10** **[V]**
`jar tf` finds no `TrackSignal`/`BlockSignal` anywhere in the Create jar. The 6.0 signal model is:

| Concept | Class | Role |
|---|---|---|
| Signal **edge point** (graph data) | `com.simibubi.create.content.trains.signal.SignalBoundary extends TrackEdgePoint` | lives on a `TrackEdge` via `EdgeData`, holds the group UUIDs |
| Signal **block entity** (world) | `...signal.SignalBlockEntity` | world state + redstone + rendering |
| Signal **group / occupancy** | `...signal.SignalEdgeGroup` | occupancy + colour + reservation; keyed by `GlobalRailwayManager.signalEdgeGroups` |
| Signal **visual state** | `SignalBlockEntity$SignalState` (inner enum) | what the lamp shows |
| Colour of a group | `...signal.EdgeGroupColor` (enum, 9 values) | |

### 3.2 `SignalBoundary` **[V]**
```java
public class SignalBoundary extends TrackEdgePoint {
  public Couple<Map<BlockPos, Boolean>> blockEntities;
  public Couple<SignalBlock$SignalType> types;
  public Couple<UUID> groups;                                   // *** the two edge groups ***
  public Couple<Boolean> sidesToUpdate;
  public Couple<SignalBlockEntity$SignalState> cachedStates;

  public void setGroup(boolean oppositeSide, UUID group);
  public void setGroupAndUpdate(TrackNode, UUID);
  public UUID getGroup(TrackNode);
  public boolean canNavigateVia(TrackNode);
  public SignalBlock$SignalType getTypeFor(BlockPos);
  public SignalBlockEntity$SignalState getStateFor(BlockPos);
  public SignalBlockEntity$OverlayState getOverlayFor(BlockPos);
  public boolean isForcedRed(TrackNode); public boolean isForcedRed(boolean);
  public boolean canMerge(); public boolean canCoexistWith(EdgePointType<?>, boolean);
  public void tick(TrackGraph, boolean simulate);
  public void blockEntityAdded(BlockEntity, boolean);
  public void blockEntityRemoved(BlockPos, boolean);
  public void invalidate(LevelAccessor);
  public void onRemoved(TrackGraph);
  public void queueUpdate(TrackNode);
  public void cycleSignalType(BlockPos);
  // read/write NBT + FriendlyByteBuf
}
```

### 3.3 `SignalEdgeGroup` — **the occupancy model a dispatcher queries** **[V]**
```java
public class SignalEdgeGroup {
  public UUID id;
  public EdgeGroupColor color;
  public Set<Train> trains;                     // trains inside this block
  public SignalBoundary reserved;               // reservation holder
  public Map<UUID, UUID> intersecting;
  public Set<SignalEdgeGroup> intersectingResolved;
  public Set<UUID> adjacent;
  public boolean fallbackGroup;

  public SignalEdgeGroup(UUID);
  public SignalEdgeGroup asFallback();
  public boolean isOccupiedUnless(Train);        // *** "is this block occupied?" (ignoring one train) ***
  public boolean isOccupiedUnless(SignalBoundary);
  public void putIntersection(UUID, UUID); public void removeIntersection(UUID);
  public void putAdjacent(UUID); public void removeAdjacent(UUID);
  public void resolveColor();
  public static SignalEdgeGroup read(CompoundTag);
  public CompoundTag write();
}
```
**"Is the block ahead occupied / red?" — the concrete query chain [V]:**
1. Get the graph: `train.graph` (public field) — or `Create.RAILWAYS.sided(level).trackNetworks.get(graphId)`.
2. Get the edge groups: `Create.RAILWAYS.signalEdgeGroups` (`Map<UUID, SignalEdgeGroup>`, public) — or `Create.RAILWAYS.sided(level).signalEdgeGroups`.
3. Resolve the group governing a position on an edge:
   `TrackEdge.getEdgeData().getEffectiveEdgeGroupId(TrackGraph)` → `UUID`
   or `TrackEdge.getEdgeData().getGroupAtPosition(TrackGraph, double t)` → `UUID`.
4. Look the group up and ask: `group.isOccupiedUnless(train)` → `boolean`.
5. Signal-lamp state at a specific signal block: `SignalBlockEntity#getState()` → `SignalBlockEntity$SignalState`, and `SignalBoundary#getStateFor(BlockPos)`.
6. Enumerate all signals on a graph: `trackGraph.getPoints(EdgePointType.SIGNAL)` → `Collection<SignalBoundary>`.

`EdgeData` **[V]** (this is what a per-edge occupancy query needs):
```java
public class EdgeData {
  public static final UUID passiveGroup;
  public boolean hasSignalBoundaries();
  public UUID getSingleSignalGroup();
  public void setSingleSignalGroup(TrackGraph, UUID);
  public List<TrackEdgeIntersection> getIntersections();
  public UUID getGroupAtPosition(TrackGraph, double positionOnEdge);
  public List<TrackEdgePoint> getPoints();
  public UUID getEffectiveEdgeGroupId(TrackGraph);
  public <T extends TrackEdgePoint> T next(EdgePointType<T>, double position);
  public TrackEdgePoint next(double position);
  public <T extends TrackEdgePoint> T get(EdgePointType<T>, double position);
  public boolean hasPoints(); public boolean hasIntersections();
}
```

`Train` also caches its own occupancy **[V]**: `Train.occupiedSignalBlocks : Map<UUID,UUID>`, `Train.reservedSignalBlocks : Set<UUID>`, filled by `Train#collectInitiallyOccupiedSignalBlocks()` and updated by `frontSignalListener()` / `backSignalListener()`.

`Navigation` exposes the *train-in-motion* view **[V]**: `public Pair<UUID, Boolean> waitingForSignal; public double distanceToSignal; public int ticksWaitingForSignal;` plus private `waitingForChainedGroups : Map<UUID, Pair<SignalBoundary, Boolean>>` and private `reserveChain()`.

`EdgeGroupColor` **[V]**: `YELLOW, GREEN, BLUE, ORANGE, LAVENDER, RED, CYAN, BROWN, WHITE`; `StreamCodec STREAM_CODEC`; `int strikeFrom(int)`, `Color get()`, `static EdgeGroupColor getDefault()`, `static EdgeGroupColor findNextAvailable(int mask)`.

`SignalPropagator` **[V]** (static helpers, public): `onSignalRemoved(TrackGraph, SignalBoundary)`, `notifySignalsOfNewNode(TrackGraph, TrackNode)`, `propagateSignalGroup(TrackGraph, SignalBoundary, boolean)`, `collectChainedSignals(TrackGraph, SignalBoundary, boolean)`, `walkSignals(TrackGraph, SignalBoundary, boolean, Predicate<Pair<TrackNode,SignalBoundary>>, Predicate<EdgeData>, boolean)`, `notifyTrains(TrackGraph, TrackEdge...)`.

`SignalBlock` **[V]**: `EnumProperty<SignalBlock$SignalType> TYPE`, `BooleanProperty POWERED`, `getAnalogOutputSignal` (reads the signal state), `onWrenched` cycles the signal type.

---

## 4. Track graph, routing, and track switches

### 4.1 `TrackGraph` **[V]** — `com.simibubi.create.content.trains.graph.TrackGraph`
```java
public class TrackGraph {
  public static final AtomicInteger graphNetIdGenerator, nodeNetIdGenerator;
  public UUID id;
  public Color color;                                       // net.createmod.catnip.theme.Color

  // package-private storage (NOT directly reachable from another package):
  java.util.Map<TrackNodeLocation, TrackNode> nodes;
  java.util.Map<Integer, TrackNode> nodesById;
  java.util.Map<TrackNode, java.util.Map<TrackNode, TrackEdge>> connectionsByNode;
  EdgePointStorage edgePoints;
  java.util.Map<ResourceKey<Level>, TrackGraphBounds> bounds;
  int netId, checksum;

  public TrackGraph(); public TrackGraph(UUID);

  // --- edge points (signals, stations, observers, addon types) ---
  public <T extends TrackEdgePoint> void addPoint(EdgePointType<T>, T);
  public <T extends TrackEdgePoint> T getPoint(EdgePointType<T>, UUID);
  public <T extends TrackEdgePoint> Collection<T> getPoints(EdgePointType<T>);
  public <T extends TrackEdgePoint> T removePoint(EdgePointType<T>, UUID);
  public void tickPoints(boolean simulate);

  // --- traversal (this is the route-calculation surface) ---
  public Set<TrackNodeLocation> getNodes();
  public TrackNode locateNode(Level, Vec3);
  public TrackNode locateNode(TrackNodeLocation);
  public TrackNode getNode(int netId);
  public Map<TrackNode, TrackEdge> getConnectionsFrom(TrackNode);      // *** adjacency ***
  public TrackEdge getConnection(Couple<TrackNode>);
  public boolean createNodeIfAbsent(TrackNodeLocation$DiscoveredLocation);
  public void loadNode(TrackNodeLocation, int, Vec3);
  public void addNode(TrackNode); public boolean addNodeIfAbsent(TrackNode);
  public boolean removeNode(LevelAccessor, TrackNodeLocation);
  public void connectNodes(LevelAccessor, TrackNodeLocation$DiscoveredLocation,
                           TrackNodeLocation$DiscoveredLocation, BezierConnection);
  public void disconnectNodes(TrackNode, TrackNode);
  public boolean putConnection(TrackNode, TrackNode, TrackEdge);

  public static int nextNodeId(); public static int nextGraphId();
  public void transferAll(TrackGraph);
  public Set<TrackGraph> findDisconnectedGraphs(LevelAccessor, Map<Integer, Pair<Integer,UUID>>);
  public void transfer(LevelAccessor, TrackNode, TrackGraph);
  public boolean isEmpty();
  public TrackGraphBounds getBounds(Level);
  public float distanceToLocationSqr(Level, Vec3);
  public int getChecksum();
  public void markDirty();
  public CompoundTag write(HolderLookup.Provider, DimensionPalette);
  public static TrackGraph read(CompoundTag, HolderLookup.Provider, DimensionPalette);
}
```
> **Note:** `connectionsByNode` is package-private; the public accessor is `getConnectionsFrom(TrackNode)`. There is no public `getConnections()`.

### 4.2 `TrackNode` / `TrackEdge` **[V]**
```java
public class TrackNode {
  int netId; Vec3 normal; TrackNodeLocation location;            // all private/package
  public TrackNode(TrackNodeLocation, int netId, Vec3 normal);
  public TrackNodeLocation getLocation();
  public int getNetId();
  public Vec3 getNormal();
}

public class TrackEdge {
  public TrackNode node1, node2;
  public TrackEdge(TrackNode, TrackNode, BezierConnection turn, TrackMaterial material);
  public TrackMaterial getTrackMaterial();
  public boolean isTurn(); public boolean isInterDimensional();
  public EdgeData getEdgeData();
  public BezierConnection getTurn();
  public Vec3 getDirection(boolean); public Vec3 getDirectionAt(double t);
  public boolean canTravelTo(TrackEdge);
  public double getLength(); public double incrementT(double, double);
  public Vec3 getPosition(TrackGraph, double); public Vec3 getNormal(TrackGraph, double);
  public Vec3 getPositionSmoothed(TrackGraph, double); public Vec3 getNormalSmoothed(TrackGraph, double);
  public Collection<double[]> getIntersection(TrackNode, TrackNode, TrackEdge, TrackNode, TrackNode);
}
```

### 4.3 Pathfinding / route computation **[V]**
```java
public class DiscoveredPath {
  public List<Couple<TrackNode>> path;      // each element is one graph edge (node pair)
  public GlobalStation destination;
  public double distance;
  public double cost;
  public DiscoveredPath(double distance, double cost,
                        List<Couple<TrackNode>> path, GlobalStation destination);
}
```
Create's own route search lives **inside `Navigation`** and is public:
```java
Navigation.findPathTo(GlobalStation destination, double distanceBias) -> DiscoveredPath
Navigation.findPathTo(ArrayList<GlobalStation> destinations, double distanceBias) -> DiscoveredPath
Navigation.search(double distance, boolean, ArrayList<GlobalStation>, Navigation$StationTest)
Navigation.search(double distance, double, boolean, ArrayList<GlobalStation>, Navigation$StationTest)
Navigation.findNearestApproachable(boolean)
Navigation.startNavigation(DiscoveredPath) -> double
```
Internals (private, bytecode-observed) are a `Map`-based A*-style frontier over `TrackGraph.getConnectionsFrom(node)` with `TrackEdge` cost/length and per-candidate pruning; `control(TravellingPoint)` and `controlSignalScout()` pick the next edge (`ITrackSelector`). **[I]** for the algorithm specifics, **[V]** for the entry-point signatures.

There is no standalone public `TrackGraph.findPath(...)`. If an addon needs a custom route, it must either reuse `Navigation.findPathTo` (which requires a `GlobalStation` target) or walk `getConnectionsFrom` itself.

`TrackGraphHelper` **[V]**: `static TrackGraphLocation getGraphLocationAt(Level, BlockPos, Direction$AxisDirection, Vec3)` and `getBezierGraphLocationAt(Level, BlockPos, Direction$AxisDirection, BezierTrackPointLocation)` — this is how a block turns a world position into a graph location.

### 4.4 `EdgePointType` — **the official addon registry for new graph features** **[V]**
```java
public class com.simibubi.create.content.trains.graph.EdgePointType<T extends TrackEdgePoint> {
  public static final Map<ResourceLocation, EdgePointType<?>> TYPES;      // *** PUBLIC, MUTABLE ***
  public static final EdgePointType<SignalBoundary> SIGNAL;
  public static final EdgePointType<GlobalStation> STATION;
  public static final EdgePointType<TrackObserver> OBSERVER;

  public static <T extends TrackEdgePoint> EdgePointType<T> register(ResourceLocation id,
                                                                    Supplier<T> factory);   // *** PUBLIC ***
  public EdgePointType(ResourceLocation id, Supplier<T> factory);
  public T create();
  public ResourceLocation getId();
  public static TrackEdgePoint read(FriendlyByteBuf, DimensionPalette);
}

public abstract class TrackEdgePoint {
  public UUID id;
  public Couple<TrackNodeLocation> edgeLocation;
  public double position;
  public void setId(UUID); public UUID getId();
  public void setType(EdgePointType<?>); public EdgePointType<?> getType();
  public abstract boolean canMerge();
  public boolean canCoexistWith(EdgePointType<?>, boolean);
  public abstract void invalidate(LevelAccessor);
  protected void invalidateAt(LevelAccessor, BlockPos);
  public abstract void blockEntityAdded(BlockEntity, boolean);
  public abstract void blockEntityRemoved(BlockPos, boolean);
  public void onRemoved(TrackGraph);
  public void setLocation(Couple<TrackNodeLocation>, double);
  public double getLocationOn(TrackEdge);
  public boolean canNavigateVia(TrackNode);
  public boolean isPrimary(TrackNode);
  public void read(CompoundTag, HolderLookup.Provider, boolean, DimensionPalette);
  public void read(FriendlyByteBuf, DimensionPalette);
  public void write(CompoundTag, HolderLookup.Provider, DimensionPalette);
  public void write(FriendlyByteBuf, DimensionPalette);
  public void tick(TrackGraph, boolean simulate);
}

public abstract class SingleBlockEntityEdgePoint extends TrackEdgePoint {
  public ResourceKey<Level> blockEntityDimension;
  public BlockPos blockEntityPos;
  public BlockPos getBlockEntityPos();
  public ResourceKey<Level> getBlockEntityDimension();
  // implements blockEntityAdded/Removed, invalidate, canMerge, read, write
}
```
`EdgePointStorage` **[V]** (the per-graph store): `put`, `get`, `remove`, `values(EdgePointType<T>)`, `getMap(EdgePointType<?>)`, `tick(TrackGraph, boolean)`, `transferAll(TrackGraph, EdgePointStorage)`, `write/read`.
`TrackGraph.getPoints(type)` / `addPoint(type, point)` / `removePoint(type, id)` are the public wrappers.

**To attach a new edge-point type to a track block**, use `com.simibubi.create.content.trains.track.TrackTargetingBehaviour<T extends TrackEdgePoint>` **[V]**:
```java
public class TrackTargetingBehaviour<T extends TrackEdgePoint> extends BlockEntityBehaviour {
  public static final BehaviourType<TrackTargetingBehaviour<?>> TYPE;
  public TrackTargetingBehaviour(SmartBlockEntity be, EdgePointType<T> edgePointType);
  public T getEdgePoint();
  public T createEdgePoint();
  public void invalidateEdgePoint(CompoundTag);
  public boolean hasValidTrack();
  public ITrackBlock getTrack();
  public BlockPos getGlobalPosition();
  public Direction$AxisDirection getTargetDirection();
  public BezierTrackPointLocation getTargetBezier();
  public TrackGraphLocation determineGraphLocation();
  public boolean isOnCurve(); public boolean isOrthogonal();
  public void tick(); public void destroy();
  // + static render(...) for the placement overlay
}
```
This is exactly what `SignalBlockEntity`, `StationBlockEntity`, `TrackObserverBlockEntity` and railways' `TrackSwitchBlockEntity` all expose as `public TrackTargetingBehaviour<...> edgePoint`.

### 4.5 Track switches (道岔) — **they are NOT part of Create** **[V]**
Confirmed by a full Create-jar entry scan: Create 6.0.10 has **no** `TrackSwitch*` class. The only "switch" in Create is the unrelated redstone `ThresholdSwitchBlock` (`content/redstone/thresholdSwitch/`).

Switches are provided by **Create: Railways** — `railways-0.3.0-alpha.2+neoforge-mc1.21.1.jar`, which *is* Create-6 compatible:
`META-INF/neoforge.mods.toml`: `modId="railways"`, `version="0.3.0-alpha.2+neoforge-mc1.21.1"`, requires `neoforge [21.1.233,)`, `create [6.0.7,)`, `minecraft [1.21.1]`. **[V]**
`create_connected`, `create_train_parts`, `simurail`, `trainutilities` contain **no** switch classes. **[V]**

Railways switch API **[V]** — `com.railwayteam.railways.content.switches`:
```java
public abstract class TrackSwitchBlock extends HorizontalDirectionalBlock
        implements IBE<TrackSwitchBlockEntity>, IWrenchable {
  public static final BooleanProperty LOCKED;
  public static TrackSwitchBlock manual(BlockBehaviour$Properties);
  public static TrackSwitchBlock automatic(BlockBehaviour$Properties);
  public Class<TrackSwitchBlockEntity> getBlockEntityClass();
  public BlockEntityType<? extends TrackSwitchBlockEntity> getBlockEntityType();
  public int getAnalogOutputSignal(BlockState, Level, BlockPos);
}

public class TrackSwitchBlockEntity extends SmartBlockEntity
        implements TransformableBlockEntity, IHaveGoggleInformation {
  public TrackTargetingBehaviour<TrackSwitch> edgePoint;         // *** graph link ***
  public TrackSwitch getSwitch();
  public TrackSwitchBlock$SwitchState getState();
  public boolean isAutomatic(); public boolean isLocked();
  public boolean isNormal(); public boolean isReverseLeft(); public boolean isReverseRight();
  public boolean hasExit(TrackSwitchBlock$SwitchState);
  public boolean addToGoggleTooltip(List<Component>, boolean);
  public int getTargetAnalogOutput();
  void cycleState();                                            // package-private
  boolean cycleState(TrackSwitchBlock$SwitchConstraint);         // package-private
  net.minecraft.world.InteractionResult onUse(boolean);          // package-private
}

// *** the graph-side object: this is what a dispatcher should manipulate ***
public class TrackSwitch extends SingleBlockEntityEdgePoint {
  public static int getSelectionPriority();
  public boolean isAutomatic(); public boolean isLocked();
  public TrackSwitchBlock$SwitchState getSwitchState();
  public boolean setSwitchState(TrackSwitchBlock$SwitchState);       // *** PUBLIC server-side setter ***
  public boolean trySetSwitchState(TrackSwitchBlock$SwitchState);    // *** PUBLIC validated setter ***
  public TrackSwitchBlock$SwitchState getTargetState(TrackNodeLocation from);
  public EdgePointType<?> getType();                                 // -> CREdgePointTypes.SWITCH
  public TrackNodeLocation getSwitchTarget();
  public boolean hasStraightExit(); public boolean hasLeftExit(); public boolean hasRightExit();
  public void updateEdges(TrackGraph);                               // *** applies routing ***
  public void setEdgesActive(TrackGraph);
  public void tick(TrackGraph, boolean simulate);
  public boolean canCoexistWith(EdgePointType<?>, boolean);
  public void blockEntityAdded(BlockEntity, boolean);
  public void onRemoved(TrackGraph);
  // write/read NBT + buf
}

public final class TrackSwitchBlock$SwitchState extends Enum<TrackSwitchBlock$SwitchState>
        implements StringRepresentable {
  public static final SwitchState NORMAL, REVERSE_LEFT, REVERSE_RIGHT;
  public static SwitchState fromSteerDirection(TravellingPoint$SteerDirection, boolean);
  public boolean canSwitchTo(SwitchState, TrackSwitchBlock$SwitchConstraint);
  public SwitchState nextStateFor(TrackSwitch, TrackSwitchBlock$SwitchConstraint);
}
```
Registration: `com.railwayteam.railways.registry.CREdgePointTypes` **[V]**
```java
public static final EdgePointType<TrackCoupler> COUPLER;
public static final EdgePointType<TrackSwitch>  SWITCH;
public static final EdgePointType<TrackBuffer>  BUFFER;
public static void register();     // -> EdgePointType.register(Railways.asResource("switch"), TrackSwitch::new)
```

**How the switch actually redirects trains [V/I]:** railways adds a *mix-in interface* onto Create's edge data:
```java
public interface com.railwayteam.railways.mixin_interfaces.ISwitchDisabledEdge {
  void setEnabled(boolean); boolean isEnabled();
  void setAutomatic(boolean); boolean isAutomatic();
  void setAutomaticallySelected(); int getAutomaticallySelectedPriority(); boolean isAutomaticallySelected();
  void ackAutomaticSelection();
  public static boolean isEnabled(TrackEdge edge);
  public static boolean isDisabled(TrackEdge edge);
  public static boolean isAutomatic(TrackEdge edge);
  public static void automaticallySelect(TrackEdge edge);
}
```
`TrackSwitch.updateEdges(TrackGraph)` / `setEdgesActive(TrackGraph)` enable the edges belonging to the selected exit and disable the others; `TravellingPoint`'s track selection then cannot pick a disabled edge. **[I]** — the exact injection target is in `railways-common.mixins.json` / `railways.mixins.json`, which I did not fully enumerate.

**Enumerating switches** **[V]**:
- per position: `((TrackSwitchBlockEntity) level.getBlockEntity(pos)).getSwitch()` → `TrackSwitch`
- per graph: `trackGraph.getPoints(CREdgePointTypes.SWITCH)` → `Collection<TrackSwitch>` (this is the generic `TrackGraph.getPoints(EdgePointType<T>)` API)
- per graph UUID for all edge points: `EdgePointStorage.getMap(type)`
- client HUD: `com.railwayteam.railways.content.switches.TrainHUDSwitchExtension`; sync packet: `com.railwayteam.railways.util.packet.SwitchDataUpdatePacket(SwitchState, automatic, isWrong, isLocked)`

> **Caution:** railways' switch type is registered under railways' own namespace and its `TrackSwitch` class only exists when railways is installed. If raildispatch must work *without* railways, use your own `EdgePointType` and implement the edge-enable semantics yourself (which requires a mixin into `TravellingPoint`/`EdgeData` unless you go through `TrackEdge.canTravelTo` or `Navigation.control`).

### 4.6 Other graph API **[V]**
- `TrackNodeLocation extends Vec3i`: `public ResourceKey<Level> dimension; public int yOffsetPixels;` ctors from `Vec3`/doubles; `in(Level)`/`in(ResourceKey<Level>)`, `getLocation()`, `getDimension()`, `equalsIgnoreDim(Object)`, `write/read(DimensionPalette)`, `send/receive(FriendlyByteBuf, DimensionPalette)`, `allAdjacent()`.
- `TrackEdgeIntersection`: `public double location; public Couple<TrackNodeLocation> target; public double targetLocation; public UUID groupId; public UUID id;`
- `TrackGraphLocation`, `TrackGraphBounds`, `TrackGraphSync`, `TrackGraphPacket`, `TrackGraphRequestPacket`, `TrackGraphRollCallPacket`, `DiscoverPath`-related classes exist; `TrackGraphSync` is the client sync channel.
- **`com.simibubi.create.api.event.TrackGraphMergeEvent extends net.neoforged.bus.api.Event`** **[V]** — public getters `getGraphMergedInto()` / `getGraphMergedFrom()`. A NeoForge event an addon can subscribe to for graph-topology changes.

---

## 5. Stations

### 5.1 `GlobalStation` **[V]** — `com.simibubi.create.content.trains.station.GlobalStation`
```java
public class GlobalStation extends SingleBlockEntityEdgePoint {
  public String name;                                              // *** the station name ***
  public WeakReference<Train> nearestTrain;
  public boolean assembling;
  public Map<BlockPos, GlobalPackagePort> connectedPorts;
  public GlobalStation();

  public boolean canApproachFrom(TrackNode);
  public boolean canNavigateVia(TrackNode);
  public void reserveFor(Train);
  public void cancelReservation(Train);
  public void trainDeparted(Train);
  public Train getPresentTrain();
  public Train getImminentTrain();
  public Train getNearestTrain();
  public void runMailTransfer();
  // read/write NBT + FriendlyByteBuf
}
```

### 5.2 `StationBlockEntity` **[V]**
```java
public class StationBlockEntity extends SmartBlockEntity implements TransformableBlockEntity {
  public TrackTargetingBehaviour<GlobalStation> edgePoint;         // *** the graph link ***
  public DoorControlBehaviour doorControls;
  public LerpedFloat flag;
  public static WorldAttached<Map<BlockPos, BoundingBox>> assemblyAreas;
  public Component lastDisassembledTrainName;
  public int lastDisassembledMapColorIndex;

  public GlobalStation getStation();
  public boolean updateName(String name);
  public boolean enterAssemblyMode(ServerPlayer);
  public boolean exitAssemblyMode();
  public boolean tryEnterAssemblyMode();
  public void assemble(UUID playerUUID);                            // *** creates the Train ***
  public void cancelAssembly();
  public boolean tryDisassembleTrain(ServerPlayer);
  public boolean isAssembling();
  public void dropSchedule(ServerPlayer, Train);
  public void updateMapColor(int);
  public void refreshAssemblyInfo();
  public boolean isValidBogeyOffset(int);
  public Direction getAssemblyDirection();
  public ItemStack getAutoSchedule();
  public void attachPackagePort(PackagePortBlockEntity);
  public void removePackagePort(PackagePortBlockEntity);
  public void lazyTick(); public void tick();
  public boolean trackClicked(Player, InteractionHand, ITrackBlock, BlockState, BlockPos);
  public void remove(); public void invalidate();
}
```
`StationBlock` **[V]**: `public static final BooleanProperty ASSEMBLING`; `protected void displayScreen(StationBlockEntity, Player)`; opens `StationScreen` via `AbstractStationScreen`.

### 5.3 Station lookup by name — **no global map** **[V]**
`StationBlockEntity.updateName(String)` **[V]** bytecode:
```java
if (!updateStationState(station -> station.name = newName)) return false;
notifyUpdate();
return true;
```
`updateStationState` is `private boolean updateStationState(Consumer<GlobalStation>)`. There is **no** uniqueness check and **no** global name→station map anywhere in Create. `GlobalRailwayManager` has only `trackNetworks`, `signalEdgeGroups`, `trains` (no stations map).

Therefore "find a station by name" is always **per-graph enumeration**:
```java
for (GlobalStation s : train.graph.getPoints(EdgePointType.STATION))
    if (s.name.matches(regex)) { ... }
```
This is exactly what `DestinationInstruction.start` does (§1.5). **[V]**
Uniqueness of station names is *not* enforced by Create — duplicate names will both match. **[V]**

### 5.4 Station-related packets **[V]**
```java
public class StationEditPacket extends BlockEntityConfigurationPacket<StationBlockEntity> {
  public static final StreamCodec<ByteBuf, StationEditPacket> STREAM_CODEC;
  public static StationEditPacket dropSchedule(BlockPos);
  public static StationEditPacket tryAssemble(BlockPos);
  public static StationEditPacket tryDisassemble(BlockPos);
  public static StationEditPacket configure(BlockPos, boolean assemblyMode, String name, DoorControl);
  protected void applySettings(ServerPlayer, StationBlockEntity);
}
// registered as AllPackets.CONFIGURE_STATION

public abstract class TrainEditPacket implements CustomPacketPayload {
  protected final UUID id; protected final String name;
  protected final ResourceLocation iconType; protected final int mapColor;
  public void handleSided(Player);
  // nested: TrainEditPacket$Serverbound  (AllPackets.C_CONFIGURE_TRAIN)
  //         TrainEditPacket$TrainEditReturnPacket
}
```
`TrainEditPacket.handleSided` bytecode **[V]** — this is the **train rename/address precedent**:
```java
Train t = Create.RAILWAYS.sided(player.level()).trains.get(this.id);
if (t == null) return;
if (!this.name.isBlank()) t.name = Component.literal(this.name);
t.icon = TrainIconType.byId(this.iconType);
t.mapColorIndex = this.mapColor;
CatnipServices.NETWORK.sendToAll(new TrainEditPacket$TrainEditReturnPacket(id, name, iconType, mapColor));
```

### 5.5 An existing "address" concept: `GlobalPackagePort.address` **[V]**
`com.simibubi.create.content.trains.station.GlobalPackagePort` has `public String address;` (package-logistics addressing), and `AllDataComponents.PACKAGE_ADDRESS` is a `DataComponentType<String>`. This is Create's own precedent for string addressing — but it is scoped to package ports, not trains.

**Strongest prior art for a rail "address tag" is createrailwaysnavigator** (§8.3) with `de.mrjulsen.crn.data.StationTag`.

---

## 6. Editing a train's schedule from code / GUI

### 6.1 The schedule menu is bound to an **`ItemStack`**, not a `Train` **[V]**
```java
public class ScheduleItem extends Item implements MenuProvider, ItemCopyingRecipe$SupportsItemCopying {
  public ScheduleItem(Item$Properties);
  public InteractionResult useOn(UseOnContext);                       // delegates to use(...)
  public InteractionResultHolder<ItemStack> use(Level, Player, InteractionHand);
  public InteractionResult handScheduleTo(ItemStack, Player, LivingEntity, InteractionHand);
  public void appendHoverText(ItemStack, Item$TooltipContext, List<Component>, TooltipFlag);
  public static Schedule getSchedule(HolderLookup.Provider, ItemStack);
  public AbstractContainerMenu createMenu(int, Inventory, Player);
  public Component getDisplayName();
  public DataComponentType<?> getComponentType();                     // -> AllDataComponents.TRAIN_SCHEDULE
}
```
- `use(...)` opens the menu with `player.openMenu(this, buf -> ...)` — i.e. the **item** is the `MenuProvider`; the schedule travels in `AllDataComponents.TRAIN_SCHEDULE` (`DataComponentType<CompoundTag>`, the NBT from `Schedule#write`). **[V]**
- `handScheduleTo(...)` bytecode **[V]** — **this is how Create installs a schedule onto a train**:
  ```java
  Schedule s = ScheduleItem.getSchedule(player.registryAccess(), stack);
  // walk player.getRootVehicle() -> CarriageContraptionEntity -> getCarriage().train
  // reject if not a conductor seat / no conductor
  if (!train.runtime.getSchedule().entries.isEmpty()) { deny(); return SUCCESS; }   // must be empty
  train.runtime.setSchedule(s, /* isAutoSchedule = */ true);
  AllAdvancements.CONDUCTOR.awardTo(player);
  stack.shrink(1);
  ```
  So the addon-level one-liner to give a train a schedule is:
  ```java
  train.runtime.setSchedule(schedule, true);
  ```

```java
public class ScheduleMenu extends HeldItemGhostItemMenu          // GhostItemMenu<ItemStack>
        implements (via GhostItemMenu) IClearableMenu {
  public boolean slotsActive;
  public int targetSlotsActive;
  public ScheduleMenu(MenuType<?>, int id, Inventory, RegistryFriendlyByteBuf);
  public ScheduleMenu(MenuType<?>, int id, Inventory, ItemStack);
  protected ItemStackHandler createGhostInventory();
  protected boolean allowRepeats();
  protected void addSlots();
  protected Slot createPlayerSlot(Inventory, int, int, int);
  protected void saveData(ItemStack);
}
public class ScheduleScreen extends AbstractSimiContainerScreen<ScheduleMenu> { ... }
```
Class chain **[V]**: `ScheduleMenu` → `HeldItemGhostItemMenu` → `GhostItemMenu<ItemStack>` → `MenuBase<ItemStack>` → `AbstractContainerMenu`. `ScheduleScreen` → `AbstractSimiContainerScreen<ScheduleMenu>` → `AbstractContainerScreen<ScheduleMenu>`.
Registered as `AllMenuTypes.SCHEDULE : MenuEntry<ScheduleMenu>`. **[V]**

### 6.2 `ScheduleEditPacket` — writes to the **held item only** **[V]**
```java
public final class ScheduleEditPacket extends Record implements ServerboundPacketPayload {
  private final Schedule schedule;
  public static final StreamCodec<RegistryFriendlyByteBuf, ScheduleEditPacket> STREAM_CODEC;
  public ScheduleEditPacket(Schedule);
  public void handle(ServerPlayer player);
  public PacketTypeProvider getTypeProvider();     // -> AllPackets.CONFIGURE_SCHEDULE
  public Schedule schedule();
}
```
`handle` bytecode **[V]**:
```java
ItemStack stack = player.getMainHandItem();
if (!AllItems.SCHEDULE.isIn(stack)) return;
if (schedule.entries.isEmpty()) stack.remove(AllDataComponents.TRAIN_SCHEDULE);
else                            stack.set(AllDataComponents.TRAIN_SCHEDULE,
                                              schedule.write(player.registryAccess()));
player.getCooldowns().addCooldown(stack.getItem(), 5);
```
**There is no `ConfigureRoutePacket` in Create 6.0.10.** **[V]** The packet names are exactly: `ScheduleEditPacket` (all packets enum name `CONFIGURE_SCHEDULE`), `StationEditPacket` (`CONFIGURE_STATION`), `TrainEditPacket$Serverbound` (`C_CONFIGURE_TRAIN`).

### 6.3 Packet registration — `AllPackets` is a **closed enum** **[V]**
```java
public final class AllPackets extends Enum<AllPackets> implements BasePacketPayload$PacketTypeProvider {
  public static final AllPackets CONFIGURE_SCHEDULE, CONFIGURE_STATION,
                               C_CONFIGURE_TRAIN, S_CONFIGURE_TRAIN,
                               SYNC_RAIL_GRAPH, SYNC_EDGE_GROUP, ADD_TRAIN, REMOVE_TRAIN,
                               RELOCATE_TRAIN, TRAIN_COLLISION, C_TRAIN_HUD, S_TRAIN_HUD,
                               C_TRAIN_HONK, S_TRAIN_HONK, S_TRAIN_PROMPT, TRACK_GRAPH_REQUEST,
                               TRACK_GRAPH_ROLL_CALL, TRAIN_MAP_REQUEST, TRAIN_MAP_SYNC, ...;
  private <T extends BasePacketPayload> AllPackets(Class<T>, StreamCodec<? super RegistryFriendlyByteBuf, T>);
  public static void register();
}
```
An addon **cannot** add an enum constant. Use Create's underlying library instead:
```java
public class net.createmod.catnip.net.base.CatnipPacketRegistry {
  public final String modId; public final String networkVersion;
  public final Set<CatnipPacketRegistry$PacketType<?>> packetsView;
  public CatnipPacketRegistry(String modId, int networkVersion);
  public CatnipPacketRegistry(String modId, String networkVersion);
  public void registerPacket(CatnipPacketRegistry$PacketType<?>);
  public void registerAllPackets();
}
public final class CatnipPacketRegistry$PacketType<T extends BasePacketPayload> extends Record {
  PacketType(CustomPacketPayload$Type<T> type, Class<T> clazz,
             StreamCodec<? super RegistryFriendlyByteBuf, T> codec);
}
public interface net.createmod.catnip.net.base.BasePacketPayload extends CustomPacketPayload {
  BasePacketPayload$PacketTypeProvider getTypeProvider();
  default CustomPacketPayload$Type<? extends CustomPacketPayload> type();
}
// plus ServerboundPacketPayload (handle(ServerPlayer)) and ClientboundPacketPayload (handle(LocalPlayer))
```
`net.createmod.catnip` ships inside the `ponder` jar. **[V]**

---

## 7. Registration hooks Create provides to addons

### 7.1 `CreateRegistrate` — the main addon entry point **[V]**
```java
public class com.simibubi.create.foundation.data.CreateRegistrate
        extends com.tterrag.registrate.AbstractRegistrate<CreateRegistrate> {
  protected CreateRegistrate(String modId);
  public static CreateRegistrate create(String modId);                 // *** public factory ***
  public CreateRegistrate setTooltipModifierFactory(Function<Item, TooltipModifier>);
  public CreateRegistrate setCreativeTab(DeferredHolder<CreativeModeTab, CreativeModeTab>);
  public CreateRegistrate registerEventListeners(IEventBus);

  public <T extends BlockEntity> CreateBlockEntityBuilder<T, CreateRegistrate> blockEntity(
      String name, BlockEntityBuilder$BlockEntityFactory<T>);
  public <T extends BlockEntity, P> CreateBlockEntityBuilder<T, P> blockEntity(
      P parent, String name, BlockEntityBuilder$BlockEntityFactory<T>);
  public <T extends Entity> CreateEntityBuilder<T, CreateRegistrate> entity(
      String name, EntityType$EntityFactory<T>, MobCategory);
  public <T extends MountedItemStorageType<?>> SimpleBuilder<...> mountedItemStorage(String, Supplier<T>);
  public <T extends MountedFluidStorageType<?>> SimpleBuilder<...> mountedFluidStorage(String, Supplier<T>);
  public <T extends DisplaySource> SimpleBuilder<DisplaySource, T, CreateRegistrate> displaySource(String, Supplier<T>);
  public <T extends DisplayTarget> SimpleBuilder<DisplayTarget, T, CreateRegistrate> displayTarget(String, Supplier<T>);
  public FluidBuilder<...> standardFluid(String[, FluidTypeFactory]);
  public FluidBuilder<...> virtualFluid(String[, ...]);
  public static boolean isInCreativeTab(RegistryEntry<?,?>, DeferredHolder<CreativeModeTab, CreativeModeTab>);
  public static NonNullConsumer<? super T> casingConnectivity(BiConsumer<T, CasingConnectivity>);
  public static NonNullConsumer<? super T> blockModel(Supplier<NonNullFunction<BakedModel, ? extends BakedModel>>);
  public static NonNullConsumer<? super T> itemModel(Supplier<NonNullFunction<BakedModel, ? extends BakedModel>>);
  public static NonNullConsumer<? super Block> connectedTextures(Supplier<ConnectedTextureBehaviour>);
}
```
Access Create's own instance: **`Create.registrate()`** (`Create.REGISTRATE` is `private`) — **[V]**. Prefer your *own* `CreateRegistrate.create("raildispatch")` for your blocks/items so ids land in your namespace.

Inherited from `AbstractRegistrate` (Registrate 1.3.0, jarjar'd) **[V]**:
```java
public <R,T extends R> NoConfigBuilder<R,T,S> generic(String name, ResourceKey<Registry<R>>, NonNullSupplier<T>);
public <T extends Item> ItemBuilder<T,S> item(String name, NonNullFunction<Item$Properties,T>);
public <T extends Block> BlockBuilder<T,S> block(String name, NonNullFunction<BlockBehaviour$Properties,T>);
public <T extends BlockEntity> BlockEntityBuilder<T,S> blockEntity(String name, BlockEntity$BlockEntityFactory<T>);
public <T extends Entity> EntityBuilder<T,S> entity(String name, EntityType$EntityFactory<T>, MobCategory);
public <T extends AbstractContainerMenu, SC extends Screen & MenuAccess<T>>
       MenuBuilder<T,SC,S> menu(String name, MenuBuilder$ForgeMenuFactory<T>,
                               NonNullSupplier<MenuBuilder$ScreenFactory<T,SC>>);
```

### 7.2 `SimpleRegistry` — Create's generic "associate X with block/be" registry **[V]**
```java
public interface com.simibubi.create.api.registry.SimpleRegistry<K, V> {
  void register(K key, V value);
  void registerProvider(SimpleRegistry$Provider<K,V>);
  void invalidate();
  V get(K);
  V get(StateHolder<K, ?>);
  static <K,V> SimpleRegistry<K,V> create();
  interface Multi<K,V> extends SimpleRegistry<K, List<V>> {
    void add(K, V); void addProvider(SimpleRegistry$Provider<K,V>);
    List<V> get(K); List<V> get(StateHolder<K,?>);
    static <K,V> Multi<K,V> create();
  }
}
```
`SimpleBuilder<R,T,P>` (**[V]**) is the Registrate-side helper: `.byBlock(SimpleRegistry)`, `.byBlock(SimpleRegistry$Multi)`, `.byBlockEntity(...)`, `.byEntity(...)`, `.byFluid(...)`, `.associate(Block)`, `.associateBlockTag(TagKey)`, `.associate(BlockEntityType)`, `.associateBeTag(TagKey)`, `.associate(EntityType)`, `.associateEntityTag(TagKey)`.

### 7.3 Create's own registries (read-only from an addon) **[V]**
| Class | Shape | Addon-writable? |
|---|---|---|
| `com.simibubi.create.AllBlocks` | `BlockEntry<T>` constants: `TRACK`, `FAKE_TRACK`, `TRACK_STATION`, `TRACK_SIGNAL`, `TRACK_OBSERVER`, `SMALL_BOGEY`, `LARGE_BOGEY`, `DISPLAY_LINK`, `DISPLAY_BOARD` | No |
| `com.simibubi.create.AllItems` | `ItemEntry`: `SCHEDULE`, … | No |
| `com.simibubi.create.AllBlockEntityTypes` | `BlockEntityEntry<T>`: `TRACK`, `FAKE_TRACK`, `TRACK_STATION`, `FLAP_DISPLAY`, `TRACK_SIGNAL`, `TRACK_OBSERVER`, `DISPLAY_LINK` | No |
| `com.simibubi.create.AllMenuTypes` | `MenuEntry<C>`: `SCHEDULE`, `FILTER`, `TOOLBOX`, … ; `private static register(String, ForgeMenuFactory<C>, NonNullSupplier<ScreenFactory<C,S>>)` | No |
| `com.simibubi.create.AllPackets` | closed **enum** | No |
| `com.simibubi.create.AllDataComponents` | `DataComponentType<T>`: `TRAIN_SCHEDULE : DataComponentType<CompoundTag>`, `PACKAGE_ADDRESS : DataComponentType<String>`, `TRACK_TARGETING_ITEM_*`, … | No |
| `com.simibubi.create.AllDisplaySources` / `AllDisplayTargets` | `RegistryEntry<DisplaySource/Target, T>` in the `CreateRegistries.DISPLAY_SOURCE/DISPLAY_TARGET` registries | No (register your own into the same registry) |
| `com.simibubi.create.AllBogeyStyles` | `public static final Map<ResourceLocation, BogeyStyle> BOGEY_STYLES; public static final Map<ResourceLocation, Map<ResourceLocation, BogeyStyle>> CYCLE_GROUPS; public static Map<ResourceLocation, BogeyStyle> getCycleGroup(ResourceLocation); public static void init();` | **Map is public static but the class only calls `init()`; registration is by putting into `BOGEY_STYLES`** **[I]** |
| `com.simibubi.create.AllMovementBehaviours` / `AllInteractionBehaviours` | only `static void registerDefaults()` (package-private) — registration goes through `MovementBehaviour.REGISTRY` / `MovingInteractionBehaviour.REGISTRY` **[I]** | via the API classes |
| `com.simibubi.create.content.trains.entity.TrainIconType` | `public static Map<ResourceLocation, TrainIconType> REGISTRY; public static void register(ResourceLocation id, ResourceLocation sheet, int x, int y);` | **Yes — public register()** |

Create's **new-style** registries (`com.simibubi.create.api.registry.CreateRegistries` / `CreateBuiltInRegistries`) **[V]**:
```java
CreateRegistries.DISPLAY_SOURCE      : ResourceKey<Registry<DisplaySource>>
CreateRegistries.DISPLAY_TARGET      : ResourceKey<Registry<DisplayTarget>>
CreateRegistries.ARM_INTERACTION_POINT_TYPE, FAN_PROCESSING_TYPE, ITEM_ATTRIBUTE_TYPE,
CreateRegistries.MOUNTED_ITEM_STORAGE_TYPE, MOUNTED_FLUID_STORAGE_TYPE, CONTRAPTION_TYPE,
CreateRegistries.PACKAGE_PORT_TARGET_TYPE, POTATO_PROJECTILE_TYPE, POTATO_PROJECTILE_RENDER_MODE,
CreateRegistries.POTATO_PROJECTILE_ENTITY_HIT_ACTION, POTATO_PROJECTILE_BLOCK_HIT_ACTION
CreateBuiltInRegistries.<same names>   // as net.minecraft.core.Registry instances
CreateBuiltInRegistries.init()
```
`DisplaySource` / `DisplayTarget` **[V]**:
```java
public abstract class DisplaySource {
  public static final SimpleRegistry$Multi<Block, DisplaySource> BY_BLOCK;
  public static final SimpleRegistry$Multi<BlockEntityType<?>, DisplaySource> BY_BLOCK_ENTITY;
  public abstract List<MutableComponent> provideText(DisplayLinkContext, DisplayTargetStats);
  public void transferData(DisplayLinkContext, DisplayTarget, int);
  public void onSignalReset(DisplayLinkContext); public void populateData(DisplayLinkContext);
  public int getPassiveRefreshTicks(); public boolean shouldPassiveReset();
  protected final ResourceLocation getId(); protected String getTranslationKey();
  public Component getName();
  public void loadFlapDisplayLayout(DisplayLinkContext, FlapDisplayBlockEntity, FlapDisplayLayout[, int]);
  public List<List<MutableComponent>> provideFlapDisplayText(DisplayLinkContext, DisplayTargetStats);
  public void initConfigurationWidgets(DisplayLinkContext, ModularGuiLineBuilder, boolean);
  public static NonNullUnaryOperator<BlockBuilder<B,P>> displaySource(RegistryEntry<DisplaySource, ? extends DisplaySource>);
  public static DisplaySource get(ResourceLocation);
  public static List<DisplaySource> getAll(LevelAccessor, BlockPos);
}
public abstract class DisplayTarget {
  public static final SimpleRegistry<Block, DisplayTarget> BY_BLOCK;
  public static final SimpleRegistry<BlockEntityType<?>, DisplayTarget> BY_BLOCK_ENTITY;
  public abstract void acceptText(int line, List<MutableComponent>, DisplayLinkContext);
  public abstract DisplayTargetStats provideStats(DisplayLinkContext);
  public AABB getMultiblockBounds(LevelAccessor, BlockPos);
  public Component getLineOptionText(int);
  public static void reserve(int, BlockEntity, DisplayLinkContext);
  public boolean isReserved(int, BlockEntity, DisplayLinkContext);
  public static NonNullUnaryOperator<BlockBuilder<B,P>> displayTarget(RegistryEntry<DisplayTarget, ? extends DisplayTarget>);
  public static DisplayTarget get(ResourceLocation[, LevelAccessor, BlockPos]);
}
```
Create has a train-display source precedent: `content/redstone/displayLink/source/TrainStatusDisplaySource` (registered as `AllDisplaySources.TRAIN_STATUS`) and `ObservedTrainNameSource` (`OBSERVED_TRAIN_NAME`), plus `StationSummaryDisplaySource` (`STATION_SUMMARY`). **There is no `TrainDisplayRegistry` class** in Create 6.0.10 — **[V]** (entry scan).

### 7.4 Ponder **[V]**
```java
public interface net.createmod.ponder.api.registration.PonderPlugin {
  String getModId();
  default void registerScenes(PonderSceneRegistrationHelper<ResourceLocation>);
  default void registerTags(PonderTagRegistrationHelper<ResourceLocation>);
  default void registerSharedText(SharedTextRegistrationHelper);
  default void onPonderLevelRestore(PonderLevel);
  default void indexExclusions(IndexExclusionHelper);
}
public class net.createmod.ponder.foundation.PonderIndex {
  public static void addPlugin(PonderPlugin);
  public static void forEachPlugin(Consumer<PonderPlugin>);
  public static Stream<PonderPlugin> streamPlugins();
  public static void reload(); public static void registerAll();
  public static SceneRegistryAccess getSceneAccess();
  public static TagRegistryAccess  getTagAccess();
  public static LangRegistryAccess getLangAccess();
  public static boolean editingModeActive();
}
```
Register with `PonderIndex.addPlugin(...)` at client init. Requires a `ponder` mod dependency in `neoforge.mods.toml` (`[1.0.82,)`). Create's own scenes live in `com.simibubi.create.infrastructure.ponder.AllCreatePonderScenes` / `AllCreatePonderTags` (read-only). **[V]**

### 7.5 Registrate registration callback **[V]**
```java
public class com.simibubi.create.api.registrate.CreateRegistrateRegistrationCallback {
  public static <R, T extends R> void register(ResourceKey<? extends Registry<R>>,
                                               ResourceLocation, NonNullConsumer<? super T>);
  public static void provideRegistrate(CreateRegistrate);
}
```

### 7.6 What an addon must register where — summary
| Goal | Required registration |
|---|---|
| **New schedule instruction type** | `Schedule.INSTRUCTION_TYPES.add(Pair.of(ResourceLocation, MyInstruction::new))` during common setup. No mixin, no Create API change. **Proven in production by railways + CRN** (§8). |
| **New schedule wait condition** | `Schedule.CONDITION_TYPES.add(Pair.of(ResourceLocation, MyCondition::new))`. |
| **New block** | `CreateRegistrate.create("raildispatch").block(name, MyBlock::new)...register()` (or plain NeoForge `DeferredRegister.Blocks`). |
| **New block entity** | `registrate.blockEntity(name, MyBE::new).validBlock(...).register()` → `CreateBlockEntityBuilder<T, CreateRegistrate>`; plus a `BlockEntityType` for the ticker/renderer. |
| **New menu + screen** | `registrate.menu(name, ForgeMenuFactory<T>, NonNullSupplier<ScreenFactory<T,SC>>)`. Create's `AllMenuTypes` is closed. |
| **New train display source** | `registrate.displaySource(name, MySource::new).byBlock(...)/.byBlockEntity(...).register()` → registers in `CreateRegistries.DISPLAY_SOURCE` **and** `DisplaySource.BY_BLOCK/BY_BLOCK_ENTITY`. |
| **New edge-point type (signal/switch/address marker)** | `EdgePointType.register(ResourceLocation, Supplier<T>)`, then `TrackTargetingBehaviour<MyPoint>` on your block entity, then `TrackGraph.addPoint(...)` / `createEdgePoint()`. |
| **New network packet** | `new CatnipPacketRegistry("raildispatch", 1)` + `registerPacket(new PacketType<>(type, clazz, codec))` + `registerAllPackets()`. Cannot extend `AllPackets`. |
| **New bogey style** | `AllBogeyStyles.BOGEY_STYLES.put(id, style)` + `CYCLE_GROUPS`; or `AllBogeyStyles.init()` ordering. **[I]** |
| **Movement behaviour on contraptions** | `com.simibubi.create.api.behaviour.movement.MovementBehaviour` registry (see trainutilities' `TrainUtilitiesBuilderTransformers` usage). |
| **Ponder scenes** | `PonderIndex.addPlugin(new MyPonderPlugin())` at client init. |
| **NeoForge-side** | `IEventBus`, `DeferredRegister` for anything Create has no helper for; `Create.RAILWAYS` / `RailwaySavedData` are Create-owned and **not** extensible except by mixin (which is what CRN does, §8.3). |

---

## 8. Precedents: what real addons actually do

### 8.1 `trainutilities-neoforge-3.0.3.jar` — **NOT a schedule/addressing mod** **[V]**
- **mods.toml**: `modId = "trainutilities"`, `version = "3.0.3"`, `displayName = "Create Train Utilities"`, `authors = "ProgrammerLP"`.
  Dependencies: `neoforge [21.1,)`, `minecraft [1.21.1,)`, `architectury [13.0.8,)`, `dragonlib [beta-3.0.23,)`, **`create [6.0.7,]`** (note: *closed* upper bound `]`).
  Mixin config: `trainutilities.mixins.json`, single mixin `net.adeptstack.mixin.ContraptionControlsMovingInteractionMixin`.
- **Actual content** (description + class inventory + data files): custom **sliding train doors** with opening/closing sounds, **platform blocks** (NL/DE/CH/PKP/Budapest/CIS designs), `LineBlock`, `IsoWallBlock`, sound unit / speaker membrane / transistor items, door-sound config screens.
- **Complete list of Create internals it touches** (from `javap -c -p` constant pools of all 45 classes), ordered by reference count:

  | Create FQCN | Referencing trainutilities classes |
  |---|---|
  | `content.contraptions.behaviour.MovementContext` (102) | `DoorBlockMovementBehaviour`, `TrainSlidingDoorMovementBehaviour` |
  | `content.contraptions.Contraption` (57) | same two + `DoorBlockMovingInteraction`, `TrainSlidingDoorMovingInteraction` |
  | `foundation.data.CreateRegistrate` (38) | `Main`, `MainNeoForge`, `registry.ModBlockEntities`, `registry.ModItems`, `registry.TrainUtilitiesBuilderTransformers` |
  | `content.decoration.slidingDoor.DoorControlBehaviour` (30) | `DoorBlockMovementBehaviour`, `TrainSlidingDoorMovementBehaviour` |
  | `content.contraptions.AbstractContraptionEntity` (29) | same |
  | `content.contraptions.elevator.ElevatorContraption` (16), `ElevatorColumn` (6), `ElevatorColumn$ColumnCoords` (4) | same |
  | `content.decoration.slidingDoor.DoorControl` (16), `SlidingDoorBlock` (5), `SlidingDoorBlockEntity` (3) | same / `TrainSlidingDoorBlock`, `TrainSlidingDoorBlockEntity` |
  | `content.trains.entity.CarriageContraptionEntity` (13), `Carriage` (10), `Train` (8) | `DoorBlockMovementBehaviour`, `TrainSlidingDoorMovementBehaviour`, `mixin.ContraptionControlsMovingInteractionMixin` |
  | `foundation.data.TagGen` (10) | `registry.TrainUtilitiesBuilderTransformers` |
  | `foundation.blockEntity.behaviour.BlockEntityBehaviour` (8), `BehaviourType` (8), `foundation.blockEntity.SmartBlockEntity` (6) | `DoorBlockMovementBehaviour`, `TrainSlidingDoorMovementBehaviour` |
  | `content.trains.station.GlobalStation` (6) | same two (station-arrival door opening) |
  | `AllTags$AllItemTags` (6), `AllTags$AllBlockTags` (6) | `TrainUtilitiesBuilderTransformers` |
  | `foundation.item.TooltipModifier` (5), `ItemDescription$Modifier` (3), `KineticStats` (3) | `Main` |
  | `api.behaviour.movement.MovementBehaviour` (4), `api.behaviour.interaction.MovingInteractionBehaviour` (4) | `TrainUtilitiesBuilderTransformers` |
  | `content.contraptions.ContraptionWorld` (4) | movement behaviours |
  | `AllBlocks` (3), `foundation.data.CreateBlockEntityBuilder` (2) | `TrainUtilitiesBuilderTransformers`, `registry.ModBlockEntities` |
  | `content.contraptions.behaviour.SimpleBlockMovingInteraction` (2), `AllSoundEvents$SoundEntry` (2) | moving interactions, mixin |
  | `content.contraptions.actors.contraptionControls.ContraptionControlsBlockEntity` (1), `foundation.blockEntity.renderer.SafeBlockEntityRenderer` (1), `AllSoundEvents` (1) | mixin, `TrainSlidingDoorBlockRenderer` |

- **Answers to the explicit question:**
  - vehicle tags / address tags: **NO** (no class, no lang key, no data file).
  - coupling by tag: **NO**.
  - hump shunting (驼峰调车): **NO**.
  - schedule instruction registration: **NO** — it never references `com/simibubi/create/content/trains/schedule/*`.
  - custom schedule conditions: **NO**.
  - custom menus bound to trains: **NO** (it has `ChangeDoorSoundScreen`/platform placement screens bound to blocks/items).
  - It is a **decoration/contraption** addon. Useful only as an example of `CreateRegistrate` + `MovementBehaviour` + `DoorControlBehaviour` + `GlobalStation` (station-arrival door triggering) usage.

### 8.2 `railways-0.3.0-alpha.2` — schedule instructions + switches (the real precedent) **[V]**
`com.railwayteam.railways.registry.CRSchedule`:
```java
public class CRSchedule {
  private static void registerInstruction(String name, Supplier<? extends ScheduleInstruction>);
  private static void registerCondition  (String name, Supplier<? extends ScheduleWaitCondition>);
  public static void register();          // body is literally `return;` — all work is in <clinit>
}
```
`registerInstruction` bytecode (decompiled semantics):
```java
Schedule.INSTRUCTION_TYPES.add(Pair.of(Railways.asResource(name), supplier));
// registerCondition: Schedule.CONDITION_TYPES.add(Pair.of(Railways.asResource(name), supplier));
```
Custom types: `WaypointDestinationInstruction extends DestinationInstruction` (overrides `getId()`, `supportsConditions()`), `RedstoneLinkInstruction` (+`$CustomRedstoneActor`), `StationLoadedCondition`.
Custom graph feature: `CREdgePointTypes.COUPLER/SWITCH/BUFFER` via `EdgePointType.register(...)`.
Custom edge-state: `mixin_interfaces.ISwitchDisabledEdge` mixed onto Create's edge data.
Custom schedule UI/behaviour: mixins `MixinScheduleRuntime`, `AccessorScheduleRuntime`, `MixinScheduleItem`, interface `IIndexedSchedule`, plus NeoForge/Architectury multiloader plumbing.

### 8.3 `createrailwaysnavigator-…-beta-0.9.1-C6` — **closest prior art to an "address/tag" concept** **[V]**
`de.mrjulsen.crn.registry.ModSchedule` (same pattern as railways):
```
Schedule.INSTRUCTION_TYPES.add(Pair.of(new ResourceLocation("createrailwaysnavigator", <name>), supplier))
Schedule.CONDITION_TYPES.add(...)
instruction ids: travel_section, reset_timings, prioritized_destination_instruction
condition  ids: dynamic_delay, train_separation
```
Data model — a **named station tag**, exactly the "address" shape:
```java
public class de.mrjulsen.crn.data.StationTag {
  public static final int MAX_NAME_LENGTH;
  protected UUID id;
  protected TagName tagName;
  protected Map<String, StationTag$StationInfo> stations;   // *** station name -> info ***
  protected Lock owner; protected Owner lastEditor; protected long lastEditedTime;
  public StationTag(UUID, TagName, Owner[, Map<String, StationInfo>]);
  public CompoundTag toNbt();
  public static StationTag fromNbt(CompoundTag, UUID);
  public TagName getTagName();
  public void add(String stationName, StationTag$StationInfo);
  public void addAll(Map<String, StationTag$StationInfo>);
  public boolean contains(String stationName);
  // ... updateInfoForStation / copy / getOwner / getLastEditor / getLastEditedTime
}
public interface de.mrjulsen.crn.data.schedule.instruction.IStationTagInstruction
        extends ICustomSuggestionsInstruction { }
public class de.mrjulsen.crn.data.schedule.instruction.PrioritizedDestinationInstruction
        extends DestinationInstruction {
  public static final String NBT_FILTERS, NBT_AVOID_RED_SIGNAL, NBT_AVOID_TRAINS;
  public static final byte MAX_ENTRIES;
  public List<String> getFilters();
  public String getFilter();
  public boolean shouldAvoidRedSignals(); public boolean shouldAvoidTrains();
  public DiscoveredPath start(ScheduleRuntime, Level);   // picks among candidate stations by penalty
}
```
Related: `de.mrjulsen.crn.data.SavedRoutesManager` (client-side saved routes), `de.mrjulsen.crn.data.navigation.TrainSchedule`, `de.mrjulsen.crn.data.train.ScheduleSection`, `de.mrjulsen.crn.data.schedule.condition.TrainSeparationCondition`, `DynamicDelayCondition`, `IDelayedWaitCondition`.
Server-side persistence/sync is done by **mixins**, not Create APIs:
`de.mrjulsen.crn.mixin.GlobalRailwayManagerMixin` (injects `GlobalRailwayManager#tick`), `GlobalTrainDisplayDataMixin`, `TrainMixin`, `ScheduleRuntimeMixin`, `AccessorScheduleRuntime`, `ScheduleEntryMixin`, `ScheduleScreenMixin`, `ScheduleScreenAccessor`, `extensions.ScheduledDelayMixin`, `TrainPenaltyAccessor`, `TrainStatusAccessor`.

> **Take-away:** CRN proves that (a) new instruction/condition types register cleanly via the public lists with **no mixin**, and (b) anything that needs *extra server state persisted inside Create's saved data* (tags, routes) currently requires a **mixin into the schedule/train classes** — or the addon must keep its own `SavedData`/`GlobalRailwayManager`-independent store. `StationTag` itself is stored in a CRN-owned manager, not in Create's `RailwaySavedData`.

### 8.4 Other jars checked **[V]**
| Jar | Schedule refs | Switch refs | Notes |
|---|---|---|---|
| `create_train_parts-0.4.0-1.21.1-6.0.9-216.jar` | none | none | bogey/parts mod |
| `simurail-1.21.1-0.0.0 (1).jar` | none | none | |
| `create_connected-1.3.1-mc1.21.1.jar` | none | none | "Create: Connected" is not the switch mod for this pack |
| `create_bb-…jar` | none | `data/compat/steamnrails/MenuSwitchButton` only | unrelated UI switch |

---

## 9. Verified vs Inferred

### 9.1 VERIFIED (javap-printed signature, or javap bytecode of the exact class)
- Create 6.0.10 has **no** `TrackSwitchBlock`, no `TrackSignal`, no `ConfigureRoutePacket`, no `AllScheduleInstructions`, no `TrainDisplayRegistry` (full `jar tf` scan).
- `Schedule.INSTRUCTION_TYPES` / `Schedule.CONDITION_TYPES` are `public static` **`java.util.ArrayList`** — mutable, addon-writable.
- Create's own `registerInstruction`/`registerCondition` are `private static` and do `List.add(Pair.of(Create.asResource(path), supplier))`.
- `ScheduleInstruction.fromTag` / `ScheduleWaitCondition.fromTag` resolve by `"Id"` → `ResourceLocation.parse` → linear scan of the public lists → `Supplier.get()`; unknown id logs a warning and falls back to `new DestinationInstruction()`.
- **railways `CRSchedule`** and **CRN `ModSchedule`** both append directly to those public lists (bytecode read from both jars) — the pattern is production-proven.
- `DestinationInstruction.start` = `train.graph.getPoints(EdgePointType.STATION)` + `GlobalStation.name.matches(regex)` + `train.navigation.findPathTo(ArrayList, double)`.
- `Navigation.findPathTo(GlobalStation|ArrayList<GlobalStation>, double) -> DiscoveredPath` is public.
- `EdgePointType.register(ResourceLocation, Supplier<T>)` is public static; `EdgePointType.TYPES` is a public mutable `Map`.
- `TrackGraph.getConnectionsFrom(TrackNode) -> Map<TrackNode, TrackEdge>`, `getConnection(Couple<TrackNode>)`, `getPoints(EdgePointType<T>)`, `addPoint`, `getPoint`, `removePoint` are public.
- `EdgeData.getEffectiveEdgeGroupId(TrackGraph)`, `getGroupAtPosition(TrackGraph, double)`, `next(EdgePointType<T>, double)` are public.
- `SignalEdgeGroup.isOccupiedUnless(Train)` / `isOccupiedUnless(SignalBoundary)` are public; `SignalEdgeGroup.trains`, `.reserved`, `.color` are public fields.
- `Create.RAILWAYS` (public static `GlobalRailwayManager`); `GlobalRailwayManager.trains` / `trackNetworks` / `signalEdgeGroups` are public fields; `addTrain`/`removeTrain(UUID)`/`sided(LevelAccessor)` are public.
- `Create.RAILWAYS.removeTrain(id)` is what `Train#disassemble` uses.
- Create's only coupling code is minecart-scoped (`content/contraptions/minecart/*`, `CouplingCommand` with `ONLY_MINECARTS_ALLOWED`); `TrainRelocator.carriageWrenched` only arms the relocate flow and never mutates `Train.carriages`.
- `Train.runtime` is a public field; `Train#tick` calls `runtime.tick(level)`.
- `ScheduleRuntime.setSchedule(Schedule, boolean)` body (reset → assign → clamp `currentEntry` from `savedProgress` → `paused=false` → `isAutoSchedule=flag` → `status.newSchedule()` → prime predictions → `displayLinkUpdateRequested=true`).
- `ScheduleItem.handScheduleTo(...)` calls `train.runtime.setSchedule(schedule, true)`.
- `ScheduleEditPacket.handle(ServerPlayer)` only mutates the main-hand `ItemStack` (`AllItems.SCHEDULE` + `AllDataComponents.TRAIN_SCHEDULE`), never a `Train`.
- `ScheduleMenu extends HeldItemGhostItemMenu extends GhostItemMenu<ItemStack>`; `ScheduleScreen extends AbstractSimiContainerScreen<ScheduleMenu>`.
- `TrainEditPacket.handleSided` sets `train.name`, `train.icon`, `train.mapColorIndex` from `Create.RAILWAYS.sided(level).trains.get(id)`.
- `StationBlockEntity.updateName(String)` just assigns `GlobalStation.name` — no uniqueness check, no global name→station map.
- `AllPackets` is a closed enum with a private constructor — not addon-extensible.
- `Create.REGISTRATE` is private; `Create.registrate()` is the public accessor.
- `CreateRegistrate.create(String)`, `.blockEntity(...)`, `.displaySource(...)`, `.displayTarget(...)` are public.
- `DisplaySource.BY_BLOCK`/`BY_BLOCK_ENTITY` are `SimpleRegistry$Multi`; `DisplayTarget.BY_BLOCK`/`BY_BLOCK_ENTITY` are `SimpleRegistry`.
- `com.simibubi.create.api.event.TrackGraphMergeEvent extends net.neoforged.bus.api.Event` with public getters.
- `net.createmod.catnip.*` and `net.createmod.ponder.*` are bundled in Create's jarjar'd `ponder-neoforge-1.0.82+mc1.21.1.jar`; `CatnipPacketRegistry` and `PonderIndex.addPlugin(PonderPlugin)` are the addon hooks.
- `railways` 0.3.0-alpha.2 requires `create [6.0.7,)` + `neoforge [21.1.233,)` → compatible with Create 6.0.10 on this pack.
- `trainutilities` 3.0.3 requires `create [6.0.7,]`; contains **zero** schedule/address/coupling/hump-shunting code; its only mixin is `ContraptionControlsMovingInteractionMixin`.
- CRN contains a real address-tag model: `de.mrjulsen.crn.data.StationTag` with `Map<String, StationTag$StationInfo> stations`.
- `TrackSwitch.setSwitchState(SwitchState)` / `trySetSwitchState(SwitchState)` are public; `SwitchState` = `NORMAL, REVERSE_LEFT, REVERSE_RIGHT`; `CREdgePointTypes.SWITCH` is an `EdgePointType<TrackSwitch>`; `TrackSwitch` extends `SingleBlockEntityEdgePoint`; `TrackSwitchBlockEntity.edgePoint` is a public `TrackTargetingBehaviour<TrackSwitch>`.
- `TrackTargetingBehaviour<T extends TrackEdgePoint>` has a public constructor `(SmartBlockEntity, EdgePointType<T>)` and `getEdgePoint()`, `createEdgePoint()`, `determineGraphLocation()`.
- `Schedule` registered ids: `destination, package_delivery, package_retrieval, rename, throttle`; conditions: `delay, time_of_day, fluid_threshold, item_threshold, redstone_link, player_count, idle, unloaded, powered`.

### 9.2 INFERRED (bytecode/behaviour/usage-derived, not a printed signature)
- The exact A*/frontier algorithm inside `Navigation.findPathTo` / `search` (the entry points and the `TrackGraph.getConnectionsFrom` + `TrackEdge.getLength()` cost model are verified; the pruning heuristics are not).
- Railways' edge-enable mechanism: that `TrackSwitch.updateEdges` / `setEdgesActive` *disable* the non-selected branch edges and that `TravellingPoint`'s selector then skips them — the `ISwitchDisabledEdge` interface and those methods are verified, but the mixin injection targets (in `railways.mixins.json` / `railways-common.mixins.json`) were not fully enumerated.
- `AllBogeyStyles.BOGEY_STYLES.put(...)` as the addon registration path (the map is public and `init()` only registers Create's own styles; no `register(...)` method exists).
- `MovementBehaviour.REGISTRY` / `MovingInteractionBehaviour.REGISTRY` being the addon-facing registries behind `AllMovementBehaviours` / `AllInteractionBehaviours` (those two classes expose only package-private `registerDefaults()`).
- That `Schedule.INSTRUCTION_TYPES` mutation is *sufficient* for full GUI support: `Schedule.getTypeOptions(List)` and `DestinationSuggestions` were verified, but I did not verify that `ScheduleScreen`'s type-cycle widget reads the list at the right time for a late registration — register during `FMLCommonSetupEvent` to be safe.
- Station-name uniqueness being unenforced: verified that `updateName` does not check, but a uniqueness check could exist in `StationScreen`/`StationEditPacket` client-side; not exhaustively traced.
- `ScheduleScreen`'s internal widget ids / `clickMenuButton` indices (only the superclass chain was verified).

### 9.3 UNDETERMINED
- Whether a train can be **split/merged at runtime** without disassembly. No API exists; whether it is technically achievable by constructing `new Train(...)` + `GlobalRailwayManager.addTrain/removeTrain` + `Train.detachFromTracks/reattachToTracks` was **not** attempted — this needs a live test.
- Exact `railways-common.mixins.json` injection targets for `EdgeData`/`TrackEdge` (the `ISwitchDisabledEdge` implementer was not identified).
- Whether `CreateRegistries.DISPLAY_SOURCE` is writable from an addon at runtime via NeoForge `DeferredRegister` (the registry key is public; the creation path in `CreateBuiltInRegistries` is private). The `CreateRegistrate.displaySource(...)` + `SimpleBuilder` path is verified as the intended route.
- Full mixin target list of CRN (`createrailwaysnavigator.mixins.json` was only grepped for schedule/train entries).
- Whether `TrainIconType.register(...)` is safe for addons (public, but client-render oriented and not versioned).
- The precise wire format of `DimensionPalette` (needed if an addon serialises a custom `TrackEdgePoint` manually — but `TrackEdgePoint.write/read(DimensionPalette)` is provided, so this may not matter).

---

## 10. Practical integration notes for a dispatch/"address" mod

1. **Adding an address-aware destination instruction is a no-mixin, supported operation.** Register during common setup:
   ```java
   Schedule.INSTRUCTION_TYPES.add(Pair.of(
       ResourceLocation.fromNamespaceAndPath("raildispatch", "address_destination"),
       AddressDestinationInstruction::new));
   ```
   Implement `AddressDestinationInstruction extends TextScheduleInstruction` (or `extends DestinationInstruction` if you want Create's filter/label UI for free, as railways and CRN both do):
   - `getId()` → your `ResourceLocation`
   - `supportsConditions()` → `true`
   - `start(ScheduleRuntime, Level)` → resolve your address registry to a set of `GlobalStation`s, then `runtime.train.navigation.findPathTo(ArrayList<GlobalStation>, bias)`
   - optionally `getSummary()`, `getSecondLineIcon()`, `getSecondLineTooltip(int)`, `initConfigurationWidgets(ModularGuiLineBuilder)`, `getTitleAs(String)`

2. **Do not try to extend `AllPackets`, `AllMenuTypes`, `AllBlocks`, `AllItems`, `AllBlockEntityTypes`, `AllDataComponents`.** Use `CreateRegistrate.create("raildispatch")` and `CatnipPacketRegistry`.

3. **Train identity** is `Train.id : UUID`; the live registry is `Create.RAILWAYS.sided(level).trains`. Train *display* name is `Train.name : Component` (mutable, and `TrainEditPacket` is the precedent for changing it).

4. **Setting a schedule** from code: `train.runtime.setSchedule(schedule, /*isAutoSchedule=*/true)`. Create's own gate is "current schedule must be empty" — that check lives in `ScheduleItem.handScheduleTo`, not in `ScheduleRuntime`, so an addon can bypass it.

5. **Occupancy query** for a dispatcher:
   ```java
   UUID gid = edge.getEdgeData().getEffectiveEdgeGroupId(graph);      // or getGroupAtPosition(graph, t)
   SignalEdgeGroup g = Create.RAILWAYS.sided(level).signalEdgeGroups.get(gid);
   boolean occupied = g != null && g.isOccupiedUnless(train);
   ```
   Plus `train.occupiedSignalBlocks` / `train.reservedSignalBlocks` for the train's own view.

6. **Switches require railways** (`create [6.0.7,)` compatible). If railways may be absent, implement your own `EdgePointType` + a `TrackTargetingBehaviour` on your own block and reproduce the edge-disable semantics (this is the only part that may need a mixin).

7. **Routing** = `Navigation.findPathTo` (station-targeted only). For non-station targets, walk the graph yourself with `TrackGraph.getConnectionsFrom` + `TrackEdge.getLength()` / `canTravelTo`.

8. **Persisting addon state** (addresses, dispatcher state): `Create.RAILWAYS`/`RailwaySavedData` are not extensible — either write your own `SavedData` (recommended, no mixin), or follow CRN and mixin into `GlobalRailwayManager`.

9. **Train displays**: register a `DisplaySource` via `registrate.displaySource(name, MySource::new).byBlock(...)`; precedent `TrainStatusDisplaySource` / `ObservedTrainNameSource` / `StationSummaryDisplaySource`.
