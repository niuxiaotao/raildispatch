# Drive By Wire — offline API reconnaissance

Disassembled with `javap` (JDK 21.0.8) from `D:\DAOXIAN\.minecraft\versions\1.21.1-NeoForge_21.1.233\mods\drivebywire-0.2.9.jar`.
Method bodies read from bytecode (`javap -p -c`), parameter names from `LocalVariableTable`, mixin targets/redirect targets from
`RuntimeVisibleAnnotations` (`javap -p -v`). Working copies of every dump live in `_recon\dbw\dump\` and the unpacked jar in
`_recon\dbw\extract\`.

## STEP 1/2 — Identification

| Field | Value |
| --- | --- |
| Jar | `D:\DAOXIAN\.minecraft\versions\1.21.1-NeoForge_21.1.233\mods\drivebywire-0.2.9.jar` (269,068 bytes) |
| modId | `drivebywire` |
| displayName | `Drive By Wire` |
| version | `0.2.9` |
| authors | `StratoDonut` |
| license | `All Rights Reserved` |
| credits | `Porting work in progress for the Sable ecosystem.` |
| root package | `edn.stratodonut.drivebywire` |
| mod class | `edn.stratodonut.drivebywire.DriveByWireMod` (`@Mod(drivebywire)`, `public static final String MOD_ID`, `asResource(String) -> ResourceLocation`) |

Found directly in the mods folder — no nested-jar digging was needed for Drive By Wire itself.
`META-INF/neoforge.mods.toml` dependencies: `neoforge [21.1.219,)` required, `minecraft [1.21.1]` required,
`create [6.0.9,)` required AFTER, `sable [1.0.6,)` required AFTER, `create_tweaked_controllers [0,)` optional AFTER.
Mixin config `drivebywire.mixins.json`.

## STEP 3 — Redstone channel API

### (a) What is a "channel"?

**A channel is a bare `java.lang.String` name.** There is no `ChannelAddress`, `RedstoneChannel`, `Frequency`, or
`ChannelKey` class anywhere in the jar. There is no registry and no codec for channel names — they are plain strings
compared with `String.equals` (map keys).

Channel identity is the record:

```
public final class edn.stratodonut.drivebywire.wire.graph.WireNetworkNode$InputKey extends java.lang.Record {
  private final long sourcePos;
  private final java.lang.String channel;
  public WireNetworkNode$InputKey(long, java.lang.String);
  public long sourcePos();
  public java.lang.String channel();
}
```

i.e. a channel is always scoped to the **source block position that emits on it**.

The one public constant:

```
public static final java.lang.String edn.stratodonut.drivebywire.wire.WireNetworkManager.WORLD_CHANNEL;  // = "world"
```

Known in-game channel names (extracted from the static initialisers of
`ControllerHubBlock.CHANNELS`, `TweakedControllerHubBlock.CHANNELS`,
`LinkedControllerWireServerHandler.KEY_TO_CHANNEL`, `TweakedControllerWireServerHandler.BUTTON_TO_CHANNEL` /
`AXIS_TO_CHANNEL`):

* `world` — the generic redstone channel.
* Create Linked Controller keys (`ControllerHubBlock.CHANNELS`, exactly this order):
  `keyUp`, `keyDown`, `keyLeft`, `keyRight`, `keyJump`, `keyShift`.
* Tweaked-controller buttons: `buttonA`, `buttonB`, `buttonX`, `buttonY`, `shoulderLeft`, `shoulderRight`,
  `buttonBack`, `buttonStart`, `buttonGuide`, `leftJoyStickClick`, `rightJoyStickClick`, `dPadUp`, `dPadRight`,
  `dPadDown`, `dPadLeft`.
* Tweaked-controller axes: `axisLeftX+`, `axisLeftX-`, `axisLeftY+`, `axisLeftY-`, `axisRightX+`, `axisRightX-`,
  `axisRightY+`, `axisRightY-`, `axisTriggerLeft`, `axisTriggerRight`.
  (`TweakedControllerHubBlock.CHANNELS` = `Stream.concat(AXIS_TO_CHANNEL, BUTTON_TO_CHANNEL)`.)

**How a channel is named in game:** the player holds `drivebywire:wire`, right-clicks a source block to select it, then
scrolls the mouse wheel to cycle the channel (HUD: `drivebywire.wire.channel.selected` → "Selected Channel: %s"),
then right-clicks the sink block. Blocks that are not a `MultiChannelWireSource` are always `"world"`.

### (b) How a block EMITS a value onto a channel

**Not a capability, not a BlockEntity interface, not a registry — a plain static call.**

```java
public static void edn.stratodonut.drivebywire.wire.WireNetworkManager.trySetSignalAt(
        net.minecraft.world.level.Level level,
        net.minecraft.core.BlockPos  pos,
        java.lang.String             channel,
        int                          value);          // -> get(level).setSource(level, pos, channel, value)
