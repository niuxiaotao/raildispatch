# Create 1.21.1 (6.0.10) - Train Schedule API reference (bytecode-verified)

Target jar: `D:\DAOXIAN\.minecraft\versions\1.21.1-NeoForge_21.1.233\mods\[机械动力] create-1.21.1-6.0.10.jar`
Tool: `C:\Program Files\Java\jdk-21\bin\javap.exe -p [-c|-v] -cp <jar> <class>`
All signatures below were read from the class files. All NBT key strings below were read from the
`ldc` constants inside `write` / `fromTag` / `readAdditional` bytecode - they are literal, not guessed.
Anything not inspected is marked `UNVERIFIED`.

Namespace note: every registered instruction/condition id is built with `Create.asResource(path)` =>
`create:<path>`. Translation keys are built as `<namespace>.schedule.instruction.<path>` /
`<namespace>.schedule.condition.<path>` (see `Schedule.getTypeOptions`).

---

## 1. `com.simibubi.create.content.trains.schedule.Schedule`

```
public class Schedule {
  public static final StreamCodec<RegistryFriendlyByteBuf, Schedule> STREAM_CODEC;
  public static List<Pair<ResourceLocation, Supplier<? extends ScheduleInstruction>>> INSTRUCTION_TYPES;
  public static List<Pair<ResourceLocation, Supplier<? extends ScheduleWaitCondition>>> CONDITION_TYPES;
  public List<ScheduleEntry> entries;      // entry type IS ScheduleEntry
  public boolean cyclic;
  public int savedProgress;
  private static void registerInstruction(String, Supplier<? extends ScheduleInstruction>);
  private static void registerCondition(String, Supplier<? extends ScheduleWaitCondition>);
  public static <T> List<? extends Component> getTypeOptions(List<Pair<ResourceLocation, T>>);
  public Schedule();                                        // -> this(new ArrayList<>(), true, 0)
  public Schedule(List<ScheduleEntry>, boolean, int);
  public CompoundTag write(HolderLookup.Provider);
  public static Schedule fromTag(HolderLookup.Provider, CompoundTag);
}
```

- entries are stored as a plain `java.util.List<ScheduleEntry>` (`entries`). There is NO `ScheduleEntry[]`.
- There is **no tick / advance / cycle method on `Schedule` itself**. `Schedule` is pure data + (de)serialization.
  All advancement lives in `ScheduleRuntime` (section 8).
- `STREAM_CODEC` = `StreamCodec.composite(list(ScheduleEntry.STREAM_CODEC), s -> s.entries,
  ByteBufCodecs.BOOL, s -> s.cyclic, ByteBufCodecs.VAR_INT, s -> s.savedProgress, Schedule::new)`.

### NBT (`Schedule.write` / `Schedule.fromTag`)
| Key | Type | Notes |
|---|---|---|
| `Entries` | `ListTag` (element type 10 = CompoundTag) | written via `NBTHelper.writeCompoundList`; read with `getList("Entries", 10)` |
| `Cyclic` | `byte`/boolean | always written. `fromTag` uses `getBoolean` => **false when absent**, even though `new Schedule()` defaults to true |
| `Progress` | `int` | written **only if `savedProgress > 0`**; read only if `contains("Progress")` |

- Entry index state: `Schedule.savedProgress` (int, key `Progress`) is the *persisted* resume index;
  the live index is `ScheduleRuntime.currentEntry`. On `ScheduleRuntime.setSchedule(...)`:
  `currentEntry = Mth.clamp(schedule.savedProgress, 0, schedule.entries.size() - 1)`.
- A `new Schedule()` = `entries = []`, `cyclic = true`, `savedProgress = 0`.

---

## 2. `com.simibubi.create.content.trains.schedule.ScheduleEntry`

```
public class ScheduleEntry {
  public static final StreamCodec<RegistryFriendlyByteBuf, ScheduleEntry> STREAM_CODEC;
  public ScheduleInstruction instruction;
  public List<List<ScheduleWaitCondition>> conditions;   // outer = columns, inner = conditions in a column
  public ScheduleEntry();                                 // conditions = new ArrayList<>()
  public ScheduleEntry(ScheduleInstruction, List<List<ScheduleWaitCondition>>);
  public ScheduleEntry clone(HolderLookup.Provider);      // = fromTag(provider, write(provider))
  public CompoundTag write(HolderLookup.Provider);
  public static ScheduleEntry fromTag(HolderLookup.Provider, CompoundTag);
}
```

### Fields
- destination instruction: `public ScheduleInstruction instruction`
- condition instructions: `public List<List<ScheduleWaitCondition>> conditions`
- **there is no wait/delay field on the entry.** A "wait" is expressed as a condition
  (usually a `ScheduledDelay` in the first column). The schedule editor even creates new entries that way:
  `ScheduleScreen.lambda$action$15` builds `new ScheduleEntry()` + `new ScheduledDelay()` inside a new
  inner `ArrayList` and adds it as the single outer element (`entry.conditions.add(column)`).

### NBT (`ScheduleEntry.write` / `fromTag`)
| Key | Type | Notes |
|---|---|---|
| `Instruction` | `CompoundTag` | result of `instruction.write(provider)` |
| `Conditions` | `ListTag` of `ListTag` of `CompoundTag` | written **only if `instruction.supportsConditions()`**; outer list element type is 9, read with `getList("Conditions", 9)` and non-`ListTag` elements are skipped |

### "complete" / when to move to the next entry
`ScheduleEntry` has no completion logic; `ScheduleRuntime.tickConditions(Level)` decides (exact algorithm
in section 8). Summary of the verified algorithm:

