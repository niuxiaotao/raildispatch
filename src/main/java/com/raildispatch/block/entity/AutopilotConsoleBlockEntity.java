package com.raildispatch.block.entity;

import com.raildispatch.RDConfig;
import com.raildispatch.RailDispatch;
import com.raildispatch.api.RailServices;
import com.raildispatch.api.ScheduleView;
import com.raildispatch.api.Schedules;
import com.raildispatch.api.SignalAspect;
import com.raildispatch.api.SignalInfo;
import com.raildispatch.api.TrackNetwork;
import com.raildispatch.api.TrackProbe;
import com.raildispatch.api.VehicleHandle;
import com.raildispatch.train.YardState;
import com.raildispatch.autopilot.AutopilotState;
import com.raildispatch.autopilot.TimetableEntry;
import com.raildispatch.block.AutopilotConsoleBlock;
import com.raildispatch.cab.CabRegistry;
import com.raildispatch.control.RouteProgress;
import com.raildispatch.control.SpeedController;
import com.raildispatch.menu.AutopilotConsoleMenu;
import com.raildispatch.registry.RDBlockEntities;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * The autopilot console: a destination, a route and a speed loop.
 *
 * <p>It exists because the physics cab's workflow starts with a Create train schedule, and a player who
 * just wants a vehicle to run to a station should not have to author one. The console is given a name -
 * a station, an address plate or a tagged vehicle - asks the ground layer for a route, and then
 * <b>follows that route node by node</b> instead of steering at the destination in a straight line.
 *
 * <p>Three things make the journey work:
 *
 * <ul>
 *   <li><b>The route is followed, not aimed at.</b> Create's route is an ordered node sequence (the same
 *       shape as its own {@code DiscoveredPath}); the next node is always on the rails, and the remaining
 *       distance summed along it drives the stopping curve. Aiming at a station block beside the track,
 *       or at the far side of a loop, is how a vehicle cuts corners or stops in the wrong place.</li>
 *   <li><b>Switches are re-asserted as they are approached.</b> They are aligned when the route is
 *       granted, but another vehicle, a player or an automatic switch can move them afterwards. The
 *       console re-drives the turnouts ahead of it whenever the route reaches a new node (and at least
 *       every five seconds), so the route it holds keeps meaning something.</li>
 *   <li><b>Traction and braking leave through Drive By Wire channels.</b> Two named channels - forward and
 *       the bogies' own brake - are published on this block's position. The console is a
 *       <b>one-direction</b> controller: it drives away from its screen face (the way the operator faces
 *       when standing at it) and there is no reverse channel. A destination behind it is refused rather
 *       than reversed into; bidirectional working is two consoles, one at each end.</li>
 * </ul>
 *
 * <p>The control loop is a feed-forward + PID speed controller ({@link SpeedController}); the block itself
 * only decides which speed to ask for. If a physics cab on the same vehicle has a schedule installed, the
 * console stands down and sends no command at all - two controllers on one vehicle would otherwise brake
 * against each other.
 */