```

Convenience facade (this is what the controller compat code actually calls):

```java
public final class edn.stratodonut.drivebywire.compat.ControllerSignalStore {
  public static void setSignal(net.minecraft.world.level.Level, net.minecraft.core.BlockPos, java.lang.String, int);
  public static void clear(net.minecraft.world.level.Level, net.minecraft.core.BlockPos);
  public static java.util.Map<java.lang.String,java.lang.Integer> getSignals(net.minecraft.world.level.Level, net.minecraft.core.BlockPos);
}
```

Manager instance API (from `WireNetworkManager`):

```java
public static WireNetworkManager get(net.minecraft.world.level.Level);
public void setSource(net.minecraft.world.level.Level, net.minecraft.core.BlockPos, java.lang.String channel, int value);
public void clearSourceSignals(net.minecraft.world.level.Level, net.minecraft.core.BlockPos);
public java.util.Map<java.lang.String,java.lang.Integer> getSourceSignals(net.minecraft.core.BlockPos);
public static void trySetSignalAt(net.minecraft.world.level.Level, net.minecraft.core.BlockPos, java.lang.String, int);
public static int  computeWorldSignal(net.minecraft.world.level.Level, net.minecraft.core.BlockPos);
public int  getSignalAt(net.minecraft.core.BlockPos, net.minecraft.core.Direction);
public void attachLevel(net.minecraft.world.level.Level);
public void flushPendingGraphRebuild(net.minecraft.world.level.Level);
```

**Value range — no clamping anywhere in the wire layer.** `WireNetworkManager.setSource` and
`WireNetworkNode.setInput` both branch on `if (value <= 0)` and *remove* the input; any `int > 0` is stored verbatim
(`Integer` boxed into a `HashMap`). There is no upper bound and no `Math.min(.., 15)` in the emission path.

Values actually produced by the shipped code:

| Producer | Range |
| --- | --- |
| `"world"` channel, from `WireCommonEvents.onNeighborNotify` / `computeWorldSignal` | 0..15 (`BlockState.getSignal` / `Level.getBestNeighborSignal`) |
| Linked Controller buttons (`LinkedControllerWireServerHandler.receivePressed`) | 15 pressed, 0 released (literal `bipush 15` / `iconst_0`) |
| Tweaked Controller buttons (`receiveButton`) | 15 / 0 |
| Tweaked Controller axes (`receiveAxis`) | 0..255 — `Byte.toUnsignedInt(byteValue)` of each axis byte |

Sink-side read-back is `WireNetworkNode.getSignal()` = `Math.max` over all stored inputs (`0` when empty), so values
above 15 do reach consumers unchanged.

**Delivery:** `WireNetworkManager.applySignalToSink(Level, long, String, WireNetworkSink, int)` sets the node input for
`BlockFace.of(sinkPos, sinkDirection)` under key `new InputKey(sourcePos, channel)`, then calls
`level.updateNeighborsAt(sinkPos.relative(direction), level.getBlockState(sinkPos.relative(direction)).getBlock())`.

**Automatic redstone capture** (`WireCommonEvents.onNeighborNotify(BlockEvent.NeighborNotifyEvent)`):
if the notified block `BlockState.isSignalSource()`, it takes `max` of `state.getSignal(level, pos, dir)` over all six
directions and calls `trySetSignalAt(level, pos, "world", max)`. Then for each notified side whose neighbour is *not*
a signal source it calls `trySetSignalAt(level, neighbourPos, "world", level.getBestNeighborSignal(neighbourPos))`.
Pending rebuilds are flushed from `WireCommonEvents.onLevelTick(LevelTickEvent.Post)` →
`manager.flushPendingGraphRebuild(level)`.

### (c) How a block RECEIVES / subscribes

**There is no subscribe interface and no callback.** Consumers receive through **vanilla redstone reads**
(`Level.getSignal(BlockPos, Direction)`), which is patched in:

```java
// edn.stratodonut.drivebywire.mixin.MixinServerLevel  (public abstract class extends net.minecraft.world.level.Level, @Mixin ServerLevel)
public int getSignal(net.minecraft.core.BlockPos pos, net.minecraft.core.Direction dir) {
    // vanilla: state.getSignal(...) , weak-power direct signal
    // then:
    return Math.max(vanilla,
        WireNetworkManager.get(this).getSignalAt(pos.relative(dir.getOpposite()), dir));
}
```

`getSignalAt` resolves `BlockFace.of(pos, dir)` in `WireNetworkManager.nodes` and returns
`WireNetworkNode.getSignal()` (max of inputs), or `0` if no node exists for that face.

**"Reverse wire" read helper** (used by the physics/train mixins, see (e)):

```java
public final class edn.stratodonut.drivebywire.compat.WireRedstoneCompat {
  public static int getSignalIncludingReverseWire(
      net.minecraft.world.level.Level, net.minecraft.core.BlockPos, net.minecraft.core.Direction);
}
// bytecode: max( level.getSignal(pos,dir),
//                manager.getSignalAt(pos.relative(dir), dir.getOpposite()),
//                manager.getSignalAt(pos, dir) )
```

**How the consumer is notified:** `Level.updateNeighborsAt(poweredPos, block)` from `applySignalToSink` /
`WireNetworkManager.notifySink(Level, BlockFace)`, where `poweredPos = sinkPos.relative(sinkDirection)`. The consumer
then polls `getSignal`. Nothing is pushed into the consumer.

Sink connection identity is the record:

```java
public final class edn.stratodonut.drivebywire.wire.graph.WireNetworkNode$WireNetworkSink extends java.lang.Record {
  private final long position;   // BlockPos.asLong()
  private final int  direction;  // Direction.get3DDataValue()
  public WireNetworkNode$WireNetworkSink(long, int);
  public static WireNetworkNode$WireNetworkSink of(net.minecraft.core.BlockPos, net.minecraft.core.Direction);
}
```

Connection bookkeeping is `WireNetworkManager$SinkReference(long sourcePos, String channel, int direction)` and the
public graph shape is `Map<Long /*sink pos*/, Map<String /*channel*/, Set<WireNetworkSink>>>` (exposed via
`getNetwork()` and mirrored on the client in `ClientWireNetworkHandler.currentNetwork`).

Connection creation/removal API:

```java
public static WireNetworkManager$ConnectionResult createConnection(
    net.minecraft.world.level.Level, net.minecraft.core.BlockPos source, net.minecraft.core.BlockPos sink,
    net.minecraft.core.Direction direction, java.lang.String channel);