- per outer column `i` there is one `int` progress (`ScheduleRuntime.conditionProgress`) and one
  `CompoundTag` context (`ScheduleRuntime.conditionContext`), both sized to `entry.conditions.size()`
  and created in `destinationReached()`.
- each tick, for every column `i` in order: if `progress[i] >= conditions.get(i).size()` the ENTRY is complete
  (`state = PRE_TRANSIT`, `currentEntry++`, return). Otherwise tick `conditions.get(i).get(progress[i])`;
  if it returns true, clear that column's context (`set(i, new CompoundTag())`) and `progress[i]++`.
- net semantics: conditions inside one column are satisfied **in order and ALL must return true (AND)**;
  the entry is finished as soon as **ANY single column is fully satisfied (OR across columns)**.
- if the entry's instruction has `supportsConditions() == false`, `tickConditions` immediately
  `state = PRE_TRANSIT; currentEntry++`.

---

## 3. The two hierarchies (both rooted at `IScheduleInput`)

```
public interface IScheduleInput {
  Pair<ItemStack, Component> getSummary();        // abstract
  ResourceLocation getId();                       // abstract
  CompoundTag getData();                          // abstract
  void setData(HolderLookup.Provider, CompoundTag);// abstract
  default int slotsTargeted() { return 0; }
  default List<Component> getTitleAs(String);     // default: translatable(<ns>.schedule.instruction|condition.<path>)
  default ItemStack getSecondLineIcon() { return ItemStack.EMPTY; }
  default void setItem(int, ItemStack) {}
  default ItemStack getItem(int) { return ItemStack.EMPTY; }
  default List<Component> getSecondLineTooltip(int) { return null; }
  default void initConfigurationWidgets(ModularGuiLineBuilder) {}   // client
  default boolean renderSpecialIcon(GuiGraphics, int, int) { return false; }  // client
}

public abstract class ScheduleDataEntry implements IScheduleInput {
  protected CompoundTag data;                                  // ctor: data = new CompoundTag()
  public CompoundTag getData();
  public void setData(HolderLookup.Provider p, CompoundTag t); // data = t; readAdditional(p, t)
  protected void writeAdditional(HolderLookup.Provider, CompoundTag) {}  // no-op
  protected void readAdditional(HolderLookup.Provider, CompoundTag) {}   // no-op
  protected <T> T enumData(String key, Class<T> c);  // c.getEnumConstants()[ data.getInt(key) % len ]
  protected String textData(String key);             // data.getString(key)
  protected int intData(String key);                 // data.getInt(key)
}
```

### (a) Destination instructions
```
public abstract class ScheduleInstruction extends ScheduleDataEntry {
  public static final StreamCodec<RegistryFriendlyByteBuf, ScheduleInstruction> STREAM_CODEC;
  public abstract boolean supportsConditions();
  public abstract DiscoveredPath start(ScheduleRuntime, Level);
  public final CompoundTag write(HolderLookup.Provider);
  public static ScheduleInstruction fromTag(HolderLookup.Provider, CompoundTag);  // unknown id -> new DestinationInstruction() + WARN log
}
  +-- TextScheduleInstruction (abstract)   getLabelText() = textData("Text"); getTitleAs(); initConfigurationWidgets(addTextInput, key "Text")
  |     +-- DestinationInstruction         id create:destination
  |     +-- ChangeTitleInstruction         id create:rename
  |     +-- FetchPackagesInstruction       id create:package_retrieval
  +-- ChangeThrottleInstruction            id create:throttle (extends ScheduleInstruction directly)
  +-- DeliverPackagesInstruction           id create:package_delivery (extends ScheduleInstruction directly)
```
Quirk (verified): `ScheduleInstruction.fromTag` calls `readAdditional(provider, outerTag)` **and then**
`readAdditional(provider, dataTag)`, then assigns `data = dataTag`.

### (b) Wait conditions
```
public abstract class ScheduleWaitCondition extends ScheduleDataEntry {
  public static final StreamCodec<RegistryFriendlyByteBuf, ScheduleWaitCondition> STREAM_CODEC;
  public abstract boolean tickCompletion(Level, Train, CompoundTag);              // core "is satisfied" method
  protected void requestStatusToUpdate(CompoundTag);                              // ctx.StatusVersion++
  public final CompoundTag write(HolderLookup.Provider);
  public static ScheduleWaitCondition fromTag(HolderLookup.Provider, CompoundTag); // unknown id -> returns NULL + WARN log
  public abstract MutableComponent getWaitingStatus(Level, Train, CompoundTag);
}
  +-- LazyTickedScheduleCondition (abstract, ctor(int tickRate))
  |     // tickCompletion: reads ctx "Time"; if (time % tickRate == 0) { if (lazyTickCompletion(...)) return true; } ctx "Time"++ ; return false
  |     protected abstract boolean lazyTickCompletion(Level, Train, CompoundTag);
  |     +-- CargoThresholdCondition (abstract, ctor: tickRate 20, data "Threshold" = "10")
  |           +-- ItemThresholdCondition    id create:item_threshold
  |           +-- FluidThresholdCondition   id create:fluid_threshold
  +-- TimedWaitCondition (abstract, ctor: data "Value" = 5, data "TimeUnit" = TimeUnit.SECONDS.ordinal())
  |     public int totalWaitTicks() = getValue() * getUnit().ticksPer
  |     public int getValue()    = intData("Value")
  |     public TimeUnit getUnit() = enumData("TimeUnit", TimeUnit.class)
  |     protected Component formatTime(boolean)
  |     +-- ScheduledDelay              id create:delay
  |     +-- IdleCargoCondition          id create:idle
  +-- PlayerPassengerCondition          id create:player_count
  +-- RedstoneLinkCondition             id create:redstone_link
  +-- StationPoweredCondition           id create:powered
  +-- StationUnloadedCondition          id create:unloaded
  +-- TimeOfDayCondition                id create:time_of_day   (extends ScheduleWaitCondition directly, NOT TimedWaitCondition)
```

