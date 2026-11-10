package com.raildispatch.physics;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyAxle;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyProbeData;
import com.raildispatch.api.TrackProbe;
import com.raildispatch.api.VehicleHandle;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackGraph;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * One assembled physics structure.
 *
 * <p>The bogies are held as <b>object references</b>, resolved once when the handle is built from the
 * structure's own actor list. They are deliberately never addressed by position: an assembled
 * structure lives in a plot far from the origin, so a world-space lookup for its blocks finds nothing
 * at all - which was a real bug in the first version of this class.
 *
 * <p>Commands take one of two routes, and only ever one of them:
 *
 * <ul>
 *   <li><b>With bogies</b>: the command goes to the bogies through Simurail's remote override API,
 *       keyed by the controlling block's position. That is the mechanism the mod provides for a block
 *       elsewhere driving a bogie, and unlike the computer override fields it is not wiped every
 *       tick.</li>
 *   <li><b>Without bogies</b>: the command is turned into a mass-scaled impulse on the rigid body, so
 *       that a vehicle with no Simurail bogie in it still moves rather than ignoring every command.
 *       Impulse magnitude is {@code mass * acceleration * dt}, with {@code dt} cached per game tick
 *       because a cab commands every 5 ticks and a reverser every tick - without the cache the second
 *       and third call in one tick would see a zero interval and apply a token impulse.</li>
 * </ul>
 */
final class SableVehicle implements VehicleHandle {

    /** Along-heading acceleration at full drive, used only on the no-bogie fallback path. */
    private static final double MAX_ACCELERATION = 2.0D;
    /**
     * Full-brake deceleration in blocks per second squared. This is the number the cab's braking curve
     * is written against, and the impulse is capped at the momentum actually available, so a full
     * brake slows the vehicle to a stop and never past it.
     */
    private static final double MAX_BRAKE = 3.0D;
    /** Yaw acceleration at full steer, used only on the no-bogie fallback path. */
    private static final double MAX_YAW_ACCELERATION = 0.8D;
    /**
     * Steering uses a scalar inertia estimate instead of the real tensor. The tensor's axis convention
     * could not be verified offline, and a wrong axis would spin the vehicle in a nonsensical
     * direction; a mass-proportional yaw authority is predictable and tunable.
     */
    private static final double STEER_INERTIA_FACTOR = 4.0D;

    private static final double MIN_DT = 0.05D;
    private static final double MAX_DT = 0.5D;
    /**
     * Ceiling for a believable travel sample, in blocks per tick. Far above anything a vehicle can do
     * under its own power, so it only ever rejects pose corrections and teleports.
     */
    private static final double MAX_PLAUSIBLE_SPEED = 3.0D;
    /**
     * Below this speed (blocks per TICK, so 0.2 blocks/s) there is no trustworthy direction and braking
     * is skipped. This used to be 0.15, which is 3 blocks/s: a shunting wagon rolling at 1 block/s was
     * treated as standing still, so the retarder's brake was accepted and then silently dropped.
     */
    private static final double MIN_BRAKE_SPEED = 0.01D;

    private final Level level;
    private final ServerSubLevel subLevel;
    private final List<PhysicsBogeyBlockEntity> bogeys;

    private long lastCommandTick;
    private double lastDt = MIN_DT;
    private final Vector3d rememberedForward = new Vector3d(0.0D, 0.0D, 1.0D);
    /** Displacement-based travel measurement; see {@link #speed()}. */
    private long lastSampleTick = -1L;
    private Vec3 lastSamplePosition = Vec3.ZERO;
    private double travelSpeed;
    private Vec3 travelDirection;
    /**
     * The last direction the vehicle was actually seen travelling, <b>kept after it stops</b>.
     *
     * <p>Deliberately separate from {@link #travelDirection}, which is cleared when the vehicle stops because the
     * brake uses it to choose which way to push and a stale direction there would mean braking a stationary
     * vehicle. This one answers a different question - which way is this vehicle pointing - and that does not
     * stop being true because it has come to rest. A direction taken from real displacement is also the only
     * heading whose sign is unambiguous: an axis drawn between two bogeys has no defined front, so half of its
     * answers would have come out backwards.
     */
    private Vec3 lastTravelledDirection;

