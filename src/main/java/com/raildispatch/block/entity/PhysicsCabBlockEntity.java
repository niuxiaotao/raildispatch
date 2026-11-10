package com.raildispatch.block.entity;

import com.raildispatch.RDConfig;
import com.raildispatch.RailDispatch;
import com.simibubi.create.AllDataComponents;
import com.raildispatch.api.CouplerBridge;
import com.raildispatch.api.PhysicsBridge;
import com.raildispatch.api.RailServices;
import com.raildispatch.api.ScheduleView;
import com.raildispatch.api.Schedules;
import com.raildispatch.api.SignalAspect;
import com.raildispatch.api.SignalInfo;
import com.raildispatch.api.TrackNetwork;
import com.raildispatch.api.VehicleHandle;
import com.raildispatch.block.PhysicsCabBlock;
import com.raildispatch.cab.CabRegistry;
import com.raildispatch.cab.CabState;
import com.raildispatch.data.RailTags;
import com.raildispatch.menu.PhysicsCabMenu;
import com.raildispatch.registry.RDBlockEntities;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * The physics cab.
 *
 * <p>It is the only block that commands a whole vehicle. Every five ticks it:
 * <ol>
 *   <li>resolves which physics vehicle it is mounted on (by tag first, then by position);</li>
 *   <li>reads the Create train schedule in its slot and works out the current destination;</li>
 *   <li>asks the track network what the signal ahead says and how far away it is;</li>
 *   <li>asks for a route - from a dispatch centre when one is in range, otherwise it locks the
 *       route itself;</li>
 *   <li>writes traction, braking and steering into the vehicle's physics bogies.</li>
 * </ol>
 *
 * <p>The five-tick period is deliberate: a physics bogey integrates a force, so issuing a command at
 * 4 Hz is smooth, cheap, and immune to a single missed tick.
 */