Nested enums (both verified):
```
TimedWaitCondition$TimeUnit { TICKS(ticksPer=1,"t","generic.unit.ticks"), SECONDS(20,"s","generic.unit.seconds"),
                              MINUTES(1200,"min","generic.unit.minutes") }
CargoThresholdCondition$Ops { GREATER(">"), LESS("<"), EQUAL("=") }   // ordinals 0,1,2
   public boolean test(int held, int threshold)  // GREATER: held > thr ; EQUAL: held == thr ; LESS: held < thr
```

---

## 4. Registry (there is NO `AllScheduleInstructions` / `AllScheduleConditions`)

Verified by `jar tf` + filter: the classes `AllScheduleInstructions` and `AllScheduleConditions`
**do not exist** in this jar. Registration is done by private static helpers inside `Schedule.<clinit>`:

```
private static void registerInstruction(String path, Supplier<? extends ScheduleInstruction> s) {
  INSTRUCTION_TYPES.add(Pair.of(Create.asResource(path), s));
}
private static void registerCondition(String path, Supplier<? extends ScheduleWaitCondition> s) {
  CONDITION_TYPES.add(Pair.of(Create.asResource(path), s));
}
```
Both lists are `public static` (and mutable).

### Destination instructions - complete list (registration order, verified from `<clinit>` order + bootstrap method handles)
| # | ResourceLocation | Implementing class |
|---|---|---|
| 1 | `create:destination` | `...schedule.destination.DestinationInstruction` |
| 2 | `create:package_delivery` | `...schedule.destination.DeliverPackagesInstruction` |
| 3 | `create:package_retrieval` | `...schedule.destination.FetchPackagesInstruction` |
| 4 | `create:rename` | `...schedule.destination.ChangeTitleInstruction` |
| 5 | `create:throttle` | `...schedule.destination.ChangeThrottleInstruction` |

### Wait conditions - complete list (registration order)
| # | ResourceLocation | Implementing class |
|---|---|---|
| 1 | `create:delay` | `...schedule.condition.ScheduledDelay` |
| 2 | `create:time_of_day` | `...schedule.condition.TimeOfDayCondition` |
| 3 | `create:fluid_threshold` | `...schedule.condition.FluidThresholdCondition` |
| 4 | `create:item_threshold` | `...schedule.condition.ItemThresholdCondition` |
| 5 | `create:redstone_link` | `...schedule.condition.RedstoneLinkCondition` |
| 6 | `create:player_count` | `...schedule.condition.PlayerPassengerCondition` |
| 7 | `create:idle` | `...schedule.condition.IdleCargoCondition` |
| 8 | `create:unloaded` | `...schedule.condition.StationUnloadedCondition` |
| 9 | `create:powered` | `...schedule.condition.StationPoweredCondition` |

Any other id found in NBT was written by an addon; the vanilla reader must handle it as
"unknown id": instructions fall back to `DestinationInstruction` (data preserved), conditions are
**dropped as null** (and `ScheduleEntry.conditions` inner lists would then contain a null - see UNVERIFIED).

---

## 5. Per-class detail

Common serialization envelope for every instruction/condition (both `ScheduleInstruction.write` and
`ScheduleWaitCondition.write` are `final` and identical):

| Key | Type | Value |
|---|---|---|
| `Id` | `String` | `getId().toString()` e.g. `create:delay` |
| `Data` | `CompoundTag` | `data.copy()` after `writeAdditional(provider, copy)` |

`getData()`/`setData()` hand out / take the raw `data` tag; `setData` also calls `readAdditional`.
Per-class `data` sub-keys (inside `Data`) are listed below.

### (a) Destination instructions

**`DestinationInstruction`** (`create:destination`) - the station instruction
- `supportsConditions() = true`; `getSecondLineIcon()` = `AllBlocks.TRACK_STATION.asStack()`
- data key: **`Text`** (String) - the station name filter. `getFilter() = textData("Text")`
- `getFilterForRegex() = Glob.toRegexPattern(getFilter(), "")` (glob -> regex)
- `start(ScheduleRuntime runtime, Level level)`:
  1. if `!train.hasForwardConductor() && !train.hasBackwardConductor()` ->
     `train.status.missingConductor()`, `runtime.startCooldown()`, return `null`.
  2. iterate `train.graph.getPoints(EdgePointType.STATION)` (a `Collection<GlobalStation>`) and collect
     every station with `station.name.matches(filterRegex)`.
  3. `DiscoveredPath path = train.navigation.findPathTo(matchedStations, Double.MAX_VALUE)`.
  4. if `path == null` -> if anything matched `train.status.failedNavigation()` else
     `train.status.failedNavigationNoTarget(getFilter())`; `runtime.startCooldown()`; return `null`.
  5. else return the path (state handling is done by the runtime).
- Note: it does NOT itself check "already at that station"; `ScheduleRuntime.tick` compares
  `path.destination == train.getCurrentStation()` and calls `destinationReached()`.

**`ChangeTitleInstruction`** (`create:rename`)
- `supportsConditions() = false`; icon = `Items.NAME_TAG`
- data key: **`Text`** (String); `getScheduleTitle() = getLabelText() = textData("Text")`
- `start(...)`: `runtime.currentTitle = getScheduleTitle(); runtime.state = PRE_TRANSIT; runtime.currentEntry++; return null;`
  (i.e. it consumes an entry without navigating).