public static boolean hasConnection(Level, BlockPos source, BlockPos sink, Direction, String channel);
public static boolean removeConnection(Level, BlockPos source, BlockPos sink, Direction, String channel);
public static boolean removeAllFromSource(net.minecraft.server.level.ServerPlayer, Level, BlockPos source);
public static void    handleAssemblyMove(ServerLevel from, ServerLevel to, BlockPos fromPos, BlockPos toPos);
public net.minecraft.nbt.CompoundTag save(net.minecraft.nbt.CompoundTag);
public void load(net.minecraft.nbt.CompoundTag);
public WireNetworkManager$BackupSnapshot createBackupSnapshot(Level, BlockPos, Direction);
public WireNetworkManager$RestoreResult  restoreBackupSnapshot(Level, BlockPos, Direction, CompoundTag);
```

`WireNetworkManager$ConnectionResult` enum: `OK`, `FAIL_EXISTS`, `FAIL_TOO_MANY_SOURCES`,
`FAIL_TOO_MANY_SINKS`, `FAIL_SAME_BLOCK` (with `isSuccess()`, `getDescription()`).
Private limits `MAX_SOURCES` / `MAX_SINKS_PER_SOURCE` exist — their numeric values are **UNVERIFIED** (constants are
inlined/private and not shown by `javap -p`).

### (d) Block-entity base class or helper to expose channels

**There is no BlockEntity base class.** To advertise a channel list you implement this interface **on the Block**
(not a BlockEntity):

```java
public interface edn.stratodonut.drivebywire.wire.MultiChannelWireSource {
  java.util.List<java.lang.String> wire$getChannels();                       // full, ordered channel list
  java.lang.String wire$nextChannel(java.lang.String current, boolean forward); // cycles the list
}
```

Neither method has a `default` implementation. `wire$nextChannel` bytecode is
`CHANNELS.get(Math.floorMod(CHANNELS.indexOf(current) + (forward ? 1 : -1), CHANNELS.size()))`, returning
`CHANNELS.getFirst()` when `current` is not in the list.

Only implementors inside the jar: `edn.stratodonut.drivebywire.blocks.ControllerHubBlock` and
`edn.stratodonut.drivebywire.blocks.TweakedControllerHubBlock`; two mixins bolt the interface onto Create blocks:
`MixinLecternControllerBlock` (targets Create's `LecternControllerBlock`, `DRIVEBYWIRE$CHANNELS`) and
`MixinLecternTweakedBlock` (`DRIVEBYWIRE$CHANNELS` = `concat(BUTTON_TO_CHANNEL, AXIS_TO_CHANNEL)`).

The interface is consulted **client-side only** (`ClientWireNetworkHandler.changeChannel(Block, boolean)`); the server
side never reads it. Emission is fully decoupled (anyone may call `trySetSignalAt`).

Helper a mod is expected to use for persistence through Sable blueprints:
`edn.stratodonut.drivebywire.blocks.WireNetworkBackupBlock` + `WireNetworkBackupBlockEntity`
(`drivebywire:backup_block`), driven by `WireNetworkManager.createBackupSnapshot` / `restoreBackupSnapshot`.

Also part of the "helper" surface: `edn.stratodonut.drivebywire.util.BlockFace` record
`(long pos, int dir)` with `of(long,int)` / `of(BlockPos,Direction)`.

### (e) Existing physics-vehicle / train integration

All of it is Mixin-based; there is no public compat API to call.

| Mixin (`edn.stratodonut.drivebywire.mixin.*`) | Target | What it does |
| --- | --- | --- |
| `MixinServerLevel` | `net.minecraft.server.level.ServerLevel` | overrides `getSignal(BlockPos, Direction)` to `max(vanilla, manager.getSignalAt(pos.relative(dir.getOpposite()), dir))` |
| `compat.aeronautics.MixinWheelMountBlockEntity` | `@Pseudo @Mixin(targets = "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity")` | 4 `@Redirect`s of `Level.getSignal(BlockPos,Direction)`, all replaced by `WireRedstoneCompat.getSignalIncludingReverseWire`: in `sable$physicsTick` → `drivebywire$useWireForBrakeSignal`; in `tick` → `drivebywire$useWireForClientBrakeSignal`; in `getSteeringSignal` (ordinal 0) → `drivebywire$useWireForLeftSteeringSignal`; (ordinal 1) → `drivebywire$useWireForRightSteeringSignal` |
| `compat.tracks.MixinSableTrackBlockEntity` | `@Pseudo @Mixin(targets = "dev.qwxon.tracks.content.blocks.sable_track.SableTrackBlockEntity")` | 2 `@Redirect`s of `Level.getSignal`: in `sable$physicsTick` → `drivebywire$useWireForTrackPhysicsSignal`; in `tick` → `drivebywire$useWireForTrackVisualSignal` |
| `compat.MixinSubLevelAssemblyHelper` | `dev.ryanhcode.sable.api.SubLevelAssemblyHelper$AssemblyTransform` | remaps the wire network after a Sable sublevel move (`WireNetworkManager.handleAssemblyMove`) |
| `compat.MixinLecternControllerBlock` / `MixinLecternControllerBlockEntity` / `MixinLinkedControllerInputPacket` / `MixinLinkedControllerStopLecternPacket` | Create `LecternControllerBlock` / `LecternControllerBlockEntity` / `LinkedControllerInputPacket` / `LinkedControllerStopLecternPacket` | hub binding + rerouting held-controller input into channels |
| `compat.tweaked.Mixin*` | `create_tweaked_controllers` | same, for tweaked controllers (axis + button channels) |
| `client.MixinLinkedControllerClientHandlerAccessor`, `client.MixinTweakedLinkedControllerClientHandlerAccessor`, `client.MixinSchematicAndQuillHandler` | client | accessors + schematic sync |

Notes:
* `dev.ryanhcode.offroad` = modid **`offroad`**, display "Create Offroad" — it is *not* a separate download; it ships
  inside `[机械动力：航空学] create-aeronautics-bundled-1.21.1-1.3.0.jar` at
  `META-INF/jarjar/dev.ryanhcode.offroad.offroad-neoforge-1.21.1-1.3.0.jar` (verified: that nested jar contains
  `dev/ryanhcode/offroad/content/blocks/wheel_mount/WheelMountBlockEntity.class`).
* `dev.qwxon.tracks.content.blocks.sable_track.SableTrackBlockEntity` **was not found in any installed jar** — searched
  all seven mods jars plus the nested jarjar entries of `create-aeronautics-bundled` (aeronautics, offroad, simulated),
  `sable` and `create`. So DBW soft-targets a "Sable Tracks" mod that is not installed here; which mod provides
  `dev.qwxon.tracks` is **UNVERIFIED**. (`@Pseudo` means the mixin is silently skipped when absent.)
* **No Create train / bogey / contraption API usage found.** Create usage is limited to
  `com.simibubi.create.AllItems.LINKED_CONTROLLER` and the Lectern Controller block/BE. The mod's own ponder text says
  "Cable networks only work on assembled VS2 ships", and the physics integration is Sable/Offroad wheels + tracks.
* `WireNetworkManager` is Sable-aware throughout: `dev.ryanhcode.sable.Sable.HELPER.getContaining(Level, Vec3i)`,
  `dev.ryanhcode.sable.sublevel.SubLevel`, `dev.ryanhcode.sable.api.schematic.SubLevelSchematicSerializationContext`.

### (f) How a player configures a channel in game

**There is no menu, no GUI, and no DataComponent for the channel.** The channel is a purely client-side selection made
with an item and mouse wheel, then sent to the server in the connection packet.

* Selection — `edn.stratodonut.drivebywire.client.ClientWireNetworkHandler`:
  * `onMouseScrolled(InputEvent.MouseScrollingEvent)`: requires `player.getMainHandItem().is(WireItems.WIRE.get())` and a
    selected source; then `changeChannel(sourceBlock, scrollDeltaY > 0)` and the event is cancelled.
  * `changeChannel(Block, boolean forward)`: if the block `instanceof MultiChannelWireSource` →
    `currentChannel = ((MultiChannelWireSource) block).wire$nextChannel(currentChannel, forward)`, else
    `currentChannel = "world"`; null falls back to `"world"`; shows `drivebywire.wire.channel.selected` with the
    translation key from `TweakedControllerWireServerHandler.CHANNEL_TO_LANG_KEY`.
  * `onRightClickBlock(PlayerInteractEvent.RightClickBlock)` → `handleWireUse(...)`: first click selects the source,
    second click on another block sends `WireAddConnectionPacket(source, sink, direction, currentChannel)`; clicking an
    already-connected face sends `WireRemoveConnectionPacket(...)`; clicking the source again clears it.
* Wire-connection payloads (records implementing `CustomPacketPayload`), all carry the channel as a `String`:
  * `edn.stratodonut.drivebywire.network.WireAddConnectionPacket(BlockPos source, BlockPos sink, Direction direction, String channel)`
  * `edn.stratodonut.drivebywire.network.WireRemoveConnectionPacket(BlockPos source, BlockPos sink, Direction direction, String channel)`
  * `edn.stratodonut.drivebywire.network.WireNetworkFullSyncPacket(CompoundTag network)` (`sendTo(ServerPlayer)`)
  * `edn.stratodonut.drivebywire.network.WireNetworkRequestSyncPacket()` (singleton `INSTANCE`)
  * `edn.stratodonut.drivebywire.network.BindLecternControllerHubPacket(BlockPos lecternPos, BlockPos hubPos)`
  Registered by `edn.stratodonut.drivebywire.network.WirePackets.register(RegisterPayloadHandlersEvent)`, payload
  version `"1"`.
* Hub binding item — **Create's own Linked Controller**, no new item:
  `com.simibubi.create.AllItems.LINKED_CONTROLLER`. `ControllerHubBlock.useItemOn(...)` (and the tweaked twin) do:
  if `AllItems.LINKED_CONTROLLER.isIn(stack)` → `HubItem.putHub(stack, pos)`, play
  `drivebywire:plug_in`, message `"Controller connected!"`, return `ItemInteractionResult.SUCCESS`.
* `ItemStack` storage — the **hub position**, not the channel, goes into the vanilla
  `net.minecraft.core.component.DataComponents.CUSTOM_DATA` (`CustomData`) tag:

```java
public final class edn.stratodonut.drivebywire.util.HubItem {
  private static final java.lang.String HUB_KEY;        // "DriveByWireHub"
  private static final java.lang.String LEGACY_HUB_KEY; // "Hub"
  public static void putHub(net.minecraft.world.item.ItemStack, net.minecraft.core.BlockPos);
  public static java.util.Optional<net.minecraft.core.BlockPos> getHubPos(net.minecraft.world.item.ItemStack);
  public static void ifHubPresent(net.minecraft.world.item.ItemStack, java.util.function.Consumer<net.minecraft.core.BlockPos>);
}
```

  `putHub` writes **both** `DriveByWireHub` (current) and `Hub` (legacy) as `long` (`BlockPos.asLong()`) into a copy of
  the `CustomData` tag and sets `DataComponents.CUSTOM_DATA` back. `getHubPos` reads `DriveByWireHub` first, then
  legacy `Hub`, else `Optional.empty()`. **No custom `DataComponentType` is registered by this mod.**
  Lectern-hub binding lives in NBT instead, via `mixinducks.LecternControllerHubDuck`
  (`BlockPos drivebywire$getHubPos()` / `void drivebywire$setHubPos(BlockPos)`), key
  `MixinLecternControllerBlockEntity.DRIVEBYWIRE$HUB_KEY` — the literal string value is **UNVERIFIED**.
* Items: `edn.stratodonut.drivebywire.items.WireItem` (`drivebywire:wire`, "Cable"; plain `Item`, no overrides) and
  `edn.stratodonut.drivebywire.items.WireCutterItem` (`drivebywire:wire_cutter`, "Cable Cutter"; overrides `useOn`).
* Config: `edn.stratodonut.drivebywire.WireConfig.CONFIG_SPEC`, only option
  `shouldConsumeWires` (`ModConfigSpec$BooleanValue`).

### (g) Registry / DeferredRegister / event for registering channel providers

**None. There is no capability and no provider registry.** Verified by scanning the constant pool of every class in the
jar for `RegisterCapabilitiesEvent`, `BlockCapability`, `Capabilities.`, `RegisterEvent` — zero hits. The extension
mechanism is: implement `MultiChannelWireSource` on your `Block` (client scroll list) **and** call
`WireNetworkManager.trySetSignalAt(...)` / `ControllerSignalStore.setSignal(...)` from your own server-side logic.

What *is* registered (ids verified from the `DeferredRegister` create calls):

| Holder | Registry | ids |
| --- | --- | --- |
| `WireBlocks.BLOCKS` (`DeferredRegister.Blocks`, namespace `drivebywire`) | blocks | `drivebywire:backup_block` → `WireNetworkBackupBlock`, `drivebywire:controller_hub` → `ControllerHubBlock`, `drivebywire:tweaked_controller_hub` → `TweakedControllerHubBlock` |
| `WireItems.ITEMS` (`DeferredRegister.Items`) | items | `drivebywire:wire` → `WireItem`, `drivebywire:wire_cutter` → `WireCutterItem`, `drivebywire:backup_block`, `drivebywire:controller_hub`, `drivebywire:tweaked_controller_hub` (all `BlockItem`) |
| `WireBlockEntities.BLOCK_ENTITY_TYPES` | block entity types | `drivebywire:backup_block` → `BlockEntityType<WireNetworkBackupBlockEntity>` (the only BE in the mod) |
| `WireSounds.SOUND_EVENTS` | sound events | `drivebywire:plug_in`, `drivebywire:plug_out` |
| `WireCreativeTabs.CREATIVE_MODE_TABS` | creative tabs | `drivebywire:base` (lang key `itemGroup.drivebywire`), gated on `create_tweaked_controllers` |
| `WirePackets.register(RegisterPayloadHandlersEvent)` | payloads | the 5 packets above, version `"1"` |

Each `register(IEventBus)` static is called from `DriveByWireMod`'s constructor.
Forge/NeoForge events subscribed: `WireCommonEvents.onLevelTick(LevelTickEvent.Post)` and
`WireCommonEvents.onNeighborNotify(BlockEvent.NeighborNotifyEvent)`.

Persistence: `edn.stratodonut.drivebywire.wire.WireNetworkSavedData extends SavedData` with
`factory()` and `static WireNetworkManager get(ServerLevel)`; `WireNetworkManager.get(Level)` dispatches to it on the
server and to a `net.createmod.catnip.data.WorldAttached<WireNetworkManager> CLIENT_MANAGERS` on the client.
Serialised NBT keys observed in the constant pool: `Connections`, `Source`, `Sink`, `SourceOwnerSubLevel`,
`SinkOwnerSubLevel`, `Direction`, `Channel`, `Facing`, `UnsupportedConnections`, `SnapshotVersion`, `OwnerSubLevel`,
`PlacementResolved` (snapshot versions `RELATIVE_SNAPSHOT_VERSION`, `OWNER_AWARE_SNAPSHOT_VERSION`).

## STEP 4 — Directions (forward vs reverse)

**The data model is purely "a value on a named channel". There is no forward vs backward/reverse channel.**

* Channel identity `WireNetworkNode$InputKey(long sourcePos, String channel)` contains **no direction**.
* `Direction` appears only as the **cable attachment face on the sink**, i.e. which face of the sink block the endpoint
  plugs into and therefore which neighbouring block gets powered (`poweredPos = sinkPos.relative(direction)`):
  `WireNetworkSink(long position, int direction)`, `BlockFace(long pos, int dir)`,
  `SinkReference(long sourcePos, String channel, int direction)`,
  `createConnection(Level, BlockPos source, BlockPos sink, Direction direction, String channel)`.
* The only "reverse" in the API is the *read-side* helper
  `WireRedstoneCompat.getSignalIncludingReverseWire(Level, BlockPos, Direction)`, which additionally queries
  `getSignalAt(pos.relative(dir), dir.getOpposite())` so a cable plugged into the *other* face of the block is still
  seen. This is vanilla redstone read-symmetry, **not** a separate channel or a data-flow direction.
* The only `boolean` in the channel API is
  `MultiChannelWireSource.wire$nextChannel(String current, boolean forward)` — `javap` `LocalVariableTable` names the
  parameters `current` and `forward`, and the body is
  `CHANNELS.get(Math.floorMod(index + (forward ? 1 : -1), CHANNELS.size()))`. It is the **scroll/cycle direction for
  channel selection**, not a channel direction.
* On the receive side directionality cannot exist either: `WireNetworkNode.getSignal()` returns the `Math.max` over all
  inputs on that face, so every channel feeding a face is collapsed into one scalar. Channels are distinguishable only
  on the *source/connection* side (which channel a source emits on, and which channel a connection was created for).

Consequence for a mod that wants directional control (e.g. throttle forward vs reverse): it must encode direction in
the **channel name** (two channels, as the mod itself does for axes with `axisLeftY+` / `axisLeftY-`) or in the sign of
the value — the wire layer gives no direction for free.

## UNVERIFIED items

* Numeric values of `WireNetworkManager.MAX_SOURCES` / `MAX_SINKS_PER_SOURCE`.
* The literal NBT key string behind `MixinLecternControllerBlockEntity.DRIVEBYWIRE$HUB_KEY` (only the private static
  field name is visible).
* Which installed mod (if any) provides `dev.qwxon.tracks.content.blocks.sable_track.SableTrackBlockEntity` — the class
  is absent from every installed jar and nested jarjar entry.
* Exact user-visible behaviour of `drivebywire:wire` / `drivebywire:wire_cutter` beyond `WireCutterItem.useOn`
  (the wire item's own class is empty; the flow lives in `ClientWireNetworkHandler` and the packets).
* No runtime testing was performed (offline static reconnaissance only).