public class AutopilotConsoleBlockEntity extends BlockEntity
        implements MenuProvider, BlockEntitySubLevelActor {

    // ---- synced status (ContainerData) ----
    public static final int DATA_STATE = 0;
    public static final int DATA_SIGNAL = 1;
    public static final int DATA_SPEED = 2;
    public static final int DATA_TARGET = 3;
    public static final int DATA_ROUTE = 4;
    public static final int DATA_REMAINING = 5;
    public static final int DATA_WAYPOINT = 6;
    public static final int DATA_WAYPOINTS = 7;
    public static final int DATA_DRIVE = 8;
    public static final int DATA_BRAKE = 9;
    public static final int DATA_FLAGS = 10;
    public static final int DATA_TIMETABLE_INDEX = 11;
    public static final int DATA_TIMETABLE_COUNT = 12;
    public static final int DATA_TIMETABLE_FLAGS = 13;
    public static final int DATA_SCHEDULE = 14;
    public static final int DATA_SCHEDULE_ENTRY = 15;
    public static final int DATA_SCHEDULE_ENTRIES = 16;
    public static final int DATA_COUNT = 17;

    public static final int TIMETABLE_FLAG_RUNNING = 1;
    public static final int TIMETABLE_FLAG_WAITING = 2;
    public static final int TIMETABLE_FLAG_LOOP = 4;

    public static final int FLAG_STAND_DOWN = 2;
    public static final int FLAG_REDSTONE = 4;
    public static final int FLAG_DERAILED = 8;
    public static final int FLAG_FOLLOWING = 16;
    /** The destination/route starts behind the console's driving direction, so it was refused. */
    public static final int FLAG_WRONG_DIRECTION = 32;

    // ---- console commands, mirrored by the screen ----
    public static final int ACTION_START = 0;
    public static final int ACTION_STOP = 1;
    public static final int ACTION_CLEAR = 2;
    public static final int ACTION_TIMETABLE_ADD = 3;
    public static final int ACTION_TIMETABLE_REMOVE = 4;
    public static final int ACTION_TIMETABLE_CLEAR = 5;
    public static final int ACTION_TIMETABLE_LOOP = 6;
    public static final int ACTION_TIMETABLE_START = 7;

    /**
     * The channel this console used to publish before it became one-directional. Nothing reads it now, but
     * a world upgraded from that build may still hold a non-zero value on a drivetrain; zeroing it once
     * keeps a stale throttle from surviving the upgrade.
     */
    private static final String LEGACY_BACKWARD_CHANNEL = "raildispatch:autopilot_backward";

    private static final int CONTROL_PERIOD = 5;
    private static final int ROUTE_RETRY_TICKS = 40;
    private static final int SWITCH_REFRESH_TICKS = 20;
    /** Re-align turnouts this far ahead, in blocks. */
    private static final double SWITCH_AHEAD = 48.0D;

    /**
     * The deceleration the stopping curve assumes, in blocks per second squared.
     *
     * <p>Deliberately below what the vehicle can actually do - the cab measured about 1.46 - so the
     * profile is achievable with margin and the vehicle begins easing off early rather than arriving hot.
     */
    /**
     * Assumed deceleration for the stopping curve, in blocks/s². Deliberately below the vehicle's
     * theoretical brake so the curve starts braking early; the old 1.0 assumed more brake than the
     * physics bogies actually deliver and the train crossed the platform before slowing.
     */
    private static final double BRAKE_DECELERATION = 0.35D;

    // Per-console driving parameters. They are persisted and sent in the update tag, and copied to the
    // other cab of the same train, so both ends drive with the same tuning.
    private double tuningMaxSpeed = RDConfig.maxSpeed();
    private double tuningAcceleration = TRACTION_RISE_PER_SECOND;
    private double tuningBrakeDecel = BRAKE_DECELERATION;
    private double tuningApproach = 1.0D;
    private double tuningSmoothing = 0.35D;

    /** How far away a player may be and still be told that the vehicle is off the rails, in blocks. */
    private static final double DERAL_PROMPT_RANGE = 32.0D;

    private static final double TRACTION_RISE_PER_SECOND = 0.22D;
    private static final double STEER_INERTIA_GAIN = 2.0D;
    private static final int MAX_DESTINATION_LENGTH = 96;

    public static final int SLOT_SCHEDULE = 0;

    /** Guards the cross-console schedule copy against bouncing the same stack back and forth. */
    private boolean syncingSchedule;

    /**
     * One schedule owner per vehicle. Both consoles share the schedule item, but only one of them may
     * execute an entry; otherwise the one at the other end starts the same entry a tick later and drives
     * the train the opposite way.
     */
    private static final java.util.Map<BlockPos, BlockPos> SCHEDULE_OWNER =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Loaded consoles, so a server-stopping event can clear every live channel before Sable saves. */
    private static final java.util.Set<AutopilotConsoleBlockEntity> LOADED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void shutdownAll(net.minecraft.server.MinecraftServer server) {
        for (AutopilotConsoleBlockEntity console : LOADED) {
            if (console.level instanceof ServerLevel serverLevel && serverLevel.getServer() == server) {
                console.engaged = false;
                console.scheduleActive = false;
                console.pendingHandoff = null;
                console.clearChannels(serverLevel);
            }
        }
    }

    /** One active driver for the whole coupled train, keyed by its smallest sub-level UUID. */
    private static final java.util.Map<UUID, BlockPos> TRAIN_DRIVER =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final SimpleContainerData data = new SimpleContainerData(DATA_COUNT);
    private final ItemStackHandler inventory = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            retryTimer = 0;
            if (level instanceof ServerLevel server) {
                if (inventory.getStackInSlot(SLOT_SCHEDULE).isEmpty()) {
                    // Taking the schedule out ends it on the whole structure.
                    scheduleActive = false;
                    scheduleWaitUntil = 0L;
                    for (AutopilotConsoleBlockEntity sibling : siblings(server)) {
                        if (!sibling.inventory.getStackInSlot(SLOT_SCHEDULE).isEmpty()) {
                            sibling.inventory.setStackInSlot(SLOT_SCHEDULE, ItemStack.EMPTY);
                        }
                    }
                } else {
                    syncScheduleToSiblings(server);
                }
            }
            sync();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return Schedules.isScheduleItem(stack);
        }
    };
    private final SpeedController speedPid = new SpeedController(RDConfig.autopilotPidKp(),
            RDConfig.autopilotPidKi(), RDConfig.autopilotPidKd(), RDConfig.autopilotPidIntegralLimit());

    private String destination = "";
    private final List<TimetableEntry> timetable = new ArrayList<>();
    private boolean timetableLoop = true;
    private boolean timetableRunning;
    private boolean timetableWaiting;
    private int timetableIndex;
    private boolean scheduleActive;
    private long scheduleWaitUntil;
    /** Blocks per second cap set by a speed-limit schedule entry; negative means no cap. */
    private double scheduleSpeedLimit = -1.0D;
    private int scheduleEntry;
    private int scheduleCount;
    private String detail = "";
    /** Last detail string sent to clients, so the screen's message line is not a stale copy. */
    private String lastSyncedDetail = "";
    private AutopilotState state = AutopilotState.IDLE;
    private boolean engaged;
    private boolean standAside;

    private UUID vehicleId;
    private VehicleHandle currentVehicle;

    private SignalAspect signal = SignalAspect.NONE;
    private double signalDistance = -1.0D;
    private double speed;
    private double targetSpeed;
    private double commandedDrive;
    private double commandedBrake;
    private double commandedSteer;

    private boolean routeGranted;
    private RouteProgress progress;
    /** The granted route itself, kept because the signal search must walk its edges, not a world heading. */
    private TrackNetwork.PlannedRoute currentRoute;
    private int retryTimer;

    private double filteredSpeed = -1.0D;
    private double lastDistanceBrake;
    private long lastControlTick = -1L;
    private long lastApplyTick = -1L;
    private int lastSwitchIndex = -1;
    private long lastSwitchTick = -1L;
    private double closestEnd = -1.0D;
    private double lastAppliedOutput;
    private long lastSelfSignalLog = -100L;
    /** True while an uncouple schedule entry is pulsing the redstone/channel output. */
    private boolean uncoupling;
    private long couplerPulseUntil;
    private boolean loggedFirstCycle;
    private boolean loggedNoVehicle;
    private long lastShuntSignalLog;
    /** Console waiting to take over; control is not transferred until the train has stopped. */
    private AutopilotConsoleBlockEntity pendingHandoff;
    private long handoffSince;
    private boolean loggedNoKineticPower;
    /** Set only by the explicit "enter shunting" schedule entry and cleared by "exit shunting". */
    private boolean shuntingMode;
    private boolean loggedDestinationTrouble;
    private boolean warnedWire;
    private boolean derailed;
    /** Set when the resolved destination/route starts behind this console, so it was refused. */
    private boolean wrongDirection;
    private int publishedForward = -1;
    private int publishedBrake = -1;
    private int publishedUncouple = -1;
    private boolean legacyBackwardCleared;

    public AutopilotConsoleBlockEntity(BlockPos pos, BlockState state) {
        super(RDBlockEntities.AUTOPILOT_CONSOLE.get(), pos, state);
    }

    // ------------------------------------------------------------------ accessors

    public ContainerData statusData() {
        return data;
    }

    public AutopilotState state() {
        return state;
    }

    public String destination() {
        return destination;
    }

    public String detail() {
        return detail;
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public boolean hasSchedule() {
        return !inventory.getStackInSlot(SLOT_SCHEDULE).isEmpty();
    }

    public boolean scheduleActive() {
        return scheduleActive;
    }

    public int scheduleEntry() {
        return scheduleEntry;
    }

    public int scheduleCount() {
        return scheduleCount;
    }

    public List<TimetableEntry> timetable() {
        return List.copyOf(timetable);
    }

    public boolean timetableLoop() {
        return timetableLoop;
    }

    public boolean timetableRunning() {
        return timetableRunning;
    }

    public boolean timetableWaiting() {
        return timetableWaiting;
    }

    public int timetableIndex() {
        return timetableIndex;
    }

    public TimetableEntry currentTimetableEntry() {
        return timetable.isEmpty() ? null
                : timetable.get(Math.max(0, Math.min(timetable.size() - 1, timetableIndex)));
    }

    public boolean isEngaged() {
        return engaged;
    }

    /** True while this console is the controller of its vehicle; the cab yields to this. */
    public boolean isDriving() {
        return engaged && !standAside;
    }

    public SignalAspect signal() {
        return signal;
    }

    public double speed() {
        return speed;
    }

    public double targetSpeed() {
        return targetSpeed;
    }

    public int comparatorOutput() {
        return state.comparatorOutput();
    }

    /**
     * Applies a command from the screen (or from a sneaking player's empty hand).
     *
     * <p>The destination is clamped rather than rejected: a name one character too long is a typo, not an
     * attack, and the server must not trust the client's length either way.
     */
    /** Puts a Create train schedule into the console's slot; the previous one is handed back. */
    public void installSchedule(ItemStack stack, Player player) {
        if (!Schedules.isScheduleItem(stack)) {
            return;
        }
        ItemStack previous = inventory.getStackInSlot(SLOT_SCHEDULE);
        if (!previous.isEmpty()) {
            if (!player.getInventory().add(previous)) {
                player.drop(previous, false);
            }
        }
        ItemStack installed = stack.copy();
        installed.setCount(1);
        inventory.setStackInSlot(SLOT_SCHEDULE, installed);
        if (level instanceof ServerLevel server) {
            syncScheduleToSiblings(server);
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        player.displayClientMessage(
                Component.translatable("raildispatch.autopilot.schedule_installed"), true);
        setChanged();
        sync();
    }

    /** Gives the installed Create schedule back and stops running it. */
    public void ejectSchedule(Player player) {
        ItemStack stack = inventory.getStackInSlot(SLOT_SCHEDULE);
        if (stack.isEmpty()) {
            return;
        }
        inventory.setStackInSlot(SLOT_SCHEDULE, ItemStack.EMPTY);
        scheduleActive = false;
        scheduleWaitUntil = 0L;
        if (level instanceof ServerLevel server) {
            for (AutopilotConsoleBlockEntity sibling : siblings(server)) {
                if (!sibling.inventory.getStackInSlot(SLOT_SCHEDULE).isEmpty()) {
                    sibling.inventory.setStackInSlot(SLOT_SCHEDULE, ItemStack.EMPTY);
                }
            }
        }
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        player.displayClientMessage(
                Component.translatable("raildispatch.autopilot.schedule_removed"), true);
        setChanged();
        sync();
    }

    public double tuningMaxSpeed() {
        return tuningMaxSpeed;
    }

    public double tuningAcceleration() {
        return tuningAcceleration;
    }

    public double tuningBrakeDecel() {
        return tuningBrakeDecel;
    }

    public double tuningApproach() {
        return tuningApproach;
    }

    public double tuningSmoothing() {
        return tuningSmoothing;
    }

    /** Applies parameters from the screen and copies them to the other cab of the train. */
    public void applyTuning(double maxSpeed, double acceleration, double brakeDecel,
                            double approach, double smoothing) {
        tuningMaxSpeed = clamp(maxSpeed, 0.5D, 30.0D);
        tuningAcceleration = clamp(acceleration, 0.02D, 3.0D);
        tuningBrakeDecel = clamp(brakeDecel, 0.05D, 2.0D);
        tuningApproach = clamp(approach, 0.4D, 2.5D);
        tuningSmoothing = clamp(smoothing, 0.03D, 0.95D);
        setChanged();
        sync();
        if (level instanceof ServerLevel server) {
            for (AutopilotConsoleBlockEntity sibling : siblings(server)) {
                if (sibling == this) {
                    continue;
                }
                sibling.tuningMaxSpeed = tuningMaxSpeed;
                sibling.tuningAcceleration = tuningAcceleration;
                sibling.tuningBrakeDecel = tuningBrakeDecel;
                sibling.tuningApproach = tuningApproach;
                sibling.tuningSmoothing = tuningSmoothing;
                sibling.setChanged();
                sibling.sync();
            }
            RailDispatch.debug("Autopilot console at {}: tuning max={} accel={} brake={} "
                            + "approach={} smoothing={}", worldPosition, tuningMaxSpeed,
                    tuningAcceleration, tuningBrakeDecel, tuningApproach, tuningSmoothing);
        }
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    public void handleCommand(Player player, String requestedDestination, int action, int clock) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        switch (action) {
            case ACTION_START -> {
                if (hasSchedule()) {
                    startSchedule(server, player);
                    break;
                }
                String wanted = cleanDestination(requestedDestination);
                if (wanted.isEmpty()) {
                    player.displayClientMessage(
                            Component.translatable("raildispatch.autopilot.no_destination"), true);
                    return;
                }
                timetableRunning = false;
                timetableWaiting = false;
                scheduleActive = false;
                scheduleWaitUntil = 0L;
                startLeg(server, wanted);
                player.displayClientMessage(Component.translatable(
                        "raildispatch.autopilot.started", wanted), true);
            }
            case ACTION_STOP -> {
                stopJourney(server);
                detail = "stopped";
                player.displayClientMessage(
                        Component.translatable("raildispatch.autopilot.stopped"), true);
            }
            case ACTION_CLEAR -> {
                stopJourney(server);
                destination = "";
                detail = "cleared";
                player.displayClientMessage(
                        Component.translatable("raildispatch.autopilot.cleared"), true);
            }
            case ACTION_TIMETABLE_ADD -> {
                String wanted = cleanDestination(requestedDestination);
                if (wanted.isEmpty()) {
                    player.displayClientMessage(
                            Component.translatable("raildispatch.autopilot.no_destination"), true);
                    return;
                }
                int ticks = clock >= 0 ? TimetableEntry.ticksFromPackedClock(clock) : -1;
                if (ticks < 0) {
                    ticks = defaultDeparture(server);
                }
                if (timetable.size() >= 16) {
                    timetable.remove(0);
                }
                TimetableEntry entry = new TimetableEntry(wanted, ticks);
                timetable.add(entry);
                if (timetableIndex >= timetable.size()) {
                    timetableIndex = 0;
                }
                player.displayClientMessage(Component.translatable(
                        "raildispatch.autopilot.timetable_added", entry.clock(), wanted), true);
            }
            case ACTION_TIMETABLE_REMOVE -> {
                if (timetable.isEmpty()) {
                    player.displayClientMessage(
                            Component.translatable("raildispatch.autopilot.timetable_empty"), true);
                    return;
                }
                timetable.remove(timetable.size() - 1);
                if (timetableIndex >= timetable.size()) {
                    timetableIndex = 0;
                }
                player.displayClientMessage(
                        Component.translatable("raildispatch.autopilot.timetable_removed"), true);
            }
            case ACTION_TIMETABLE_CLEAR -> {
                timetable.clear();
                timetableRunning = false;
                timetableWaiting = false;
                timetableIndex = 0;
                player.displayClientMessage(
                        Component.translatable("raildispatch.autopilot.timetable_cleared"), true);
            }
            case ACTION_TIMETABLE_LOOP -> {
                timetableLoop = !timetableLoop;
                player.displayClientMessage(Component.translatable(
                        timetableLoop ? "raildispatch.autopilot.timetable_loop_on"
                                : "raildispatch.autopilot.timetable_loop_off"), true);
            }
            case ACTION_TIMETABLE_START -> {
                if (timetable.isEmpty()) {
                    player.displayClientMessage(
                            Component.translatable("raildispatch.autopilot.timetable_empty"), true);
                    return;
                }
                scheduleActive = false;
                scheduleWaitUntil = 0L;
                timetableRunning = true;
                timetableWaiting = false;
                timetableIndex = 0;
                beginTimetableEntry(server, player);
            }
            default -> {
                return;
            }
        }
        setChanged();
        sync();
    }

    private static String cleanDestination(String requested) {
        String wanted = requested == null ? "" : requested.trim();
        if (wanted.length() > MAX_DESTINATION_LENGTH) {
            wanted = wanted.substring(0, MAX_DESTINATION_LENGTH);
        }
        return wanted;
    }

    /** One ad-hoc leg, without touching the timetable. */
    private void startLeg(ServerLevel server, String wanted) {
        VehicleHandle here = resolveVehicle(server);
        if (here != null) {
            claimTrainDriver(server, here);
        }
        destination = wanted;
        engaged = true;
        standAside = false;
        loggedDestinationTrouble = false;
        // Tear down the previous leg first: its route is reserved against this vehicle, and requesting a new
        // one without releasing would leave the old reservation's switches locked to a journey nobody is
        // driving any more.
        releaseRoute(server);
        autopilotReset();
        state = AutopilotState.PLANNING;
        detail = "planning";
    }

    private void stopJourney(ServerLevel server) {
        timetableRunning = false;
        timetableWaiting = false;
        scheduleActive = false;
        scheduleWaitUntil = 0L;
        engaged = false;
        autopilotReset();
        releaseRoute(server);
        state = AutopilotState.IDLE;
        brakeNow();
    }

    private Optional<ScheduleView> scheduleView(ServerLevel server) {
        return Schedules.read(server.registryAccess(), inventory.getStackInSlot(SLOT_SCHEDULE));
    }

    /** The other consoles of this train, including consoles on a coupled sub-level. */
    /** Releases the other end's old leg so both consoles cannot drive opposite ways at once. */
    private void standDownSiblings(ServerLevel server) {
        for (AutopilotConsoleBlockEntity sibling : siblings(server)) {
            standDown(sibling, server);
        }
        // Deliberately no Level.getChunk() scan here. A Sable sub-level is a plot, not an ordinary
        // world chunk at worldPosition's coordinates: worldPosition is plot space (e.g. 20481032,
        // 129, 20495365), so getChunk(cx, cz) would address a real world chunk tens of millions of
        // blocks away, not the car. Consoles in the same sub-level and in coupled sub-levels are
        // already reached through VehicleHandle.actorsOfType() in siblings().
    }

    private void standDown(AutopilotConsoleBlockEntity sibling, ServerLevel server) {
        if (sibling == this) {
            return;
        }
        sibling.engaged = false;
        sibling.scheduleActive = false;
        sibling.scheduleWaitUntil = 0L;
        sibling.destination = "";
        sibling.standAside = true;
        sibling.wrongDirection = false;
        sibling.releaseRoute(server);
        sibling.autopilotReset();
        sibling.state = AutopilotState.IDLE;
        sibling.detail = "standing by for the other console";
        // Do not leave the previous brake=15 latched on this cab's own channels. Guarding the write
        // only stopped NEW values; the old 15 kept reaching the bogies.
        sibling.clearChannels(server);
        sibling.setChanged();
        sibling.sync();
    }

    /**
     * Maps every sub-level of the coupled train to one owner. Keying only by this console's own
     * sub-level was not enough: the two consoles sit on different cars with different UUIDs, so each
     * saw itself as the first owner and both executed the entry.
     */
    private static final String SCHEDULE_OWNER_TAG = "RailDispatchScheduleOwner";

    /** Owner stored on the schedule stack itself, so both consoles see the same value when it syncs. */
    private BlockPos scheduleOwner() {
        ItemStack stack = inventory.getStackInSlot(SLOT_SCHEDULE);
        if (stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.contains(SCHEDULE_OWNER_TAG) ? BlockPos.of(tag.getLong(SCHEDULE_OWNER_TAG)) : null;
    }

    private void setScheduleOwner(BlockPos owner) {
        ItemStack stack = inventory.getStackInSlot(SLOT_SCHEDULE);
        if (stack.isEmpty()) {
            return;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putLong(SCHEDULE_OWNER_TAG, owner.asLong()));
        setChanged();
    }

    private void claimScheduleOwner(ServerLevel server, BlockPos owner) {
        SCHEDULE_OWNER.put(owner, owner);
        for (AutopilotConsoleBlockEntity sibling : siblings(server)) {
            SCHEDULE_OWNER.put(sibling.worldPosition, owner);
        }
    }

    private static UUID trainKey(ServerLevel server, VehicleHandle vehicle) {
        UUID key = vehicle.id();
        for (VehicleHandle handle : YardState.handles(server, vehicle)) {
            if (handle.id().compareTo(key) < 0) {
                key = handle.id();
            }
        }
        return key;
    }

    private void claimTrainDriver(ServerLevel server, VehicleHandle vehicle) {
        TRAIN_DRIVER.put(trainKey(server, vehicle), worldPosition);
    }

    private boolean anotherConsoleIsTrainDriver(ServerLevel server, VehicleHandle vehicle) {
        BlockPos driver = TRAIN_DRIVER.get(trainKey(server, vehicle));
        if (driver != null && !driver.equals(worldPosition)
                && level.getBlockEntity(driver) instanceof AutopilotConsoleBlockEntity) {
            return true;
        }
        if (driver != null && !driver.equals(worldPosition)) {
            TRAIN_DRIVER.remove(trainKey(server, vehicle));
        }
        // A console on an adjacent car whose coupler link is not visible in this sub-level's handle
        // list must still yield. A nearby registered driver is enough to suppress this one.
        for (BlockPos other : TRAIN_DRIVER.values()) {
            if (other.equals(worldPosition)) {
                continue;
            }
            if (other.distSqr(worldPosition) <= 32.0D * 32.0D
                    && level.getBlockEntity(other) instanceof AutopilotConsoleBlockEntity) {
                return true;
            }
        }
        return false;
    }

    private List<AutopilotConsoleBlockEntity> siblings(ServerLevel server) {
        Optional<VehicleHandle> here = RailServices.physics().vehicleOf(server, worldPosition);
        if (here.isEmpty()) {
            return List.of();
        }
        List<AutopilotConsoleBlockEntity> others = new ArrayList<>();
        java.util.Set<UUID> visited = new java.util.HashSet<>();
        collectConsoles(here.get(), others, visited);
        for (UUID id : here.get().connectedVehicleIds()) {
            RailServices.physics().vehicleById(server, id)
                    .ifPresent(connected -> collectConsoles(connected, others, visited));
        }
        return others;
    }

    private void collectConsoles(VehicleHandle handle, List<AutopilotConsoleBlockEntity> out,
                                 java.util.Set<UUID> visited) {
        if (!visited.add(handle.id())) {
            return;
        }
        for (AutopilotConsoleBlockEntity console : handle.actorsOfType(AutopilotConsoleBlockEntity.class)) {
            if (console != this && !out.contains(console)) {
                out.add(console);
            }
        }
    }

    /** Copies the Create schedule stack, including savedProgress, to the other consoles. */
    private void syncScheduleToSiblings(ServerLevel server) {
        if (syncingSchedule) {
            return;
        }
        ItemStack stack = inventory.getStackInSlot(SLOT_SCHEDULE);
        syncingSchedule = true;
        try {
            List<AutopilotConsoleBlockEntity> siblings = siblings(server);
            int copied = 0;
            for (AutopilotConsoleBlockEntity sibling : siblings) {
                ItemStack theirs = sibling.inventory.getStackInSlot(SLOT_SCHEDULE);
                if (!ItemStack.matches(theirs, stack)) {
                    sibling.inventory.setStackInSlot(SLOT_SCHEDULE, stack.copy());
                    copied++;
                }
            }
            if (copied > 0 || !siblings.isEmpty()) {
                RailDispatch.debug("Autopilot console at {}: schedule sync, {} sibling console(s), "
                        + "{} updated", worldPosition, siblings.size(), copied);
            }
        } finally {
            syncingSchedule = false;
        }
    }

    /**
     * Hands the current leg to the console at the other end when this one faces the wrong way.
     *
     * <p>Both consoles hold the same Create schedule. One-direction consoles exist as a pair; when the
     * next entry heads back the other way, the console facing that way must start the leg, not the one
     * that has just arrived. The comparison is the drive direction against the route's first movement,
     * the same direction the local gate uses.
     */
    private boolean handOffToSibling(ServerLevel server, VehicleHandle vehicle, Vec3 firstMovement) {
        // Coupling direction is chosen explicitly from the target coupler. The route's first leg often
        // starts by reversing over the node behind the train, which used to hand the entry to the other
        // cab; that cab then handed it straight back from the facing check, forever.
        if (com.raildispatch.train.ConsistLocator.isConsistTarget(destination)) {
            return false;
        }
        Vec3 mine = driveDirection(vehicle);
        double myDot = mine == null ? -2.0D : mine.dot(firstMovement);
        AutopilotConsoleBlockEntity best = null;
        double bestDot = Math.max(myDot + 0.05D, 0.1D);
        for (AutopilotConsoleBlockEntity sibling : siblings(server)) {
            if (!sibling.hasSchedule()) {
                continue;
            }
            Vec3 theirDrive = RailServices.physics().vehicleOf(server, sibling.getBlockPos())
                    .map(sibling::driveDirection).orElse(null);
            if (theirDrive == null) {
                continue;
            }
            double dot = theirDrive.dot(firstMovement);
            if (dot > bestDot) {
                bestDot = dot;
                best = sibling;
            }
        }
        if (best == null) {
            return false;
        }
        RailDispatch.debug("Autopilot console at {}: handing the leg to the console at {} "
                        + "(facing {} vs first movement {}); stopping before the handoff", worldPosition,
                best.worldPosition, String.format(Locale.ROOT, "%.2f", bestDot),
                String.format(Locale.ROOT, "%.2f", myDot));
        beginHandoff(server, vehicle, best);
        return true;
    }

    /** Stops the whole train first; the pending console starts only once speed is zero. */
    private void beginHandoff(ServerLevel server, VehicleHandle vehicle, AutopilotConsoleBlockEntity target) {
        pendingHandoff = target;
        handoffSince = server.getGameTime();
        releaseRoute(server);
        routeGranted = false;
        engaged = false;
        scheduleActive = false;
        timetableRunning = false;
        timetableWaiting = false;
        state = AutopilotState.HOLDING;
        detail = "stopping before control handoff";
        wrongDirection = false;
        holdVehicle(vehicle);
    }

    /**
     * Coupling direction is a facing question, not a route question: choose the console whose forward
     * axis points at the target consist, before a route is ever planned. The old check used the first
     * route leg, which can loop around and make the wrong cab look correct.
     */
    private boolean handOffCoupleDirection(ServerLevel server, VehicleHandle vehicle, String service) {
        java.util.UUID near = com.raildispatch.train.ConsistLocator
                .nearestVehicle(server, service, vehicle.position()).orElse(null);
        if (near == null) {
            return false;
        }
        VehicleHandle target = RailServices.physics().vehicleById(server, near).orElse(null);
        if (target == null) {
            return false;
        }
        Vec3 toTarget = target.position().subtract(vehicle.position());
        toTarget = new Vec3(toTarget.x, 0.0D, toTarget.z);
        if (toTarget.lengthSqr() < 1.0E-4D) {
            return false;
        }
        Vec3 direction = toTarget.normalize();
        Vec3 mine = driveDirection(vehicle);
        if (mine == null) {
            return false;
        }
        double myDot = mine.dot(direction);
        AutopilotConsoleBlockEntity best = null;
        double bestDot = myDot + 0.05D;
        for (AutopilotConsoleBlockEntity sibling : siblings(server)) {
            VehicleHandle their = RailServices.physics().vehicleOf(server, sibling.worldPosition)
                    .orElse(null);
            if (their == null) {
                continue;
            }
            Vec3 theirs = sibling.driveDirection(their);
            if (theirs == null) {
                continue;
            }
            double dot = theirs.dot(direction);
            if (dot > bestDot) {
                bestDot = dot;
                best = sibling;
            }
        }
        if (best == null) {
            return false;
        }
        RailDispatch.debug("Autopilot console at {}: coupling direction is toward {}, handing the "
                        + "entry to console at {} (facing {} vs mine {})", worldPosition,
                String.format(Locale.ROOT, "%.1f/%.1f", target.position().x, target.position().z),
                best.worldPosition, String.format(Locale.ROOT, "%.2f", bestDot),
                String.format(Locale.ROOT, "%.2f", myDot));
        syncScheduleToSiblings(server);
        beginHandoff(server, vehicle, best);
        return true;
    }

    private void startSchedule(ServerLevel server, Player player) {
        Optional<VehicleHandle> here = RailServices.physics().vehicleOf(server, worldPosition);
        if (here.isPresent()) {
            vehicleId = here.get().id();
            claimTrainDriver(server, here.get());
            claimScheduleOwner(server, worldPosition);
            setScheduleOwner(worldPosition);
        }
        Optional<ScheduleView> view = scheduleView(server);
        if (view.isEmpty()) {
            if (player != null) {
                player.displayClientMessage(
                        Component.translatable("raildispatch.autopilot.schedule_empty"), true);
            }
            return;
        }
        timetableRunning = false;
        timetableWaiting = false;
        scheduleActive = true;
        scheduleWaitUntil = 0L;
        scheduleSpeedLimit = -1.0D;
        startScheduleEntry(server, view.get());
        if (player != null) {
            player.displayClientMessage(
                    Component.translatable("raildispatch.autopilot.schedule_started"), true);
        }
    }

    private void startScheduleEntry(ServerLevel server, ScheduleView view) {
        // The owner is stored on the schedule item and copied with it. This survives the two consoles
        // being on different cars with different vehicle UUIDs, which is what defeated every
        // vehicle-map owner check: both ends saw a different first owner and both executed the entry.
        BlockPos itemOwner = scheduleOwner();
        if (itemOwner != null && !itemOwner.equals(worldPosition)
                && level.getBlockEntity(itemOwner) instanceof AutopilotConsoleBlockEntity) {
            scheduleActive = false;
            engaged = false;
            destination = "";
            state = AutopilotState.IDLE;
            detail = "another console owns this schedule";
            clearChannels(server);
            RailDispatch.debug("Autopilot console at {}: schedule entry ignored, console at {} owns "
                    + "this schedule item", worldPosition, itemOwner);
            return;
        }
        setScheduleOwner(worldPosition);
        syncScheduleToSiblings(server);
        VehicleHandle resolved = resolveVehicle(server);
        if (resolved == null) {
            scheduleActive = false;
            engaged = false;
            state = AutopilotState.IDLE;
            detail = "schedule entry without a vehicle";
            return;
        }
        vehicleId = resolved.id();
        // The schedule-item owner above is authoritative. A second vehicle/sub-level owner map used to
        // be consulted here as well; after a coupling it could still name the other console and block
        // the next entry even though the item owner said this console. Do not let two owners disagree.
        claimTrainDriver(server, resolved);
        CabRegistry.claim(server, vehicleId, worldPosition);
        standDownSiblings(server);
        scheduleEntry = view.currentEntry() + 1;
        scheduleCount = view.entryCount();
        if (view.speedLimit().isPresent()) {
            double limit = view.speedLimit().get();
            scheduleSpeedLimit = limit > 0.0D ? limit : -1.0D;
            scheduleActive = true;
            engaged = false;
            destination = "";
            scheduleWaitUntil = 0L;
            releaseRoute(server);
            autopilotReset();
            state = AutopilotState.HOLDING;
            detail = scheduleSpeedLimit > 0.0D
                    ? String.format(Locale.ROOT, "speed limit %.1f b/s", scheduleSpeedLimit)
                    : "speed limit cleared";
            setChanged();
            sync();
            RailDispatch.debug("Autopilot console at {}: schedule speed limit {}", worldPosition,
                    scheduleSpeedLimit > 0.0D
                            ? String.format(Locale.ROOT, "%.1f blocks/s", scheduleSpeedLimit) : "cleared");
            advanceSchedule(server);
            return;
        }
        if (view.shuntingChange() != 0) {
            shuntingMode = view.shuntingChange() == ScheduleView.SHUNTING_ENTER;
            scheduleActive = true;
            engaged = false;
            standAside = false;
            uncoupling = false;
            destination = "";
            releaseRoute(server);
            autopilotReset();
            scheduleWaitUntil = server.getGameTime() + 20L;
            state = AutopilotState.HOLDING;
            detail = shuntingMode ? "entering shunting mode" : "leaving shunting mode";
            setChanged();
            sync();
            RailDispatch.debug("Autopilot console at {}: {}", worldPosition,
                    shuntingMode ? "entering shunting mode (signals may be passed)"
                            : "leaving shunting mode (signals enforced again)");
            return;
        }
        if (view.coupleConsist().isPresent()) {
            String service = view.coupleConsist().get();
            if (handOffCoupleDirection(server, resolved, service)) {
                return;
            }
            Vec3 from = resolved.position();
            java.util.UUID near = com.raildispatch.train.ConsistLocator
                    .nearestVehicle(server, service, from).orElse(null);
            com.raildispatch.api.CouplerInfo coupler = com.raildispatch.train.ConsistLocator
                    .nearestCoupler(server, service, resolved).orElse(null);
            destination = com.raildispatch.train.ConsistLocator.destination(service, near, coupler);
            scheduleActive = true;
            engaged = true;
            standAside = false;
            uncoupling = false;
            loggedDestinationTrouble = false;
            releaseRoute(server);
            autopilotReset();
            state = AutopilotState.PLANNING;
            detail = "coupling to service " + service + " at its nearest end"
                    + (shuntingMode ? "; shunting mode, signals ignored" : "");
            setChanged();
            sync();
            String consoleGraph = resolved.trackGraph() == null ? "none" : resolved.trackGraph().id.toString();
            String targetGraph = near == null ? "none"
                    : RailServices.physics().vehicleById(server, near)
                            .map(v -> v.trackGraph() == null ? "none" : v.trackGraph().id.toString())
                            .orElse("missing");
            RailDispatch.debug("Autopilot console at {}: couple-consist entry for service '{}', "
                            + "target coupler {} world {} (plot {}) near car {}; console graph {}, target "
                            + "car graph {}", worldPosition, service,
                    coupler == null ? "<none>" : coupler.pos(),
                    coupler == null ? "<none>" : String.format(Locale.ROOT, "%.2f/%.2f/%.2f",
                            coupler.worldEnd().x, coupler.worldEnd().y, coupler.worldEnd().z),
                    coupler == null ? "<none>" : String.format(Locale.ROOT, "%.2f/%.2f/%.2f",
                            coupler.end().x, coupler.end().y, coupler.end().z),
                    near, consoleGraph, targetGraph);
            return;
        }
        if (view.uncouple()) {
            // Hold the uncouple redstone/channel for 20 seconds, but do not block the schedule:
            // advance to the next entry immediately, in parallel with the pulse.
            scheduleActive = true;
            engaged = false;
            destination = "";
            standAside = false;
            uncoupling = false;
            scheduleWaitUntil = 0L;
            releaseRoute(server);
            autopilotReset();
            pulseCoupler(server, 400);
            state = AutopilotState.PLANNING;
            detail = "uncoupling pulse (20s)";
            setChanged();
            sync();
            advanceSchedule(server);
            return;
        }
        String wanted = view.destination()
                .orElseGet(() -> view.coupleTag().orElseGet(() -> view.shuntAddress().orElse("")));
        scheduleEntry = view.currentEntry() + 1;
        scheduleCount = view.entryCount();
        if (wanted.isBlank()) {
            scheduleActive = false;
            engaged = false;
            state = AutopilotState.IDLE;
            detail = "schedule entry has no supported destination";
            setChanged();
            sync();
            return;
        }
        destination = wanted;
        engaged = true;
        standAside = false;
        loggedDestinationTrouble = false;
        releaseRoute(server);
        autopilotReset();
        state = AutopilotState.PLANNING;
        detail = "planning";
        setChanged();
        sync();
    }

    /** Drives the console's redstone output and uncouple channel high for {@code ticks}. */
    private void pulseCoupler(ServerLevel server, int ticks) {
        couplerPulseUntil = server.getGameTime() + ticks;
        server.updateNeighborsAt(worldPosition, getBlockState().getBlock());
        publishChannels(server);
        RailDispatch.debug("Autopilot console at {}: uncouple pulse, redstone and Drive By Wire "
                + "channel {} high for {} ticks", worldPosition, AutopilotConsoleBlock.CHANNEL_UNCOUPLE, ticks);
    }

    /**
     * Vanilla redstone output of the console block: the larger of the published forward/brake channel.
     * Drive By Wire has its own channel map, but a player measuring the block with a comparator or
     * redstone dust needs a real block signal too.
     */
    public int redstoneOutput() {
        return Math.max(publishedForward, publishedBrake);
    }

    /** Redstone output level. Zero when the uncouple pulse is not active. */
    public int couplerSignal(Level level) {
        if (couplerPulseUntil <= 0L || level == null) {
            return 0;
        }
        return level.getGameTime() < couplerPulseUntil ? 15 : 0;
    }

    private void advanceSchedule(ServerLevel server) {
        Optional<ScheduleView> next = Schedules.advance(server.registryAccess(),
                inventory.getStackInSlot(SLOT_SCHEDULE));
        // Carry the advanced Progress to the other console before it is asked to run the next entry.
        syncScheduleToSiblings(server);
        if (next.isEmpty()) {
            scheduleActive = false;
            scheduleWaitUntil = 0L;
            engaged = false;
            state = AutopilotState.ARRIVED;
            detail = "schedule complete";
            setChanged();
            sync();
            return;
        }
        startScheduleEntry(server, next.get());
    }

    /**
     * Makes the entry at {@link #timetableIndex} current: sets its destination and either starts the leg
     * or holds at the platform until its departure time.
     */
    private void beginTimetableEntry(ServerLevel server, Player player) {
        if (timetable.isEmpty()) {
            stopJourney(server);
            return;
        }
        if (timetableIndex >= timetable.size()) {
            if (!timetableLoop) {
                timetableRunning = false;
                timetableWaiting = false;
                engaged = false;
                state = AutopilotState.ARRIVED;
                detail = "timetable complete";
                setChanged();
                sync();
                return;
            }
            timetableIndex = 0;
        }
        TimetableEntry entry = timetable.get(timetableIndex);
        destination = entry.destination();
        engaged = true;
        standAside = false;
        loggedDestinationTrouble = false;
        releaseRoute(server);
        autopilotReset();
        int wait = waitTicks(server, entry);
        if (wait > 0) {
            timetableWaiting = true;
            state = AutopilotState.HOLDING;
            detail = "timetable: depart " + entry.clock() + " in " + formatWait(wait);
        } else {
            timetableWaiting = false;
            state = AutopilotState.PLANNING;
            detail = "planning";
        }
        if (player != null) {
            player.displayClientMessage(Component.translatable(
                    "raildispatch.autopilot.timetable_started", entry.clock(), entry.destination()), true);
        }
        setChanged();
        sync();
    }

    /** Ticks until the entry's departure; 0 when it is due, or late by at most ten seconds. */
    private static int waitTicks(ServerLevel server, TimetableEntry entry) {
        int now = (int) Math.floorMod(server.getDayTime(), (long) TimetableEntry.DAY);
        int departure = entry.departureTicks();
        if (departure >= now) {
            return departure - now;
        }
        int past = now - departure;
        // A train up to one Minecraft hour late should go now, not wait a whole day; anything more is the
        // next day's service. One Minecraft hour is 1000 ticks, about fifty real seconds.
        return past <= TimetableEntry.TICKS_PER_HOUR ? 0 : TimetableEntry.DAY - past;
    }

    private static String formatWait(int ticks) {
        int seconds = Math.max(0, ticks / 20);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** Default departure for an entry added without a time: about one Minecraft hour ahead. */
    private static int defaultDeparture(ServerLevel server) {
        int now = (int) Math.floorMod(server.getDayTime(), (long) TimetableEntry.DAY);
        return Math.floorMod(now + TimetableEntry.TICKS_PER_HOUR + 200, TimetableEntry.DAY);
    }

    public void onNeighborChanged() {
        retryTimer = 0;
    }

    /**
     * Commands the service brake immediately after a stop command.
     *
     * <p>{@link #autopilotReset()} zeroes the commanded values, which is right for a leg that is being
     * torn down - but on its own it would leave a moving vehicle coasting until the next decision. The
     * rigid-body brake is what actually slows it; the channel's parking brake only holds the result.
     */
    private void brakeNow() {
        commandedBrake = 1.0D;
        if (currentVehicle != null && currentVehicle.isValid()) {
            currentVehicle.command(worldPosition, 0.0D, 1.0D, 0.0D);
        }
    }

    /** Forgets the leg: route, PID and direction all belong to the journey that just ended. */
    private void autopilotReset() {
        routeGranted = false;
        progress = null;
        retryTimer = 0;
        speedPid.reset();
        lastApplyTick = -1L;
        lastSwitchIndex = -1;
        lastSwitchTick = -1L;
        filteredSpeed = -1.0D;
        closestEnd = -1.0D;
        lastDistanceBrake = 0.0D;
        wrongDirection = false;
        targetSpeed = 0.0D;
        commandedDrive = 0.0D;
        commandedBrake = 0.0D;
        commandedSteer = 0.0D;
    }

    private void releaseRoute(ServerLevel server) {
        if (vehicleId != null) {
            RailServices.track().releaseRoute(server, vehicleId);
        }
        routeGranted = false;
        progress = null;
        currentRoute = null;
    }

    // ------------------------------------------------------------------ tick

    public void serverTick() {
        actorTick();
    }

    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        actorTick();
    }

    /**
     * One pass per game tick, whichever hook got here first; decisions are taken every fifth tick.
     *
     * <p>The command is refreshed every tick on purpose. Sable's brake is an impulse with a per-tick
     * time step, and the channels are only dirty-checked, so re-sending the same command is cheap -
     * whereas letting it lapse between decisions is not.
     */
    private void actorTick() {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        if (server.getServer() != null && !server.getServer().isRunning()) {
            // Do not drive or publish during world shutdown. A live forward signal keeps a bogie
            // ticking while Sable/Simurail is tearing down sub-levels, which is when the null
            // kinetic-network NPE surfaces.
            clearChannels(server);
            return;
        }
        long now = server.getGameTime();
        if (now == lastControlTick) {
            return;
        }
        lastControlTick = now;
        if (couplerPulseUntil > 0L && now >= couplerPulseUntil) {
            couplerPulseUntil = 0L;
            server.updateNeighborsAt(worldPosition, getBlockState().getBlock());
            publishChannels(server);
            RailDispatch.debug("Autopilot console at {}: uncouple pulse ended", worldPosition);
        } else if (couplerPulseUntil > 0L && now % 20L == 0L) {
            // Re-notify neighbours during the 20 s hold; some couplers only sample on a neighbour update.
            server.updateNeighborsAt(worldPosition, getBlockState().getBlock());
        }
        if (now % CONTROL_PERIOD == 0L) {
            decide(server, now);
        }
        refreshCommand();
        publishChannels(server);
    }

    private void decide(ServerLevel server, long now) {
        if (!loggedFirstCycle) {
            loggedFirstCycle = true;
            RailDispatch.debug("Autopilot console at {} is running its control loop ({}).",
                    worldPosition, server.getClass().getSimpleName());
        }

        VehicleHandle vehicle = resolveVehicle(server);
        if (vehicle == null) {
            if (!loggedNoVehicle) {
                loggedNoVehicle = true;
                RailDispatch.LOGGER.warn("Autopilot console at {} found no physics vehicle: physicsAvailable={}, "
                                + "cachedVehicleId={}. Mount it on the vehicle and assemble.",
                        worldPosition, RailServices.physics().isAvailable(), vehicleId);
            }
            standAside = true;
            currentVehicle = null;
            speed = 0.0D;
            targetSpeed = 0.0D;
            state = AutopilotState.IDLE;
            publishStatus();
            return;
        }
        currentVehicle = vehicle;
        vehicleId = vehicle.id();
        speed = vehicle.speed() * 20.0D;

        BlockPos itemOwner = scheduleOwner();
        if (itemOwner != null && !itemOwner.equals(worldPosition)
                && level.getBlockEntity(itemOwner) instanceof AutopilotConsoleBlockEntity) {
            standAside = true;
            engaged = false;
            scheduleActive = false;
            state = AutopilotState.IDLE;
            detail = "standing by for the schedule owner";
            publishStatus();
            return;
        }

        if (anotherConsoleIsTrainDriver(server, vehicle)) {
            standAside = true;
            engaged = false;
            state = AutopilotState.IDLE;
            detail = "another console drives this train";
            publishStatus();
            return;
        }

        if (pendingHandoff != null) {
            if (pendingHandoff.isRemoved()) {
                pendingHandoff = null;
            } else {
                holdVehicle(vehicle);
                state = AutopilotState.HOLDING;
                detail = "stopping before control handoff";
                if (vehicle.speed() * 20.0D < 0.4D || now - handoffSince > 200L) {
                    vehicle.emergencyStop();
                    AutopilotConsoleBlockEntity target = pendingHandoff;
                    pendingHandoff = null;
                    scheduleActive = false;
                    engaged = false;
                    destination = "";
                    releaseRoute(server);
                    state = AutopilotState.IDLE;
                    detail = "control handed over at standstill";
                    // Release this cab's parking brake before the other cab drives, or the latched 15
                    // holds the train even though the new cab publishes brake=0.
                    clearChannels(server);
                    claimScheduleOwner(server, target.worldPosition);
                    target.setScheduleOwner(target.worldPosition);
                    target.startSchedule(server, null);
                    RailDispatch.debug("Autopilot console at {}: train stopped; control handed to "
                            + "console at {}", worldPosition, target.worldPosition);
                }
                publishStatus();
                return;
            }
        }

        if (reportDerailment(server, now, vehicle)) {
            publishStatus();
            return;
        }

        // A schedule-driven cab always wins: it is the more specific instruction a player gave. Standing
        // aside must mean "send no command", not "command a stop" - a holding cab that still commanded
        // would fight the very cab it is yielding to.
        if (scheduleCabInCharge(vehicle)) {
            standAside = true;
            engaged = false;
            releaseRoute(server);
            state = AutopilotState.IDLE;
            detail = "schedule cab in charge";
            publishStatus();
            return;
        }
        // A second console on the same vehicle stands aside. One console per vehicle drives, first come
        // first served through the cabinet registry; the loser sends no command and keeps its hands off
        // the route table, which is keyed by vehicle and would otherwise be released out from under the
        // console that is actually driving.
        if (anotherConsoleDriving(server, vehicle)) {
            standAside = true;
            state = AutopilotState.IDLE;
            detail = "another console in charge";
            publishStatus();
            return;
        }

        standAside = false;
        if (engaged) {
            // Declares which block owns this vehicle, so anything else that respects the registry (the
            // cab's own guard does) can see that the console is in charge.
            CabRegistry.claim(server, vehicle.id(), worldPosition);
        } else {
            // A console that has stopped must give the vehicle back. A stale registry entry made
            // YardState believe a free wagon was still under traction, so nameplate shunting never ran.
            CabRegistry.releaseAt(server, worldPosition);
        }

        // A redstone signal is an emergency stop, and one that has to be acknowledged: the console
        // disengages rather than resuming the moment the signal falls, so a lever cannot be used as a
        // second throttle by accident.
        if (level.hasNeighborSignal(worldPosition)) {
            if (engaged) {
                engaged = false;
                releaseRoute(server);
                detail = "stopped by redstone";
                RailDispatch.debug("Autopilot console at {} stopped by a redstone signal", worldPosition);
            }
            state = AutopilotState.HOLDING;
            holdVehicle(vehicle);
            publishStatus();
            return;
        }

        if (scheduleActive && uncoupling) {
            holdVehicle(vehicle);
            state = AutopilotState.HOLDING;
            detail = "uncoupling";
            signal = SignalAspect.NONE;
            signalDistance = -1.0D;
            if (now >= scheduleWaitUntil) {
                uncoupling = false;
                if (couplerPulseUntil > 0L) {
                    couplerPulseUntil = 0L;
                    level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
                    publishChannels(server);
                }
                advanceSchedule(server);
            }
            publishStatus();
            return;
        }

        // Create schedule: a timed wait condition holds the vehicle at the platform, then the schedule is
        // advanced and its next entry begins. Placed after the redstone check for the same reason as the
        // timetable wait: an emergency stop must not be silently resumed.
        if (scheduleActive && scheduleWaitUntil > 0L) {
            long remaining = scheduleWaitUntil - now;
            if (remaining > 0L) {
                holdVehicle(vehicle);
                state = AutopilotState.HOLDING;
                detail = "schedule wait " + formatWait((int) remaining);
                signal = SignalAspect.NONE;
                signalDistance = -1.0D;
                publishStatus();
                return;
            }
            scheduleWaitUntil = 0L;
            advanceSchedule(server);
            if (!scheduleActive) {
                publishStatus();
                return;
            }
        }

        // Not a journey: keep the vehicle where it is. Checked only after the schedule's own
        // destination-less entries (enter/leave shunting, uncouple) have had their wait processed;
        // otherwise those entries could never advance to the next schedule entry.
        if (!engaged || destination.isBlank()) {
            if (state != AutopilotState.ARRIVED) {
                state = AutopilotState.IDLE;
            }
            if (state == AutopilotState.IDLE) {
                signal = SignalAspect.NONE;
                signalDistance = -1.0D;
            }
            holdVehicle(vehicle);
            publishStatus();
            return;
        }

        // Timetable: hold at the platform until the current entry's departure time, then fall through to
        // the normal planning path. The leg was not started when the entry became current; this is the
        // moment it starts. Kept after the redstone check so an emergency stop cannot be silently resumed
        // by the timetable.
        if (timetableRunning && timetableWaiting) {
            TimetableEntry entry = currentTimetableEntry();
            int wait = entry == null ? 0 : waitTicks(server, entry);
            if (wait > 0) {
                holdVehicle(vehicle);
                state = AutopilotState.HOLDING;
                detail = "timetable: depart " + entry.clock() + " in " + formatWait(wait);
                signal = SignalAspect.NONE;
                signalDistance = -1.0D;
                publishStatus();
                return;
            }
            timetableWaiting = false;
            state = AutopilotState.PLANNING;
            detail = "planning";
            setChanged();
        }

        switch (state) {
            case IDLE, PLANNING -> plan(server, vehicle);
            case RUNNING, APPROACHING, HOLDING -> drive(server, vehicle, now);
            case ARRIVED -> holdVehicle(vehicle);
        }
        publishStatus();
    }

    // ------------------------------------------------------------------ planning

    private void plan(ServerLevel server, VehicleHandle vehicle) {
        if (retryTimer > 0) {
            retryTimer--;
            holdVehicle(vehicle);
            return;
        }
        TrackNetwork network = RailServices.track();
        if (!network.isAvailable()) {
            detail = "no track network";
            state = AutopilotState.HOLDING;
            retryTimer = ROUTE_RETRY_TICKS;
            holdVehicle(vehicle);
            reportDestinationTrouble(server, network);
            return;
        }
        Optional<Vec3> target = network.resolveDestination(server, destination);
        if (target.isEmpty()) {
            // Never "drive on without a route": the destination could not be resolved, and proceeding is
            // how the whole dispatch layer came to look dead.
            detail = "destination not found: " + destination;
            state = AutopilotState.HOLDING;
            retryTimer = ROUTE_RETRY_TICKS;
            holdVehicle(vehicle);
            reportDestinationTrouble(server, network);
            return;
        }

        TryRoute:
        {
            TrackNetwork.RouteGrant grant = network.requestRoute(server, vehicle.id(), vehicle.name(),
                    vehicle.position(), target.get(), RDConfig.allowLocalSwitchControl());
            routeGranted = grant.granted();
            detail = grant.detail();
            retryTimer = ROUTE_RETRY_TICKS;
            if (!grant.granted()) {
                state = AutopilotState.PLANNING;
                holdVehicle(vehicle);
                break TryRoute;
            }
            Optional<TrackNetwork.PlannedRoute> route = network.grantedRoute(server, vehicle.id());
            if (route.isEmpty()) {
                // A network that grants but does not publish its node sequence cannot be followed. Treat
                // that as a refusal rather than driving blind at the destination.
                network.releaseRoute(server, vehicle.id());
                routeGranted = false;
                detail = "route granted without a node sequence";
                state = AutopilotState.PLANNING;
                holdVehicle(vehicle);
                break TryRoute;
            }
            // A route that immediately returns along the edge it came from needs a reversal. This console
            // has no reverse thrust, so refuse it up front rather than driving to the turnaround and
            // stopping there. The test is an exact node repeat (A -> B -> A), which is what a stub
            // reversal looks like; a balloon loop revisits a node without making it the very next one and
            // is still a forward journey.
            int reversal = reversalIndex(route.get());
            if (reversal >= 0) {
                network.releaseRoute(server, vehicle.id());
                routeGranted = false;
                engaged = false;
                state = AutopilotState.IDLE;
                wrongDirection = true;
                detail = "route reverses at node " + reversal + "; this console only drives forward";
                holdVehicle(vehicle);
                RailDispatch.debug("Autopilot console at {}: route to '{}' reverses at node {}; "
                        + "refusing it (no reverse channel)", worldPosition, destination, reversal);
                break TryRoute;
            }
            // One-direction gate. The console drives away from its screen face, so a route whose first
            // movement sets off the other way is not something it can execute: reversing out of the
            // station is the other console's job. Refuse instead of driving off in the wrong direction.
            //
            // The direction tested is the route follower's first leg, not "the first waypoint's outgoing
            // edge": a plan may start with the near end of the edge the vehicle already stands on, and the
            // immediate movement is toward the far end. RouteProgress skips that near node when the
            // vehicle lies between the two, so its first leg is the movement that will actually happen.
            RouteProgress plannedProgress = new RouteProgress(route.get());
            plannedProgress.update(vehicle.position());
            Vec3 drive = driveDirection(vehicle);
            Vec3 firstMovement = plannedProgress.legDirection(vehicle.position());
            if (drive != null && firstMovement != null && drive.dot(firstMovement) < -0.1D
                    && !com.raildispatch.train.ConsistLocator.isConsistTarget(destination)) {
                if (handOffToSibling(server, vehicle, firstMovement)) {
                    break TryRoute;
                }
                network.releaseRoute(server, vehicle.id());
                routeGranted = false;
                engaged = false;
                state = AutopilotState.IDLE;
                detail = "destination is behind this console; use the console facing that way";
                wrongDirection = true;
                holdVehicle(vehicle);
                RailDispatch.debug("Autopilot console at {}: destination '{}' resolves behind this "
                        + "console (drive direction {} vs first movement {}); refusing to reverse",
                        worldPosition, destination, drive, firstMovement);
                break TryRoute;
            }
            if (drive != null && firstMovement != null && drive.dot(firstMovement) < -0.1D) {
                RailDispatch.debug("Autopilot console at {}: coupling route starts with a reverse "
                                + "leg; continuing because the facing-side console was chosen from the "
                                + "target coupler", worldPosition);
            }
            wrongDirection = false;
            progress = plannedProgress;
            currentRoute = route.get();
            state = AutopilotState.RUNNING;
            detail = String.format(Locale.ROOT, "route %.0f blocks, %d junction(s)",
                    route.get().length(), route.get().junctions().size());
            closestEnd = -1.0D;
            speedPid.reset();
            lastApplyTick = -1L;
            filteredSpeed = -1.0D;
            RailDispatch.debug("Autopilot console at {}: route to '{}' GRANTED ({}); following {} node(s)",
                    worldPosition, destination, detail, progress.size());
        }
    }

    // ------------------------------------------------------------------ driving

    private void drive(ServerLevel server, VehicleHandle vehicle, long now) {
        TrackNetwork network = RailServices.track();
        // The route table is session state, and anything else driving this vehicle - a cab whose
        // schedule was just changed, a release from a dispatch centre - can take it away. Following a
        // route that no longer exists would mean running switches nobody reserved.
        if (!routeGranted || progress == null || currentRoute == null
                || network.grantedRoute(server, vehicle.id()).isEmpty()) {
            routeGranted = false;
            progress = null;
            currentRoute = null;
            state = AutopilotState.PLANNING;
            retryTimer = 0;
            holdVehicle(vehicle);
            return;
        }
        Vec3 position = vehicle.position();
        progress.update(position);
        Vec3 next = progress.nextPoint();
        double remaining = progress.remaining(position);

        // Signals are read along the route's own edges, not along a world heading. A heading is a
        // straight line to the next node, and at a turnout that line can point down the other branch -
        // which reads the neighbouring line's signal, or reports none at all while a red light stands
        // ahead. The route knows which edges are being traversed; the search walks those in order.
        SignalInfo info = network.signalAheadAlong(server, currentRoute, progress.index(), position,
                RDConfig.signalLookahead());
        // A long train can straddle a signal: its centre is still on the near side while the leading axle
        // has already passed the signal and occupied the block, so the route walk reports a red a few
        // blocks "ahead" of the centre. At low speed that deadlocks the train against a signal it has
        // itself triggered. Simurail's probe reads from the leading axle; if it sees no signal ahead, a
        // red this close behind the axle is the signal the train is already occupying, not one to stop at.
        TrackProbe probe = vehicle.probeAhead();
        if (info.aspect() == SignalAspect.DANGER
                && probe != null
                && probe.signalDistance() < 0.0D
                && info.distance() >= 0.0D
                && info.distance() < 8.0D
                && speed < 3.0D) {
            if (now - lastSelfSignalLog >= 100L) {
                lastSelfSignalLog = now;
                RailDispatch.debug("Autopilot console at {}: ignoring a red {} blocks from the centre; "
                                + "the leading axle has already passed it and the probe sees no signal ahead",
                        worldPosition, String.format(Locale.ROOT, "%.1f", info.distance()));
            }
            info = SignalInfo.NONE;
        }
        // Coupling to a consist is a shunting move, not a line run. A shunting movement may pass a
        // signal at danger and may pass a signal that only faces the other direction; the driver is
        // authorised by the movement itself. Everything else - redstone stop, derailment, route and
        // switch reservations - still applies.
        if (isShunting()) {
            if (info.aspect() != SignalAspect.NONE && now - lastShuntSignalLog >= 100L) {
                lastShuntSignalLog = now;
                RailDispatch.debug("Autopilot console at {}: shunting, ignoring {} signal at {} "
                                + "blocks while approaching the consist",
                        worldPosition, info.aspect(), String.format(Locale.ROOT, "%.1f", info.distance()));
            }
            info = SignalInfo.NONE;
        }
        signal = info.aspect();
        signalDistance = info.distance();

        double wanted = isShunting() ? Math.min(tuningMaxSpeed, 5.0D) : tuningMaxSpeed;
        if (scheduleSpeedLimit > 0.0D) {
            wanted = Math.min(wanted, scheduleSpeedLimit);
        }
        if (signal == SignalAspect.DANGER && RDConfig.stopOnDanger()) {
            if (signalDistance < 0.0D) {
                // "There is something in the block and I cannot see how far away" means stop.
                state = AutopilotState.HOLDING;
                holdVehicle(vehicle);
                return;
            }
            wanted = Math.min(wanted, speedForStop(signalDistance, RDConfig.signalBrakeMargin()));
            if (wanted <= 0.08D) {
                state = AutopilotState.HOLDING;
                holdVehicle(vehicle);
                return;
            }
            state = AutopilotState.APPROACHING;
        } else if (signal == SignalAspect.CAUTION && RDConfig.creepOnCaution()) {
            wanted = Math.min(wanted, 4.0D);
            state = AutopilotState.APPROACHING;
        } else {
            state = AutopilotState.RUNNING;
        }

        // The route itself limits speed: a turnout taken at line speed is how a bogie leaves the rails.
        double toTurn = next == null ? 0.0D : horizontalDistance(position, next);
        wanted = Math.min(wanted, curveSpeed(progress.turnAngle(position), toTurn));

        Vec3 end = progress.endPoint();
        double endDistance = end == null ? remaining : horizontalDistance(position, end);
        if (closestEnd < 0.0D || endDistance < closestEnd) {
            closestEnd = endDistance;
        }
        // Until the marker is passed, the stopping curve uses the arc length remaining, which is correct
        // on a curve. Once the current distance starts growing again the marker has been passed, and the
        // curve must use the closest distance instead: feeding the growing value into speedForArrival made
        // the target climb and the train accelerate away from the station it had just missed.
        boolean passedEnd = closestEnd >= 0.0D && endDistance > closestEnd + 0.2D;
        double stopDistance = passedEnd ? closestEnd : remaining;
        wanted = Math.min(wanted, speedForArrival(stopDistance));
        // Physics trains do not achieve the theoretical stopping curve. Cap the approach speed well before
        // the platform so there is enough distance left even when the bogies brake weaker than modelled;
        // without this the train arrived at the marker at 5+ b/s and overran it.
        boolean coupledTrain = !vehicle.connectedVehicleIds().isEmpty();
        double approach = approachSpeed(Math.min(remaining, endDistance));
        if (coupledTrain) {
            // A coupled consist is braked only by the locomotive; the trailing sub-levels push it.
            // Assume the effective braking is weaker and start the approach slower.
            approach *= 0.85D;
        }
        wanted = Math.min(wanted, approach);
        targetSpeed = wanted;

        // The route end for a consist is the coupling point, not a platform. Stop on the consist itself:
        // waiting for the route's atEnd test let a coupled train keep pushing past its target because the
        // target's own vehicle had already been shoved away from the recorded point.
        if (isShunting()) {
            // The coupler's own block state is the authoritative "coupled" signal. Distance is only a
            // fallback when the chosen coupler cannot be re-read.
            com.raildispatch.api.CouplerInfo chosen =
                    com.raildispatch.train.ConsistLocator.couplerOf(server, destination);
            if (chosen != null && chosen.connected()) {
                targetSpeed = 0.0D;
                arrive(server, vehicle);
                return;
            }
            double couplingGap = com.raildispatch.train.ConsistLocator.distanceTo(server, destination, vehicle);
            if (couplingGap >= 0.0D && couplingGap <= 4.0D) {
                targetSpeed = 0.0D;
                state = AutopilotState.APPROACHING;
                if (couplingGap <= 3.0D && speed < 0.8D) {
                    arrive(server, vehicle);
                    return;
                }
            } else if (couplingGap > 4.0D) {
                // Ease onto the consist instead of arriving at the shunting cap and then slamming the
                // brakes in the last three blocks.
                targetSpeed = Math.min(targetSpeed, Math.max(1.0D, couplingGap * 0.8D));
            }
        }

        // Below walking pace a brake impulse and the next traction pulse fight each other tick by
        // tick. Once the target speed is zero, remove the remaining velocity in one idempotent step.
        if (targetSpeed <= 0.02D && speed > 0.0D && speed < 0.5D) {
            vehicle.emergencyStop();
        }

        // The configured radius is a fallback for address plates beside the rails; for a station marker a
        // five-block radius lets the train declare arrival while still moving and coast past the platform.
        double arrivalRadius = Math.min(RDConfig.arriveRadius(), 1.5D);
        if (progress.atEnd(position, arrivalRadius)) {
            if (speed < 0.6D) {
                arrive(server, vehicle);
                return;
            }
            targetSpeed = 0.0D;
        }
        // Overshoot guard. A route can end a few blocks beside the rails (an address plate), so the
        // arrival radius can be missed by a metre; without this the vehicle would drive past its mark and
        // then chase it back for ever, because the last leg points behind it. One block past the closest
        // point is already past the marker; three was enough time to accelerate away.
        if (closestEnd >= 0.0D && endDistance > closestEnd + 1.0D
                && closestEnd <= arrivalRadius + 4.0D) {
            RailDispatch.debug("Autopilot console at {}: passed its destination by {} blocks; stopping here",
                    worldPosition, String.format(Locale.ROOT, "%.1f", endDistance - closestEnd));
            arrive(server, vehicle);
            return;
        }

        // Re-assert the turnouts ahead. Not every cycle: each attempt asks every Railways switch in range
        // which of its states leads where, and the driver logs its answer, so this runs when the route
        // reaches a new node or every five seconds - often enough to correct a turnout somebody else
        // moved, sparse enough not to fill the log.
        if (now % SWITCH_REFRESH_TICKS == 0L
                && (progress.index() != lastSwitchIndex || now - lastSwitchTick >= 100L)) {
            lastSwitchIndex = progress.index();
            lastSwitchTick = now;
            network.alignSwitchesAhead(server, vehicle.id(), position, SWITCH_AHEAD);
        }

        // The controller: feed-forward + PID, positive traction, negative braking.
        if (filteredSpeed < 0.0D) {
            filteredSpeed = speed;
        }
        double output = speedPid.update(now, targetSpeed, filteredSpeed, RDConfig.autopilotFullSignalSpeed());
        // Last stretch: guarantee brake, not just hope the curve is enough. This is the difference
        // between stopping at the platform and the observed "passed its destination by 5.4 blocks":
        // the PID can be near zero while the train is still rolling, so inside ten blocks of the end a
        // brake request is forced regardless of the trim.
        if (endDistance < 24.0D && speed > speedForArrival(endDistance) + 0.5D) {
            // Only force full brake when the train is genuinely too fast for the remaining distance.
            // The old speed > 0.2 test braked at every low-speed creep step, then released next tick -
            // the observed 0.37 -> 0.01 -> 0.37 cycle.
            output = Math.min(output, -1.0D);
        }
        // The PID assumes the bogie actuator is proportional. It is not reliable enough for that: the
        // train was still accelerating to 11.5 b/s with a 4.0 target and only started braking after it
        // was already over speed. Treat overspeed as a hard condition - full brake - independently of the
        // trim, and start the full brake when the actual speed needs more than the distance left.
        if (speed > targetSpeed + 0.35D) {
            output = -1.0D;
        }
        double available = Math.min(remaining, endDistance);
        double effectiveBrake = coupledTrain ? 0.20D : 0.30D;
        double stopNeeded = speed * speed / (2.0D * effectiveBrake) + 1.0D;
        // Distance loop: brake demand grows continuously as the remaining distance approaches the
        // distance needed to stop. This is a separate loop from the speed PID and feeds the analog
        // brake channel, so the train does not wait for a discrete "too close" threshold.
        double distanceError = available - stopNeeded;
        double targetDistanceBrake = clamp01(-distanceError / 10.0D);
        // The distance loop may only brake when the speed loop is not asking to accelerate. Letting
        // its old residual persist into a low-speed acceleration phase was the 0.21 -> 0 -> 0.21
        // judder: target speed was 1.0, but a distance brake from several seconds earlier still won.
        boolean distanceBrakeAllowed = targetSpeed <= 0.15D || speed > targetSpeed + 0.2D;
        if (!distanceBrakeAllowed) {
            lastDistanceBrake = 0.0D;
        } else {
            double release = targetDistanceBrake <= 0.0D ? 0.10D : 0.03D;
            lastDistanceBrake = Math.max(targetDistanceBrake, lastDistanceBrake - release);
            if (lastDistanceBrake > 0.0D) {
                output = Math.min(output, -lastDistanceBrake);
            }
        }
        if (speed > 0.1D && available <= stopNeeded) {
            output = -1.0D;
        }
        lastAppliedOutput = output;
        double steer = next == null ? 0.0D : steerToward(vehicle, next);
        applyControl(vehicle, output, steer, now);

        if (now % 100L == 0L) {
            RailDispatch.debug("autopilot {}: state={} speed={} tgt={} out={} remaining={} end={} "
                            + "node={}/{} signal={} d={}",
                    worldPosition, state,
                    String.format(Locale.ROOT, "%.2f", filteredSpeed),
                    String.format(Locale.ROOT, "%.2f", targetSpeed),
                    String.format(Locale.ROOT, "%.2f", lastAppliedOutput),
                    String.format(Locale.ROOT, "%.0f", remaining),
                    String.format(Locale.ROOT, "%.1f", endDistance),
                    progress.index() + 1, progress.size(), signal,
                    String.format(Locale.ROOT, "%.0f", signalDistance));
            RailDispatch.debug("channels: forward={} brake={} uncouple={} driveCmd={} brakeCmd={}",
                    publishedForward, publishedBrake, publishedUncouple,
                    String.format(Locale.ROOT, "%.3f", commandedDrive),
                    String.format(Locale.ROOT, "%.3f", commandedBrake));
            if (lastDistanceBrake > 0.0D) {
                RailDispatch.debug("distance brake loop: {}/15", Math.round(lastDistanceBrake * 15.0D));
            }
        }
    }

    /**
     * The world direction this console drives in, or null when the structure's orientation is not known.
     *
     * <p>The block's screen face points at the operator - placement turns it towards the player - so the
     * direction the operator is looking when standing at the screen is the <b>opposite</b> of the block's
     * FACING. That is the console's one direction: the way the train leaves the platform, and the way it
     * reads signals. It matches the cab's measured convention (the forward channel drives opposite the
     * block's screen face), so both controllers agree on which way is forward.
     *
     * <p>The vector is rotated out of the structure's own frame with {@code localToWorld}: inside an
     * assembled structure a block's facing is a plot-space direction, not a world one.
     */
    private Vec3 driveDirection(VehicleHandle vehicle) {
        BlockState blockState = getBlockState();
        if (!blockState.hasProperty(AutopilotConsoleBlock.FACING)) {
            return null;
        }
        Direction facing = blockState.getValue(AutopilotConsoleBlock.FACING);
        Vec3 local = new Vec3(facing.getStepX(), 0.0D, facing.getStepZ());
        Vec3 world = vehicle.localToWorld(local);
        if (world == null) {
            return null;
        }
        Vec3 flat = new Vec3(-world.x, 0.0D, -world.z);
        return flat.lengthSqr() < 1.0E-6D ? null : flat.normalize();
    }

    /**
     * The first node where the route doubles straight back (A -> B -> A), or -1.
     *
     * <p>That shape is a stub reversal: the vehicle would have to change direction at B. This console has
     * no reverse channel, so such a route is refused at planning time. A balloon loop that revisits a
     * node later is not matched, because its repeat is not the immediate next node.
     */
    private static int reversalIndex(TrackNetwork.PlannedRoute route) {
        if (route == null) {
            return -1;
        }
        java.util.List<Vec3> points = route.waypoints();
        for (int i = 1; i + 1 < points.size(); i++) {
            if (points.get(i - 1).distanceToSqr(points.get(i + 1)) < 1.0E-4D) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Throttle and brake out to the vehicle and to the wire channels.
     *
     * <p>The split mirrors the cab's: positive output is traction, negative is braking, and they are
     * never both non-zero. Traction builds at a controlled rate and is cut instantly; braking may be
     * applied gently but is released instantly, because setting off against brakes that are still letting
     * go is the more visible mistake.
     */
    private void applyControl(VehicleHandle vehicle, double output, double steer, long now) {
        // Small PID corrections at walking pace are below what a 0-15 wire channel and the bogies can
        // resolve. Feeding them through produced alternating one-tick drive/brake pulses; a deadband
        // lets the low-speed brake and the holding state settle instead.
        if (Math.abs(output) < 0.04D && speed < 0.5D) {
            output = 0.0D;
        }
        double magnitude = clamp01(Math.abs(output));
        // The command is re-sent every tick, but it is only re-shaped every control cycle, and the
        // traction limit is a rate per second - so it has to be paced by the cycle, not by the tick.
        long elapsed = lastApplyTick < 0L ? CONTROL_PERIOD : Math.max(1L, now - lastApplyTick);
        lastApplyTick = now;
        double dt = Math.max(0.05D, Math.min(1.0D, elapsed / 20.0D));
        double wantedDrive = output > 0.0D ? magnitude : 0.0D;
        commandedDrive = wantedDrive > commandedDrive
                ? Math.min(wantedDrive, commandedDrive + tuningAcceleration * dt)
                : wantedDrive;
        double wantedBrake = output < 0.0D ? magnitude : 0.0D;
        if (speed < 1.0D) {
            // A first-order low pass instead of an instant release; at shunting speed the quantised
            // 0-15 channel was otherwise sent 0, then a brake value, then 0 on successive ticks.
            commandedBrake = commandedBrake + (wantedBrake - commandedBrake) * tuningSmoothing;
        } else {
            commandedBrake = output < 0.0D ? slew(commandedBrake, magnitude) : 0.0D;
        }
        if (output > 0.0D) {
            // Traction and brake are mutually exclusive: releasing the brake immediately prevents the
            // wheels turning against a residual analog brake while the train stands still.
            commandedBrake = 0.0D;
        }
        commandedSteer = slew(commandedSteer, Math.max(-1.0D, Math.min(1.0D, steer)));
        commandTrain(vehicle, commandedDrive, commandedBrake, commandedSteer);
    }

    /**
     * Traction/steering go to the vehicle; service braking does NOT go through the sub-level body.
     * It is published on the Drive By Wire brake channel as redstone, which is how this console was
     * designed to brake. Sending brake=0 here suppresses the old rigid-body brake impulse.
     */
    private void commandTrain(VehicleHandle vehicle, double drive, double brake, double steer) {
        vehicle.command(worldPosition, drive, 0.0D, steer);
        // Release any stale controller hold on the trailing coupled sub-levels. This is not braking
        // them; it clears drive/brake overrides so the locomotive can actually pull the consist.
        if (level instanceof ServerLevel server) {
            for (VehicleHandle handle : YardState.handles(server, vehicle)) {
                if (handle != vehicle) {
                    handle.command(worldPosition, 0.0D, 0.0D, 0.0D);
                }
            }
        }
    }

    /** True while the current movement is a shunting approach to a consist. */
    private boolean isShunting() {
        return shuntingMode;
    }

    private void arrive(ServerLevel server, VehicleHandle vehicle) {
        holdVehicle(vehicle);
        releaseRoute(server);
        speedPid.reset();
        if (com.raildispatch.train.ConsistLocator.isConsistTarget(destination)) {
            String service = com.raildispatch.train.ConsistLocator.serviceOf(destination);
            var target = com.raildispatch.train.ConsistLocator.byService(server, service);
            if (target.isPresent()) {
                // Stop every coupled car, not just the locomotive's own sub-level. The target consist
                // was still rolling when the locomotive stopped, so the combined train kept moving and
                // looked as if it had never stood still after coupling.
                vehicle.emergencyStop();
                RailDispatch.debug("Autopilot console at {}: coupled to consist service '{}' "
                                + "(distance {} blocks); stopped", worldPosition, service,
                        String.format(java.util.Locale.ROOT, "%.1f",
                                vehicle.position().distanceTo(target.get().centre())));
            } else {
                RailDispatch.LOGGER.warn("Autopilot console at {}: reached service '{}' area but the "
                        + "consist is no longer present", worldPosition, service);
            }
        }
        RailDispatch.debug("Autopilot console at {}: arrived at '{}'", worldPosition, destination);
        if (scheduleActive) {
            ScheduleView view = scheduleView(server).orElse(null);
            // At least one second at the platform even when the Create entry has no timed condition. The
            // schedule's next leg otherwise starts in the same tick as the arrival, while the train is
            // still rolling - which reads as "it suddenly accelerated at the target".
            boolean couplingArrival = com.raildispatch.train.ConsistLocator.isConsistTarget(destination);
            int wait = Math.max(couplingArrival ? 60 : 20, view == null ? 0 : view.waitTicks());
            scheduleWaitUntil = server.getGameTime() + wait;
            state = AutopilotState.HOLDING;
            detail = "schedule wait " + formatWait(wait);
            RailDispatch.debug("Autopilot console at {}: coupling stop complete; advancing to the next "
                    + "schedule entry in {} ticks", worldPosition, wait);
            setChanged();
            sync();
            return;
        }
        if (timetableRunning) {
            advanceTimetable(server, vehicle);
            return;
        }
        engaged = false;
        state = AutopilotState.ARRIVED;
        detail = "arrived";
    }

    /**
     * Moves to the next timetable entry after an arrival. The next entry departs from the stop just
     * reached, which is exactly what a timetable means: arrival at A, departure from A.
     */
    private void advanceTimetable(ServerLevel server, VehicleHandle vehicle) {
        int next = timetableIndex + 1;
        if (next >= timetable.size()) {
            if (!timetableLoop) {
                timetableRunning = false;
                timetableWaiting = false;
                engaged = false;
                state = AutopilotState.ARRIVED;
                detail = "timetable complete";
                setChanged();
                sync();
                return;
            }
            next = 0;
        }
        timetableIndex = next;
        beginTimetableEntry(server, null);
        if (!timetableRunning) {
            // beginTimetableEntry found the list finished; do not overwrite its "timetable complete".
            setChanged();
            sync();
            return;
        }
        if (timetableWaiting) {
            holdVehicle(vehicle);
            state = AutopilotState.HOLDING;
        } else {
            state = AutopilotState.PLANNING;
        }
        setChanged();
        sync();
    }

    private void holdVehicle(VehicleHandle vehicle) {
        commandedDrive = 0.0D;
        commandedBrake = 1.0D;
        commandedSteer = 0.0D;
        commandTrain(vehicle, 0.0D, 1.0D, 0.0D);
    }

    private void refreshCommand() {
        VehicleHandle vehicle = currentVehicle;
        if (vehicle == null || !vehicle.isValid() || standAside) {
            return;
        }
        commandTrain(vehicle, commandedDrive, commandedBrake, commandedSteer);
        // The measurement is smoothed before the PID sees it. Sable's displacement speed is already
        // lightly filtered, but its first difference still carries a little quantisation, and a
        // derivative term that chases that would add noise to the throttle.
        double raw = vehicle.speed() * 20.0D;
        filteredSpeed = filteredSpeed < 0.0D ? raw : filteredSpeed + (raw - filteredSpeed) * 0.35D;
        speed = filteredSpeed;
    }

    // ------------------------------------------------------------------ wire channels

    /**
     * Publishes forward traction and the bogies' brake on this block's own channels.
     *
     * <p>There is no backward channel: this console drives one direction only. Service braking is the
     * rigid-body deceleration commanded through {@link VehicleHandle#command}; the brake channel is the
     * bogies' own parking/holding brake, engaged when the console is standing still or not started.
     *
     * <p>Values are only written when they change: every publish walks the wire network's sinks and fires
     * neighbour updates. Publishing a standstill is still necessary once - a stale non-zero value left on
     * a channel would keep a drivetrain energised after the console was stopped or broken, so the removal
     * path clears them explicitly.
     */
    private void publishChannels(ServerLevel server) {
        // Nothing is published until the first decision has been taken. Before that the console has not
        // yet resolved its vehicle, so it cannot know whether it is the controller or is standing aside for
        // a cab - and writing a parking brake onto a channel a driving cab is using would stop that cab on
        // the console's very first tick.
        if (!loggedFirstCycle) {
            return;
        }
        // A console standing aside must not publish zero onto the shared wire channels. The other
        // console is the active owner; publishing brake=15 or forward=0 here overwrote the active
        // console's throttle every tick, which is why the train sat at speed=0 with a positive output.
        if (standAside) {
            return;
        }
        if (currentVehicle != null && anotherConsoleIsTrainDriver(server, currentVehicle)) {
            return;
        }
        // The schedule item is copied to both cabs and carries the owner position. This works even
        // when the reverse cab sits in a different Sable plot (so no UUID or distance key matches).
        BlockPos itemOwner = scheduleOwner();
        if (itemOwner != null && !itemOwner.equals(worldPosition)
                && level.getBlockEntity(itemOwner) instanceof AutopilotConsoleBlockEntity) {
            return;
        }
        // An idle console must not publish its parking brake either. The reverse cab at the other end
        // is idle while the active cab drives, but its brake=15 was reaching the bogie's redstone
        // brake input and holding the whole train.
        // Any console that is not engaged, not running a schedule and not handing over is idle for
        // this purpose, whatever its state enum happens to be. The reverse cab sat in HOLDING after
        // its last stop, so the earlier IDLE/ARRIVED test let it keep publishing brake=15.
        if (!engaged && !scheduleActive && pendingHandoff == null
                && couplerSignal(server) <= 0 && publishedUncouple <= 0) {
            return;
        }
        int forward;
        int brake;
        {
            boolean stopped = state == AutopilotState.HOLDING || state == AutopilotState.ARRIVED
                    || state == AutopilotState.PLANNING || state == AutopilotState.IDLE;
            // Service braking is the redstone brake channel, not a sub-level impulse. Forward is cut
            // while braking so the drivetrain cannot pull against its own brake.
            // Only a genuine standstill cuts traction. Gating it on any residual commandedBrake
            // suppressed the throttle for as long as the analog brake took to decay, which read as
            // "no throttle output" even though the console was commanding drive.
            // Emit the throttle regardless of whether some other bogie on the train lacks a kinetic
            // network. The bogie tick mixin skips those bogies; zeroing the whole channel meant one
            // unpowered bogie suppressed traction for the powered ones - observed as forward=0.
            forward = stopped ? 0 : (int) Math.round(clamp01(commandedDrive) * 15.0D);
            // Analog brake: the brake channel carries the commanded 0..15 level, not an on/off 15.
            // At a genuine standstill it still goes to 15 to hold.
            brake = stopped ? 15 : (int) Math.round(clamp01(commandedBrake) * 15.0D);
        }
        int uncouple = couplerSignal(server) > 0 ? 15 : 0;
        // Always re-publish while driving. A one-shot write of brake=0 could be overwritten by the
        // other console or lost while the bogie was still holding its previous parking brake; the
        // result was wheels turning against a latched brake with forward=8 in the log.
        if (forward == 0 && brake == 0 && uncouple == 0
                && forward == publishedForward && brake == publishedBrake
                && uncouple == publishedUncouple) {
            return;
        }
        if (forward != publishedForward || brake != publishedBrake || uncouple != publishedUncouple) {
            RailDispatch.debug("publishing at {}: forward={} brake={} uncouple={} state={} "
                            + "engaged={} schedule={}",
                    worldPosition, forward, brake, uncouple, state, engaged, scheduleActive);
        }
        publishedForward = forward;
        publishedBrake = brake;
        publishedUncouple = uncouple;
        try {
            edn.stratodonut.drivebywire.wire.WireNetworkManager.trySetSignalAt(
                    server, worldPosition, AutopilotConsoleBlock.CHANNEL_FORWARD, forward);
            edn.stratodonut.drivebywire.wire.WireNetworkManager.trySetSignalAt(
                    server, worldPosition, AutopilotConsoleBlock.CHANNEL_BRAKE, brake);
            edn.stratodonut.drivebywire.wire.WireNetworkManager.trySetSignalAt(
                    server, worldPosition, AutopilotConsoleBlock.CHANNEL_UNCOUPLE, uncouple);
            if (!legacyBackwardCleared) {
                legacyBackwardCleared = true;
                edn.stratodonut.drivebywire.wire.WireNetworkManager.trySetSignalAt(
                        server, worldPosition, LEGACY_BACKWARD_CHANNEL, 0);
            }
        } catch (Throwable throwable) {
            if (!warnedWire) {
                warnedWire = true;
                RailDispatch.LOGGER.warn("Autopilot console at {} could not publish its Drive By Wire channels",
                        worldPosition, throwable);
            }
        }
    }

    /** Clears the channels on removal, so a broken console cannot leave a drivetrain energised. */
    private void clearChannels(ServerLevel server) {
        try {
            edn.stratodonut.drivebywire.wire.WireNetworkManager.trySetSignalAt(
                    server, worldPosition, AutopilotConsoleBlock.CHANNEL_FORWARD, 0);
            edn.stratodonut.drivebywire.wire.WireNetworkManager.trySetSignalAt(
                    server, worldPosition, AutopilotConsoleBlock.CHANNEL_BRAKE, 0);
            edn.stratodonut.drivebywire.wire.WireNetworkManager.trySetSignalAt(
                    server, worldPosition, AutopilotConsoleBlock.CHANNEL_UNCOUPLE, 0);
            edn.stratodonut.drivebywire.wire.WireNetworkManager.trySetSignalAt(
                    server, worldPosition, LEGACY_BACKWARD_CHANNEL, 0);
        } catch (Throwable ignored) {
            // Best effort: the block is going away and there is nothing useful to do about a wire failure.
        }
    }

    // ------------------------------------------------------------------ helpers

    private VehicleHandle resolveVehicle(ServerLevel server) {
        if (!RailServices.physics().isAvailable()) {
            return null;
        }
        if (vehicleId != null) {
            Optional<VehicleHandle> bound = RailServices.physics().vehicleById(server, vehicleId);
            if (bound.isPresent() && bound.get().isValid()) {
                return bound.get();
            }
            vehicleId = null;
        }
        Optional<VehicleHandle> here = RailServices.physics().vehicleOf(server, worldPosition);
        if (here.isPresent() && here.get().isValid()) {
            vehicleId = here.get().id();
            return here.get();
        }
        return null;
    }

    /**
     * True when another autopilot console on this vehicle already holds it.
     *
     * <p>The lookup goes through the cabinet registry rather than through a geometric comparison: the
     * two consoles may sit at opposite ends of a structure whose blocks live in a plot, so comparing
     * positions against anything in the world would be meaningless.
     */
    private boolean anotherConsoleDriving(ServerLevel server, VehicleHandle vehicle) {
        Optional<BlockPos> owner = CabRegistry.controllerOf(server, vehicle.id());
        if (owner.isEmpty() || owner.get().equals(worldPosition)) {
            return false;
        }
        return server.getBlockEntity(owner.get()) instanceof AutopilotConsoleBlockEntity other
                && other != this && other.isDriving();
    }

    /** True when a physics cab on this vehicle carries a schedule, in which case the cab is in charge. */
    private static boolean scheduleCabInCharge(VehicleHandle vehicle) {
        for (PhysicsCabBlockEntity cab : vehicle.actorsOfType(PhysicsCabBlockEntity.class)) {
            if (cab != null && cab.hasSchedule()) {
                return true;
            }
        }
        return false;
    }

    private boolean reportDerailment(ServerLevel server, long now, VehicleHandle vehicle) {
        if (!vehicle.isDerailed()) {
            derailed = false;
            return false;
        }
        holdVehicle(vehicle);
        if (!derailed) {
            derailed = true;
            RailDispatch.LOGGER.warn("Autopilot console at {}: the vehicle has come off the rails", worldPosition);
        }
        if (now % 40L == 0L) {
            Vec3 at = vehicle.position();
            server.getPlayers(player -> player.distanceToSqr(at) < DERAL_PROMPT_RANGE * DERAL_PROMPT_RANGE)
                    .forEach(player -> player.displayClientMessage(
                            Component.literal("杞﹁締宸茶劚杞?/ vehicle is off the rails"), true));
        }
        state = AutopilotState.HOLDING;
        return true;
    }

    private void reportDestinationTrouble(ServerLevel server, TrackNetwork network) {
        if (loggedDestinationTrouble) {
            return;
        }
        loggedDestinationTrouble = true;
        java.util.List<String> known = network.knownDestinations(server);
        RailDispatch.LOGGER.warn("Autopilot console at {} cannot reach '{}'. Known destinations ({}): {}",
                worldPosition, destination, known.size(),
                known.isEmpty() ? "<none - no stations, address plates or vehicle tags are registered>"
                        : String.join(", ", known.subList(0, Math.min(20, known.size()))));
    }

    private void publishStatus() {
        int flags = 0;
        if (wrongDirection) {
            flags |= FLAG_WRONG_DIRECTION;
        }
        if (standAside) {
            flags |= FLAG_STAND_DOWN;
        }
        if (level != null && level.hasNeighborSignal(worldPosition)) {
            flags |= FLAG_REDSTONE;
        }
        if (derailed) {
            flags |= FLAG_DERAILED;
        }
        if (routeGranted && progress != null) {
            flags |= FLAG_FOLLOWING;
        }
        data.set(DATA_STATE, state.ordinal());
        data.set(DATA_SIGNAL, signal.ordinal());
        data.set(DATA_SPEED, (int) Math.round(filteredSpeed < 0.0D ? 0.0D : filteredSpeed * 100.0D));
        data.set(DATA_TARGET, (int) Math.round(targetSpeed * 100.0D));
        data.set(DATA_ROUTE, routeGranted ? 1 : 0);
        data.set(DATA_REMAINING, progress == null ? -1 : (int) Math.round(progress.remaining(
                currentVehicle == null ? Vec3.ZERO : currentVehicle.position())));
        // One-based on purpose: the screen shows "5/12", and a human reading a route does not call the
        // first node zero.
        data.set(DATA_WAYPOINT, progress == null ? 0 : Math.min(progress.index() + 1, progress.size()));
        data.set(DATA_WAYPOINTS, progress == null ? 0 : progress.size());
        data.set(DATA_DRIVE, (int) Math.round(clamp01(commandedDrive) * 100.0D));
        data.set(DATA_BRAKE, (int) Math.round(clamp01(commandedBrake) * 100.0D));
        data.set(DATA_FLAGS, flags);
        data.set(DATA_TIMETABLE_INDEX, timetableIndex);
        data.set(DATA_TIMETABLE_COUNT, timetable.size());
        int timetableFlags = 0;
        if (timetableRunning) {
            timetableFlags |= TIMETABLE_FLAG_RUNNING;
        }
        if (timetableWaiting) {
            timetableFlags |= TIMETABLE_FLAG_WAITING;
        }
        if (timetableLoop) {
            timetableFlags |= TIMETABLE_FLAG_LOOP;
        }
        data.set(DATA_TIMETABLE_FLAGS, timetableFlags);
        data.set(DATA_SCHEDULE, hasSchedule() ? 1 : 0);
        data.set(DATA_SCHEDULE_ENTRY, scheduleEntry);
        data.set(DATA_SCHEDULE_ENTRIES, scheduleCount);
        updateLitState();
        if (!detail.equals(lastSyncedDetail)) {
            // The detail line is the only explanation a player gets when a route is refused or a
            // destination cannot be resolved, and it is a string - so it travels in the block tag rather
            // than in the integer status slots.
            lastSyncedDetail = detail;
            sync();
        }
    }

    private void updateLitState() {
        if (!(level instanceof ServerLevel)) {
            return;
        }
        BlockState blockState = getBlockState();
        if (!blockState.hasProperty(AutopilotConsoleBlock.POWERED)) {
            return;
        }
        boolean lit = engaged && state != AutopilotState.IDLE && state != AutopilotState.ARRIVED;
        if (blockState.getValue(AutopilotConsoleBlock.POWERED) != lit) {
            level.setBlock(worldPosition, blockState.setValue(AutopilotConsoleBlock.POWERED, lit),
                    Block.UPDATE_ALL);
        }
    }

    private void sync() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    private double speedForStop(double distance, double margin) {
        return Math.sqrt(2.0D * tuningBrakeDecel * Math.max(0.0D, distance - margin));
    }

    /**
     * The arrival curve, with no signal margin: applying the margin here would bring the vehicle to a halt
     * a whole margin short of its destination.
     */
    private double speedForArrival(double distance) {
        return Math.sqrt(2.0D * tuningBrakeDecel * Math.max(0.0D, distance - 0.5D));
    }

    /**
     * A speed limit for the turnout or curve at the next node.
     *
     * <p>A straight run is unlimited, and the limit eases in over the last 40 blocks so the vehicle is
     * already slow when the points arrive rather than braking on top of them.
     */
    /** Approach cap: slow enough that the physical brake can actually stop at the marker. */
    private double approachSpeed(double distance) {
        distance *= tuningApproach;
        if (distance > 120.0D) {
            return tuningMaxSpeed;
        }
        if (distance > 80.0D) {
            return 6.0D;
        }
        if (distance > 40.0D) {
            return 3.5D;
        }
        if (distance > 15.0D) {
            return 2.0D;
        }
        if (distance > 6.0D) {
            return 1.0D;
        }
        return 0.6D;
    }

    private double curveSpeed(double angleDegrees, double distanceToTurn) {
        if (angleDegrees < 8.0D || distanceToTurn > 60.0D) {
            return RDConfig.maxSpeed();
        }
        // A physics train takes a turnout much slower than a Create train. The old 9/6/4 limits let it
        // enter a sharp switch at 6.3 b/s and put a bogie on the ground; these are derailment limits, not
        // comfort limits.
        double limit = angleDegrees < 20.0D ? 8.0D
                : angleDegrees < 40.0D ? 4.5D
                : angleDegrees < 70.0D ? 3.0D : 1.5D;
        double blend = Math.max(0.0D, Math.min(1.0D, (60.0D - distanceToTurn) / 40.0D));
        return RDConfig.maxSpeed() - (RDConfig.maxSpeed() - limit) * blend;
    }

    private double steerToward(VehicleHandle vehicle, Vec3 target) {
        Vec3 velocity = vehicle.velocity();
        Vec3 forward = new Vec3(velocity.x, 0.0D, velocity.z);
        if (forward.lengthSqr() < 1.0E-6D) {
            return 0.0D;
        }
        Vec3 toTarget = target.subtract(vehicle.position());
        Vec3 lateral = new Vec3(toTarget.x, 0.0D, toTarget.z);
        if (lateral.lengthSqr() < 1.0E-4D) {
            return 0.0D;
        }
        forward = forward.normalize();
        lateral = lateral.normalize();
        double cross = forward.x * lateral.z - forward.z * lateral.x;
        return Math.max(-1.0D, Math.min(1.0D, cross * STEER_INERTIA_GAIN)) * RDConfig.steerGain();
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static double slew(double from, double to) {
        double step = 0.25D;
        return to > from ? Math.min(to, from + step) : Math.max(to, from - step);
    }

    // ------------------------------------------------------------------ plumbing

    @Override
    public void setRemoved() {
        LOADED.remove(this);
        if (level instanceof ServerLevel server) {
            CabRegistry.releaseAt(server, worldPosition);
            releaseRoute(server);
            if (currentVehicle != null && currentVehicle.isValid()) {
                currentVehicle.releaseControl(worldPosition);
            }
            clearChannels(server);
        }
        super.setRemoved();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("raildispatch.gui.autopilot_console");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new AutopilotConsoleMenu(containerId, playerInventory, this);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        LOADED.add(this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("Destination", destination);
        tag.putString("Detail", detail);
        // Enough state to resume after a world reload. The route itself is deliberately NOT persisted: it
        // is session state in the network, so on load the console re-requests a route for the same
        // destination instead of driving on authority that no longer exists.
        tag.putBoolean("Engaged", engaged);
        tag.putBoolean("ShuntingMode", shuntingMode);
        tag.putBoolean("ScheduleActive", scheduleActive);
        tag.putLong("ScheduleWaitUntil", scheduleWaitUntil);
        tag.putBoolean("TimetableLoop", timetableLoop);
        tag.put("Schedule", inventory.serializeNBT(registries));
        ListTag entries = new ListTag();
        for (TimetableEntry entry : timetable) {
            entries.add(entry.save());
        }
        tag.put("Timetable", entries);
        if (vehicleId != null) {
            tag.putUUID("Vehicle", vehicleId);
        }
        tag.putDouble("TuningMaxSpeed", tuningMaxSpeed);
        tag.putDouble("TuningAcceleration", tuningAcceleration);
        tag.putDouble("TuningBrakeDecel", tuningBrakeDecel);
        tag.putDouble("TuningApproach", tuningApproach);
        tag.putDouble("TuningSmoothing", tuningSmoothing);
        tag.putDouble("ScheduleSpeedLimit", scheduleSpeedLimit);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        destination = tag.getString("Destination");
        detail = tag.getString("Detail");
        tuningMaxSpeed = tag.contains("TuningMaxSpeed") ? tag.getDouble("TuningMaxSpeed") : RDConfig.maxSpeed();
        tuningAcceleration = tag.contains("TuningAcceleration")
                ? tag.getDouble("TuningAcceleration") : TRACTION_RISE_PER_SECOND;
        tuningBrakeDecel = tag.contains("TuningBrakeDecel")
                ? tag.getDouble("TuningBrakeDecel") : BRAKE_DECELERATION;
        tuningApproach = tag.contains("TuningApproach") ? tag.getDouble("TuningApproach") : 1.0D;
        tuningSmoothing = tag.contains("TuningSmoothing") ? tag.getDouble("TuningSmoothing") : 0.35D;
        scheduleSpeedLimit = tag.contains("ScheduleSpeedLimit")
                ? tag.getDouble("ScheduleSpeedLimit") : -1.0D;
        timetableLoop = !tag.contains("TimetableLoop") || tag.getBoolean("TimetableLoop");
        timetable.clear();
        if (tag.contains("Timetable", Tag.TAG_LIST)) {
            ListTag entries = tag.getList("Timetable", Tag.TAG_COMPOUND);
            for (int i = 0; i < entries.size() && i < 16; i++) {
                TimetableEntry entry = TimetableEntry.load(entries.getCompound(i));
                if (!entry.destination().isBlank()) {
                    timetable.add(entry);
                }
            }
        }
        timetableRunning = false;
        timetableWaiting = false;
        timetableIndex = 0;
        scheduleActive = false;
        scheduleWaitUntil = 0L;
        scheduleEntry = 0;
        scheduleCount = 0;
        if (tag.contains("Schedule", Tag.TAG_COMPOUND)) {
            inventory.deserializeNBT(registries, tag.getCompound("Schedule"));
        }
        if (tag.hasUUID("Vehicle")) {
            vehicleId = tag.getUUID("Vehicle");
        }
        // Resume the journey after a world reload, but never resume the ROUTE: authority is session state
        // in the network and does not survive the restart. The console re-requests a route for the same
        // destination and picks up the same Create schedule entry. If it was stopped, arrived or handed
        // off, Engaged is false and it stays idle.
        boolean wasEngaged = tag.getBoolean("Engaged");
        boolean wasSchedule = tag.getBoolean("ScheduleActive");
        long savedWait = tag.getLong("ScheduleWaitUntil");
        shuntingMode = tag.getBoolean("ShuntingMode");
        if (wasEngaged && !destination.isBlank()) {
            engaged = true;
            standAside = false;
            scheduleActive = wasSchedule && hasSchedule();
            scheduleWaitUntil = scheduleActive ? savedWait : 0L;
            routeGranted = false;
            progress = null;
            retryTimer = 0;
            commandedDrive = 0.0D;
            commandedBrake = 0.0D;
            commandedSteer = 0.0D;
            state = scheduleWaitUntil > 0L ? AutopilotState.HOLDING : AutopilotState.PLANNING;
            detail = "resumed after reload";
            if (level instanceof ServerLevel server) {
                RailDispatch.debug("Autopilot console at {}: resuming destination '{}' after reload "
                        + "({}, {} schedule)", worldPosition, destination, state,
                        scheduleActive ? "with" : "without");
            }
        } else {
            engaged = false;
            standAside = false;
            scheduleActive = false;
            scheduleWaitUntil = 0L;
            state = AutopilotState.IDLE;
            routeGranted = false;
            progress = null;
            retryTimer = 0;
            commandedDrive = 0.0D;
            commandedBrake = 0.0D;
            commandedSteer = 0.0D;
        }
        // A mode-toggle entry has no destination but is still an active schedule wait. Restore it, so a
        // reload between "enter shunting" and the coupling entry does not strand the schedule.
        if (!scheduleActive && wasSchedule && destination.isBlank() && savedWait > 0L) {
            scheduleActive = true;
            scheduleWaitUntil = savedWait;
            state = AutopilotState.HOLDING;
            detail = "resumed shunting-mode change";
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