**`ChangeThrottleInstruction`** (`create:throttle`)
- `supportsConditions() = false`; ctor sets `data.putInt("Value", 100)`
- data key: **`Value`** (int, percent). `public float getThrottle() { return intData("Value") / 100.0f; }`
- `start(...)`: `runtime.train.throttle = getThrottle(); runtime.state = PRE_TRANSIT; runtime.currentEntry++; return null;`

**`FetchPackagesInstruction`** (`create:package_retrieval`)
- `supportsConditions() = true`; icon = `PackageStyles.getDefaultBox()`
- data key: **`Text`** (String, package address glob); blank filter -> pattern `*`
- `start(...)`: needs `level.getServer()`; requires a conductor (same as destination); iterates
  stations -> `station.connectedPorts` (`Map<BlockPos, GlobalPackagePort>`), uses `port.offlineBuffer`
  (`ItemStackHandler`) or, if the pos is loaded and the BE is a `PostboxBlockEntity`, its `inventory`
  (`SmartInventory`); a slot counts when `PackageItem.isPackage(stack)` &&
  `!PackageItem.matchAddress(stack, port.address)` && `PackageItem.getAddress(stack).matches(filterRegex)`.
  If no station found: `runtime.startCooldown(); state = PRE_TRANSIT; currentEntry++; return null;`
  Else `train.navigation.findPathTo(matchedStations, Double.MAX_VALUE)`; null -> `failedNavigation()` +
  `startCooldown()` + `null`.

**`DeliverPackagesInstruction`** (`create:package_delivery`)
- `supportsConditions() = true`; icon = white package postbox
- **no data keys of its own** (`data` stays empty)
- `start(...)`: requires a conductor. For each `train.carriages` -> `carriage.storage.getAllItems()`
  (`CombinedInvWrapper`), for each slot that `PackageItem.isPackage(...)`, remember the first package
  address, then scan all stations' `connectedPorts` and add the station when
  `PackageItem.matchAddress(stack, port.address)` is true. If no station found:
  if an address was seen -> `train.status.failedPackageNoTarget(address)` + `startCooldown()`,
  else `state = PRE_TRANSIT; currentEntry++`; both return `null`. Else `findPathTo(..., Double.MAX_VALUE)`.

### (b) Wait conditions

**`ScheduledDelay`** (`create:delay`) - the "wait N ticks" condition
- `tickCompletion(level, train, ctx)`: `time = ctx.getInt("Time")`; if `time >= totalWaitTicks()` return true;
  else `ctx.putInt("Time", time + 1)`, `requestDisplayIfNecessary(ctx, time)`, return false.
- `totalWaitTicks() = intData("Value") * enumData("TimeUnit", TimeUnit.class).ticksPer`
- data keys: **`Value`** (int; ctor default 5), **`TimeUnit`** (int ordinal; ctor default 1 = SECONDS ->
  20 ticks). Missing keys => `Value=0`, `TimeUnit=ordinal 0 = TICKS` => completes immediately.
- Max: `Value` UI range is 0..120 with shift step 15; `TimeUnit` has only TICKS/SECONDS/MINUTES.

**`IdleCargoCondition`** (`create:idle`)
- extends `TimedWaitCondition` (so it also has `Value` + `TimeUnit`).
- `tickCompletion`: `min` over carriages of `carriage.storage.getTicksSinceLastExchange()`;
  `ctx.putInt("Time", min)`; returns `min > totalWaitTicks()`.

**`CargoThresholdCondition`** (abstract)
- extends `LazyTickedScheduleCondition` with `tickRate = 20`; ctor sets `data.putString("Threshold", "10")`
- data keys: **`Threshold`** (String! parsed with `Integer.valueOf`, on `NumberFormatException` it is reset to
  `"0"` and 0 is returned), **`Operator`** (int ordinal of `Ops`; 0=GREATER, 1=LESS, 2=EQUAL),
  **`Measure`** (int, `intData("Measure")`; 1 = stacks, anything else = items)
- `getThreshold()`, `getOperator()`, `getMeasure()`, `slotsTargeted() = 1`
- `lazyTickCompletion(level, train, ctx)`: `last = ctx.contains("LastChecked") ? ctx.getInt("LastChecked") : -1`;
  `version = sum over carriages of carriage.storage.getVersion()`; if `version == last` return false;
  `ctx.putInt("LastChecked", version)`; return `test(level, train, ctx)`.
  (=> cargo thresholds are only re-evaluated every 20 ticks AND only when the summed cargo version changed.)
- context keys written by it: `LastChecked` (int), `CurrentDisplay` (int, via `requestStatusToUpdate(int, ctx)`);
  `requestStatusToUpdate` also bumps `StatusVersion`.

**`ItemThresholdCondition`** (`create:item_threshold`)
- data key added: **`Item`** (CompoundTag, `FilterItemStack.serializeNBT(provider)`; read via
  `FilterItemStack.of(provider, tag)`)
- `test(...)`: sums over all carriages `carriage.storage.getAllItems()`
  (`CombinedInvWrapper`): for each slot where `filter.test(level, stack)`:
  `Measure == 1` counts a stack as 1 only when `stack.getCount() == stack.getMaxStackSize()`,
  otherwise it adds `stack.getCount()`. Then `requestStatusToUpdate(total, ctx)` and
  `getOperator().test(total, getThreshold())`.
- public helpers: `setItem(int, ItemStack)`, `getItem(int)`.

**`FluidThresholdCondition`** (`create:fluid_threshold`)
- data key added: **`Bucket`** (CompoundTag, `FilterItemStack.serializeNBT(provider)`), read via
  `FilterItemStack.of(...)`; field `private FilterItemStack compareStack`