    SableVehicle(Level level, ServerSubLevel subLevel, List<PhysicsBogeyBlockEntity> bogeys) {
        this.level = level;
        this.subLevel = subLevel;
        this.bogeys = List.copyOf(bogeys);
    }

    ServerSubLevel subLevel() {
        return subLevel;
    }

    @Override
    public UUID id() {
        return subLevel.getUniqueId();
    }

    @Override
    public String name() {
        String name = subLevel.getName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return "vehicle-" + id().toString().substring(0, 8);
    }

    @Override
    public Vec3 position() {
        Vector3dc position = subLevel.logicalPose().position();
        return new Vec3(position.x(), position.y(), position.z());
    }

    @Override
    public boolean isDerailed() {
        // A resolved sub-level with no bogey has no axle on the rails. It cannot drive, and the car carrying
        // the bogey may be derailed while this one is not - which is exactly how a console mounted on a
        // trailing car kept planning routes while the train was lying off the track. Treat it as derailed:
        // hold, and tell the player, instead of searching from a body that cannot move.
        if (bogeys.isEmpty()) {
            return true;
        }
        for (PhysicsBogeyBlockEntity bogey : bogeys) {
            try {
                if (bogey.isDerailed()) {
                    return true;
                }
            } catch (Throwable throwable) {
                // Unreadable is not derailed: a false alarm would stop a healthy vehicle for no reason.
            }
        }
        return false;
    }

    @Override
    public TravellingPoint leadingTrackPoint() {
        PhysicsBogeyAxle axle = leadingAxle();
        if (axle == null) {
            return null;
        }
        try {
            return axle.getTrackPoint();
        } catch (Throwable throwable) {
            return null;
        }
    }