public class PhysicsCabBlockEntity extends BlockEntity
        implements MenuProvider, BlockEntitySubLevelActor {

    public static final int SLOT_SCHEDULE = 0;
    private static final int CONTROL_PERIOD = 5;
    private static final int ROUTE_RETRY_TICKS = 40;
    /**
     * Speed controller gains, in command units per block per second.
     *
     * <p>An analogue throttle means a proper controller is possible. The proportional-with-dead-band it replaced
     * had to hold its last command inside the band - dropping to zero made the vehicle coast, slow down and be
     * driven again, which the driver saw as lurching - and it had no way to remove a standing error at all, which
     * is precisely what the integral term is for.
     */
    private static final double PID_KP = 0.22D;
    private static final double PID_KI = 0.05D;
    private static final double PID_KD = 0.06D;
    /** Integral clamp. Keeps a long standstill from banking a huge correction to spend all at once later. */
    private static final double PID_INTEGRAL_LIMIT = 3.0D;
    /**
     * How fast traction may build, per second, and deliberately slow.
     *
     * <p>A real electric locomotive does not apply its power in one step; it notches up, and the whole train's
     * slack is taken up gradually. Cutting power, by contrast, is immediate - that is what a driver expects, and
     * it is why this limit applies to rising traction only. Expressed per second rather than per control cycle so
     * that changing the control period cannot quietly change how the vehicle sets off.
     */
    private static final double TRACTION_RISE_PER_SECOND = 0.22D;
    /** How far away a player may be and still be told that the vehicle is off the rails, in blocks. */
    private static final double DERAL_PROMPT_RANGE = 32.0D;
    /** Deceleration assumed when shaping a braking curve, in blocks per second squared. */
    /**
     * The deceleration the stopping curve assumes, in blocks per second squared.
     *
     * <p>Measured from the telemetry: the vehicle stops from 7.3 blocks per second in five seconds, about
     * 1.46 m/s^2, and that is at full brake. Assuming anything close to that leaves the profile with no margin at
     * all, so a control lag of a single cycle puts the vehicle past the mark - which is exactly what happened when
     * this was 1.5. It is deliberately well below what the vehicle can do, so the profile is achievable with room
     * to spare and the vehicle begins to ease off early. A slightly sluggish approach is the intended trade.
     */
    private static final double BRAKE_DECELERATION = 1.0D;

    // ContainerData indices, mirrored to the client menu by the vanilla menu sync.
    public static final int DATA_STATE = 0;
    public static final int DATA_SIGNAL = 1;
    public static final int DATA_SPEED = 2;
    public static final int DATA_WAIT = 3;
    public static final int DATA_ROUTE = 4;
    public static final int DATA_SCHEDULE = 5;
    public static final int DATA_BOGEYS = 6;
    public static final int DATA_ENTRY = 7;
    public static final int DATA_ENTRIES = 8;
    public static final int DATA_COUNT = 9;

    /**
     * The status mirror the menu syncs.
     *
     * <p>It must be a real store, not a read-through view: vanilla sync reads each slot with
     * {@code get} on the server and writes it back with {@code set} on the client. A no-op {@code set}
     * silently discards everything the server sent, and the screen then shows the client copy's own
     * fields - which are always zero, because the client never runs the control loop. That is exactly
     * how the cab ended up reporting "0 bogeys" while its own log said it had found two.
     */
    private final SimpleContainerData data = new SimpleContainerData(DATA_COUNT);

    /** Guards the sibling copy so two cabs cannot bounce the same stack back and forth. */
    private boolean syncingSchedule;

    private final ItemStackHandler inventory = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            retryTimer = 0;
            if (!(level instanceof ServerLevel server)) {
                return;
            }
            if (!inventory.getStackInSlot(SLOT_SCHEDULE).isEmpty()) {
                // Installing, editing or advancing the schedule on this cab must reach the other cab. The
                // two cabs are one control position split across the vehicle: the one facing the
                // destination takes over at the next leg, and it has to find the same entry, not a stale
                // copy from whenever the schedule was installed.
                syncScheduleToSiblings(server);
                return;
            }
            // Emptying the slot takes the schedule off the whole vehicle, so it cannot be collected twice.
            for (PhysicsCabBlockEntity sibling : siblings(server)) {
                if (!sibling.inventory.getStackInSlot(SLOT_SCHEDULE).isEmpty()) {
                    sibling.inventory.setStackInSlot(SLOT_SCHEDULE, ItemStack.EMPTY);
                }
            }
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return Schedules.isScheduleItem(stack);
        }
    };

    private CabState state = CabState.IDLE;
    private UUID vehicleId;
    /** A vehicle tag this cab is addressed by; overrides position-based resolution. */
    private String vehicleTag = "";
    private SignalAspect signal = SignalAspect.NONE;
    private double signalDistance = -1.0D;
    private double speed;
    private String destination = "";
    private String routeDetail = "";
    private int waitTicks;
    private int retryTimer;
    private boolean routeGranted;
    private long lastControlTick = -1L;
    private boolean loggedFirstCycle;
    private boolean loggedNoVehicle;
    /** Commanded traction and braking, carried between cycles so the command can be ramped. */
    private double commandedDrive;
    private double commandedBrake;
    private double commandedSteer;
    /** The vehicle resolved by the last decision, re-used by the per-tick command refresh. */
    private VehicleHandle currentVehicle;
    /** Exponentially smoothed speed in blocks per second; negative means "no sample yet". */
    private double filteredSpeed = -1.0D;
    /**
     * Last values the drive loop computed, kept only so the telemetry line can print them.
     *
     * <p>They are what says whether the approach is being shaped by the distance curve at all: a distance that
     * never shrinks means the destination being measured is not the one being driven to, and a distance that
     * shrinks while the target speed stays high means the curve is not being applied. Those are different faults,
     * and without these two numbers they look identical from the cab.
     */
    private double lastDistance = -1.0D;
    private double lastTargetSpeed = -1.0D;
    /**
     * The speed this vehicle settles at when the throttle signal is at full strength, in blocks per second.
     *
     * <p>Expected to be set per vehicle, because one cab design is meant to drive many kinds of stock. It is the
     * ratio in the controller's feed-forward term, so it is what makes the same control law correct on a heavy
     * locomotive and a light railcar alike.
     */
    private double fullSignalSpeed = 12.0D;
    /** PID state for the speed controller. */
    private double speedIntegral;
    private double lastSpeedError;
    private long lastPidTick;
    /** Closest horizontal approach to the current destination; negative means "not measured yet". */
    private double closestDestination = -1.0D;
    /** Last heading actually travelled, in world space, kept even once the vehicle stops. */
    private Vec3 lastHeading;
    private Vec3 lastHeadingPosition;
    /** Identity of the schedule that was last known to be installed or finished. */
    private int lastScheduleId;
    /** Set on load, so the cab grabs the bogies before they can drive the vehicle themselves. */
    private boolean needsImmediateHold;
    /**
     * True while a player has taken control of this cab, as opposed to it running unattended.
     *
     * <p>Deliberately not persisted: after a reload nobody is holding the controls, so the cab should come
     * back in its unattended state rather than believing a driver is still there.
     */
    private boolean engaged;
    /** The player holding the controls, while engaged. */
    private java.util.UUID engagedPlayer;

    private boolean manualForward;
    private boolean manualBackward;
    /** True while the driver holds the brake key. */
    private boolean manualBrake;
    /** Wound-up throttle setpoint, -1 (full reverse) to +1 (full forward). */
    private double manualThrottle;
    /** Last values published onto the wire network, so an unchanged pair is not republished. */
    private int publishedForward = -1;
    private int publishedBackward = -1;
    private int publishedBogieBrake = -1;
    /**
     * True while the route runs against the way this cab faces, so the vehicle has to reverse to follow it.
     *
     * <p>This is what the backward wire channel is for. Until now the automated path only ever produced a
     * positive throttle, so nothing a schedule could ask for would ever come out of that channel.
     */
    private boolean reversing;
    /**
     * True once the direction has been settled for the current leg.
     *
     * <p>The direction has to be decided per leg, not "whenever the vehicle happens to be slow". Being slow is
     * not a rare event - it happens at every signal the cab stops for - and re-deciding on each of those stops
     * is what let the direction flip while running: a stop caused by an opposing signal re-ran the decision,
     * which could reverse the vehicle, which changed which signals faced it, and so on.
     */
    private boolean directionDecided;
    /**
     * Which way this vehicle actually goes when the throttle is sent out as "forward", learned from motion.
     *
     * <p>It has to be learned, not derived: which way the engine turns for a given channel is decided by the
     * player's wiring, not by anything this mod can see.
     *
     * <p>It is stored <b>corrected for the state it was observed under</b>. Raw travel direction is not usable as
     * a heading, because the same field means opposite things depending on whether the vehicle was reversing when
     * it was recorded - so using it raw made the decision self-confirming: reversing once recorded a heading that
     * pointed the wrong way, which argued for reversing again, for ever. Correcting at the moment of observation
     * gives a quantity that does not move when the vehicle changes direction, which is what breaks that loop.
     */
    private Vec3 forwardHeading;
    private boolean warnedWire;
    /** Latched so the derailment is reported once when it happens, not every cycle. */
    private boolean derailed;
    /**
     * True while an autopilot console on this vehicle is driving it.
     *
     * <p>Two controllers on one vehicle work against each other by construction: both apply braking
     * impulses, and whichever publishes last wins the wire channels. The console is the more specific
     * choice a player made by pressing start, so the cab stands down - and, crucially, stops sending
     * commands rather than commanding a stop.
     */
    private boolean standingDownForConsole;

    /**
     * Holds the vehicle and tells the players standing next to it when it has come off the rails.
     *
     * <p>The message is addressed using the <b>vehicle's</b> position, not the cab's: a cab's position is a plot
     * coordinate millions of blocks from the origin, so using it to find nearby players would silently find
     * nobody at all.
     *
     * <p>Repeated while the condition lasts, because the action bar fades. That is a stand-in for the
     * persistent status line Create shows on its trains, which needs the client HUD channel this mod does not
     * have yet.
     *
     * @return true when the vehicle is derailed, meaning nothing else should be attempted
     */
    private boolean reportDerailment(ServerLevel server, long now) {
        VehicleHandle vehicle = currentVehicle;
        if (vehicle == null || !vehicle.isValid()) {
            vehicle = RailServices.physics().vehicleOf(server, worldPosition).orElse(null);
            currentVehicle = vehicle;
        }
        if (vehicle == null || !vehicle.isDerailed()) {
            derailed = false;
            return false;
        }
        hold(vehicle);
        if (!derailed) {
            derailed = true;
            RailDispatch.LOGGER.warn("cab {}: the vehicle has come off the rails", worldPosition);
        }
        if (now % 40L == 0L) {
            Vec3 at = vehicle.position();
            server.getPlayers(player -> player.distanceToSqr(at) < DERAL_PROMPT_RANGE * DERAL_PROMPT_RANGE)
                    .forEach(player -> player.displayClientMessage(
                            Component.literal("车辆已脱轨 / vehicle is off the rails"), true));
        }
        return true;
    }

    /**
     * Records the keys held by the player at the controls.
     *
     * <p>Anyone who is not the player holding this cab's controls is ignored: the packet is a report of
     * one's own keyboard, so it must never become a way for another player to drive someone else's cab.
     */
    public void setManualInput(net.minecraft.world.entity.player.Player player, boolean forward, boolean backward,
                               boolean brake) {
        if (!engaged || engagedPlayer == null || !engagedPlayer.equals(player.getUUID())) {
            return;
        }
        manualForward = forward;
        manualBackward = backward;
        manualBrake = brake;
    }

    /** Takes control, or hands it back, reporting the result in the action bar. */
    public void toggleEngaged(net.minecraft.world.entity.player.Player player) {
        engaged = !engaged;
        engagedPlayer = engaged ? player.getUUID() : null;
        if (!engaged) {
            // Handing back control returns the vehicle to its schedule rather than abandoning it.
            setState(CabState.IDLE);
            retryTimer = 0;
        }
        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                (engaged ? "接管中" : "待命")
                        + "  速度 " + String.format(java.util.Locale.ROOT, "%.1f", speed) + " 格/秒"
                        + "  信号 " + signal
                        + (hasSchedule() ? "  时刻表 " + entryIndex + "/" + entryCount : "  无时刻表")), true);
    }
    /** Game tick at which the current wait ends. Absolute, so the wait cannot drift with the loop rate. */
    private long waitUntilTick;
    /** How long the vehicle has been held at the same red signal, and the last gap seen. */
    private int heldTicks;
    private double lastHeldDistance = -1.0D;
    private boolean loggedCreep;
    /** True when the red ahead is known to be another train's occupancy, which is never passed. */
    private boolean signalOccupiedByTrain;
    private boolean loggedDestinationTrouble;
    private boolean loggedRouteResult;
    private int activeBogeys;
    private int entryIndex;
    private int entryCount;

    public PhysicsCabBlockEntity(BlockPos pos, BlockState state) {
        super(RDBlockEntities.PHYSICS_CAB.get(), pos, state);
    }

    // ------------------------------------------------------------------ accessors

    public ItemStackHandler inventory() {
        return inventory;
    }

    public ContainerData statusData() {
        return data;
    }

    public CabState state() {
        return state;
    }

    public SignalAspect signal() {
        return signal;
    }

    public double speed() {
        return speed;
    }

    public String destination() {
        return destination;
    }

    public String routeDetail() {
        return routeDetail;
    }

    public String vehicleTag() {
        return vehicleTag;
    }

    public void setVehicleTag(String tag) {
        this.vehicleTag = tag == null ? "" : tag;
        this.vehicleId = null;
        this.retryTimer = 0;
        setChanged();
        sync();
    }

    public boolean hasSchedule() {
        return !inventory.getStackInSlot(SLOT_SCHEDULE).isEmpty();
    }

    /** True while this cab is the active controller; a free wagon either has no engaged cab or none at all. */
    public boolean isEngaged() {
        return engaged;
    }

    public void onNeighborChanged() {
        retryTimer = 0;
    }

    /**
     * Puts one schedule from {@code stack} into the cab's slot, handing whatever was there back to the
     * player rather than deleting it - a schedule can hold a lot of editing work.
     */
    public void installSchedule(ItemStack stack, Player player) {
        ItemStack previous = inventory.getStackInSlot(SLOT_SCHEDULE);
        if (!previous.isEmpty()) {
            if (!player.getInventory().add(previous)) {
                player.drop(previous, false);
            }
        }
        ItemStack installed = stack.copy();
        installed.setCount(1);
        inventory.setStackInSlot(SLOT_SCHEDULE, installed);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        // Mirror onto the vehicle's other cab. The two cabs exist so the vehicle can run in either
        // direction, and the one in charge is whichever faces the destination - so a schedule living on
        // only one of them means the vehicle forgets its route as soon as the other cab takes over. The
        // player's gesture is the source of truth, so the last install wins.
        if (player.level() instanceof ServerLevel server) {
            for (PhysicsCabBlockEntity sibling : siblings(server)) {
                sibling.inventory.setStackInSlot(SLOT_SCHEDULE, installed.copy());
            }
        }
        player.displayClientMessage(
                Component.translatable("raildispatch.gui.physics_cab.schedule_installed"), true);
    }

    /**
     * Copies the schedule stack - including Create's persisted {@code savedProgress} - to every sibling
     * cab. ItemStack.matches() is what terminates the copy: the sibling's own onContentsChanged then sees
     * a stack identical to this one and does not copy it back.
     */
    private void syncScheduleToSiblings(ServerLevel server) {
        if (syncingSchedule) {
            return;
        }
        ItemStack stack = inventory.getStackInSlot(SLOT_SCHEDULE);
        syncingSchedule = true;
        try {
            java.util.List<PhysicsCabBlockEntity> siblings = siblings(server);
            int copied = 0;
            for (PhysicsCabBlockEntity sibling : siblings) {
                ItemStack theirs = sibling.inventory.getStackInSlot(SLOT_SCHEDULE);
                if (!ItemStack.matches(theirs, stack)) {
                    sibling.inventory.setStackInSlot(SLOT_SCHEDULE, stack.copy());
                    copied++;
                }
            }
            if (copied > 0 || !siblings.isEmpty()) {
                RailDispatch.debug("cab {}: schedule sync, {} sibling cab(s), {} updated",
                        worldPosition, siblings.size(), copied);
            }
        } finally {
            syncingSchedule = false;
        }
    }

    /**
     * The other cabs of this train, including cabs on a coupled sub-level.
     *
     * <p>A multi-car physics train is several sub-levels. The local actor list only contains this car's
     * cab, so a two-car train used to report zero siblings: the schedule was never copied across and the
     * direction test never saw the cab at the other end. The coupled sub-levels are reached through the
     * bogies' own connection dependencies, then through the physics bridge by UUID.
     */
    private java.util.List<PhysicsCabBlockEntity> siblings(ServerLevel server) {
        java.util.Optional<VehicleHandle> here = RailServices.physics().vehicleOf(server, worldPosition);
        if (here.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<PhysicsCabBlockEntity> others = new java.util.ArrayList<>();
        java.util.Set<UUID> visited = new java.util.HashSet<>();
        collectCabs(here.get(), others, visited);
        for (UUID id : here.get().connectedVehicleIds()) {
            RailServices.physics().vehicleById(server, id)
                    .ifPresent(connected -> collectCabs(connected, others, visited));
        }
        return others;
    }

    private void collectCabs(VehicleHandle handle, java.util.List<PhysicsCabBlockEntity> out,
                             java.util.Set<UUID> visited) {
        if (!visited.add(handle.id())) {
            return;
        }
        for (PhysicsCabBlockEntity cab : handle.actorsOfType(PhysicsCabBlockEntity.class)) {
            if (cab != this && !out.contains(cab)) {
                out.add(cab);
            }
        }
    }

    /** Gives the installed schedule back to the player. */
    public void ejectSchedule(Player player) {
        ItemStack schedule = inventory.getStackInSlot(SLOT_SCHEDULE);
        if (schedule.isEmpty()) {
            return;
        }
        // Taking the schedule out takes it off the whole vehicle, both cabs together. Clearing only the cab
        // that was clicked would leave the other one still holding a copy, and the vehicle would carry on
        // running a schedule its driver believes they have removed.
        if (player.level() instanceof ServerLevel server) {
            for (PhysicsCabBlockEntity sibling : siblings(server)) {
                sibling.inventory.setStackInSlot(SLOT_SCHEDULE, ItemStack.EMPTY);
            }
        }
        inventory.setStackInSlot(SLOT_SCHEDULE, ItemStack.EMPTY);
        if (!player.getInventory().add(schedule)) {
            player.drop(schedule, false);
        }
        player.displayClientMessage(
                Component.translatable("raildispatch.gui.physics_cab.schedule_removed"), true);
    }

    public int comparatorOutput() {
        if (state == CabState.IDLE && !hasSchedule()) {
            return 0;
        }
        return switch (state) {
            case IDLE -> 1;
            case REQUESTING_ROUTE -> 4;
            case RUNNING -> 8;
            case APPROACHING -> 11;
            case HOLDING -> 15;
            case WAITING -> 6;
            case ARRIVED -> 2;
            case COUPLING -> 10;
        };
    }

    /** Copies the live status into the synced container data. Called once per control cycle. */
    private void publishStatus() {
        data.set(DATA_STATE, state.ordinal());
        data.set(DATA_SIGNAL, signal.ordinal());
        data.set(DATA_SPEED, (int) Math.round(speed * 100.0D));
        data.set(DATA_WAIT, level == null ? waitTicks
                : (int) Math.max(0L, waitUntilTick - level.getGameTime()));
        data.set(DATA_ROUTE, routeGranted ? 1 : 0);
        data.set(DATA_SCHEDULE, hasSchedule() ? 1 : 0);
        data.set(DATA_BOGEYS, activeBogeys);
        data.set(DATA_ENTRY, entryIndex);
        data.set(DATA_ENTRIES, entryCount);
    }

    // ------------------------------------------------------------------ tick

    public void serverTick() {
        actorTick();
    }

    /**
     * Sable's own hook for a block entity that is part of an assembled structure.
     *
     * <p>An assembled structure's blocks live in a plot, and the plot's tick does not tick block
     * entities the way a normal chunk does - the physics engine drives its parts through this actor
     * interface instead. Without it a cab that has been assembled onto a vehicle would never run at
     * all, which is indistinguishable from "the feature does not work".
     *
     * <p>Running the loop from both the vanilla ticker and this hook is harmless: {@link #runControl}
     * refuses to do anything twice in the same game tick.
     */
    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        actorTick();
    }

    /**
     * One pass per game tick, whichever hook got here first.
     *
     * <p>The command is re-sent <b>every</b> tick even though the decision is only taken every fifth,
     * because Simurail stores a remote override together with a tick stamp and therefore lets it lapse.
     * A controller that refreshed only four times a second left the bogies alternating between
     * commanded and expired, which is what the driver saw as the vehicle twitching forwards in nudges.
     */
    private void actorTick() {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        long now = server.getGameTime();
        if (now == lastControlTick) {
            return;
        }
        lastControlTick = now;
        if (needsImmediateHold) {
            // Take control the moment the world is back, without waiting for the next decision.
            //
            // Simurail bogies fall back to their own control when no remote controller is attached, and
            // setRemoved() released ours on unload. Between loading and the first decision, up to five
            // ticks later, the bogies were therefore driving themselves - the burst of power noticed on
            // re-entering the world. The flag stays set until a vehicle is actually found.
            needsImmediateHold = !holdImmediately(server);
        }
        // A deterministic phase off the game clock, rather than a call counter, so that being driven
        // by two different hooks cannot shift the 4 Hz rhythm.
        if (now % CONTROL_PERIOD == 0L) {
            reportScheduleChange(server);
            if (!reportDerailment(server, now)) {
                decide(server, now);
            }
        }
        refreshCommand();
        publishThrottle();
        reportProbe(server, now);
    }

    /**
     * Logs what the vehicle's own track probe sees, once every five seconds.
     *
     * <p>Diagnostic on purpose, before any decision is built on it. The probe is reached through
     * {@code getAxle(true)}, and which axle that argument selects is not named in the bytecode - it was
     * inferred. If this line reports a signal the vehicle has already passed, or a station behind it, then the
     * argument is the other way round, and that is much cheaper to discover from a log than from a vehicle
     * that brakes at the wrong places.
     */
    private void reportProbe(ServerLevel server, long now) {
        if (now % 100L != 0L) {
            return;
        }
        VehicleHandle vehicle = currentVehicle;
        if (vehicle == null) {
            return;
        }
        com.raildispatch.api.TrackProbe probe = vehicle.probeAhead();
        if (probe == null) {
            RailDispatch.debug("cab {}: the track probe reports nothing", worldPosition);
            return;
        }
        RailDispatch.debug("cab {}: probe: signal d={} aligned={} names={} | station d={} '{}' | "
                        + "discontinuity d={}", worldPosition,
                String.format(java.util.Locale.ROOT, "%.1f", probe.signalDistance()), probe.signalAligned(),
                probe.signalNames(),
                String.format(java.util.Locale.ROOT, "%.1f", probe.stationDistance()), probe.stationName(),
                String.format(java.util.Locale.ROOT, "%.1f", probe.discontinuityDistance()));
    }

    /**
     * Publishes the current throttle onto the vehicle's wire network, for Drive By Wire.
     *
     * <p>This is how traction leaves the cab now: the cab states a throttle, the player wires that channel to
     * whatever the vehicle actually responds to, and no part of this mod has to reach into the bogeys'
     * internals. It also makes reverse expressible at last - the bogey API's strength override is a
     * non-negative level and simply cannot say "backwards", whereas a second channel can.
     *
     * <p>Published only when the value changes: every publish walks the sinks and fires neighbour updates, so
     * repeating an unchanged value each tick would be pure noise. Publishing is best-effort - if Drive By Wire
     * is absent or unhappy, the cab must still drive.
     */
    private void publishThrottle() {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        // The sign of the throttle picks the channel, exactly as Drive By Wire does for its own controller
        // axes: forward and reverse are two names, and only one of them is ever non-zero.
        //
        // Only these two. A pair of steering channels was briefly published here, from a misreading of the
        // reverser requirement as "steering" when it meant "reversing" - the direction of the engine. Since
        // forward and reverse already are that direction, nothing was left for a steering channel to do, and
        // offering channels the mod never drives would only confuse whoever wires them up.
        double throttle = clamp01(commandedDrive);
        // The direction decides which channel carries the throttle. Both are written every time so that the
        // unused one is explicitly cleared instead of sitting on the last value from the other direction.
        // Whichever side drives us towards the destination carries the throttle; the opposite side is used to
        // brake, by driving the gearbox the other way.
        //
        // This is what actually slows this vehicle. Its speed is set by the drivetrain through the directional
        // gearbox, so a force applied to the rigid body is far too weak to regulate it - and cutting the signal
        // entirely, which is what braking used to do, may be worse than useless if the drivetrain simply runs on
        // when it has no signal. Driving the opposite side is the gearbox's own brake, and it leaves one side
        // energised at all times.
        double drive = clamp01(commandedDrive);
        double brake = clamp01(commandedBrake);
        int towards = (int) Math.round(drive * 15.0D);
        int against = (int) Math.round(brake * 15.0D);
        int forward = reversing ? against : towards;
        int backward = reversing ? towards : against;
        // The bogies' own brake, at full strength over the last stretch before the stopping point.
        //
        // Reversing the drivetrain slows the vehicle but does not settle it exactly where it should stop, and that
        // remaining distance error is what this is for: over the final approach the bogies are asked for everything
        // they have, which is the one brake that acts on the vehicle directly rather than through the drivetrain.
        // Only while genuinely approaching, never merely because the last measured distance was small, and only
        // over the last couple of blocks.
        //
        // Setting off on the next leg, lastDistance still holds the previous leg's figure - the new destination has
        // not been resolved yet - so a distance-only test kept the bogies' brake at full strength and the vehicle
        // could not leave. The state is what says whether a stop is being approached at all. The range is small on
        // purpose: the bogies' brake is the last touch that settles the vehicle on the mark, while the approach
        // itself is shaped by the speed curve and the reversed drivetrain.
        // The bogies' brake holds the vehicle still once it has stopped - it does not take over the approach.
        //
        // Applying it over the last couple of blocks pinned the vehicle: the drivetrain was still being asked to
        // make the final correction, and the brake simply held against it, so the vehicle stopped away from the
        // mark and could not be moved onto it. The approach belongs to the speed curve and the drivetrain; this is
        // the parking brake that keeps the result.
        boolean stopped = state == CabState.ARRIVED || state == CabState.WAITING || state == CabState.HOLDING;
        double bogieBrake = Math.max(clamp01(commandedBrake), stopped ? 1.0D : 0.0D);
        if (stopped) {
            // Nothing is asked of the drivetrain while the parking brake holds the vehicle.
            //
            // Leaving the throttle live left the vehicle pulling against itself: the readout showed power active
            // with the brake still applied, so it could neither move nor be trusted to stay still. At a standstill
            // one of the two has to be silent, and it is the throttle.
            forward = 0;
            backward = 0;
        }
        int bogieBrakeSignal = (int) Math.round(bogieBrake * 15.0D);
        if (forward == publishedForward && backward == publishedBackward
                && bogieBrakeSignal == publishedBogieBrake) {
            return;
        }
        publishedForward = forward;
        publishedBackward = backward;
        publishedBogieBrake = bogieBrakeSignal;
        try {
            edn.stratodonut.drivebywire.wire.WireNetworkManager.trySetSignalAt(
                    server, worldPosition, com.raildispatch.block.PhysicsCabBlock.CHANNEL_FORWARD, forward);
            edn.stratodonut.drivebywire.wire.WireNetworkManager.trySetSignalAt(
                    server, worldPosition, com.raildispatch.block.PhysicsCabBlock.CHANNEL_BACKWARD, backward);
            edn.stratodonut.drivebywire.wire.WireNetworkManager.trySetSignalAt(server, worldPosition,
                    com.raildispatch.block.PhysicsCabBlock.CHANNEL_BOGIE_BRAKE, bogieBrakeSignal);
        } catch (Throwable throwable) {
            if (!warnedWire) {
                warnedWire = true;
                RailDispatch.LOGGER.warn("Could not publish the throttle onto the Drive By Wire network; "
                        + "traction will not reach the vehicle over a wire", throwable);
            }
        }
    }

    /**
     * Drives the vehicle from the controlling player's W/S input, while a player holds the controls.
     *
     * <p>The keys arrive in {@link com.raildispatch.network.CabControlPayload}, sent by the client because
     * in 1.21.1 the server cannot see a player's movement keys at all; reading {@code Player#zza} here was
     * measured at zero while W was held.
     *
     * <p>Reverse is deliberately not implemented yet: S brakes, and once stopped it keeps holding. Driving
     * backwards needs the bogies' direction channel, which has not been identified, and inventing one
     * would risk sending the vehicle the wrong way.
     */
    private void manualControl(ServerLevel server, long now) {
        Optional<VehicleHandle> here = RailServices.physics().vehicleOf(server, worldPosition);
        if (here.isEmpty() || !here.get().isValid()) {
            return;
        }
        VehicleHandle vehicle = here.get();
        currentVehicle = vehicle;
        speed = vehicle.speed() * 20.0D;

        // A throttle setpoint, not a direction. W winds it up, S winds it down, and it is allowed to cross
        // zero - at which point the traction leaves by the reverse channel instead of the forward one. The
        // brake key is deliberately separate and never touches the setpoint, so letting go of the brake
        // resumes whatever the driver had wound on rather than starting again from nothing.
        double rate = 0.02D;
        if (manualForward) {
            manualThrottle = Math.min(1.0D, manualThrottle + rate);
        } else if (manualBackward) {
            manualThrottle = Math.max(-1.0D, manualThrottle - rate);
        }
        commandedDrive = slew(commandedDrive, clamp01(Math.abs(manualThrottle)));
        commandedBrake = slew(commandedBrake, manualBrake ? clamp01(RDConfig.brakeGain()) : 0.0D);
        commandedSteer = slew(commandedSteer, 0.0D);
        if (now % 20L == 0L) {
            RailDispatch.debug("cab {}: throttle={} brake={} speed={} 格/秒", worldPosition,
                    String.format(java.util.Locale.ROOT, "%.2f", manualThrottle), manualBrake,
                    String.format(java.util.Locale.ROOT, "%.2f", speed));
        }
        vehicle.command(worldPosition, commandedDrive, commandedBrake, commandedSteer);
        publishStatus();
    }

    /**
     * Notices that the player swapped or edited the schedule - in <b>any</b> state, not only at rest.
     *
     * <p>The check used to live only in the resting states, so a schedule changed while the cab was waiting,
     * running or asking for a route was never noticed at all: the cab carried on with the old leg and never
     * asked for a new route. That is what made a changed schedule look like a switch problem - no new route
     * was ever requested, so no switch was ever driven again.
     */
    private void reportScheduleChange(ServerLevel server) {
        ItemStack stack = inventory.getStackInSlot(SLOT_SCHEDULE);
        int identity = scheduleIdentity(stack, server.registryAccess());
        if (identity == lastScheduleId) {
            return;
        }
        lastScheduleId = identity;
        syncScheduleToSiblings(server);
        // Drop back to IDLE so the new schedule is picked up, but deliberately leave the schedule's own
        // position alone: a schedule carries the entry it is on, and that is the one a driver expects to run -
        // the entire point of the item keeping its progress is that it resumes where it was left. Resetting to
        // the first entry here would quietly override the player's own choice of which leg to run.
        setState(CabState.IDLE);
        routeGranted = false;
        retryTimer = 0;
        closestDestination = -1.0D;
        resetHoldTimer();
        // The direction decision belongs to the schedule that was running, so it goes with it. Leaving it behind
        // is how a decision made for one entry leaked into every later one: the flag said the direction was
        // already settled, so no later leg ever reconsidered it, and changing the entry or the destination made
        // no difference at all - the vehicle simply kept setting off the way the old entry had wanted.
        reversing = false;
        directionDecided = false;
        // Destroy the route outright rather than only forgetting it locally.
        //
        // Resetting our own flags left the dispatch centre's sections reserved and the switches locked, so the
        // route stayed occupied for other trains until the idle timeout happened to release it. "The schedule was
        // taken out" should mean the route is gone, not that we have stopped thinking about it.
        if (vehicleId != null) {
            RailServices.track().releaseRoute(server, vehicleId);
        }
        RailDispatch.debug("cab {}: the schedule changed; running the entry it is on", worldPosition);
    }

    /**
     * Whether the route runs against the way this cab faces, and the vehicle therefore has to reverse.
     *
     * <p>Composed from two primitives that were each built for exactly this kind of question: the cab's own
     * facing rotated out of the structure's frame into the world, and the direction of the route's first leg.
     * The rotation is not optional - the block's facing on its own is a direction in the structure's plot and
     * comparing it with a world direction is how the signal search once discarded every signal there was.
     *
     * <p>Everything unknown answers false. A wrong "reverse" would send the vehicle the wrong way down the
     * line, which is far worse than driving forwards when the answer could not be worked out.
     */
    /**
     * How closely the direction to {@code towards} matches the way the vehicle is pointing: +1 straight at it,
     * -1 directly behind, {@code null} when that cannot be known.
     *
     * <p>The heading comes from the vehicle's own last real movement, not from the block's facing. The block's
     * facing was tried first and measured a dot of about +0.2 - near enough perpendicular - because a cab's
     * facing is fixed when it is placed and need not lie along the vehicle's travel axis at all. A heading taken
     * from displacement has no such problem.
     */
    /**
     * How closely the direction to {@code towards} matches the learned engine forward: +1 means the throttle
     * should go out as "forward" to get there, -1 means it should go out as "reverse", {@code null} when nothing
     * has been learned yet.
     */
    private Double destinationDot(Vec3 towards) {
        Vec3 heading = forwardHeading;
        if (heading == null || heading.lengthSqr() < 1.0E-6D) {
            // Never observed moving, so which way the throttle drives the vehicle is unknown. Forwards is the safe
            // answer: reversing the wrong way is far worse than setting off the way it already points, and the
            // direction is learned the moment it does move.
            return null;
        }
        Vec3 along = new Vec3(towards.x, 0.0D, towards.z);
        if (along.lengthSqr() < 1.0E-6D) {
            return null;
        }
        return heading.normalize().dot(along.normalize());
    }

    /** Re-asserts brake control immediately, returning false while the vehicle cannot be resolved yet. */
    private boolean holdImmediately(ServerLevel server) {
        Optional<VehicleHandle> here = RailServices.physics().vehicleOf(server, worldPosition);
        if (here.isEmpty() || !here.get().isValid()) {
            return false;
        }
        currentVehicle = here.get();
        hold(here.get());
        return true;
    }

    /** Re-asserts the current command, so the bogies' remote overrides never lapse between decisions. */
    private void refreshCommand() {
        VehicleHandle vehicle = currentVehicle;
        if (vehicle != null && vehicle.isValid() && !standingDownForConsole) {
            vehicle.command(worldPosition, commandedDrive, commandedBrake, commandedSteer);
        }
    }

    /** True when an autopilot console on this vehicle has been started and is driving it. */
    private static boolean consoleInCharge(VehicleHandle vehicle) {
        for (AutopilotConsoleBlockEntity console : vehicle.actorsOfType(AutopilotConsoleBlockEntity.class)) {
            if (console != null && console.isDriving()) {
                return true;
            }
        }
        return false;
    }

    private void decide(ServerLevel server, long now) {

        if (!loggedFirstCycle) {
            loggedFirstCycle = true;
            // Proves the loop is actually running. Without this line in the log, a cab that is silent
            // in game has not been ticked at all, which is a completely different problem from a cab
            // that runs but cannot find its vehicle.
            com.raildispatch.RailDispatch.debug(
                    "Physics cab at {} is running its control loop ({}).",
                    worldPosition, server.getClass().getSimpleName());
        }

        PhysicsBridge physics = RailServices.physics();
        VehicleHandle vehicle = resolveVehicle(server, physics);
        if (vehicle == null) {
            if (!loggedNoVehicle) {
                loggedNoVehicle = true;
                com.raildispatch.RailDispatch.LOGGER.warn("Physics cab at {} found no physics vehicle: "
                                + "physicsAvailable={}, boundTag='{}', cachedVehicleId={}.",
                        worldPosition, physics.isAvailable(), vehicleTag, vehicleId);
            }
            setState(CabState.IDLE);
            activeBogeys = 0;
            speed = 0.0D;
            routeGranted = false;
            commandedDrive = 0.0D;
            commandedBrake = 0.0D;
            currentVehicle = null;
            publishStatus();
            return;
        }
        currentVehicle = vehicle;

        // An engaged autopilot console is the driver now. Holding here would be wrong: hold() applies a
        // full braking impulse, which is exactly how two controllers end up fighting rather than one
        // yielding.
        if (consoleInCharge(vehicle)) {
            standingDownForConsole = true;
            commandedDrive = 0.0D;
            commandedBrake = 0.0D;
            commandedSteer = 0.0D;
            routeGranted = false;
            speed = vehicle.speed() * 20.0D;
            activeBogeys = vehicle.bogeyCount();
            setState(CabState.IDLE);
            publishStatus();
            return;
        }
        standingDownForConsole = false;

        if (engaged) {
            CabRegistry.claim(server, vehicle.id(), worldPosition);
        } else {
            // An idle cab must not own the vehicle; otherwise a free wagon can never be picked up by
            // yard routing or by a second cab.
            CabRegistry.releaseAt(server, worldPosition);
        }
        speed = vehicle.speed() * 20.0D;
        activeBogeys = vehicle.bogeyCount();

        ItemStack stack = inventory.getStackInSlot(SLOT_SCHEDULE);
        Optional<ScheduleView> view = Schedules.read(server.registryAccess(), stack);
        entryIndex = view.map(ScheduleView::currentEntry).orElse(0);
        entryCount = view.map(ScheduleView::entryCount).orElse(0);

        // Fall back to the learned heading rather than passing nothing.
        //
        // A null heading switched the ahead-filter off entirely inside the search, so at a standstill - exactly
        // when a vehicle waits for a signal to clear - every signal within range counted, including ones it had
        // already passed, and those went on governing it. Passing the learned heading keeps the filter working.
        // One source only: the cab's facing, negated while reversing.
        //
        // heading(vehicle) already reports the direction of travel, and negating that when reversing turned it
        // back to the cab's front - so the search followed the cab instead of the vehicle and kept finding the
        // signals it had behind it. Mixing a measured direction with a negated one is how the same sign error kept
        // coming back; the facing is stable, and "reversing" is the only thing that may flip it.
        Vec3 searchHeading = forwardHeading;
        if (searchHeading != null && !reversing) {
            // Same polarity as the "destination behind" test: the gearbox's forward side drives the vehicle
            // opposite the cab's striped face. So while running forwards the vehicle travels -facing, and the
            // search has to follow the vehicle, not the stripes - otherwise it looks behind the vehicle and
            // finds the signals it has already passed.
            searchHeading = searchHeading.scale(-1.0D);
        }
        // No signal can govern a vehicle that has not yet settled which way it is going.
        //
        // The search runs every control cycle, while the direction is settled once per leg, shortly after the
        // route is granted. Asking "what is ahead of me" during that window meant asking it about the cab's own
        // front - the forward throttle's direction - while the vehicle was about to run the other way, so it read
        // and obeyed the signals behind it. Waiting costs a fraction of a second and removes the question.
        SignalInfo info = directionDecided
                ? RailServices.track().signalAhead(server, vehicle.position(), searchHeading,
                        RDConfig.signalLookahead())
                : SignalInfo.NONE;
        signal = info.aspect();
        signalDistance = info.distance();
        signalOccupiedByTrain = info.occupiedByTrain();

        if (retryTimer > 0) {
            retryTimer--;
        }

        switch (state) {
            case IDLE -> {
                if (view.isPresent()) {
                    setState(CabState.REQUESTING_ROUTE);
                } else {
                    hold(vehicle);
                }
            }
            case REQUESTING_ROUTE -> requestRoute(server, vehicle, stack, view);
            case RUNNING, APPROACHING, HOLDING -> {
                if (!routeGranted && view.isPresent() && RailServices.track().isAvailable()) {
                    // Self-heal. A drive state without a granted route must never persist: it means the
                    // cab is running on no authority at all, and it is exactly how a stale state - or any
                    // future bug that skips the request - turns into "the dispatch layer does nothing".
                    setState(CabState.REQUESTING_ROUTE);
                    retryTimer = 0;
                    hold(vehicle);
                } else {
                    drive(server, vehicle, stack, view);
                }
            }
            case WAITING -> {
                hold(vehicle);
                // Compared against a deadline in game ticks rather than counted down once per cycle.
                // The decision loop runs every fifth tick, so decrementing by one per cycle made every
                // wait five times too long - a five second wait took twenty-five.
                if (server.getGameTime() >= waitUntilTick) {
                    advanceEntry(server, vehicle, stack);
                }
            }
            case ARRIVED -> {
                hold(vehicle);
                // Arrived used to mean "finished for ever unless the schedule is cyclic", which left a
                // completed non-cyclic schedule with no way out: it never continued, and installing a
                // fresh schedule changed nothing because there was no path back to requesting a route.
                // It is a resting state now, not a terminal one.
                int identity = scheduleIdentity(stack, server.registryAccess());
                if (identity != lastScheduleId) {
                    lastScheduleId = identity;
                    closestDestination = -1.0D;
                    retryTimer = 0;
                    setState(CabState.REQUESTING_ROUTE);
                }
            }
            case COUPLING -> coupling(server, vehicle, stack, view);
        }
        publishStatus();

        // Throttled telemetry, one line every five seconds. A control loop that oscillates is nearly
        // impossible to diagnose from the driver's seat, and these four numbers say immediately whether
        // the command is hunting, whether the signal is being seen, and how fast the vehicle thinks it
        // is going.
        if (server.getGameTime() % 100L == 0L) {
            com.raildispatch.RailDispatch.debug(
                    "cab {}: speed={} b/s drive={} brake={} signal={} signalDistance={} dist={} tgt={} "
                            + "state={} bogeys={}",
                    worldPosition,
                    String.format(java.util.Locale.ROOT, "%.2f", speed),
                    String.format(java.util.Locale.ROOT, "%.2f", commandedDrive),
                    String.format(java.util.Locale.ROOT, "%.2f", commandedBrake),
                    signal, String.format(java.util.Locale.ROOT, "%.0f", signalDistance),
                    String.format(java.util.Locale.ROOT, "%.1f", lastDistance),
                    String.format(java.util.Locale.ROOT, "%.2f", lastTargetSpeed), state, activeBogeys);
        }
    }

    // ------------------------------------------------------------------ vehicle resolution

    private VehicleHandle resolveVehicle(ServerLevel server, PhysicsBridge physics) {
        if (!physics.isAvailable()) {
            return null;
        }
        if (vehicleId != null) {
            Optional<VehicleHandle> bound = physics.vehicleById(server, vehicleId);
            if (bound.isPresent() && bound.get().isValid()) {
                return bound.get();
            }
            vehicleId = null;
        }
        if (!vehicleTag.isEmpty()) {
            Optional<UUID> tagged = RailTags.get(server).vehicleFor(vehicleTag);
            if (tagged.isPresent()) {
                Optional<VehicleHandle> found = physics.vehicleById(server, tagged.get());
                if (found.isPresent()) {
                    vehicleId = tagged.get();
                    return found.get();
                }
            }
        }
        Optional<VehicleHandle> here = physics.vehicleOf(server, worldPosition);
        if (here.isPresent()) {
            vehicleId = here.get().id();
            return here.get();
        }
        return null;
    }

    /**
     * The direction the vehicle is travelling in, or {@code null} when that cannot be determined.
     *
     * <p>The {@code null} case matters. While moving, Sable's linear velocity is world space and
     * trustworthy. While stopped there is no velocity to use, and the only other facing available is
     * the block's own FACING property - which lives in the physics engine's plot coordinate space and
     * is therefore meaningless when compared against world positions. Returning it anyway made the
     * signal search discard every signal as "behind us", which is why the cab reported no signal and
     * consequently never stopped at a red one.
     */
    private Vec3 heading(VehicleHandle vehicle) {
        // Sticky on purpose. This used to return null while the vehicle was stopped, on the grounds that
        // we could not know which way it faced - but the only effect was to disable the direction filter
        // entirely, so a signal the vehicle had already passed was still read as the governing one and
        // the cab obeyed signals behind it. Remembering the last direction it actually travelled in is
        // far better than forgetting it: a vehicle that has stopped has not changed which way it points.
        Vec3 position = vehicle.position();
        if (lastHeadingPosition != null) {
            Vec3 delta = position.subtract(lastHeadingPosition);
            if (delta.lengthSqr() > 1.0E-6D) {
                lastHeading = new Vec3(delta.x, 0.0D, delta.z).normalize();
            }
        }
        lastHeadingPosition = position;
        return lastHeading;
    }

    // ------------------------------------------------------------------ states

    private void requestRoute(ServerLevel server, VehicleHandle vehicle, ItemStack stack,
                              Optional<ScheduleView> view) {
        TrackNetwork network = RailServices.track();
        Optional<Vec3> target = destinationPosition(server, vehicle, view);
        if (target.isEmpty()) {
            // Not "drive on without a route". Without a destination there is nothing to route to, and
            // silently proceeding is what made the whole dispatch layer look dead: no route was ever
            // planned, so no dispatch centre was ever asked and no switch was ever thrown.
            hold(vehicle);
            routeDetail = "no destination resolved";
            reportDestinationTrouble(server, network, view);
            setState(CabState.REQUESTING_ROUTE);
            return;
        }
        if (!network.isAvailable()) {
            hold(vehicle);
            routeDetail = "no track network";
            reportOnce("no track network available", server);
            setState(CabState.REQUESTING_ROUTE);
            return;
        }
        if (retryTimer > 0) {
            hold(vehicle);
            return;
        }
        // Logged immediately before the network call, so that "a request was never made" becomes visible.
        //
        // The cab sat in REQUESTING_ROUTE for ever while planRoute was being called elsewhere and no granted/denied
        // line appeared - which means this method returned at one of its gates above, and the state machine then
        // waited for an answer to a question it had never asked.
        RailDispatch.debug("requestRoute: issuing request (state={} routeGranted={} retryTimer={})",
                state, routeGranted, retryTimer);
        TrackNetwork.RouteGrant grant = network.requestRoute(server, vehicle.id(), vehicle.name(),
                vehicle.position(), target.get(), RDConfig.allowLocalSwitchControl());
        routeGranted = grant.granted();
        // Logged the instant the result is applied.
        //
        // The requests were issued and answered GRANTED three times, yet the cab stayed in REQUESTING_ROUTE with no
        // destination at all - so either this assignment is not what advances the state, or something resets it on
        // the following cycle. Printing state and destination here separates those two.
        RailDispatch.debug("after grant: granted={} state={} destination={}",
                routeGranted, state, destination);
        routeDetail = grant.detail();
        retryTimer = ROUTE_RETRY_TICKS;
        if (!loggedRouteResult) {
            loggedRouteResult = true;
            com.raildispatch.RailDispatch.debug("cab {}: route from {} to {} -> {} ({})",
                    worldPosition, vehicle.position(), target.get(),
                    grant.granted() ? "GRANTED" : "DENIED", grant.detail());
        }
        if (grant.granted()) {
            // A new leg, so the "closest approach" tracking starts over.
            closestDestination = -1.0D;
            // ... and so the direction is open to be decided again, exactly once, when the vehicle sets off.
            directionDecided = false;
            boolean coupling = view.flatMap(ScheduleView::coupleTag).isPresent();
            setState(coupling ? CabState.COUPLING : CabState.RUNNING);
        } else {
            setState(CabState.REQUESTING_ROUTE);
            hold(vehicle);
        }
    }

    /**
     * Explains, once, why no destination could be resolved - and lists what could have been resolved.
     *
     * <p>Without this the cab simply said "no destination" in a detail string the screen does not even
     * show, and the dispatch layer looked like it did nothing at all.
     */
    private void reportDestinationTrouble(ServerLevel server, TrackNetwork network,
                                          Optional<ScheduleView> view) {
        if (loggedDestinationTrouble) {
            return;
        }
        loggedDestinationTrouble = true;
        String asked = view.map(schedule -> schedule.coupleTag()
                        .map(tag -> "couple vehicle '" + tag + "'")
                        .or(() -> schedule.shuntAddress().map(address -> "address '" + address + "'"))
                        .or(() -> schedule.destination().map(station -> "station '" + station + "'"))
                        .orElse("<entry has no destination text>"))
                .orElse("<no schedule>");
        java.util.List<String> known = network.knownDestinations(server);
        RailDispatch.LOGGER.warn("Cab at {} could not resolve a destination; the schedule asks for {}. "
                        + "Known destinations ({}): {}",
                worldPosition, asked, known.size(),
                known.isEmpty() ? "<none - no stations, address plates or vehicle tags are registered>"
                        : String.join(", ", known.subList(0, Math.min(20, known.size()))));
    }

    private void reportOnce(String what, ServerLevel server) {
        if (loggedDestinationTrouble) {
            return;
        }
        loggedDestinationTrouble = true;
        RailDispatch.LOGGER.warn("Cab at {} cannot route: {}", worldPosition, what);
    }

    private void drive(ServerLevel server, VehicleHandle vehicle, ItemStack stack, Optional<ScheduleView> view) {
        Optional<Vec3> target = destinationPosition(server, vehicle, view);
        double steer = target.map(point -> steerToward(vehicle, point)).orElse(0.0D);
        double targetSpeed = RDConfig.maxSpeed();

        if (signal == SignalAspect.DANGER && RDConfig.stopOnDanger()) {
            if (signalDistance < 0.0D) {
                hold(vehicle);
                setState(CabState.HOLDING);
                return;
            }
            targetSpeed = Math.min(targetSpeed, speedForStop(signalDistance));
            if (targetSpeed <= 0.08D) {
                if (deadlockedAtSignal(signalDistance)) {
                    // Call-on. A red that never clears while the gap has stopped closing is almost
                    // certainly the vehicle occupying the signal's own block, and standing there for
                    // ever is not a solution. Creep out at restricted speed instead, which is what a
                    // railway does; if the red was genuine the occupancy check stops it again.
                    targetSpeed = RDConfig.creepSpeed();
                    if (!loggedCreep) {
                        loggedCreep = true;
                        com.raildispatch.RailDispatch.debug("cab {}: held at a red signal for {} ticks "
                                        + "with the gap no longer closing; creeping out at {} b/s",
                                worldPosition, heldTicks, RDConfig.creepSpeed());
                    }
                    setState(CabState.APPROACHING);
                } else {
                    hold(vehicle);
                    setState(CabState.HOLDING);
                    return;
                }
            } else {
                setState(CabState.APPROACHING);
            }
        } else if (signal == SignalAspect.CAUTION && RDConfig.creepOnCaution()) {
            resetHoldTimer();
            targetSpeed = Math.min(targetSpeed, 4.0D);
            setState(CabState.APPROACHING);
        } else {
            resetHoldTimer();
            setState(CabState.RUNNING);
        }

        if (!routeGranted) {
            // Motion requires a route. A failed search is a wait-in-place condition, not a licence to
            // carry on under signal protection - and that is what a denied route turned into: the cab
            // held correctly while it was in REQUESTING_ROUTE, but any path that reached the drive loop
            // without a route simply drove on towards a destination it had no way of getting to.
            hold(vehicle);
            setState(CabState.REQUESTING_ROUTE);
            retryTimer = 0;
            return;
        }

        if (isControlCab(server, vehicle, target)) {
            // This cab faces the destination, so it commands the vehicle.
        } else {
            // The other cab on this vehicle faces the destination, so it is the one in charge. Standing by
            // is not the same as being idle: the cab holds, and leaves the schedule position untouched, so
            // that whichever cab is in charge is the one that advances it.
            hold(vehicle);
            setState(CabState.REQUESTING_ROUTE);
            return;
        }

        if (target.isPresent()) {
            // Which way round the vehicle has to run to reach it. This compares the cab's own world facing with
            // the straight line to the destination, which is an approximation: a route that first heads away
            // from its destination - a loop, or a long reversal through a junction - would be misread. The
            // exact answer needs the route's first leg, which lives in the network layer rather than here, so
            // it is logged clearly enough to be caught if it is ever wrong.
            // Only decided while the vehicle is standing still.
            //
            // Recomputing this every cycle made the vehicle oscillate between forwards and reverse: the
            // direction is measured towards the destination, so the moment the vehicle passed it the line
            // flipped, the cab reversed, the line flipped back, and so on. A direction is a decision taken when
            // setting off, which is also exactly what Create's own "destination is behind the train" means -
            // it is settled once, at the start of a leg, not re-argued while rolling.
            // Forward is where the cab's front face points - stated, not inferred.
            //
            // Inferring it from displacement was a guess at something that could simply be declared, and one wrong
            // guess was latched and then persisted, so every later leg ran the wrong way. The block's facing is
            // known the moment it is placed; once the front face is marked on the model it is also visible to the
            // player, who then places the cab the way the vehicle should go. It is refreshed periodically so that
            // turning the whole structure is followed too.
            if (forwardHeading == null || level.getGameTime() % 100L == 0L) {
                if (getBlockState().hasProperty(com.raildispatch.block.PhysicsCabBlock.FACING)) {
                    net.minecraft.core.Direction face =
                            getBlockState().getValue(com.raildispatch.block.PhysicsCabBlock.FACING);
                    Vec3 facingWorld = vehicle.localToWorld(
                            new Vec3(face.getStepX(), 0.0D, face.getStepZ()));
                    if (facingWorld != null && facingWorld.lengthSqr() > 1.0E-6D) {
                        forwardHeading = facingWorld.normalize();
                        setChanged();
                    }
                }
            }

            // The distance guard exists only to avoid re-deciding while the vehicle is sitting in the arrival zone,
            // where the vector towards the destination is meaningless and its direction is pure noise - that was
            // producing spurious reversals on arrival. It must therefore stay well BELOW the arrival radius: set
            // too large it swallows legitimate nearby destinations entirely, and the leg then silently inherits
            // the previous leg's direction and sets off the wrong way.
            if (!directionDecided && speed < 0.3D
                    && target.get().distanceTo(vehicle.position()) > RDConfig.arriveRadius() * 0.5D) {
                Double dot = destinationDot(target.get().subtract(vehicle.position()));
                // Positive dot means the destination is BEHIND, not ahead.
                //
                // forwardHeading is the cab's striped face; the gearbox's forward side drives the vehicle the other
                // way, so the two are opposed. Measured in game: with the destination visibly ahead, the cab chose
                // reverse and drove the backward channel. This comparison is the single place that polarity is
                // expressed - if the wiring is ever changed, this is the line that follows it, not the channels.
                boolean behind = dot != null && dot > 0.0D;
                reversing = behind;
                directionDecided = true;
                RailDispatch.debug("cab {}: setting off {} (dot={}, learned heading={})", worldPosition,
                        behind ? "in reverse - the destination lies behind" : "forwards",
                        dot == null ? "unknown" : String.format(java.util.Locale.ROOT, "%.2f", dot),
                        forwardHeading == null ? "not yet" : "known");
            } else if (server.getGameTime() % 100L == 0L) {
                RailDispatch.debug("cab {}: direction {} ({}, learned heading={})", worldPosition,
                        reversing ? "reverse" : "forwards",
                        directionDecided ? "settled for this leg" : "still to be decided",
                        forwardHeading == null ? "not yet" : "known");
            }
            // Horizontal distance, plus a "we have gone past it" test. The radius alone never fired: a
            // station block stands beside the track while the vehicle's position is its centre of mass
            // travelling along it, so the two can stay four to six blocks apart for the whole approach
            // and the vehicle simply drove past its own destination.
            double distance = horizontalDistance(vehicle.position(), target.get());
            lastDistance = distance;
            // Arrival uses its own curve, without the signal safety margin: reusing speedForStop here
            // would target 0 m/s a whole brakeMargin short of the destination.
            targetSpeed = Math.min(targetSpeed, speedForArrival(distance));
            lastTargetSpeed = targetSpeed;
            // Fine-grained while approaching, so that a single arrival yields a readable deceleration trace.
            // A sample every five seconds is far too coarse to tell "could not brake hard enough" apart from
            // "only began braking too late", and those two have opposite fixes.
            if (distance < 40.0D && level.getGameTime() % 10L == 0L) {
                RailDispatch.debug("approach: dist={} tgt={} speed={} drive={} brake={}",
                        String.format(java.util.Locale.ROOT, "%.1f", distance),
                        String.format(java.util.Locale.ROOT, "%.2f", targetSpeed),
                        String.format(java.util.Locale.ROOT, "%.2f", speed),
                        String.format(java.util.Locale.ROOT, "%.2f", commandedDrive),
                        String.format(java.util.Locale.ROOT, "%.2f", commandedBrake));
            }

            boolean reached = distance <= RDConfig.arriveRadius() && speed < 0.6D;
            boolean passed = closestDestination >= 0.0D
                    && distance > closestDestination + 2.0D
                    && closestDestination <= RDConfig.arriveRadius() + 4.0D;
            if (closestDestination < 0.0D || distance < closestDestination) {
                closestDestination = distance;
            }
            if (reached || passed) {
                if (passed && !reached) {
                    RailDispatch.debug("cab {}: passed its destination at {} blocks (closest {}) "
                                    + "without the arrival radius being met; stopping here",
                            worldPosition, String.format(java.util.Locale.ROOT, "%.1f", distance),
                            String.format(java.util.Locale.ROOT, "%.1f", closestDestination));
                }
                arrive(server, vehicle, stack, view);
                return;
            }
            if (distance <= RDConfig.arriveRadius()) {
                // Inside the arrival radius, aim to stop here rather than waiting to be slow first. The
                // old test wanted the vehicle already below walking pace at the moment it entered the
                // radius, which it never was - so it drove on past the mark and was only caught by the
                // "already gone past" fallback four blocks later.
                targetSpeed = 0.0D;
            }
        }
        applyControl(vehicle, targetSpeed, steer);
    }

    private void coupling(ServerLevel server, VehicleHandle vehicle, ItemStack stack, Optional<ScheduleView> view) {
        Optional<VehicleHandle> partner = view.flatMap(ScheduleView::coupleTag)
                .flatMap(tag -> RailTags.get(server).vehicleFor(tag))
                .flatMap(id -> RailServices.physics().vehicleById(server, id));
        if (partner.isEmpty()) {
            routeDetail = "couple target missing";
            setState(CabState.ARRIVED);
            return;
        }
        VehicleHandle other = partner.get();
        double distance = vehicle.position().distanceTo(other.position());
        if (distance > RDConfig.coupleRange()) {
            double steer = steerToward(vehicle, other.position());
            double targetSpeed = Math.min(RDConfig.maxSpeed(), speedForStop(Math.max(0.0D, distance - 1.0D)));
            applyControl(vehicle, Math.max(0.6D, targetSpeed), steer);
            return;
        }
        hold(vehicle);
        CouplerBridge coupler = RailServices.coupler();
        if (!coupler.isAvailable()) {
            routeDetail = "no coupler available";
            setState(CabState.ARRIVED);
            return;
        }
        routeDetail = coupler.couple(vehicle, other).orElse("coupling failed");
        setState(CabState.ARRIVED);
    }

    private void arrive(ServerLevel server, VehicleHandle vehicle, ItemStack stack,
                        Optional<ScheduleView> view) {
        hold(vehicle);
        RailServices.track().releaseRoute(server, vehicle.id());
        routeGranted = false;
        // Always pass through the wait phase, cyclic or not. The entry's conditions are what decide when
        // the journey continues, and treating "not cyclic" as terminal was exactly why a completed
        // arrival never moved again.
        waitTicks = Math.max(1, view.map(ScheduleView::waitTicks).orElse(1));
        waitUntilTick = level.getGameTime() + waitTicks;
        setState(CabState.WAITING);
    }

    private void advanceEntry(ServerLevel server, VehicleHandle vehicle, ItemStack stack) {
        RailServices.track().releaseRoute(server, vehicle.id());
        routeGranted = false;
        // Read the persisted index straight out of the component, before and after the advance. This is
        // the one datum that settles the open question: if Progress does not change here, the advance is
        // only happening in memory and the item is never being updated - which is what the screen
        // showed when it ran 1/2 -> 2/2 -> 1/2.
        int progressBefore = savedProgress(stack);
        Optional<ScheduleView> next = Schedules.advance(server.registryAccess(), stack);
        int progressAfter = savedProgress(stack);
        // The advance lives in this cab's stack. Without this copy the other cab would keep the old
        // Progress and, when the direction changed and it took over, start the leg that was already done.
        syncScheduleToSiblings(server);
        RailDispatch.debug("cab {}: advance {} -> {} (Progress NBT before/after), {}",
                worldPosition, progressBefore, progressAfter,
                next.isPresent() ? "continuing to the next entry" : "end of schedule");
        if (next.isEmpty()) {
            // Genuinely the end of the schedule. Record the identity of the schedule that finished, so
            // that resting in ARRIVED is not mistaken for "a new schedule has been installed".
            lastScheduleId = scheduleIdentity(stack, server.registryAccess());
            setState(CabState.ARRIVED);
            return;
        }
        // Do NOT wait again on the entry just selected.
        //
        // A wait - a delay, a redstone condition, anything in the entry's conditions - belongs to the
        // entry the vehicle has just FINISHED. That is what Create's POST_TRANSIT state means: the
        // conditions are served on arrival, and only once they are satisfied does the next entry begin.
        // Reading the newly selected entry's wait here and sitting in WAITING on it made the cab pick the
        // next destination and then never depart for it.
        setState(CabState.REQUESTING_ROUTE);
        retryTimer = 0;
    }

    /** The persisted resume index stored on the item, read directly, or -1 when there is none. */
    private static int savedProgress(ItemStack stack) {
        try {
            CompoundTag tag = stack.get(AllDataComponents.TRAIN_SCHEDULE);
            return tag == null ? -1 : tag.getInt("Progress");
        } catch (Throwable throwable) {
            return -1;
        }
    }

    /**
     * Content-based identity of the installed schedule.
     *
     * <p>This deliberately hashes the serialized schedule rather than using the identity of the component
     * instance. An instance hash is not a stable notion here: the component object is replaced whenever
     * the schedule is written back, and it differs between a stack and any copy of it - so "the schedule
     * changed" fired constantly, and a finished schedule was mistaken for a freshly installed one and
     * started over instead of stopping.
     */
    private static int scheduleIdentity(ItemStack stack, HolderLookup.Provider registries) {
        try {
            CompoundTag tag = stack.get(AllDataComponents.TRAIN_SCHEDULE);
            return tag == null ? 0 : tag.hashCode();
        } catch (Throwable throwable) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ control helpers

    private void hold(VehicleHandle vehicle) {
        // Ramps rather than slams: the commanded values carry over between cycles, so braking to a
        // stop takes a few control cycles instead of one jolt.
        commandedDrive = slew(commandedDrive, 0.0D);
        commandedBrake = slew(commandedBrake, clamp01(RDConfig.brakeGain()));
        commandedSteer = slew(commandedSteer, 0.0D);
        vehicle.command(worldPosition, commandedDrive, commandedBrake, commandedSteer);
    }

    private void applyControl(VehicleHandle vehicle, double targetSpeed, double steer) {
        // Smooth the measurement before controlling on it. Sable's linear velocity is sampled from the
        // physics sub-steps, so its magnitude carries a good deal of jitter; a controller that chased
        // that jitter would command full drive and full brake alternately and produce exactly the
        // 2..24 m/s oscillation the driver saw.
        double raw = vehicle.speed() * 20.0D;
        filteredSpeed = filteredSpeed < 0.0D ? raw : filteredSpeed + (raw - filteredSpeed) * 0.35D;
        speed = filteredSpeed;

        double error = targetSpeed - filteredSpeed;

        // Real elapsed time, so the integral and derivative terms mean the same thing whatever the control period
        // is - and so a hiccup in the tick rate cannot silently change the tuning.
        long now = level == null ? 0L : level.getGameTime();
        double dt = lastPidTick == 0L
                ? 0.25D
                : Math.max(0.05D, Math.min(1.0D, (now - lastPidTick) / 20.0D));
        lastPidTick = now;

        speedIntegral += error * dt;
        // Anti-windup. Held at a red signal, or too heavily loaded to reach the target at all, the integral would
        // otherwise keep growing for as long as the error lasts and then spend it in one violent burst.
        speedIntegral = Math.max(-PID_INTEGRAL_LIMIT, Math.min(PID_INTEGRAL_LIMIT, speedIntegral));
        double derivative = (error - lastSpeedError) / dt;
        lastSpeedError = error;

        // Feed-forward first; the PID only trims.
        //
        // The actuator is a directional gearbox driven by redstone strength, so the signal sets a SPEED rather than
        // a force: at full signal this vehicle settles at fullSignalSpeed. The main term is therefore a plain ratio
        // - target speed over full-signal speed - and that is exactly why force-type gains were wrong here. With
        // them the output saturated as soon as the error was large, and a saturated signal means full speed, so a
        // vehicle standing still was commanded straight to full throttle and shot past its target. Measured in game:
        // speed 0.00 with the command at 0.93, then 10.25 a moment later.
        double feedForward = targetSpeed / Math.max(0.5D, fullSignalSpeed);
        double trim = PID_KP * error + PID_KI * speedIntegral + PID_KD * derivative;
        double output = feedForward + trim;
        // Positive output is traction, negative is braking; never both, so the two can never fight.
        double gain = output >= 0.0D ? RDConfig.driveGain() : RDConfig.brakeGain();
        double magnitude = clamp01(Math.abs(output) * gain);
        // Traction builds slowly and is cut instantly; the brake is released just as instantly, so that setting off
        // never means pulling against brakes that are still letting go. Applying the brake may be smoothed: that
        // direction is the safe one to be slow about.
        // Traction and braking are both now published as throttle, through opposite sides of the gearbox, so no
        // signal is ever held open to keep a drivetrain from running away - the arrangement itself guarantees that
        // one side is always driven.
        double wantedDrive = output > 0.0D ? magnitude : 0.0D;
        commandedDrive = wantedDrive > commandedDrive
                ? Math.min(wantedDrive, commandedDrive + TRACTION_RISE_PER_SECOND * dt)
                : wantedDrive;
        commandedBrake = output < 0.0D ? slew(commandedBrake, magnitude) : 0.0D;
        commandedSteer = slew(commandedSteer, Math.max(-1.0D, Math.min(1.0D, steer)));
        vehicle.command(worldPosition, commandedDrive, commandedBrake, commandedSteer);
    }

    /**
     * Whether this cab is the one that should be commanding the vehicle.
     *
     * <p>The rule is the driver's: of the cabs on this vehicle, the one facing the destination is the one in
     * control. Two cabs on one vehicle exist so it can run in either direction, and only one of them may
     * ever command it - two controllers would otherwise work against each other.
     *
     * <p>Siblings are found through the vehicle's own actor list, never by position: a block inside an
     * assembled structure lives in a plot millions of blocks from the origin, so a positional lookup finds
     * nothing. And a cab's facing is a direction in the structure's own frame, so it is rotated into world
     * space before being compared with a world direction.
     *
     * <p>Anything unknown returns true - this cab drives. Silence is the one outcome that must not happen:
     * a cab that declines to drive because it could not read an orientation leaves the vehicle going nowhere
     * with no explanation.
     */
    private boolean isControlCab(ServerLevel server, VehicleHandle vehicle, Optional<Vec3> target) {
        if (!target.isPresent() || !getBlockState().hasProperty(com.raildispatch.block.PhysicsCabBlock.FACING)) {
            return true;
        }
        Vec3 towards = target.get().subtract(vehicle.position());
        Vec3 flat = new Vec3(towards.x, 0.0D, towards.z);
        if (flat.lengthSqr() < 1.0E-6D) {
            return true;
        }
        flat = flat.normalize();

        Vec3 mine = worldFacing(vehicle, getBlockState().getValue(com.raildispatch.block.PhysicsCabBlock.FACING));
        if (mine == null) {
            return true;
        }
        double myDot = mine.x * flat.x + mine.z * flat.z;

        for (PhysicsCabBlockEntity sibling : siblings(server)) {
            if (!sibling.getBlockState().hasProperty(com.raildispatch.block.PhysicsCabBlock.FACING)) {
                continue;
            }
            // The sibling may be on another sub-level, with its own orientation; rotate its facing with
            // its own handle, not with this cab's.
            Vec3 theirs = RailServices.physics().vehicleOf(server, sibling.getBlockPos())
                    .map(handle -> worldFacing(handle,
                            sibling.getBlockState().getValue(com.raildispatch.block.PhysicsCabBlock.FACING)))
                    .orElse(null);
            if (theirs == null) {
                continue;
            }
            double theirDot = theirs.x * flat.x + theirs.z * flat.z;
            if (theirDot > myDot) {
                if (server.getGameTime() % 100L == 0L) {
                    RailDispatch.debug("cab {}: standing by, the cab at {} faces the destination more "
                            + "directly ({} vs {})", worldPosition, sibling.getBlockPos(),
                            String.format(java.util.Locale.ROOT, "%.2f", theirDot),
                            String.format(java.util.Locale.ROOT, "%.2f", myDot));
                }
                return false;
            }
        }
        return true;
    }

    /** A cab's own facing, rotated out of the structure's frame into the world's. */
    private static Vec3 worldFacing(VehicleHandle vehicle, net.minecraft.core.Direction facing) {
        Vec3 local = new Vec3(facing.getStepX(), 0.0D, facing.getStepZ());
        if (local.lengthSqr() < 1.0E-6D) {
            return null;
        }
        return vehicle.localToWorld(local.normalize());
    }

    /**
     * Distance in the horizontal plane only. Height is deliberately ignored: a destination is a place on
     * the railway, and a station block or a berth stands beside the track rather than at the vehicle's
     * own height.
     */
    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Limits how fast a command may change, so a level that is quantised to 0..15 stays smooth. */
    private static double slew(double from, double to) {
        double maxStep = 0.25D;
        return to > from ? Math.min(to, from + maxStep) : Math.max(to, from - maxStep);
    }

    /**
     * True once the vehicle has been held at the same red signal long enough, with the gap no longer
     * closing, for the red to be attributable to the vehicle's own occupancy of that block.
     */
    private boolean deadlockedAtSignal(double distance) {
        if (RDConfig.creepAfterTicks() <= 0) {
            return false;
        }
        // A red that is somebody else's train is absolute. Creeping past it would be running a red at a
        // vehicle, which is the one thing a signalling system exists to prevent.
        if (signalOccupiedByTrain) {
            heldTicks = 0;
            lastHeldDistance = distance;
            return false;
        }
        if (lastHeldDistance >= 0.0D && distance < lastHeldDistance - 0.1D) {
            heldTicks = 0;
        } else {
            heldTicks++;
        }
        lastHeldDistance = distance;
        return heldTicks > RDConfig.creepAfterTicks();
    }

    private void resetHoldTimer() {
        heldTicks = 0;
        lastHeldDistance = -1.0D;
        loggedCreep = false;
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    /** Highest speed from which a vehicle can still stop within {@code distance}, in blocks/second. */
    private double speedForStop(double distance) {
        return speedForStopAt(distance, RDConfig.signalBrakeMargin());
    }

    /**
     * The same curve for a destination the vehicle is meant to reach: the safety margin is a signal
     * concept, and applying it here would bring the vehicle to a halt short of its target.
     */
    private double speedForArrival(double distance) {
        // Aim at the stopping point itself, not at the arrival radius.
        //
        // The radius is a tolerance for REPORTING that the vehicle has arrived, not a statement about where it
        // should stop. Aiming the curve at the radius meant the vehicle was permitted to be still moving at that
        // range and then halt anywhere inside it, so the stopping position was wrong by up to a full radius - the
        // "error is too large" that prompted this. It is safe to aim at the mark now because the bogies' own brake
        // and the reversed drivetrain act together: the curve used to have to stop the vehicle on its own.
        return speedForStopAt(distance, 0.5D);
    }

    private double speedForStopAt(double distance, double margin) {
        double usable = Math.max(0.0D, distance - margin);
        return Math.sqrt(2.0D * BRAKE_DECELERATION * usable);
    }

    /** Proportional steering towards a world point, -1..1. */
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
        return Math.max(-1.0D, Math.min(1.0D, cross * 2.0D)) * RDConfig.steerGain();
    }

    // ------------------------------------------------------------------ destination resolution

    /**
     * Where this entry wants the vehicle to end up, in world coordinates. Tag addressing wins over
     * station addressing, which is what makes "couple that vehicle" and "run to that vehicle" work
     * on a railway that has no station at the target.
     */
    private Optional<Vec3> destinationPosition(ServerLevel server, VehicleHandle vehicle,
                                               Optional<ScheduleView> view) {
        if (view.isEmpty()) {
            return Optional.empty();
        }
        ScheduleView schedule = view.get();
        RailTags tags = RailTags.get(server);

        Optional<String> coupleTag = schedule.coupleTag();
        if (coupleTag.isPresent()) {
            destination = "vehicle:" + coupleTag.get();
            return tags.vehicleFor(coupleTag.get())
                    .flatMap(id -> RailServices.physics().vehicleById(server, id))
                    .map(VehicleHandle::position);
        }

        Optional<String> address = schedule.shuntAddress();
        if (address.isPresent()) {
            destination = "address:" + address.get();
            return nearestPlate(server, tags.platesFor(address.get()), vehicle.position());
        }

        Optional<String> station = schedule.destination();
        if (station.isPresent()) {
            destination = station.get();
            Optional<Vec3> resolved = RailServices.track().resolveDestination(server, station.get());
            if (resolved.isEmpty()) {
                // Surface the miss instead of silently driving nowhere: the GUI and the dispatch
                // detail line are the only feedback a player gets while the vehicle is running.
                routeDetail = "destination not found: " + station.get();
            }
            return resolved;
        }

        if (!vehicleTag.isEmpty()) {
            destination = "vehicle:" + vehicleTag;
            return tags.vehicleFor(vehicleTag)
                    .flatMap(id -> RailServices.physics().vehicleById(server, id))
                    .map(VehicleHandle::position);
        }
        destination = "";
        return Optional.empty();
    }

    private Optional<Vec3> nearestPlate(ServerLevel server, List<GlobalPos> plates, Vec3 from) {
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        for (GlobalPos plate : plates) {
            if (!plate.dimension().equals(server.dimension())) {
                continue;
            }
            Vec3 candidate = Vec3.atCenterOf(plate.pos());
            double distance = candidate.distanceTo(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    // ------------------------------------------------------------------ plumbing

    private void setState(CabState next) {
        if (state != next) {
            state = next;
            setChanged();
            sync();
        }
    }

    private void sync() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    public void dropContents() {
        if (level != null) {
            ItemStack stack = inventory.getStackInSlot(SLOT_SCHEDULE);
            if (!stack.isEmpty()) {
                Block.popResource(level, worldPosition, stack);
                inventory.setStackInSlot(SLOT_SCHEDULE, ItemStack.EMPTY);
            }
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel server) {
            CabRegistry.releaseAt(server, worldPosition);
            if (vehicleId != null) {
                RailServices.track().releaseRoute(server, vehicleId);
                Optional<VehicleHandle> vehicle = RailServices.physics().vehicleById(server, vehicleId);
                vehicle.ifPresent(handle -> handle.releaseControl(worldPosition));
            }
        }
        super.setRemoved();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("raildispatch.gui.physics_cab");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new PhysicsCabMenu(containerId, playerInventory, this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", inventory.serializeNBT(registries));
        tag.putString("VehicleTag", vehicleTag);
        tag.putString("State", state.name());
        tag.putInt("WaitTicks", waitTicks);
        tag.putLong("WaitUntil", waitUntilTick);
        // Persisted because it is learned by observation and only needs observing once. Keeping it in memory only
        // meant every reload forgot which way the throttle drives the vehicle, and the first leg after a reload
        // was decided blind.
        if (forwardHeading != null) {
            tag.putDouble("HeadingX", forwardHeading.x);
            tag.putDouble("HeadingY", forwardHeading.y);
            tag.putDouble("HeadingZ", forwardHeading.z);
        }
        if (vehicleId != null) {
            tag.putUUID("Vehicle", vehicleId);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) {
            inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
        }
        vehicleTag = tag.getString("VehicleTag");
        waitTicks = tag.getInt("WaitTicks");
        waitUntilTick = tag.getLong("WaitUntil");
        Vec3 savedHeading = new Vec3(tag.getDouble("HeadingX"), tag.getDouble("HeadingY"),
                tag.getDouble("HeadingZ"));
        // A zero-length vector is how "never learned" is stored, since NBT has no null.
        forwardHeading = savedHeading.lengthSqr() > 1.0E-6D ? savedHeading : null;
        if (tag.hasUUID("Vehicle")) {
            vehicleId = tag.getUUID("Vehicle");
        }
        try {
            state = CabState.valueOf(tag.getString("State"));
        } catch (IllegalArgumentException ignored) {
            state = CabState.IDLE;
        }
        // Never resume mid-state. The state is derived from the schedule and the vehicle, so restoring
        // it put the cab straight into RUNNING on load - where it drove for ever without ever entering
        // REQUESTING_ROUTE, which is why no route was ever requested and the whole dispatch layer,
        // switches included, appeared dead.
        state = CabState.IDLE;
        routeGranted = false;
        retryTimer = 0;
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