- `test(...)`: sums `fluidStack.getAmount()` (mB) over all carriages
  `carriage.storage.getFluids()` (`MountedFluidStorageWrapper`, an `IFluidHandler`) tanks where
  `compareStack.test(level, fluidStack)`; then `requestStatusToUpdate(total / 1000, ctx)` (buckets) and
  `getOperator().test(total, getThreshold() * 1000)` (comparison in mB vs buckets*1000).
- Note: `loadFluid()` (used only for display) calls `Minecraft.getInstance()` -> client-only path.

**`PlayerPassengerCondition`** (`create:player_count`) - the "wait for passengers" condition
- data keys: **`Count`** (int, `getTarget()`), **`Exact`** (int; read with `intData("Exact")`.
  UI options are `["exactly", "or_above"]` indexes 0/1; `canOvershoot() = intData("Exact") != 0`)
- `tickCompletion(level, train, ctx)`:
  `prev = ctx.getInt("PrevPlayerCount")`; `count = train.countPlayerPassengers()`;
  `ctx.putInt("PrevPlayerCount", count)`; if `prev != count` -> `requestStatusToUpdate(ctx)`;
  then `canOvershoot() ? count >= target : count == target`.
  (So `Exact = 0` means "exactly N", `Exact = 1` means "N or above".)
- context key: `PrevPlayerCount` (int), plus `StatusVersion`.

**`RedstoneLinkCondition`** (`create:redstone_link`)
- field `public Couple<RedstoneLinkNetworkHandler.Frequency> freq`
- data keys: **`Frequency`** (`ListTag` of 2 `CompoundTag`, each an `ItemStack.saveOptional(provider)` of the
  frequency item; read with `getList("Frequency", 10)` + `Couple.deserializeEach`),
  **`Inverted`** (int; `lowActivation() = intData("Inverted") == 1`)
- `tickCompletion(level, train, ctx)`:
  `last = ctx.contains("LastChecked") ? ctx.getInt("LastChecked") : -1`;
  `version = Create.REDSTONE_LINK_NETWORK_HANDLER.globalPowerVersion.get()`;
  if `version == last` return **false** (no re-evaluation when the global link network version did not change);
  `ctx.putInt("LastChecked", version)`;
  return `Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(freq) != lowActivation()`
  (i.e. `Inverted=0` -> completes when powered; `Inverted=1` -> completes when unpowered).
- `setItem(int slot, ItemStack)` / `getItem(int)` map slot 0 -> first frequency, 1 -> second (`Couple.set(slot==0, Frequency.of(stack))`).

**`StationPoweredCondition`** (`create:powered`) - "wait until the station is redstone-powered"
- no data keys at all (`writeAdditional`/`readAdditional` empty overrides)
- `tickCompletion(level, train, ctx)`: `station = train.getCurrentStation()`; null -> false;
  `pos = station.getBlockEntityPos()`, `dim = station.getBlockEntityDimension()`;
  server = `level.getServer()`; null -> false; `serverLevel = server.getLevel(dim)`;
  null or `!serverLevel.isLoaded(pos)` -> false; else `serverLevel.hasNeighborSignal(pos)`.

**`StationUnloadedCondition`** (`create:unloaded`)
- no data keys.
- `tickCompletion(...)`: `station = train.getCurrentStation()`; null -> false; else
  `!serverLevel.isPositionEntityTicking(station.getBlockEntityPos())`.

**`TimeOfDayCondition`** (`create:time_of_day`)
- ctor sets `data.putInt("Hour", 8)` and `data.putInt("Rotation", 5)`
- data keys: **`Hour`** (int 0..23), **`Minute`** (int), **`Rotation`** (int index into the UI list
  `["every_24","every_12","every_6","every_4","every_3","every_2","every_1","every_0_45","every_0_30","every_0_15"]`)
- `getRotation()` maps `Rotation` (datapack values below verified) to a period in ticks:
  `0 -> 24000` (default), `1 -> 12000`, `2 -> 6000`, `3 -> 4000`, `4 -> 3000`, `5 -> 2000`, `6 -> 1000`,
  `7 -> 750`, `8 -> 500`, `9 -> 250`