    @Override
    public TrackGraph trackGraph() {
        PhysicsBogeyAxle axle = leadingAxle();
        if (axle == null) {
            return null;
        }
        try {
            return axle.getTrackGraph();
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * The axle at the front of the vehicle, or failing that the first axle that still holds track.
     *
     * <p>Simurail gives each bogey two axles and each axle its own Create travelling point; the leading
     * axle is the one that knows where the steel is in the direction the vehicle is pointing.
     */
    private PhysicsBogeyAxle leadingAxle() {
        PhysicsBogeyAxle fallback = null;
        for (PhysicsBogeyBlockEntity bogey : bogeys) {
            try {
                for (boolean front : new boolean[]{true, false}) {
                    PhysicsBogeyAxle axle = bogey.getAxle(front);
                    if (axle == null || !axle.hasTrack()) {
                        continue;
                    }
                    TravellingPoint point = axle.getTrackPoint();
                    if (point == null || point.edge == null) {
                        continue;
                    }
                    if (axle.isCurrentFront()) {
                        return axle;
                    }
                    if (fallback == null) {
                        fallback = axle;
                    }
                }
            } catch (Throwable throwable) {
                // An axle that cannot be read is not a reason to stop controlling the vehicle.
            }
        }
        return fallback;
    }

    @Override
    public List<UUID> connectedVehicleIds() {
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
        for (PhysicsBogeyBlockEntity bogey : bogeys) {
            try {
                for (dev.ryanhcode.sable.sublevel.SubLevel other : bogey.sable$getConnectionDependencies()) {
                    if (other != null && other != subLevel) {
                        ids.add(other.getUniqueId());
                    }
                }
            } catch (Throwable throwable) {
                // An unreadable coupling is not a reason to stop controlling this car.
            }
        }
        // Cars are coupled through Simurail automatic couplers, not only through the bogies. Without
        // these the "train" seen by YardState/nameplate sync would stop at the first car, and a coupled
        // wagon next to an active locomotive would look free-rolling.
        try {
            var plot = subLevel.getPlot();
            if (plot != null) {
                for (Object actor : plot.getBlockEntityActors()) {
                    if (actor == null || !actor.getClass().getName().contains("AutomaticCoupler")) {
                        continue;
                    }
                    try {
                        java.lang.reflect.Method dependencies =
                                actor.getClass().getMethod("sable$getConnectionDependencies");
                        Object value = dependencies.invoke(actor);
                        if (value instanceof Iterable<?> iterable) {
                            for (Object next : iterable) {
                                if (next instanceof dev.ryanhcode.sable.sublevel.SubLevel other
                                        && other != subLevel) {
                                    ids.add(other.getUniqueId());
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // A coupler implementation without the Sable dependency hook is not fatal.
                    }
                }
            }
        } catch (Throwable throwable) {
            // A sub-level without a plot during assembly simply has no known couplings yet.
        }
        return List.copyOf(ids);
    }

    @Override
    public List<String> nameplates() {
        List<String> names = new ArrayList<>();
        try {
            var plot = subLevel.getPlot();
            if (plot == null) {
                return names;
            }
            for (var holder : plot.getLoadedChunks()) {
                scanNameplates(holder.getChunk(), names);
            }
            // Fallback over the plot's own chunk bounds. Nameplates are ordinary block entities, not
            // Sable actors, and a plot can have a loaded chunk that is not in getLoadedChunks() after a
            // save/reload. Reading the chunks directly is what makes a nameplate survive that.
            var min = plot.getChunkMin();
            var max = plot.getChunkMax();
            for (int x = min.x; x <= max.x; x++) {
                for (int z = min.z; z <= max.z; z++) {
                    scanNameplates(plot.getChunk(new net.minecraft.world.level.ChunkPos(x, z)), names);
                }
            }
        } catch (Throwable throwable) {
            // An unloaded or malformed plot has no readable nameplates.
        }
        return names;
    }

    @Override
    public List<com.raildispatch.api.TerminalInfo> terminals() {
        List<com.raildispatch.api.TerminalInfo> terminals = new ArrayList<>();
        try {
            var plot = subLevel.getPlot();
            if (plot == null) {
                return terminals;
            }
            for (var holder : plot.getLoadedChunks()) {
                scanTerminals(holder.getChunk(), terminals);
            }
            var min = plot.getChunkMin();
            var max = plot.getChunkMax();
            for (int x = min.x; x <= max.x; x++) {
                for (int z = min.z; z <= max.z; z++) {
                    scanTerminals(plot.getChunk(new net.minecraft.world.level.ChunkPos(x, z)), terminals);
                }
            }
        } catch (Throwable throwable) {
            // An unloaded or malformed plot has no readable terminals.
        }
        return terminals;
    }

    @Override
    public List<com.raildispatch.api.CouplerInfo> couplers() {
        List<com.raildispatch.api.CouplerInfo> couplers = new ArrayList<>();
        try {
            var plot = subLevel.getPlot();
            if (plot == null) {
                return couplers;
            }
            for (var holder : plot.getLoadedChunks()) {
                scanCouplers(holder.getChunk(), couplers);
            }
            var min = plot.getChunkMin();
            var max = plot.getChunkMax();
            for (int x = min.x; x <= max.x; x++) {
                for (int z = min.z; z <= max.z; z++) {
                    scanCouplers(plot.getChunk(new net.minecraft.world.level.ChunkPos(x, z)), couplers);
                }
            }
        } catch (Throwable throwable) {
            // An unloaded or malformed plot has no readable couplers.
        }
        return couplers;
    }

    private void scanCouplers(net.minecraft.world.level.chunk.LevelChunk chunk,
                              List<com.raildispatch.api.CouplerInfo> couplers) {
        if (chunk == null) {
            return;
        }
        for (net.minecraft.world.level.block.entity.BlockEntity blockEntity
                : chunk.getBlockEntities().values()) {
            if (!(blockEntity instanceof
                    com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity coupler)) {
                continue;
            }
            try {
                java.lang.reflect.Field field = coupler.getClass().getDeclaredField("globalEndPos");
                field.setAccessible(true);
                Object value = field.get(coupler);
                if (!(value instanceof org.joml.Vector3d vector)) {
                    continue;
                }
                Vec3 end = new Vec3(vector.x, vector.y, vector.z);
                // globalEndPos is in plot space. Transform it out of the plot before using it for
                // distances or logging: the plot origin is around x=20,481,032, so raw plot coordinates
                // are meaningless to a player standing at x=4.
                // The live log proves globalEndPos is already in world space: it reported
                // 0.50/64.50/106.49, right beside the target route end. Applying logicalPose to it
                // produced -20,481,036, which is the plot-space value fed back through the transform a
                // second time. Use it as-is.
                Vec3 worldEnd = end;
                // The valid approach direction is outward from this car's centre to the coupler jaw.
                Vec3 radial = worldEnd.subtract(position());
                Vec3 outward = new Vec3(radial.x, 0.0D, radial.z);
                outward = outward.lengthSqr() < 1.0E-4D ? Vec3.ZERO : outward.normalize();
                couplers.add(new com.raildispatch.api.CouplerInfo(coupler.getBlockPos(), end, worldEnd,
                        outward, coupler.hasCouplerPartner()));
            } catch (Throwable ignored) {
                // A coupler without a readable joint is not a coupling target.
            }
        }
    }

    private static void scanTerminals(net.minecraft.world.level.chunk.LevelChunk chunk,
                                      List<com.raildispatch.api.TerminalInfo> terminals) {
        if (chunk == null) {
            return;
        }
        for (net.minecraft.world.level.block.entity.BlockEntity blockEntity
                : chunk.getBlockEntities().values()) {
            if (blockEntity instanceof com.raildispatch.block.entity.YardTerminalBlockEntity terminal) {
                com.raildispatch.api.TerminalInfo info = new com.raildispatch.api.TerminalInfo(
                        terminal.destination(), terminal.consist(), terminal.service(), "");
                if (!terminals.contains(info)) {
                    terminals.add(info);
                }
            }
        }
    }

    private static void scanNameplates(net.minecraft.world.level.chunk.LevelChunk chunk, List<String> names) {
        if (chunk == null) {
            return;
        }
        for (net.minecraft.world.level.block.entity.BlockEntity blockEntity
                : chunk.getBlockEntities().values()) {
            if (!blockEntity.getClass().getName().equals(
                    "dev.simulated_team.simulated.content.blocks.nameplate.NameplateBlockEntity")) {
                continue;
            }
            try {
                java.lang.reflect.Method getName = blockEntity.getClass().getMethod("getName");
                Object value = getName.invoke(blockEntity);
                if (value instanceof String text && !text.isBlank() && !names.contains(text.trim())) {
                    names.add(text.trim());
                }
            } catch (Throwable ignored) {
                // A malformed nameplate is simply not a destination.
            }
        }
    }

    @Override
    public TrackProbe probeAhead() {
        for (PhysicsBogeyBlockEntity bogey : bogeys) {
            try {
                // getAxle(true) selects the leading axle. The parameter is not named in the bytecode, so if
                // the probe ever reports nonsense - a signal behind the vehicle, say - this is the first
                // thing to try flipping.
                PhysicsBogeyProbeData data = bogey.getAxle(true).getProbeData();
                if (data != null) {
                    return new TrackProbe(data.getSignalDistance(), data.isSignalAligned(),
                            data.getSignalNames(), data.getStationDistance(), data.getStationName(),
                            data.getDiscontinuityDistance());
                }
            } catch (Throwable throwable) {
                // A probe that cannot be read is not a reason to stop controlling the vehicle.
            }
        }
        return null;
    }

    @Override
    public <T extends BlockEntity> List<T> actorsOfType(Class<T> type) {
        List<T> found = new ArrayList<>();
        try {
            LevelPlot plot = subLevel.getPlot();
            if (plot == null) {
                return found;
            }
            for (BlockEntitySubLevelActor actor : plot.getBlockEntityActors()) {
                if (type.isInstance(actor)) {
                    found.add(type.cast(actor));
                }
            }
        } catch (Throwable throwable) {
            // An unreadable actor list must not break the control loop. An empty result means "no siblings",
            // and the caller then treats this cab as the one in charge.
        }
        return found;
    }

    @Override
    public Vec3 localToWorld(Vec3 local) {
        try {
            Quaterniondc orientation = subLevel.logicalPose().orientation();
            Vector3d rotated = orientation.transform(new Vector3d(local.x, local.y, local.z));
            return new Vec3(rotated.x, rotated.y, rotated.z);
        } catch (Throwable throwable) {
            return null;
        }
    }

    @Override
    public Vec3 velocity() {
        // The raw physics velocity, in blocks per tick, including every vibration of the rigid body.
        Vector3d velocity = subLevel.latestLinearVelocity;
        return new Vec3(velocity.x, velocity.y, velocity.z);
    }

    /**
     * Travel speed in blocks per tick, measured from <b>displacement</b> rather than from the physics
     * velocity.
     *
     * <p>This is the single most important measurement in the mod. Sable's linear velocity of a
     * structure resting on rails is dominated by its own jitter: telemetry from a vehicle creeping
     * forward at roughly 0.17 blocks per second reported instantaneous speeds up to 73 blocks per
     * second, changing wildly every sample. Controlling on that number, and worse, braking against it,
     * fed the oscillation instead of damping it - the brake pushed hardest exactly when the vibration
     * was fastest.
     *
     * <p>Displacement over time is immune to that by construction: vibration averages out because the
     * structure ends up where it started within each wobble.
     */
    @Override
    public double speed() {
        long now = level.getGameTime();
        Vec3 position = position();
        if (lastSampleTick < 0L) {
            lastSampleTick = now;
            lastSamplePosition = position;
            return 0.0D;
        }
        long elapsed = now - lastSampleTick;
        if (elapsed <= 0L) {
            return travelSpeed;
        }
        Vec3 delta = position.subtract(lastSamplePosition);
        double measured = delta.length() / elapsed;
        lastSampleTick = now;
        lastSamplePosition = position;
        // Discard implausible samples instead of filtering them. The physics engine corrects a structure's
        // pose in jumps, and one jump registers as a single-tick displacement far beyond anything the
        // vehicle could actually do - a spike of 19 blocks per second against a 12 blocks per second
        // ceiling, which then commanded a hard brake at a speed the vehicle never had.
        if (measured > MAX_PLAUSIBLE_SPEED) {
            return travelSpeed;
        }
        // A light filter on top: displacement per tick is quantised by the physics step, and a raw
        // quantised signal would still make the controller twitchy at very low speeds.
        travelSpeed = travelSpeed + (measured - travelSpeed) * 0.4D;
        if (travelSpeed < 1.0E-4D) {
            travelSpeed = 0.0D;
            travelDirection = null;
        } else if (delta.lengthSqr() > 1.0E-8D) {
            // Horizontal only, and only above the jitter floor.
            //
            // At a near standstill a structure's displacement is dominated by physics jitter: the trace showed the
            // travel direction flipping between (0,0,1), (0,0.20,0.98) and (0,0.42,-0.91) at a reported speed of
            // 0.02, vertical components and all, which a rail vehicle cannot have. Braking against that is the
            // damper-with-the-wrong-sign again - each impulse a kick in a random direction - and it was what set
            // the vehicle moving when the schedule was taken out and the cab fell back to braking.
            Vec3 horizontal = new Vec3(delta.x, 0.0D, delta.z);
            if (measured > 0.15D && horizontal.lengthSqr() > 1.0E-6D) {
                travelDirection = horizontal.normalize();
                lastTravelledDirection = travelDirection;
            } else {
                // Too slow to tell which way it is going, so say so rather than inventing a direction.
                travelDirection = null;
            }
        }
        return travelSpeed;
    }

    /** The direction the vehicle is actually travelling in, or {@code null} while it is not moving. */
    Vec3 travelDirection() {
        return travelDirection;
    }

    /** The last direction this vehicle was seen travelling; survives coming to a stop. */
    @Override
    public Vec3 lastTravelledDirection() {
        return lastTravelledDirection;
    }

    @Override
    public int bogeyCount() {
        return bogeys.size();
    }

    @Override
    public List<BlockPos> bogeyPositions() {
        List<BlockPos> positions = new ArrayList<>(bogeys.size());
        for (PhysicsBogeyBlockEntity bogey : bogeys) {
            positions.add(bogey.getBlockPos());
        }
        return positions;
    }

    /**
     * Traction and steering are delegated to the bogies; <b>braking is ours</b>.
     *
     * <p>Simurail's own brake brings the vehicle to a standstill in roughly one tick, which makes it
     * unusable as a controllable deceleration: every small speed correction became an emergency stop,
     * and the vehicle lurched. Braking is therefore modelled here as a capped deceleration impulse on
     * the rigid body - it can neither slam the vehicle to a halt nor push it backwards - and the
     * bogies' brake channel is explicitly held at zero so nothing else brakes at the same time.
     */
    @Override
    public void command(BlockPos controller, double drive, double brake, double steer) {
        // The bogeys are not addressed at all any more.
        //
        // Attaching a remote controller to a bogie preempts it: the bogie then obeys the attached controller
        // and stops listening to its own redstone. That is exactly why the vehicle would not move with a cab
        // fitted and moved as soon as the cab was removed - the cab had a controller attached and was pinning
        // the force override at zero. Writing zero is not a safe "do nothing" here; not attaching anything is.
        //
        // So traction and steering now reach the vehicle only as wire channels, and braking is the cab's own
        // business, applied to the rigid body below.
        if (bogeys.isEmpty() && !Double.isNaN(drive)) {
            applyDriveImpulse(drive);
        }
        if (!Double.isNaN(brake)) {
            applyBrakeImpulse(brake);
        }
        // Do not touch Simurail's remote override maps at all. Any remote strength/brake write made
        // PhysicsBogeyBlockEntity.tick call getOrCreateNetwork() for a bogie with no kinetic network
        // and crash in updateStressFor. Traction and braking are the Drive By Wire channels.
        if (!bogeys.isEmpty() && drive > 0.0D && level.getGameTime() % 20L == 0L) {
            PhysicsBogeyBlockEntity bogey = bogeys.get(0);
            com.raildispatch.RailDispatch.debug("bogie diag: pos={} aboveSignal={} belowSignal={} "
                            + "aboveBlock={} drive={} brake={} control={} brakeStrength={} remoteBrake={} "
                            + "remoteStrength={} unpowered={} slip={} stress={}", bogey.getBlockPos(),
                    bogey.getLevel() == null ? -1 : bogey.getLevel().getSignal(
                            bogey.getBlockPos().above(), net.minecraft.core.Direction.UP),
                    bogey.getLevel() == null ? -1 : bogey.getLevel().getSignal(
                            bogey.getBlockPos().below(), net.minecraft.core.Direction.DOWN),
                    bogey.getLevel() == null ? "?" : bogey.getLevel().getBlockState(
                            bogey.getBlockPos().above()).getBlock().getName().getString(),
                    String.format(java.util.Locale.ROOT, "%.2f", drive),
                    String.format(java.util.Locale.ROOT, "%.2f", brake),
                    String.format(java.util.Locale.ROOT, "%.2f", bogey.getControlStrength()),
                    String.format(java.util.Locale.ROOT, "%.2f", bogey.getBrakeStrength()),
                    String.format(java.util.Locale.ROOT, "%.2f", bogey.getRemoteBrakeStrength()),
                    String.format(java.util.Locale.ROOT, "%.2f", bogey.getRemoteStrengthMultiplier()),
                    bogey.isUnpowered(),
                    String.format(java.util.Locale.ROOT, "%.2f", bogey.slipSpeed),
                    String.format(java.util.Locale.ROOT, "%.2f", bogey.getStressMultiplier()));
        }
    }

    @Override
    public void emergencyStop() {
        if (!bodyUsable()) {
            return;
        }
        try {
            // Set the body's velocity to zero by adding its negative, not by applying mass * velocity
            // as an impulse. The old impulse version read latestLinearVelocity, which can lag one tick;
            // called again on the next tick it then removed the previous momentum a second time and sent
            // the wagon flying. Reading the body every time makes a repeated call idempotent.
            Vector3d linear = new Vector3d(body().getLinearVelocity());
            Vector3d angular = new Vector3d(body().getAngularVelocity());
            if (linear.lengthSquared() < 1.0E-8D && angular.lengthSquared() < 1.0E-8D) {
                travelSpeed = 0.0D;
                travelDirection = null;
                return;
            }
            body().addLinearAndAngularVelocity(linear.negate(), angular.negate());
            travelSpeed = 0.0D;
            travelDirection = null;
            com.raildispatch.RailDispatch.debug("emergency stop: speed={} angular={}",
                    String.format(java.util.Locale.ROOT, "%.2f", linear.length() * 20.0D),
                    String.format(java.util.Locale.ROOT, "%.2f", angular.length()));
        } catch (Throwable throwable) {
            // A body removed between the validity check and the velocity change is not worth crashing over.
        }
    }

    @Override
    public void releaseControl(BlockPos controller) {
        for (PhysicsBogeyBlockEntity bogey : bogeys) {
            BogeyControl.release(bogey, controller);
        }
    }

    @Override
    public boolean hasKineticPower() {
        if (bogeys.isEmpty()) {
            return false;
        }
        for (PhysicsBogeyBlockEntity bogey : bogeys) {
            try {
                if (!bogey.hasNetwork()) {
                    return false;
                }
            } catch (Throwable throwable) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isValid() {
        return bodyUsable();
    }

    /**
     * True when the rigid body still exists in the physics engine.
     *
     * <p>Applying an impulse to a removed body does not fail quietly: Sable throws
     * {@code RuntimeException: Body has been removed}, which crashed the server. A structure can be
     * disassembled or unloaded at any moment while a cab is still holding a handle to it, so every
     * force application is gated on this.
     */
    private boolean bodyUsable() {
        try {
            return body().isValid();
        } catch (Throwable throwable) {
            return false;
        }
    }

    private void applyDriveImpulse(double strength) {
        if (strength <= 0.0D || !bodyUsable()) {
            return;
        }
        double impulse = mass() * MAX_ACCELERATION * clamp01(strength) * dtSeconds();
        if (impulse <= 0.0D) {
            return;
        }
        body().applyLinearImpulse(forward().mul(impulse));
        // Every impulse this mod applies is recorded, so that a vehicle moving on flat ground with its engine
        // stopped can be attributed to a force instead of guessed at. Three explanations were tried and each was
        // disproved - a slope, a stale channel value, an inverted sign - and none of that reasoning is worth as
        // much as the single fact of which impulse is actually being applied.
        com.raildispatch.RailDispatch.debug("impulse traction: dir=({}, {}, {}) mag={} speed={}",
                String.format(java.util.Locale.ROOT, "%.2f", forward().x),
                String.format(java.util.Locale.ROOT, "%.2f", forward().y),
                String.format(java.util.Locale.ROOT, "%.2f", forward().z),
                String.format(java.util.Locale.ROOT, "%.4f", impulse),
                String.format(java.util.Locale.ROOT, "%.2f", travelSpeed));
    }

    private void applyBrakeImpulse(double strength) {
        if (strength <= 0.0D || !bodyUsable()) {
            return;
        }
        // Brake against the direction of travel, not against the instantaneous velocity, and cap the
        // impulse by the momentum that travelling actually represents. Braking against raw velocity
        // pushed hardest at the peak of every vibration, which is a damper wired with the wrong sign:
        // it pumped energy into the oscillation instead of removing it.
        Vec3 direction = travelDirection();
        double measured = speed();
        // Nothing to brake below this speed, and no trustworthy direction to brake along.
        //
        // The fallback below exists so a genuinely moving vehicle is never left unbraked just because the
        // displacement filter has not caught up yet. At a near standstill, though, every available direction is
        // jitter - the trace showed them flipping sign and carrying vertical components at a reported 0.02 - and
        // an impulse along noise is a kick, not a brake. That is what set the vehicle moving when the schedule
        // was removed; with the power sources taken out as well, this is the only force left.
        if (measured < MIN_BRAKE_SPEED) {
            return;
        }
        if (direction == null) {
            // The travel measurement is unavailable or too small to trust. Falling back keeps the
            // brakes working: a brake that silently does nothing because it could not work out which
            // way the vehicle was going is far worse than one whose direction is momentarily noisy.
            Vector3d raw = new Vector3d(subLevel.latestLinearVelocity);
            raw.y = 0.0D;
            // The same jitter floor as the displacement measurement: below this the "direction" is noise, and
            // braking along noise kicks the vehicle rather than stopping it.
            if (raw.lengthSquared() < MIN_BRAKE_SPEED * MIN_BRAKE_SPEED) {
                return;
            }
            direction = new Vec3(raw.x, raw.y, raw.z).normalize();
            measured = Math.max(measured, raw.length());
        }
        double dt = dtSeconds();
        double magnitude = Math.min(mass() * MAX_BRAKE * clamp01(strength) * dt, mass() * measured);
        body().applyLinearImpulse(new Vector3d(direction.x, direction.y, direction.z).mul(-magnitude));
        com.raildispatch.RailDispatch.debug("impulse brake: dir=({}, {}, {}) mag={} speed={}",
                String.format(java.util.Locale.ROOT, "%.2f", direction.x),
                String.format(java.util.Locale.ROOT, "%.2f", direction.y),
                String.format(java.util.Locale.ROOT, "%.2f", direction.z),
                String.format(java.util.Locale.ROOT, "%.4f", magnitude),
                String.format(java.util.Locale.ROOT, "%.2f", measured));
    }

    private RigidBodyHandle body() {
        return RigidBodyHandle.of(subLevel);
    }

    private double mass() {
        try {
            double mass = subLevel.getMassTracker().getMass();
            return mass > 0.0D ? mass : 0.0D;
        } catch (Throwable throwable) {
            return 0.0D;
        }
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    /**
     * Seconds since the last command in an earlier game tick. Cached per tick on purpose - see the
     * class comment.
     */
    private double dtSeconds() {
        long now = level.getGameTime();
        if (now == lastCommandTick) {
            return lastDt;
        }
        lastDt = lastCommandTick == 0L
                ? MIN_DT
                : Math.max(MIN_DT, Math.min(MAX_DT, (now - lastCommandTick) / 20.0D));
        lastCommandTick = now;
        return lastDt;
    }

    /**
     * The horizontal direction the vehicle is travelling in, falling back to its own local forward
     * axis while stationary (otherwise it could never start moving). Local +Z as forward follows
     * Minecraft's convention for entity facing.
     */
    private Vector3d forward() {
        Vector3d fromVelocity = new Vector3d(subLevel.latestLinearVelocity);
        fromVelocity.y = 0.0D;
        if (fromVelocity.lengthSquared() > 1.0E-6D) {
            fromVelocity.normalize();
            rememberedForward.set(fromVelocity);
            return fromVelocity;
        }
        Quaterniondc orientation = subLevel.logicalPose().orientation();
        Vector3d fromPose = orientation.transform(new Vector3d(0.0D, 0.0D, 1.0D));
        fromPose.y = 0.0D;
        if (fromPose.lengthSquared() > 1.0E-6D) {
            fromPose.normalize();
            rememberedForward.set(fromPose);
        }
        return new Vector3d(rememberedForward);
    }
}