- `tickCompletion(level, train, ctx)` (window is 40 ticks; exact arithmetic from bytecode):
  ```
  int window = 40;
  int hour   = intData("Hour");
  int minute = intData("Minute");
  int rotation = getRotation();
  int time   = (int)(level.getDayTime() % rotation);
  int target = (int)( ((hour + 18) % 24) * 1000.0
                      + Math.ceil(minute / 60.0f * 1000.0f) ) % rotation;
  int delta  = time - target;
  return delta >= 0 && window >= delta;   // i.e. within the first 40 ticks of the target minute
  ```
  (The +18 shift is Create's mapping of "8:00 in the schedule" onto the internal day time.)

---

## 6. The specifics asked for

- **station/destination instruction**: `create:destination` -> `DestinationInstruction`, data `Text` = station
  name glob, `start()` resolves `GlobalStation`s by `station.name.matches(globRegex)` and calls
  `train.navigation.findPathTo(stations, Double.MAX_VALUE)`; requires a conductor.
- **"wait N ticks" / delay**: `create:delay` -> `ScheduledDelay`, data `Value` (int) x `TimeUnit`
  (`TICKS=1`, `SECONDS=20`, `MINUTES=1200`); counts up in `ctx.Time`, completes at `time >= Value*ticksPer`.
  Defaults written by the ctor: `Value=5`, `TimeUnit=SECONDS` (=100 ticks).
- **cargo full/empty**: there is no dedicated "full/empty" condition; the idiomatic encoding is
  `create:item_threshold` with `Threshold` (String number), `Operator` (GREATER/LESS/EQUAL),
  `Measure` (1 = stacks), `Item` (FilterItemStack). With `Measure=1`, a slot counts as one unit only when
  it is a full stack (`count == maxStackSize`), so ">= 27 stacks" is the usual "full train" test.
  Fluids: `create:fluid_threshold` with `Bucket` + same `Threshold`/`Operator`/`Measure`, compared in
  buckets (`mB/1000` for display, `threshold*1000` for the test).
- **wait for passengers**: `create:player_count` -> `PlayerPassengerCondition`, data `Count` (int) and
  `Exact` (int; 0 = exactly, 1 = or_above); uses `Train.countPlayerPassengers()`.
- **redstone link / power**: `create:redstone_link` -> `RedstoneLinkCondition`
  (`Frequency` itemstack list + `Inverted` int; uses `Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower`
  and short-circuits to false unless `globalPowerVersion` changed) and `create:powered` ->
  `StationPoweredCondition` (station block entity has a neighbor signal).
- **"wait for signal" conditions**: NONE exist in the vanilla registry. Signal waiting is not a schedule
  condition; it is handled inside `Navigation`/`Train`
  (`Navigation.waitingForSignal`, `waitingForChainedGroups`, `distanceToSignal`, `ticksWaitingForSignal`,
  `Train.frontSignalListener()`/`backSignalListener()`). Whether an addon registers extra condition types:
  UNVERIFIED.

---

## 7. `ScheduleItem` and the DataComponent

```
public class com.simibubi.create.content.trains.schedule.ScheduleItem
        extends net.minecraft.world.item.Item
        implements net.minecraft.world.MenuProvider,
                   com.simibubi.create.foundation.recipe.ItemCopyingRecipe$SupportsItemCopying {
  public ScheduleItem(Item.Properties);
  public InteractionResult useOn(UseOnContext);                     // delegates to use(...)
  public InteractionResultHolder<ItemStack> use(Level, Player, InteractionHand);  // opens ScheduleMenu
  public InteractionResult handScheduleTo(ItemStack, Player, LivingEntity, InteractionHand);
  public void appendHoverText(ItemStack, Item.TooltipContext, List<Component>, TooltipFlag);
  public static Schedule getSchedule(HolderLookup.Provider, ItemStack);
  public AbstractContainerMenu createMenu(int, Inventory, Player);
  public Component getDisplayName();
  public DataComponentType<?> getComponentType();
}
```

- Item entry: `AllItems.SCHEDULE` -> item id `create:schedule`
  (recipe `data/create/recipe/crafting/kinetics/schedule.json`: shapeless obsidian plate + paper -> 4x `create:schedule`,
  the recipe carries **no components**).
- The component: `com.simibubi.create.AllDataComponents.TRAIN_SCHEDULE`
  ```
  public static final DataComponentType<CompoundTag> TRAIN_SCHEDULE;
  ```
  registered under name **`train_schedule`** (=> `create:train_schedule`) with
  `.persistent(CompoundTag.CODEC).networkSynchronized(ByteBufCodecs.COMPOUND_TAG)`.
  Its payload is the raw `Schedule.write(provider)` compound (NOT a `Schedule` object).
- Getter: `public static Schedule ScheduleItem.getSchedule(HolderLookup.Provider p, ItemStack stack)`
  -> returns **null** if `!stack.has(AllDataComponents.TRAIN_SCHEDULE)`, else
  `Schedule.fromTag(p, stack.get(AllDataComponents.TRAIN_SCHEDULE))` (cast to `CompoundTag`).
  `ScheduleItem.getComponentType()` returns `AllDataComponents.TRAIN_SCHEDULE`.
- Setter: there is **no** `ScheduleItem.setSchedule(...)`. The only writers found:
  - `ScheduleEditPacket.handle(ServerPlayer)`: if `schedule.entries.isEmpty()` ->
    `stack.remove(AllDataComponents.TRAIN_SCHEDULE)`; else
    `stack.set(AllDataComponents.TRAIN_SCHEDULE, schedule.write(player.registryAccess()))`, then a 5-tick item cooldown.
  - `ScheduleRuntime.returnSchedule(HolderLookup.Provider)`: takes `AllItems.SCHEDULE.asStack()`, sets
    `schedule.savedProgress = currentEntry`, `stack.set(TRAIN_SCHEDULE, schedule.write(provider))`,
    then `discardSchedule()`; returns `ItemStack.EMPTY` when `isAutoSchedule` is true.
  - `ScheduleScreen/ScheduleMenu` only read it:
    `stack.getOrDefault(AllDataComponents.TRAIN_SCHEDULE, new CompoundTag())`; if that tag is non-empty it is
    parsed with `Schedule.fromTag`, otherwise the menu uses `new Schedule()` (empty, cyclic, progress 0).
- **Does the component exist before the editor is used? NO.** A crafted `create:schedule` has no
  `create:train_schedule` component; `getSchedule` returns null and the tooltip renders the "empty" state.
  It appears only once the editor is saved with at least one entry; saving an empty schedule removes it again.
  (The `schedule_clear` shapeless recipe - one `create:schedule` in, plain `create:schedule` out - exploits
  exactly this to strip the component.)

---

## 8. How a schedule is advanced over time (the per-tick entry point)

**The per-tick entry point is `com.simibubi.create.content.trains.schedule.ScheduleRuntime`:**

```
public ScheduleRuntime(Train train);            // field: public Train train
public void tick(Level);                        // <-- per-tick driver (called by Train.tick / Train.earlyTick)
public void tickConditions(Level);
public DiscoveredPath startCurrentInstruction(Level);
public void startCooldown();                    // cooldown = 40 ticks
public void destinationReached();
public void transitInterrupted();
public void setSchedule(Schedule, boolean isAutoSchedule);
public Schedule getSchedule();
public void discardSchedule();
public CompoundTag write(HolderLookup.Provider);
public void read(HolderLookup.Provider, CompoundTag);
public ItemStack returnSchedule(HolderLookup.Provider);
public void setSchedulePresentClientside(boolean);
public MutableComponent getWaitingStatus(Level);
public Collection<GlobalTrainDisplayData.TrainDeparturePrediction> submitPredictions();
```
Public mutable state: `schedule`, `isAutoSchedule`, `paused`, `completed`, `currentEntry`, `state`,
`conditionProgress`, `conditionContext`, `currentTitle`, `ticksInTransit`, `predictionTicks`,
`displayLinkUpdateRequested`; `private int cooldown` (INTERVAL constant, 40 ticks set by `startCooldown`).

`enum ScheduleRuntime.State { PRE_TRANSIT, IN_TRANSIT, POST_TRANSIT }`

Verified call sites: `Train.tick(Level)` (and `Train.earlyTick`) invoke `runtime.tick(level)`;
`Train.arriveAt(GlobalStation)` invokes `runtime.destinationReached()`.

### `tick(Level)` order of operations (bytecode-verified)
1. `schedule == null` -> return.
2. `paused` -> return.
3. `train.derailed` -> return.
4. `train.navigation.destination != null` -> `ticksInTransit++`; return.
5. `checkEndOfScheduleReached()`: if `currentEntry >= entries.size()` -> `currentEntry = 0`;
   if `!cyclic` -> `paused = true; completed = true`; return true (=> tick returns).
6. `if (--cooldown > 0) return;`
7. `state == IN_TRANSIT` -> return.
8. `state == POST_TRANSIT` -> `tickConditions(level)`; return.
9. otherwise (`PRE_TRANSIT`) -> `DiscoveredPath path = startCurrentInstruction(level)`;
   `startCurrentInstruction` itself first calls `checkEndOfScheduleReached()` and returns `null` if that
   is true, else `entries.get(currentEntry).instruction.start(this, level)`.
   - `path == null` -> return.
   - `train.status.successfulNavigation()`.
   - if `path.destination == train.getCurrentStation()` -> `state = IN_TRANSIT; destinationReached(); return;`
   - else if `train.navigation.startNavigation(path) != -1.0` -> `state = IN_TRANSIT; ticksInTransit = 0;`

### `destinationReached()`
- returns unless `state == IN_TRANSIT`; then `state = POST_TRANSIT`, clears `conditionProgress`/`conditionContext`,
  `displayLinkUpdateRequested = true`, calls `carriage.storage.resetIdleCargoTracker()` on every carriage,
  blends `ticksInTransit` into `predictionTicks[currentEntry]`, and (if `currentEntry < entries.size()`)
  builds `conditionProgress` (one `0` per outer column) + `conditionContext` (one new `CompoundTag` per column).

### `tickConditions(Level)` (exact)
- `entry = entries.get(currentEntry)`; `columns = entry.conditions`.
- if `!entry.instruction.supportsConditions()` -> `state = PRE_TRANSIT; currentEntry++; return;`
- for `i` in `0..columns.size()-1` (all columns, every tick):
  - `prog = conditionProgress.get(i)`; if `prog >= columns.get(i).size()` ->
    `state = PRE_TRANSIT; currentEntry++; return;`
  - `ctx = conditionContext.get(i)`; `before = ctx.getInt("StatusVersion")`
  - `if (columns.get(i).get(prog).tickCompletion(level, train, ctx))` ->
    `conditionContext.set(i, new CompoundTag()); conditionProgress.set(i, prog + 1);`
  - `displayLinkUpdateRequested |= (i == 0)`; `displayLinkUpdateRequested |= (i != 0 && before != ctx.getInt("StatusVersion"))`
- after the loop: for each carriage `carriage.storage.tickIdleCargoTracker()`.

### `ScheduleRuntime` NBT keys (`write` / `read`)
| Key | Type | Notes |
|---|---|---|
| `CurrentEntry` | `int` | read as `Math.max(0, getInt(...))` |
| `AutoSchedule` | `boolean` | |
| `Paused` | `boolean` | |
| `Completed` | `boolean` | |
| `Schedule` | `CompoundTag` | `Schedule.write(provider)`; written only if `schedule != null`, read only if `contains` |
| `State` | enum by name | `NBTHelper.writeEnum/readEnum` |
| `ConditionProgress` | `int[]` (`putIntArray`) | one entry per condition column |
| `ConditionContext` | `ListTag` of `CompoundTag` | one per condition column |
| `TransitTimes` | `int[]` | from `predictionTicks` |

Context-tag keys used by conditions (all inside `ConditionContext[i]`): `StatusVersion` (int, bumped by
`ScheduleWaitCondition.requestStatusToUpdate`), `Time` (ScheduledDelay / IdleCargo / LazyTicked),
`LastChecked` (cargo + redstone link), `CurrentDisplay` (cargo thresholds), `PrevPlayerCount`.

### `ScheduleRuntime.setSchedule(Schedule, boolean)`
`reset()` (paused=true, completed=false, isAutoSchedule=false, currentEntry=0, currentTitle="",
schedule=null, state=PRE_TRANSIT, clears lists), then `schedule = s`,
`currentEntry = Mth.clamp(s.savedProgress, 0, s.entries.size()-1)`, `paused = false`,
`isAutoSchedule = flag`, `train.status.newSchedule()`, `predictionTicks = new ArrayList<>()`,
`displayLinkUpdateRequested = true`.

---

## 9. Can a NON-Train vehicle participate? (what exists, no speculation)

- **There is no `IUnloadable`** (and no class/interface whose name contains `Unloadable`) anywhere in the jar.
- **There is no carriage interface and no navigation interface**: `Carriage`, `Navigation`, `Train`,
  `TrainCargoManager`, `GlobalStation`, `GlobalPackagePort` are all concrete classes.
  (`jar tf` shows no `trains/entity/I*.class`, `trains/graph/I*.class`, `trains/station/I*.class`.)
- **`ScheduleRuntime` is hard-bound to `Train`**: `ScheduleRuntime(Train)`, field `public Train train`,
  and it dereferences `train.navigation`, `train.carriages`, `train.status`, `train.getCurrentStation()`,
  `train.throttle`, `train.derailed`.
- **Every wait condition takes a `Train`**: `boolean tickCompletion(Level, Train, CompoundTag)` and
  `MutableComponent getWaitingStatus(Level, Train, CompoundTag)`. `ScheduleWaitCondition.fromTag` can be
  called without a Train (pure parsing), and `ScheduleInstruction.fromTag` too, but *evaluating* any
  condition requires a `Train` instance (`StationPoweredCondition`/`StationUnloadedCondition` only use
  `train.getCurrentStation()`, but they cannot be called without a Train).
- Conclusion (verified, not speculation): the schedule *data model* (`Schedule`, `ScheduleEntry`,
  `ScheduleInstruction`, `ScheduleWaitCondition`, `IScheduleInput`, `AllDataComponents.TRAIN_SCHEDULE`)
  is usable by anyone, but the *runtime* is not pluggable - a non-Train vehicle must re-implement
  `ScheduleRuntime.tick` semantics against that data model.
- Public types that exist and are reusable for such a re-implementation (all real, all Train-agnostic
  except where noted):
  - `com.simibubi.create.content.trains.entity.Navigation` (class; fields `destination`,
    `distanceToDestination`, `waitingForSignal`, `ticksWaitingForSignal`, methods
    `findPathTo(ArrayList<GlobalStation>, double)`, `startNavigation(DiscoveredPath)`, `cancelNavigation()`,
    `tick(Level)`; its ctor takes a `Train`).
  - `com.simibubi.create.content.trains.entity.TrainStatus`: `missingConductor()`, `failedNavigation()`,
    `failedNavigationNoTarget(String)`, `failedPackageNoTarget(String)`, `successfulNavigation()`,
    `newSchedule()`.
  - `com.simibubi.create.content.trains.station.GlobalStation` (`public String name`,
    `public Map<BlockPos, GlobalPackagePort> connectedPorts`, `getBlockEntityPos()`,
    `getBlockEntityDimension()`, `getPresentTrain()`, `getImminentTrain()`, `reserveFor(Train)`,
    `runMailTransfer()`), `GlobalPackagePort` (`String address`, `ItemStackHandler offlineBuffer`),
    `TrackGraph.getPoints(EdgePointType.STATION)`.
  - `com.simibubi.create.content.trains.entity.Carriage` (`public Train train`, `TrainCargoManager storage`),
    `com.simibubi.create.content.contraptions.minecart.TrainCargoManager`
    (`getAllItems()` -> `CombinedInvWrapper`, `getFluids()` -> `MountedFluidStorageWrapper`,
    `getVersion()`, `getTicksSinceLastExchange()`, `resetIdleCargoTracker()`, `tickIdleCargoTracker()`).
  - Contraption storage APIs: `com.simibubi.create.api.contraption.storage.item.MountedItemStorage(+Type,
    Wrapper, SimpleMountedStorage, ChestMountedStorage)`,
    `...storage.fluid.MountedFluidStorage(+Type, Wrapper)`, `...storage.SyncedMountedStorage`.
  - Other train-related extension points in `com.simibubi.create.api`: `contraption.train.PortalTrackProvider`
    (interface, `SimpleRegistry<Block, PortalTrackProvider> REGISTRY`, `findExit(ServerLevel, BlockFace)`),
    `data.TrainHatInfoProvider` (datagen), `schedule.hat.TrainHatInfo` (record + Codec).
  - `com.simibubi.create.content.trains.schedule.DestinationSuggestions` (client, extends
    `CommandSuggestions`) and `ScheduleScreen`/`ScheduleMenu`/`ScheduleEditPacket`
    (`ScheduleEditPacket.handle(ServerPlayer)` writes the component) - the GUI/packet path for editing.
- Client-side helper classes not needed by a reader: `ScheduleScreen$EditorSubWidgets`,
  `ScheduleMenu$InactiveSlot`, `ScheduleMenu$InactiveItemHandlerSlot`, `DestinationSuggestions`.

---

## 10. UNVERIFIED / caveats

- `ScheduleEntry.fromTag` uses `NBTHelper.readCompoundList`, which maps every element through
  `ScheduleWaitCondition.fromTag`; that method returns `null` for unknown ids, so an unknown condition id
  can put a `null` into the inner list. Whether anything later guards that null is **UNVERIFIED**.
- `DestinationInstruction` requires a conductor (`hasForwardConductor() || hasBackwardConductor()`);
  other instructions, e.g. `ChangeThrottleInstruction`/`ChangeTitleInstruction`, do not. A non-Train
  reader must decide its own equivalent - **no verified Create-side rule exists for non-Train vehicles**.
- `ScheduleScreen`/`ScheduleMenu` UI details (drag/drop, slot semantics, exact tooltip strings) were only
  partially disassembled; the important facts (component read/write, default new entry = one
  `ScheduledDelay` column) are verified from `c_menu.txt`.
- No addon-provided instruction/condition types were looked for; `Schedule.INSTRUCTION_TYPES` /
  `CONDITION_TYPES` are public and mutable, so a mod may append to them at any time.
- `TimedWaitCondition.requestDisplayIfNecessary` thresholds (1200/100 tick cadence) were read but are
  presentation-only; not reproduced here in full.
