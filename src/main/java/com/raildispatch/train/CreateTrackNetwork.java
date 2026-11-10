package com.raildispatch.train;

import com.raildispatch.RDConfig;
import com.raildispatch.RailDispatch;
import com.raildispatch.api.RailServices;
import com.raildispatch.api.RouteAuthority;
import com.raildispatch.api.Schedules;
import com.raildispatch.api.SignalAspect;
import com.raildispatch.api.SignalInfo;
import com.raildispatch.api.SwitchDriver;
import com.raildispatch.api.TrackNetwork;
import com.raildispatch.api.VehicleHandle;
import com.raildispatch.data.RailTags;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.signal.SignalBlockEntity;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import com.simibubi.create.content.trains.station.GlobalStation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;

/**
 * The ground layer, implemented on Create's track graph.
 *
 * <p>Three separate jobs live here:
 * <ul>
 *   <li><b>Signals</b> - read the lamp colour of the next signal ahead, from the signal boundary's own
 *       block positions, so the distance the driver brakes against is the distance to the lamp the
 *       player can see.</li>
 *   <li><b>Routing</b> - a shortest path over the graph, because Create exposes no public path finder
 *       ({@code Navigation.findPathTo} only targets stations); plus a reservation table, which is what
 *       actually stops two physics vehicles being sent into the same piece of railway.</li>
 *   <li><b>Switch driving</b> - delegated to {@link SwitchDriver}s, because Create itself has no switch
 *       block: junctions are graph nodes and a vehicle picks its branch from its own route.</li>
 * </ul>
 *
 * <p>Every entry point is defensive. A missing graph, an unloaded section or an odd track layout must
 * leave the cab stationary, never take the server down.
 */
public final class CreateTrackNetwork implements TrackNetwork {

    /** A graph must be this close to a vehicle before we treat it as "the railway it is on". */
    private static final double GRAPH_REACH = 64.0D;

    /**
     * Reserved sections per level: vehicle -> section ids. Create's own occupancy tracking only knows
     * about {@code Train}s, and a physics vehicle is not one, so the reservation table has to be ours.
     */
    private static final Map<ResourceKey<Level>, Map<UUID, Set<UUID>>> RESERVATIONS = new HashMap<>();

    /**
     * The node sequence of every granted route, so that a driver can follow it rather than steer at its
     * destination. Session state only - like the reservations, it is rebuilt from live vehicles within a
     * second of loading, and a route that outlived a restart would be a route nobody is driving.
     */
    private static final Map<ResourceKey<Level>, Map<UUID, PlannedRoute>> GRANTED = new HashMap<>();

    private boolean diagnosedNoSignal;
    private boolean warnedUnreadableSignal;
    private boolean diagnosedNoPath;

    /** Cached reflective handle to Railways' graph-point alignment; null when Railways is absent. */
    private static java.lang.reflect.Method graphSwitchAlignment;
    private static boolean graphSwitchAlignmentLookedUp;
    private static boolean warnedGraphSwitchAlignment;

    private CreateTrackNetwork() {}

    public static TrackNetwork create() {
        // The schedule reader is installed here too: both halves exist only when Create does.
        Schedules.installReader(new CreateScheduleReader());
        return new CreateTrackNetwork();
    }

    /**
     * Teaches Create's schedule screen about our two entry types.
     *
     * <p>{@code Schedule.INSTRUCTION_TYPES} is a public mutable list and appending to it is the
     * supported extension point - Create's own private {@code registerInstruction} only wraps
     * {@code List.add} - and both Railways and the railway navigator addon do exactly this. Registering
     * late (from mod construction) is deliberate: the list must be populated before any saved schedule
     * is deserialised, because resolution is a linear scan of it.
     */
    public static void registerScheduleInstructions() {
        try {
            java.util.function.Supplier<com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction>
                    couple = com.raildispatch.schedule.CoupleVehicleInstruction::new;
            com.simibubi.create.content.trains.schedule.Schedule.INSTRUCTION_TYPES.add(
                    net.createmod.catnip.data.Pair.of(
                            net.minecraft.resources.ResourceLocation.parse(
                                    "raildispatch:" + com.raildispatch.schedule.CoupleVehicleInstruction.PATH),
                            couple));

            java.util.function.Supplier<com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction>
                    shunt = com.raildispatch.schedule.ShuntByAddressInstruction::new;
            com.simibubi.create.content.trains.schedule.Schedule.INSTRUCTION_TYPES.add(
                    net.createmod.catnip.data.Pair.of(
                            net.minecraft.resources.ResourceLocation.parse(
                                    "raildispatch:" + com.raildispatch.schedule.ShuntByAddressInstruction.PATH),
                            shunt));

            java.util.function.Supplier<com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction>
                    uncouple = com.raildispatch.schedule.UncoupleInstruction::new;
            com.simibubi.create.content.trains.schedule.Schedule.INSTRUCTION_TYPES.add(
                    net.createmod.catnip.data.Pair.of(
                            net.minecraft.resources.ResourceLocation.parse(
                                    "raildispatch:" + com.raildispatch.schedule.UncoupleInstruction.PATH),
                            uncouple));

            java.util.function.Supplier<com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction>
                    coupleConsist = com.raildispatch.schedule.CoupleConsistInstruction::new;
            com.simibubi.create.content.trains.schedule.Schedule.INSTRUCTION_TYPES.add(
                    net.createmod.catnip.data.Pair.of(
                            net.minecraft.resources.ResourceLocation.parse(
                                    "raildispatch:" + com.raildispatch.schedule.CoupleConsistInstruction.PATH),
                            coupleConsist));

            java.util.function.Supplier<com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction>
                    enterShunting = com.raildispatch.schedule.EnterShuntingInstruction::new;
            com.simibubi.create.content.trains.schedule.Schedule.INSTRUCTION_TYPES.add(
                    net.createmod.catnip.data.Pair.of(
                            net.minecraft.resources.ResourceLocation.parse(
                                    "raildispatch:" + com.raildispatch.schedule.EnterShuntingInstruction.PATH),
                            enterShunting));

            java.util.function.Supplier<com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction>
                    exitShunting = com.raildispatch.schedule.ExitShuntingInstruction::new;
            com.simibubi.create.content.trains.schedule.Schedule.INSTRUCTION_TYPES.add(
                    net.createmod.catnip.data.Pair.of(
                            net.minecraft.resources.ResourceLocation.parse(
                                    "raildispatch:" + com.raildispatch.schedule.ExitShuntingInstruction.PATH),
                            exitShunting));

            java.util.function.Supplier<com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction>
                    speedLimit = com.raildispatch.schedule.SpeedLimitInstruction::new;
            com.simibubi.create.content.trains.schedule.Schedule.INSTRUCTION_TYPES.add(
                    net.createmod.catnip.data.Pair.of(
                            net.minecraft.resources.ResourceLocation.parse(
                                    "raildispatch:" + com.raildispatch.schedule.SpeedLimitInstruction.PATH),
                            speedLimit));

            RailDispatch.debug("Registered 7 schedule instruction types "
                    + "(couple_vehicle, couple_consist, shunt_by_address, uncouple, enter_shunting, "
                    + "exit_shunting, speed_limit)");
        } catch (Throwable throwable) {
            RailDispatch.LOGGER.error("Could not register the schedule instructions; the new entry types "
                    + "will not appear in Create's schedule screen", throwable);
        }
    }

    public static void registerSwitchDrivers(IEventBus modBus) {
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("railways")) {
                // Loaded reflectively-by-name only when railways is present, so the class - and every
                // railways type it references - is never resolved on a pack without that mod.
                RailServices.registerSwitchDriver(new com.raildispatch.train.railways.RailwaysSwitchDriver());
            }
        } catch (Throwable throwable) {
            RailDispatch.debug("Create: Railways switch driver unavailable", throwable);
        }
    }

    @Override
    public boolean isAvailable() {
        return Create.RAILWAYS != null;
    }

    // ------------------------------------------------------------------ signals

    @Override
    public SignalInfo signalAhead(ServerLevel level, Vec3 from, Vec3 heading, double lookahead) {
        try {
            // Walk the graph to the next boundary that actually governs us, and answer from that.
            //
            // Which signal governs is a topological question: it is the first boundary the vehicle reaches along
            // its own track, and the side that faces it is fixed by which node it enters through. Distance, "is it
            // ahead" and "does it face me" all follow from that walk instead of being reconstructed from positions,
            // which is what kept putting an opposing signal in charge of the vehicle.
            //
            // The older position-based search is left in place below as a fallback for the cases the walk cannot
            // settle - it is fed only when the walk returns nothing, so the two cannot disagree.
            SignalInfo walked = walkToNextSignal(level, from, heading, lookahead);
            if (walked != null) {
                return walked;
            }
            double best = Double.MAX_VALUE;
            SignalAspect bestAspect = null;
            Vec3 bestPos = null;
            boolean bestOccupied = false;

            for (TrackGraph graph : graphs(level)) {
                for (SignalBoundary boundary : graph.getPoints(EdgePointType.SIGNAL)) {
                    for (BlockPos pos : signalPositions(boundary)) {
                        if (!level.isLoaded(pos)) {
                            continue;
                        }
                        // Only a signal on OUR side of the boundary may govern us.
                        //
                        // One boundary can carry signals for both directions at the same place, and the search used
                        // to read every lamp on it - so the opposing signal's red light stopped a vehicle that was
                        // never addressed by it. The side is the whole point of the Couple Create stores them in.
                        if (!isOurSide(boundary, pos, from, graph)) {
                            continue;
                        }
                        // Measured to the signal's own marker on the track, not to its block.
                        Vec3 at = signalGhostPosition(graph, boundary).orElseGet(() -> Vec3.atCenterOf(pos));
                        double distance = at.distanceTo(from);
                        if (distance > lookahead || distance >= best) {
                            continue;
                        }
                        // Only a signal we are literally sitting on is skipped, and the threshold is
                        // deliberately tiny. A larger one looked safer but opened a hole: the cab stops
                        // about a braking margin short of a signal, so a threshold of a few blocks let
                        // the signal be ignored the moment the gap closed - and the vehicle drove
                        // straight through the red it had just stopped for. A deadlock caused by the
                        // vehicle occupying the signal's own block is handled by restricted-speed
                        // creeping in the cab instead, which is what a railway does.
                        if (distance < 1.5D) {
                            continue;
                        }
                        // Only signals the vehicle is travelling towards may govern it - but only when
                        // we actually know the heading. A null heading means "stopped or unknown", and
                        // filtering on a heading we do not have would discard every signal there is.
                        //
                        // The test uses the SIGNAL's own position, while the distance above uses its marker on the
                        // track. One variable served both until now, and the marker can still lie ahead after the
                        // signal itself has gone by - so a signal the vehicle had already passed stayed in the
                        // search and went on governing it.
                        Vec3 signalAt = Vec3.atCenterOf(pos);
                        if (heading != null && heading.lengthSqr() > 1.0E-6D
                                && signalAt.subtract(from).dot(heading) <= 0.0D) {
                            continue;
                        }
                        SignalAspect aspect = aspectOf(level, boundary, pos);
                        // Does anything on this boundary actually face our direction?
                        //
                        // A boundary can carry signals for BOTH directions at the same place - Create keeps them
                        // as a Couple whose two sides are the two target directions - so "is there a signal here"
                        // is the wrong question, and a rule written that way would never fire, because the
                        // opposing signal next to ours would always answer yes. The side, not the boundary, is
                        // what gets tested; that is also the selection Create's own canNavigateVia makes.
                        if (!facesOurWay(boundary, heading, graph)) {
                            if (!warnedUnreadableSignal) {
                                warnedUnreadableSignal = true;
                                RailDispatch.debug("Signal at {} carries no signal facing our direction "
                                        + "(only the opposing side does), so it must not be passed.", pos);
                            }
                            aspect = SignalAspect.DANGER;
                        }
                        if (aspect == SignalAspect.NONE) {
                            // Fail safe. A signal block exists here but its lamp cannot be read - which
                            // happens for a signal that has just been placed, because the boundary's
                            // cached state is still INVALID. Dropping it from the search is what let a
                            // vehicle run a red light; on a railway, "cannot tell" means "stop".
                            if (!warnedUnreadableSignal) {
                                warnedUnreadableSignal = true;
                                RailDispatch.debug("Signal at {} exists but its state could not be read; "
                                        + "treating it as a red light from now on.", pos);
                            }
                            aspect = SignalAspect.DANGER;
                        }
                        best = distance;
                        bestAspect = aspect;
                        bestPos = at;
                        bestOccupied = occupiedByTrain(boundary);
                    }
                }
            }
            if (bestAspect != null) {
                return new SignalInfo(bestAspect, best, bestPos, bestOccupied);
            }
            SignalInfo occupancy = occupancyAhead(level, from, heading);
            if (occupancy.aspect() != SignalAspect.NONE) {
                return occupancy;
            }
            diagnoseNoSignal(level, from, lookahead);
            return SignalInfo.NONE;
        } catch (Throwable throwable) {
            warn("reading signals", throwable);
            return SignalInfo.NONE;
        }
    }

    /**
     * One-shot report for "the cab says there is no signal".
     *
     * <p>It separates the two possibilities that look identical from outside: no track graph anywhere
     * near the vehicle (so there is nothing to obey), versus a graph whose signals are all further away
     * than the lookahead or invisible to the search.
     */
    private void diagnoseNoSignal(ServerLevel level, Vec3 from, double lookahead) {
        if (diagnosedNoSignal) {
            return;
        }
        diagnosedNoSignal = true;
        TrackGraph graph = graphNear(level, from).orElse(null);
        if (graph == null) {
            RailDispatch.debug("Signal search at {} found no track graph within {} blocks, so no signal "
                            + "can govern this vehicle. Known graphs: {}.",
                    from, (int) GRAPH_REACH, Create.RAILWAYS.trackNetworks.size());
            return;
        }
        double nearest = Double.MAX_VALUE;
        BlockPos nearestPos = null;
        int signals = 0;
        for (SignalBoundary boundary : graph.getPoints(EdgePointType.SIGNAL)) {
            for (BlockPos pos : signalPositions(boundary)) {
                signals++;
                double distance = Vec3.atCenterOf(pos).distanceTo(from);
                if (distance < nearest) {
                    nearest = distance;
                    nearestPos = pos;
                }
            }
        }
        RailDispatch.debug("Signal search at {} sees a track graph with {} signal block(s); nearest is {} "
                        + "at {} blocks, lookahead is {}. If the nearest is beyond the lookahead, raise the "
                        + "'signals.lookahead' config.",
                from, signals, nearestPos, nearest == Double.MAX_VALUE ? -1 : (int) nearest, (int) lookahead);
    }

    /**
     * The aspect of one signal block.
     *
     * <p>The signal block entity is asked first, because that is the lamp the player is looking at and
     * it is updated by the signal's own tick. The boundary's cached state is only a fallback: for a
     * freshly placed signal it is still {@code INVALID}, and relying on it alone made every new signal
     * invisible to the cab.
     */
    private static SignalAspect aspectOf(ServerLevel level, SignalBoundary boundary, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof SignalBlockEntity signal) {
            SignalAspect live = mapState(signal.getState());
            if (live != SignalAspect.NONE) {
                return live;
            }
        }
        return mapState(boundary.getStateFor(pos));
    }

    /**
     * Is either of a signal's protected sections occupied by a Create train?
     *
     * <p>This is the discriminator the driver's decision depends on. "Red with a train in the block"
     * must never be passed under any circumstances, whereas "red with an empty block" is what a
     * vehicle occupying the section itself looks like.
     */
    private static boolean occupiedByTrain(SignalBoundary boundary) {
        try {
            for (UUID groupId : new UUID[] {boundary.groups.getFirst(), boundary.groups.getSecond()}) {
                if (groupId == null) {
                    continue;
                }
                SignalEdgeGroup group = Create.RAILWAYS.signalEdgeGroups.get(groupId);
                if (group != null && !group.trains.isEmpty()) {
                    return true;
                }
            }
        } catch (Throwable throwable) {
            // Unreadable occupancy must be treated as occupied: the safe reading of "I cannot tell" is
            // "somebody is in there".
            return true;
        }
        return false;
    }

    /**
     * Whether this boundary has a signal on the side that faces the direction the vehicle is travelling.
     *
     * <p>Returns true whenever the answer cannot be established. Prohibiting passage on a guess would stop the
     * vehicle for reasons nobody could see, and a null heading means the vehicle is standing still anyway, which
     * the cab handles separately.
     */
    private static boolean facesOurWay(SignalBoundary boundary, Vec3 heading, TrackGraph graph) {
        if (heading == null || heading.lengthSqr() < 1.0E-6D || boundary.edgeLocation == null) {
            return true;
        }
        TrackNode firstNode = graph.locateNode(boundary.edgeLocation.getFirst());
        TrackNode secondNode = graph.locateNode(boundary.edgeLocation.getSecond());
        if (firstNode == null || secondNode == null) {
            return true;
        }
        Vec3 from = firstNode.getLocation().getLocation();
        Vec3 to = secondNode.getLocation().getLocation();
        if (from == null || to == null) {
            return true;
        }
        Vec3 along = to.subtract(from);
        if (along.lengthSqr() < 1.0E-6D) {
            return true;
        }
        // Travelling towards edgeLocation's second endpoint means the node being approached is the second one,
        // and isPrimary(that node) is true - so the side that governs us is Couple.get(true) == getFirst().
        boolean towardsSecond = heading.normalize().dot(along.normalize()) > 0.0D;
        java.util.Map<BlockPos, Boolean> side = towardsSecond
                ? boundary.blockEntities.getFirst()
                : boundary.blockEntities.getSecond();
        return side != null && !side.isEmpty();
    }

    /**
     * Whether a station's arrow points the same way as the route's final leg, or {@code null} if that cannot be
     * established.
     *
     * <p>The travel direction comes from the route itself - the node before the last one, to the last one - not
     * from a straight line to the destination. A straight line is what a curved approach or a loop would defeat,
     * and the route already knows the answer.
     *
     * <p>The sign follows the one measured in game: travelling against the edge's endpoint order reads false. It
     * was confirmed on stations in both directions of travel before anything was allowed to depend on it.
     */
    private static Boolean alignedAlong(GlobalStation station, TrackNode arrivalFrom, TrackNode end) {
        if (arrivalFrom == null || end == null || station.edgeLocation == null) {
            return null;
        }
        Vec3 first = station.edgeLocation.getFirst().getLocation();
        Vec3 second = station.edgeLocation.getSecond().getLocation();
        Vec3 from = arrivalFrom.getLocation().getLocation();
        Vec3 to = end.getLocation().getLocation();
        if (first == null || second == null || from == null || to == null) {
            return null;
        }
        Vec3 along = second.subtract(first);
        Vec3 travel = to.subtract(from);
        if (along.lengthSqr() < 1.0E-6D || travel.lengthSqr() < 1.0E-6D) {
            return null;
        }
        return travel.normalize().dot(along.normalize()) > 0.0D;
    }

    private static SignalAspect mapState(SignalBlockEntity.SignalState state) {
        if (state == null) {
            return SignalAspect.NONE;
        }
        return switch (state) {
            case GREEN -> SignalAspect.CLEAR;
            case YELLOW -> SignalAspect.CAUTION;
            case RED -> SignalAspect.DANGER;
            case INVALID -> SignalAspect.NONE;
        };
    }

    /**
     * Fallback for track with no signal blocks: if the section the vehicle sits on is occupied by a
     * train, treat it as a red. The distance is genuinely unknown here - the cab reacts by holding,
     * which is the safe reading of "there is something in the block and I cannot see how far away".
     */
    private static SignalInfo occupancyAhead(ServerLevel level, Vec3 from, Vec3 heading) {
        TrackNode node = nearestNode(level, from).orElse(null);
        if (node == null) {
            return SignalInfo.NONE;
        }
        for (TrackGraph graph : graphs(level)) {
            Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(node);
            if (connections == null) {
                continue;
            }
            for (TrackEdge edge : connections.values()) {
                UUID groupId = edge.getEdgeData().getEffectiveEdgeGroupId(graph);
                if (groupId == null) {
                    continue;
                }
                SignalEdgeGroup group = Create.RAILWAYS.signalEdgeGroups.get(groupId);
                if (group != null && !group.trains.isEmpty()) {
                    return new SignalInfo(SignalAspect.DANGER, -1.0D, null, true);
                }
            }
        }
        return SignalInfo.NONE;
    }

    /**
     * The node nearest a point, searched exhaustively in the horizontal plane.
     *
     * <p>Not {@code graph.locateNode(Level, Vec3)}: that applies a tolerance which the two positions we
     * actually have both fail. The start is a vehicle's centre of mass, a metre or two above the rail,
     * and the goal is the centre of a station block, which stands beside the track rather than on it -
     * so the lookup returned null and every route was reported as having no path, no matter how short
     * and obvious it was.
     */
    /**
     * The end of a station's own track edge, when {@code to} is that station, or null when it is not a station.
     *
     * <p>Of the edge's two ends, the one nearer where the vehicle is coming from is the one it arrives at. When the
     * approach turns out to face the other way, the arrival check rejects the route - the same path a station on
     * the main line already takes - rather than this having to predict it.
     */
    private static TrackNode stationEndNode(TrackGraph graph, Vec3 to, Vec3 from) {
        // The nearest station to the destination, not merely one within a couple of blocks of it.
        //
        // The tight radius was the fragile part: the destination arrives here as a position, and if it did not
        // land almost exactly on the station's own block the station was missed altogether and the code fell back
        // to "the nearest node" - which for a station on a side line is a node on the MAIN line, so the route
        // never entered the branch, the switch into it was never thrown, and the vehicle ran past the station and
        // away down the main line.
        GlobalStation nearest = null;
        double nearestDistance = 24.0D;
        for (GlobalStation candidate : graph.getPoints(EdgePointType.STATION)) {
            // Measured to the station's own piece of TRACK, not to its block.
            //
            // The destination arrives here as a bare position, and that position is the block of the station the
            // schedule named - which stands on that station's own edge. So the named station measures about zero
            // while every other station measures from its own edge, which is what makes this identification exact
            // in practice. Comparing block positions compared the wrong things entirely, and could settle on a
            // different station that merely happened to be nearer.
            double distance = distanceToEdge(graph, candidate, to);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        if (nearest == null) {
            return null;
        }
        TrackNode first = graph.locateNode(nearest.edgeLocation.getFirst());
        TrackNode second = graph.locateNode(nearest.edgeLocation.getSecond());
        RailDispatch.debug("Destination station '{}' is {} blocks from the resolved point; "
                        + "using its own track node, not the nearest node overall",
                nearest.name, String.format(java.util.Locale.ROOT, "%.1f", nearestDistance));
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        Vec3 firstPos = first.getLocation().getLocation();
        Vec3 secondPos = second.getLocation().getLocation();
        if (firstPos == null || secondPos == null) {
            return first;
        }
        return firstPos.distanceTo(from) <= secondPos.distanceTo(from) ? first : second;
    }

    /**
     * Distance from {@code to} to the stretch of track a station governs, falling back to its block position when
     * its edge cannot be read.
     */
    private static double distanceToEdge(TrackGraph graph, GlobalStation station, Vec3 to) {
        TrackNode first = graph.locateNode(station.edgeLocation.getFirst());
        TrackNode second = graph.locateNode(station.edgeLocation.getSecond());
        Vec3 a = first == null ? null : first.getLocation().getLocation();
        Vec3 b = second == null ? null : second.getLocation().getLocation();
        if (a == null && b == null) {
            BlockPos at = station.getBlockEntityPos();
            return at == null ? Double.MAX_VALUE : Vec3.atCenterOf(at).distanceTo(to);
        }
        if (a == null || b == null) {
            return (a == null ? b : a).distanceTo(to);
        }
        Vec3 along = b.subtract(a);
        double lengthSqr = along.lengthSqr();
        if (lengthSqr < 1.0E-6D) {
            return a.distanceTo(to);
        }
        // Point-to-segment distance: the projection onto the edge, clamped to its ends.
        double t = Math.max(0.0D, Math.min(1.0D, to.subtract(a).dot(along) / lengthSqr));
        return a.add(along.scale(t)).distanceTo(to);
    }

    /**
     * Walks forward along the track from the vehicle and returns the first boundary that governs it, or null when
     * the walk cannot decide.
     *
     * <p>Returns a result as soon as a boundary is reached, whatever it says: if the side facing the vehicle holds
     * no signal at all, the answer is DANGER - none of the signals there is addressed to it, and an unaddressed
     * boundary must not be passed. The walk is bounded both by the lookahead distance and by a step count, so a
     * pathological graph cannot hang the server.
     */
    private SignalInfo walkToNextSignal(ServerLevel level, Vec3 from, Vec3 heading, double lookahead) {
        // Without a direction there is no way to choose at a junction, and choosing wrongly walks onto a different
        // line entirely - which is how signals belonging to other tracks (and other trains) came to govern this
        // vehicle. Better to hand back and let the caller's fallback answer.
        if (heading == null || heading.lengthSqr() < 1.0E-6D) {
            return null;
        }
        for (TrackGraph graph : graphs(level)) {
            // Start from the nearest node by distance, not from locateNode.
            //
            // locateNode(Level, Vec3) is an exact hash lookup - verified in its bytecode - so it returns null for a
            // position that does not coincide with a node to the last bit. That made this walk fail on essentially
            // every call, which is why the geometric fallback kept running instead, and geometry is what produced
            // red lights where no signal existed.
            TrackNode start = nearestNode(graph, level, from);
            if (start == null) {
                continue;
            }
            TrackNode previous = null;
            TrackNode current = start;
            double travelled = 0.0D;
            for (int step = 0; step < 64 && travelled <= lookahead; step++) {
                TrackNode next = null;
                TrackEdge edge = null;
                for (java.util.Map.Entry<TrackNode, TrackEdge> connection
                        : graph.getConnectionsFrom(current).entrySet()) {
                    if (connection.getKey() == previous) {
                        continue;
                    }
                    // Only ever step the way we are actually heading. Picking a connection merely because the map
                    // offered it first turned a junction into a coin toss, and half those tosses left the route.
                    if (alignedWith(heading, current, connection.getKey())) {
                        next = connection.getKey();
                        edge = connection.getValue();
                        break;
                    }
                }
                if (next == null || edge == null) {
                    break;
                }
                SignalBoundary boundary = signalOnEdge(graph, edge);
                if (boundary != null) {
                    java.util.Map<BlockPos, Boolean> side = boundarySide(boundary, next);
                    if (side == null || side.isEmpty()) {
                        // No signal stands on our side of this boundary: it is a one-way signal for opposing
                        // traffic. It does not govern us; walking on must not turn its absence into a red light.
                        previous = current;
                        current = next;
                        travelled += edge.getLength();
                        continue;
                    }
                    BlockPos pos = null;
                    for (BlockPos candidate : side.keySet()) {
                        if (level.isLoaded(candidate)) {
                            pos = candidate;
                            break;
                        }
                    }
                    SignalAspect aspect = pos == null
                            ? SignalAspect.DANGER
                            : readableAspect(level, boundary, pos);
                    Vec3 at = signalGhostPosition(graph, boundary)
                            .orElse(pos == null ? from : Vec3.atCenterOf(pos));
                    return new SignalInfo(aspect, travelled, at, occupiedByTrain(boundary));
                }
                previous = current;
                current = next;
                travelled += edge.getLength();
            }
        }
        // "Cannot decide" is answered as "no signal ahead", not as null.
        //
        // Returning null let the caller fall back to a search built on positions, and that fallback is what put red
        // lights where there was no signal at all - a boundary whose near side held no signal was read as "must not
        // pass". The graph is the only authority here: if the walk cannot say, the honest answer is that nothing
        // governs this stretch, and the geometric path below is left unreachable by construction.
        return SignalInfo.NONE;
    }

    /**
     * The next signal along a held route.
     *
     * <p>This is the honest form of the question, and it is deliberately not built on
     * {@link #signalAhead}: that one takes a world direction and lets each junction pick a branch by
     * alignment with it. A straight line to the next node is not the route - at a turnout it can align
     * with the other branch, and on a curve it can align with neither - so a vehicle could read a signal
     * belonging to a neighbouring line, or report no signal while one stood plainly ahead.
     *
     * <p>The route already knows the edges. This walks them in order and takes each boundary's side from
     * the node the route enters it through, which is the same selection Create's own
     * {@code canNavigateVia} makes.
     *
     * <p>Distance is measured along the route: the vehicle and the signal are each projected onto the
     * edge, and every following edge contributes its length. A boundary that ends up behind the vehicle
     * is skipped, not reported as "close".
     */
    @Override
    public SignalInfo signalAheadAlong(ServerLevel level, PlannedRoute route, int fromIndex, Vec3 from,
                                       double lookahead) {
        try {
            if (route == null || from == null || lookahead <= 0.0D) {
                return SignalInfo.NONE;
            }
            List<Vec3> waypoints = route.waypoints();
            if (waypoints.size() < 2) {
                return SignalInfo.NONE;
            }
            TrackGraph graph = graphContaining(level, waypoints.get(0), from);
            if (graph == null) {
                return SignalInfo.NONE;
            }
            // Two nodes back: the follower advances its index once the vehicle is past the midpoint of
            // an edge, so the edge just traversed can still carry the signal being approached. Signals
            // genuinely behind are filtered out by the distance test below, not by the starting point.
            int start = Math.max(0, Math.min(fromIndex - 2, waypoints.size() - 2));
            double u = Double.NaN;      // position of the current edge's start node relative to the vehicle
            for (int i = start; i + 1 < waypoints.size(); i++) {
                TrackNode a = graph.locateNode(level, waypoints.get(i));
                TrackNode b = graph.locateNode(level, waypoints.get(i + 1));
                if (a == null || b == null) {
                    break;
                }
                Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(a);
                TrackEdge edge = connections == null ? null : connections.get(b);
                if (edge == null) {
                    break;
                }
                Vec3 aPos = nodePosition(a);
                Vec3 bPos = nodePosition(b);
                if (aPos == null || bPos == null) {
                    break;
                }
                Vec3 along = new Vec3(bPos.x - aPos.x, 0.0D, bPos.z - aPos.z);
                if (along.lengthSqr() < 1.0E-6D) {
                    break;
                }
                along = along.normalize();
                double length = Math.max(0.5D, edge.getLength());
                if (Double.isNaN(u)) {
                    double vehicleAlong = project(from, aPos, along, length);
                    u = -vehicleAlong;
                }
                if (u > lookahead) {
                    return SignalInfo.NONE;
                }
                SignalBoundary boundary = signalOnEdge(graph, edge);
                if (boundary != null) {
                    Map<BlockPos, Boolean> side = boundarySide(boundary, b);
                    if (side == null || side.isEmpty()) {
                        // A one-way signal for opposing traffic; not ours. Keep walking the route.
                        u += length;
                        continue;
                    }
                    BlockPos pos = firstLoaded(level, side);
                    Vec3 signalPosition = pos != null
                            ? Vec3.atCenterOf(pos)
                            : signalGhostPosition(graph, boundary).orElse(null);
                    double signalAlong = signalPosition == null
                            ? length * 0.5D
                            : project(signalPosition, aPos, along, length);
                    double distance = u + signalAlong;
                    if (distance >= 1.5D) {
                        if (distance > lookahead) {
                            return SignalInfo.NONE;
                        }
                        SignalAspect aspect = pos == null
                                ? SignalAspect.DANGER
                                : readableAspect(level, boundary, pos);
                        Vec3 marker = signalPosition == null ? from : signalPosition;
                        return new SignalInfo(aspect, distance, marker, occupiedByTrain(boundary));
                    }
                }
                u += length;
            }
            return SignalInfo.NONE;
        } catch (Throwable throwable) {
            warn("reading the signal along the route", throwable);
            return SignalInfo.NONE;
        }
    }

    /** How far a point lies along an edge, in blocks, clamped to the edge. */
    private static double project(Vec3 point, Vec3 origin, Vec3 direction, double length) {
        double along = new Vec3(point.x - origin.x, 0.0D, point.z - origin.z).dot(direction);
        return Math.max(0.0D, Math.min(length, along));
    }

    /** The graph that contains a route node; exact lookup first, then a scan when proximity missed. */
    private static TrackGraph graphContaining(ServerLevel level, Vec3 nodePosition, Vec3 near) {
        TrackGraph nearby = graphNear(level, near).orElse(null);
        if (nearby != null && nearby.locateNode(level, nodePosition) != null) {
            return nearby;
        }
        for (TrackGraph graph : graphs(level)) {
            if (graph.locateNode(level, nodePosition) != null) {
                return graph;
            }
        }
        return null;
    }

    /** The first signal block on the side that governs us which is actually loaded. */
    private static BlockPos firstLoaded(ServerLevel level, Map<BlockPos, Boolean> side) {
        if (side == null) {
            return null;
        }
        for (BlockPos candidate : side.keySet()) {
            if (level.isLoaded(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** The aspect of one signal block, with "cannot be read" treated as a red light. */
    private SignalAspect readableAspect(ServerLevel level, SignalBoundary boundary, BlockPos pos) {
        SignalAspect aspect = aspectOf(level, boundary, pos);
        if (aspect != SignalAspect.NONE) {
            return aspect;
        }
        // A signal block exists here but its lamp cannot be read - which happens just after one is placed, while
        // the boundary's cached state is still INVALID. On a railway "cannot tell" means "stop".
        if (!warnedUnreadableSignal) {
            warnedUnreadableSignal = true;
            RailDispatch.debug("Signal at {} exists but its state could not be read; treating it as a red "
                    + "light.", pos);
        }
        return SignalAspect.DANGER;
    }

    // ------------------------------------------------------ walking the graph towards the next signal
    //
    // The pieces below exist so that "which signal governs me" can be answered by walking the track graph
    // instead of by comparing positions. Distance, "is it ahead" and "which side faces me" are all consequences
    // of the walk: the first boundary reached is the governing one, and the side is fixed by which node the walk
    // enters it through. They are added unused for now; the search itself is switched over next.

    /** Whether the edge from {@code from} to {@code to} agrees with the direction of travel. */
    private static boolean alignedWith(Vec3 heading, TrackNode from, TrackNode to) {
        if (heading == null || heading.lengthSqr() < 1.0E-6D) {
            return false;
        }
        Vec3 a = from.getLocation().getLocation();
        Vec3 b = to.getLocation().getLocation();
        if (a == null || b == null) {
            return false;
        }
        Vec3 along = b.subtract(a);
        return along.lengthSqr() > 1.0E-6D && heading.normalize().dot(along.normalize()) > 0.0D;
    }

    /**
     * Every node reachable from {@code start} along the track graph.
     *
     * <p>Exists so that "can this destination be reached at all" is answered by the graph rather than by comparing
     * coordinates. A destination whose node is not in this set is not connected to the vehicle's track, and saying
     * so is a better answer than a search that simply fails.
     *
     * <p>Bounded by a visit limit so a pathological graph cannot hang the server.
     */
    private static Set<TrackNode> reachableFrom(TrackGraph graph, TrackNode start) {
        Set<TrackNode> reached = new HashSet<>();
        if (graph == null || start == null) {
            return reached;
        }
        ArrayDeque<TrackNode> queue = new ArrayDeque<>();
        reached.add(start);
        queue.add(start);
        while (!queue.isEmpty() && reached.size() < 20000) {
            TrackNode current = queue.poll();
            Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(current);
            if (connections == null) {
                continue;
            }
            for (TrackNode next : connections.keySet()) {
                if (reached.add(next)) {
                    queue.add(next);
                }
            }
        }
        return reached;
    }

    /** Graph node locations within {@code radius} blocks of a position, nearest first. */
    private static java.util.List<TrackNodeLocation> nodesNear(TrackGraph graph, BlockPos near, double radius) {
        Vec3 wanted = Vec3.atCenterOf(near);
        java.util.List<TrackNodeLocation> found = new java.util.ArrayList<>();
        java.util.List<Double> distances = new java.util.ArrayList<>();
        for (TrackNodeLocation location : graph.getNodes()) {
            // Raw coordinates, because that is what a TrackNodeLocation holds.
            //
            // Its constructor takes the Vec3 unchanged and .in(level) only normalises the dimension, so the value
            // is exactly what was recorded when the node was discovered. Routing it through nodePosition() applied
            // a conversion that turned one junction into a point twice as far away, which is how a node 140 blocks
            // off was reported as "within 8 blocks".
            Vec3 position = location.getLocation();
            if (position == null) {
                continue;
            }
            double distance = position.distanceToSqr(wanted);
            if (distance > radius * radius) {
                continue;
            }
            int insertAt = 0;
            while (insertAt < distances.size() && distances.get(insertAt) <= distance) {
                insertAt++;
            }
            distances.add(insertAt, distance);
            found.add(insertAt, location);
        }
        return found;
    }

    /**
     * The cost of stepping over one edge: its real length plus a penalty that steers the search towards lines whose
     * signals face the direction of travel.
     */
    private static double edgeCost(TrackGraph graph, TrackNode entering, TrackEdge edge) {
        return edgeCost(graph, entering, edge, false);
    }

    private static double edgeCost(TrackGraph graph, TrackNode entering, TrackEdge edge,
                                   boolean ignoreOpposingSignals) {
        double length = Math.max(0.5D, edge.getLength());
        if (ignoreOpposingSignals) {
            // A shunting/coupling move is authorised past line signals. Charging it +500 per opposing
            // signal is what turned a 20-block coupler move into the observed 287-block loop around
            // the yard, just to avoid one signal facing the other way.
            return length;
        }
        SignalBoundary boundary = signalOnEdge(graph, edge);
        if (boundary == null) {
            return length * 1.2D;          // unsignalled track: slightly less attractive than a signalled one
        }
        java.util.Map<BlockPos, Boolean> side = boundarySide(boundary, entering);
        if (side != null && !side.isEmpty()) {
            return length;                 // a signal faces us here - the cheapest step
        }
        return length + 500.0D;            // only opposing signals: avoided unless there is no alternative
    }

    /**
     * Whether any signal on this path has a side facing the direction the path travels.
     *
     * <p>Walks consecutive node pairs, which is the same walk the signal search uses: the edge between them tells
     * which stretch of track is being entered, and the node being entered fixes the side. Nothing here compares
     * positions, so a signal on an adjoining line cannot be counted.
     */
    private static boolean pathHasAlignedSignal(TrackGraph graph, java.util.List<TrackNode> path) {
        int edges = 0;
        int boundaries = 0;
        for (int i = 0; i + 1 < path.size(); i++) {
            TrackNode fromNode = path.get(i);
            TrackNode toNode = path.get(i + 1);
            TrackEdge edge = graph.getConnectionsFrom(fromNode).get(toNode);
            if (edge == null) {
                continue;
            }
            edges++;
            SignalBoundary boundary = signalOnEdge(graph, edge);
            if (boundary == null) {
                continue;
            }
            boundaries++;
            java.util.Map<BlockPos, Boolean> side = boundarySide(boundary, toNode);
            if (side != null && !side.isEmpty()) {
                return true;
            }
        }
        // Logged only on failure, because a refusal with no detail is indistinguishable from a wrong answer.
        // It also separates the two possible causes: no boundary found on any edge at all (the matching is too
        // strict) versus boundaries found but none facing this way (the side selection is wrong).
        RailDispatch.debug("No aligned signal on this path: {} edge(s) walked, {} signal boundary(ies) found",
                edges, boundaries);
        // A line carrying no signals at all is acceptable - a siding or a single-track branch is governed by
        // nothing, and refusing those made every single-line route impossible. What must not happen is a route
        // whose only signals face the other way: the vehicle would be obeying signals addressed to opposing
        // traffic, or running a stretch it believes is signalled when it is not.
        return boundaries == 0;
    }

    /** The signal boundary lying on a given edge, or null when that stretch of track carries none. */
    private static SignalBoundary signalOnEdge(TrackGraph graph, TrackEdge edge) {
        for (SignalBoundary boundary : graph.getPoints(EdgePointType.SIGNAL)) {
            if (boundary.edgeLocation == null) {
                continue;
            }
            // BOTH of the boundary's endpoints must be this edge's two endpoints - not merely one shared node.
            //
            // A boundary sits at a node, and a node is shared by every edge meeting there, so "shares an endpoint"
            // made one signal belong to several stretches of track at once. The walk then picked up signals from
            // the adjoining line and obeyed them. The boundary's own edgeLocation names the exact stretch it
            // governs, and that is what to compare against.
            TrackNodeLocation boundaryFirst = boundary.edgeLocation.getFirst();
            TrackNodeLocation boundarySecond = boundary.edgeLocation.getSecond();
            boolean sameWay = edge.node1.getLocation().equals(boundaryFirst)
                    && edge.node2.getLocation().equals(boundarySecond);
            boolean reversed = edge.node1.getLocation().equals(boundarySecond)
                    && edge.node2.getLocation().equals(boundaryFirst);
            if (sameWay || reversed) {
                return boundary;
            }
        }
        return null;
    }

    private static boolean endpointOf(TrackEdge edge, TrackNodeLocation location) {
        return edge.node1.getLocation().equals(location) || edge.node2.getLocation().equals(location);
    }

    /**
     * The side of a boundary that governs a vehicle entering it through {@code enteringNode}.
     *
     * <p>This is Create's own selection: a signal is navigable-via for a node when the side matching
     * {@code isPrimary(node)} is not empty, and {@code isPrimary} is true exactly for the second endpoint.
     */
    private static java.util.Map<BlockPos, Boolean> boundarySide(SignalBoundary boundary, TrackNode enteringNode) {
        if (boundary.blockEntities == null || boundary.edgeLocation == null) {
            return null;
        }
        // The signal that governs a train entering this boundary from the near end stands on the side the train
        // is COMING FROM, not on the side it is entering. The previous mapping returned the entering node's own
        // side - the signal facing opposing traffic - and the console then obeyed signals addressed to trains
        // coming the other way.
        boolean enteringSecond = boundary.edgeLocation.getSecond().equals(enteringNode.getLocation());
        return enteringSecond ? boundary.blockEntities.getFirst() : boundary.blockEntities.getSecond();
    }

    /**
     * Whether a particular signal block stands on the side of its boundary that faces the direction of travel.
     *
     * <p>Answers true whenever the question cannot be settled, so that an unreadable boundary never silently
     * removes a signal from the search - a missed red light is far worse than one obeyed needlessly.
     */
    private static boolean isOurSide(SignalBoundary boundary, BlockPos pos, Vec3 from, TrackGraph graph) {
        if (from == null || boundary.edgeLocation == null || boundary.blockEntities == null) {
            return true;
        }
        TrackNode firstNode = graph.locateNode(boundary.edgeLocation.getFirst());
        TrackNode secondNode = graph.locateNode(boundary.edgeLocation.getSecond());
        if (firstNode == null || secondNode == null) {
            return true;
        }
        Vec3 a = firstNode.getLocation().getLocation();
        Vec3 b = secondNode.getLocation().getLocation();
        if (a == null || b == null) {
            return true;
        }
        // Which endpoint the vehicle is approaching, decided by POSITION - the one it will reach first.
        //
        // "May I enter" is a question about a node, and Create answers it per node (canNavigateVia). Doing it from
        // the direction of travel was both more roundabout and less reliable, because that direction is derived
        // from displacement and is noise whenever the vehicle is slow or has just been loaded.
        boolean towardsSecond = b.distanceToSqr(from) < a.distanceToSqr(from);
        // Approaching the second endpoint means the signal facing us is the second side's, and vice versa.
        java.util.Map<BlockPos, Boolean> side = towardsSecond
                ? boundary.blockEntities.getSecond()
                : boundary.blockEntities.getFirst();
        return side == null || side.isEmpty() || side.containsKey(pos);
    }

    /**
     * The midpoint of a signal boundary's own stretch of track - the marker the player placed - or empty when its
     * edge cannot be read.
     *
     * <p>A signal is a track marker exactly as a station is, so measuring to its block puts both the distance and
     * the "is it ahead of me" test in the wrong place: a block standing beside the track reads nearer or further
     * than the boundary it governs, and the search then takes in signals that are not really ahead of the vehicle.
     */
    private static Optional<Vec3> signalGhostPosition(TrackGraph graph, SignalBoundary boundary) {
        if (boundary.edgeLocation == null) {
            return Optional.empty();
        }
        TrackNode first = graph.locateNode(boundary.edgeLocation.getFirst());
        TrackNode second = graph.locateNode(boundary.edgeLocation.getSecond());
        Vec3 a = first == null ? null : first.getLocation().getLocation();
        Vec3 b = second == null ? null : second.getLocation().getLocation();
        if (a != null && b != null) {
            return Optional.of(a.add(b).scale(0.5D));
        }
        return Optional.ofNullable(a != null ? a : b);
    }

    /**
     * The midpoint of a station's own stretch of track - the marker the player placed - or empty when its edge
     * cannot be read.
     */
    private static Optional<Vec3> stationGhostPosition(TrackGraph graph, GlobalStation station) {
        if (station.edgeLocation == null) {
            return Optional.empty();
        }
        TrackNode first = graph.locateNode(station.edgeLocation.getFirst());
        TrackNode second = graph.locateNode(station.edgeLocation.getSecond());
        Vec3 a = first == null ? null : first.getLocation().getLocation();
        Vec3 b = second == null ? null : second.getLocation().getLocation();
        if (a != null && b != null) {
            return Optional.of(a.add(b).scale(0.5D));
        }
        return Optional.ofNullable(a != null ? a : b);
    }

    private static TrackNode nearestNode(TrackGraph graph, Level level, Vec3 pos) {
        TrackNodeLocation best = null;
        double bestDistance = Double.MAX_VALUE;
        for (TrackNodeLocation location : graph.getNodes()) {
            if (location.getDimension() != null && !location.getDimension().equals(level.dimension())) {
                continue;
            }
            Vec3 nodePos = location.getLocation();
            if (nodePos == null) {
                continue;
            }
            double dx = nodePos.x - pos.x;
            double dz = nodePos.z - pos.z;
            double distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = location;
            }
        }
        return best == null ? null : graph.locateNode(best);
    }

    /**
     * Explains a failed search in the terms that actually distinguish the causes: an endpoint that could
     * not be located at all, versus a graph that is split into disconnected pieces.
     */
    private void diagnoseNoPath(TrackGraph graph, TrackNode start, TrackNode end, Vec3 from, Vec3 to,
                                int reachable) {
        if (diagnosedNoPath) {
            return;
        }
        diagnosedNoPath = true;
        RailDispatch.debug("No path over the track graph from {} to {}. Start node: {}, end node: {}. "
                        + "Graph holds {} node(s); {} reachable from the start. A small reachable count next "
                        + "to a large node count means the track is in disconnected pieces, or the target is "
                        + "on a different graph.",
                from, to, start == null ? "<not found>" : start.getLocation().getLocation(),
                end == null ? "<not found>" : end.getLocation().getLocation(),
                graph.getNodes().size(), reachable);
    }

    private static List<BlockPos> signalPositions(SignalBoundary boundary) {
        List<BlockPos> positions = new ArrayList<>(2);
        addPositions(positions, boundary.blockEntities.getFirst());
        addPositions(positions, boundary.blockEntities.getSecond());
        return positions;
    }

    private static void addPositions(List<BlockPos> into, Map<BlockPos, Boolean> from) {
        if (from != null) {
            into.addAll(from.keySet());
        }
    }

    // ------------------------------------------------------------------ routing

    /**
     * Plans a route, preferring the ported native search when the destination is a station.
     *
     * <p>Create's own path search only targets {@link GlobalStation}s, so that is the case the port covers.
     * Address plates and vehicle tags are this mod's extension and keep the geometric planner as a
     * fallback; so does any native search that finds nothing, which keeps the console usable instead of
     * dead when a pack's graph has an unusual shape.
     */
    @Override
    public Optional<PlannedRoute> planRoute(ServerLevel level, Vec3 from, Vec3 to) {
        return planRoute(level, from, to, null);
    }

    @Override
    public Optional<PlannedRoute> planRoute(ServerLevel level, Vec3 from, Vec3 to, VehicleHandle vehicle) {
        if (!RDConfig.nativeStationRouting()) {
            return planRouteFallback(level, from, to);
        }
        boolean stationDestination = CreatePathfinder.hasStationNear(level, to);
        if (stationDestination) {
            try {
                Optional<CreatePathfinder.Result> nativeRoute = CreatePathfinder.find(level, from, to, vehicle);
                if (nativeRoute.isPresent()) {
                    clearSwitchProbes();
                    CreatePathfinder.Result result = nativeRoute.get();
                    RailDispatch.debug("Native path search: station '{}', {} waypoint(s), {} junction(s), "
                                    + "cost {}", result.station().name, result.waypoints().size(),
                            result.junctions().size(),
                            String.format(java.util.Locale.ROOT, "%.1f", result.cost()));
                    return Optional.of(new PlannedRoute(from, to, result.waypoints(), result.junctions()));
                }
                // A switch moved this tick does not change the graph's connection map until it is rebuilt, so
                // searching again immediately would see the old graph. Move one state and let the console's
                // next attempt do the search.
                if (advanceSwitchProbe(level, from, to)) {
                    RailDispatch.debug("Native path search: no route yet; a switch state was changed and "
                            + "the next planning attempt will search again");
                    return Optional.empty();
                }
            } catch (Throwable throwable) {
                warn("running the native path search", throwable);
            }
            // A station destination is never handed to the geometric planner. That planner deliberately
            // ignores the station's approach direction and reports "route allowed"; the result is a long
            // way round that arrives at the platform from the wrong end - observed as a 277-block,
            // 27-node route to a station the native search reaches in 59 blocks. If the native search
            // cannot find a route, the honest answer is "no route", not a different station's approach.
            RailDispatch.debug("Native path search found no route to the station near {}; refusing the "
                    + "geometric fallback for a station destination", to);
            return Optional.empty();
        }
        // A coupler/address plate sits beside the rails, not on a graph node. Handing that raw world
        // point to the geometric planner produced the 288-block loop around a coupler 20 blocks away:
        // there is no graph entry at the point itself. Project it onto the same graph as the vehicle
        // first, then plan to that projected on-rail point.
        Optional<TrackGraph> fromGraph = graphNear(level, from);
        Optional<TrackGraph> toGraph = graphNear(level, to);
        if (fromGraph.isPresent() && toGraph.isPresent() && fromGraph.get() != toGraph.get()) {
            RailDispatch.debug("No common track graph between {} (graph {}) and {} (graph {}); "
                            + "the target must be reached by connecting the two lines",
                    from, fromGraph.get().id, to, toGraph.get().id);
            return Optional.empty();
        }
        TrackGraph routeGraph = fromGraph.orElse(toGraph.orElse(null));
        Vec3 projectedTo = projectOntoGraph(level, to, routeGraph).orElse(to);
        if (projectedTo.distanceToSqr(to) > 1.0E-4D) {
            RailDispatch.debug("Projected non-station target {} onto the track graph as {} "
                    + "(graph {})", to, projectedTo, routeGraph == null ? "none" : routeGraph.id);
        }
        if (probeBlockSwitchStates(level, from, projectedTo)) {
            RailDispatch.debug("Plan for non-station target {}: a block switch was changed; "
                    + "retrying pathfinding after the graph rebuild", projectedTo);
            return Optional.empty();
        }
        if (advanceSwitchProbe(level, from, projectedTo)) {
            RailDispatch.debug("Plan for non-station target {}: a switch pair state was set; "
                    + "retrying pathfinding after the graph rebuild", projectedTo);
            return Optional.empty();
        }
        Optional<PlannedRoute> fallback = planRouteFallback(level, from, projectedTo, true);
        if (fallback.isPresent()) {
            clearSwitchProbes();
        }
        return fallback;
    }

    /**
     * Tries to open the direct branch by changing one block switch near the start-to-target line.
     * Graph switch points are absent on some crossings; the block is then the only handle. A changed
     * block state is the signal to stop and let the graph rebuild before searching again.
     */
    private boolean probeBlockSwitchStates(ServerLevel level, Vec3 from, Vec3 to) {
        BlockPos min = BlockPos.containing(Math.min(from.x, to.x) - 12.0D,
                Math.min(from.y, to.y) - 8.0D, Math.min(from.z, to.z) - 12.0D);
        BlockPos max = BlockPos.containing(Math.max(from.x, to.x) + 12.0D,
                Math.max(from.y, to.y) + 8.0D, Math.max(from.z, to.z) + 12.0D);
        for (BlockPos candidate : BlockPos.betweenClosed(min, max)) {
            BlockPos at = candidate.immutable();
            for (SwitchDriver driver : RailServices.switchDrivers()) {
                if (!driver.canDrive(level, at)) {
                    continue;
                }
                net.minecraft.world.level.block.state.BlockState before = level.getBlockState(at);
                if (!driver.driveTowards(level, at, to)) {
                    driver.drive(level, at, true);
                }
                if (!level.getBlockState(at).equals(before)) {
                    RailDispatch.debug("Block switch at {} changed toward target {}", at, to);
                    return true;
                }
            }
        }
        return false;
    }

    /** Projects a world point onto the nearest edge or node of one track graph. */
    private static Optional<Vec3> projectOntoGraph(ServerLevel level, Vec3 target, TrackGraph graph) {
        if (graph == null) {
            return Optional.empty();
        }
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        Set<TrackEdge> seen = new HashSet<>();
        for (TrackNodeLocation location : graph.getNodes()) {
            if (location.getDimension() != null && !location.getDimension().equals(level.dimension())) {
                continue;
            }
            Vec3 at = location.getLocation();
            if (at != null) {
                double distance = at.distanceToSqr(target);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = at;
                }
            }
            TrackNode node = graph.locateNode(location);
            if (node == null) {
                continue;
            }
            Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(node);
            if (connections == null) {
                continue;
            }
            for (TrackEdge edge : connections.values()) {
                if (!seen.add(edge)) {
                    continue;
                }
                for (int step = 0; step <= 16; step++) {
                    Vec3 point = edge.getPosition(graph, step / 16.0D);
                    if (point == null) {
                        continue;
                    }
                    double distance = point.distanceToSqr(target);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = point;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Advances a multi-attempt switch probe by one state. Reflective, like every other Railways touchpoint.
     *
     * @return true when a switch was moved and the caller should retry the plan later
     */
    private boolean advanceSwitchProbe(ServerLevel level, Vec3 from, Vec3 to) {
        if (!net.neoforged.fml.ModList.get().isLoaded("railways")) {
            return false;
        }
        try {
            Class<?> driver = Class.forName("com.raildispatch.train.railways.RailwaysSwitchDriver");
            Method advance = driver.getMethod("advanceProbe", ServerLevel.class, Vec3.class, Vec3.class,
                    double.class);
            return (boolean) advance.invoke(null, level, from, to, 64.0D);
        } catch (Throwable throwable) {
            warn("advancing the switch state probe", throwable);
            return false;
        }
    }

    private void clearSwitchProbes() {
        if (!net.neoforged.fml.ModList.get().isLoaded("railways")) {
            return;
        }
        try {
            Class<?> driver = Class.forName("com.raildispatch.train.railways.RailwaysSwitchDriver");
            driver.getMethod("clearProbes").invoke(null);
        } catch (Throwable throwable) {
            warn("clearing the switch state probe", throwable);
        }
    }

    private Optional<PlannedRoute> planRouteFallback(ServerLevel level, Vec3 from, Vec3 to) {
        return planRouteFallback(level, from, to, false);
    }

    private Optional<PlannedRoute> planRouteFallback(ServerLevel level, Vec3 from, Vec3 to,
                                                     boolean ignoreOpposingSignals) {
        // Logged at entry, unconditionally.
        //
        // Every earlier attempt at diagnosing a failed route put its message downstream of where the failure
        // actually happened, and the log came back empty for no reason - twice. This line settles the first
        // question there is: is planRoute even being reached with these arguments?
        RailDispatch.debug("planRoute: from={} to={}", from, to);
        try {
            TrackGraph graph = graphNear(level, from).orElse(null);
            if (graph == null) {
                // graphNear is a proximity lookup, and it came back empty for a vehicle sitting plainly on rails -
                // so an empty answer does not mean there is no track. Without this fallback the method returned
                // immediately, before the search, and the caller then reported "no path over the track graph" as if
                // the search had failed; the search never ran at all.
                double best = 64.0D * 64.0D;
                for (TrackGraph candidate : graphs(level)) {
                    for (TrackNodeLocation location : candidate.getNodes()) {
                        Vec3 nodePosition = location.getLocation();
                        if (nodePosition == null) {
                            continue;
                        }
                        double distance = nodePosition.distanceToSqr(from);
                        if (distance < best) {
                            best = distance;
                            graph = candidate;
                        }
                    }
                }
                RailDispatch.debug("planRoute: graphNear found nothing; using a graph whose nearest node is "
                                + "{} blocks from the vehicle", String.format(java.util.Locale.ROOT, "%.1f",
                                        Math.sqrt(best)));
            }
            if (graph == null) {
                RailDispatch.debug("planRoute: no track within 64 blocks of {}; giving up", from);
                return Optional.empty();
            }
            TrackNode start = nearestNode(graph, level, from);
            // The destination's own node when the destination is a station - not whichever node lies nearest to it.
            //
            // A station on a side line sits only a few blocks from the main line, so "the nearest node" picks the
            // MAIN line node: the route then never enters the branch, the switch that leads into it is never part
            // of the route and therefore never thrown, and the vehicle ends up stopped near the station rather than
            // at it. Taking the station's own endpoint is what puts the branch on the route at all.
            TrackNode end = stationEndNode(graph, to, from);
            if (end == null) {
                end = nearestNode(graph, level, to);
            }
            if (start == null || end == null) {
                diagnoseNoPath(graph, start, end, from, to, 0);
                return Optional.empty();
            }

            // Weighted search, so the route chosen is the one its signals actually favour.
            //
            // A Dijkstra was written here first and was wrong in a way that mattered: it kept its frontier in a
            // PriorityQueue ordered by a cost map that it then mutated, which breaks the heap invariant and settles
            // nodes out of order. The answer is not to abandon weights but to make each frontier entry carry its
            // own cost, so the queue never reads state something else can change underneath it. Weights are what
            // let the search prefer a line whose signals face the vehicle over one whose signals face the other
            // way; unweighted, the first route reached wins, which is how a route governed by opposing signals came
            // to be chosen when a better one existed.
            record Step(double cost, TrackNode node) {
            }
            Map<TrackNode, TrackNode> previous = new HashMap<>();
            Map<TrackNode, Double> best = new HashMap<>();
            java.util.PriorityQueue<Step> queue =
                    new java.util.PriorityQueue<>(java.util.Comparator.comparingDouble(Step::cost));
            best.put(start, 0.0D);
            queue.add(new Step(0.0D, start));
            boolean found = start.equals(end);
            int reachable = 1;
            while (!queue.isEmpty() && !found) {
                Step step = queue.poll();
                // Only a POPPED destination is settled. Stopping when the destination is merely discovered - which
                // is what this used to do - makes the search behave exactly like the unweighted one it replaced:
                // whichever route reaches the destination first wins, and the costs never get a say.
                if (step.node().equals(end)) {
                    found = true;
                    break;
                }
                if (step.cost() > best.getOrDefault(step.node(), Double.MAX_VALUE)) {
                    continue;                   // a cheaper route to this node was queued after this entry
                }
                Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(step.node());
                if (connections == null) {
                    continue;
                }
                for (Map.Entry<TrackNode, TrackEdge> connection : connections.entrySet()) {
                    double cost = step.cost()
                            + edgeCost(graph, connection.getKey(), connection.getValue(),
                            ignoreOpposingSignals);
                    if (cost >= best.getOrDefault(connection.getKey(), Double.MAX_VALUE)) {
                        continue;
                    }
                    if (!best.containsKey(connection.getKey())) {
                        reachable++;
                    }
                    best.put(connection.getKey(), cost);
                    previous.put(connection.getKey(), step.node());
                    queue.add(new Step(cost, connection.getKey()));
                }
            }
            if (!found) {
                // The diagnosis belongs on the failing path itself.
                //
                // It was previously written into alignSwitches, which only runs once a route has been GRANTED - so
                // for precisely the case that never gets granted, nothing was ever printed and the log looked empty
                // for no reason. These numbers answer the open question directly: whether the start node exposes
                // fewer edges than a switch has exits, which would mean the branch is not in the graph at all.
                RailDispatch.debug("no path: start={} node={} edges={} | end={} node={} edges={} reachable={}",
                        from, start.getLocation(), graph.getConnectionsFrom(start).size(),
                        to, end == null ? null : end.getLocation(),
                        end == null ? -1 : graph.getConnectionsFrom(end).size(),
                        reachable);
                diagnoseNoPath(graph, start, end, from, to, reachable);
                return Optional.empty();
            }

            List<TrackNode> path = new ArrayList<>();
            for (TrackNode node = end; node != null; node = previous.get(node)) {
                path.add(0, node);
                if (node.equals(start)) {
                    break;
                }
            }

            List<Vec3> waypoints = new ArrayList<>(path.size());
            List<BlockPos> junctions = new ArrayList<>();
            for (TrackNode node : path) {
                Vec3 position = nodePosition(node);
                if (position != null) {
                    waypoints.add(position);
                }
                Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(node);
                if (connections != null && connections.size() > 2) {
                    junctions.add(BlockPos.containing(nodePositionOrSelf(node)));
                }
            }
            // Diagnostic before any behaviour depends on it.
            //
            // The first attempt at this asked the wrong node and could therefore only ever answer one way:
            // it used the node nearest the *destination*, which is fixed by the station itself and identical no
            // matter which side the vehicle arrives from. A check that cannot come out the other way proves
            // nothing. So the question is asked about the node the route arrives *from*, and it is asked twice -
            // once through Create's own isPrimary and once through plain geometry - because the two only have
            // to disagree once to show that one of them is wrong.
            TrackNode arrivalFrom = path.size() >= 2 ? path.get(path.size() - 2) : null;
            if (end != null) {
                Vec3 toNode = end.getLocation().getLocation();
                Vec3 fromNode = arrivalFrom == null ? null : arrivalFrom.getLocation().getLocation();
                for (TrackGraph candidate : graphs(level)) {
                    for (GlobalStation station : candidate.getPoints(EdgePointType.STATION)) {
                        if (station.edgeLocation == null) {
                            continue;
                        }
                        if (!station.edgeLocation.getFirst().equals(end.getLocation())
                                && !station.edgeLocation.getSecond().equals(end.getLocation())) {
                            continue;
                        }
                        boolean primaryFromArrival = arrivalFrom != null && station.isPrimary(arrivalFrom);
                        Boolean geometry = null;
                        Vec3 first = station.edgeLocation.getFirst().getLocation();
                        Vec3 second = station.edgeLocation.getSecond().getLocation();
                        if (fromNode != null && first != null && second != null) {
                            Vec3 along = second.subtract(first);
                            Vec3 travel = toNode.subtract(fromNode);
                            if (along.lengthSqr() > 1.0E-6D && travel.lengthSqr() > 1.0E-6D) {
                                geometry = travel.normalize().dot(along.normalize()) > 0.0D;
                            }
                        }
                        RailDispatch.debug("Destination '{}': from {} to {} -> isPrimary(from)={} geometry={}",
                                station.name, fromNode, toNode, primaryFromArrival, geometry);
                        // The driver's own rule, now that the criterion has been checked in both directions of
                        // travel: a station whose arrow points against our direction of travel is not a target
                        // we may run to, and there is no point retrying a route that cannot be aligned. Failing
                        // immediately is deliberate - retrying for ever on a permanent condition was one of the
                        // behaviours that made the dispatch layer look broken.
                        //
                        // The geometry answer is the one trusted here: Create's isPrimary asked about a node that
                        // need not lie on the station's own edge, so it answered false no matter which way the
                        // vehicle came, and a check that cannot come out the other way proves nothing.
                        // Reported, not enforced.
                        //
                        // The test asks whether the station's edge runs the same way as our arrival, and it was
                        // taken as a stand-in for the direction the station faces. In the field it disagrees with
                        // the station the player sees - a station plainly aligned with the direction of travel came
                        // out as facing against it. Until the criterion is shown to match what a player sees, using
                        // it to refuse routes blocks journeys that are perfectly legal, which is worse than not
                        // checking at all.
                        if (Boolean.FALSE.equals(geometry)) {
                            RailDispatch.debug("Station '{}' reads as facing against our direction of travel "
                                    + "(criterion not trusted; route allowed)", station.name);
                        }
                    }
                }
            }
            // Whether the destination station may be approached from the direction we are arriving.
            //
            // Asked unconditionally, of the station nearest the destination. The earlier version only asked when
            // the station's own edge happened to contain the node computed as the route's end; when it did not,
            // the question was skipped in silence, and a station facing the other way was simply driven to and
            // stopped at with nothing in the log to explain it.
            TrackNode arrivalNode = path.size() >= 2 ? path.get(path.size() - 2) : null;
            GlobalStation destinationStation = null;
            double nearestStation = Double.MAX_VALUE;
            for (TrackGraph candidate : graphs(level)) {
                for (GlobalStation station : candidate.getPoints(EdgePointType.STATION)) {
                    BlockPos at = station.getBlockEntityPos();
                    if (at == null) {
                        continue;
                    }
                    double distance = Vec3.atCenterOf(at).distanceTo(to);
                    if (distance < nearestStation) {
                        nearestStation = distance;
                        destinationStation = station;
                    }
                }
            }
            if (destinationStation != null) {
                Boolean aligned = alignedAlong(destinationStation, arrivalNode, end);
                RailDispatch.debug("Destination '{}' ({} blocks from the target): facing our arrival = {}",
                        destinationStation.name,
                        String.format(java.util.Locale.ROOT, "%.1f", nearestStation),
                        aligned == null ? "unknown" : aligned);
                if (Boolean.FALSE.equals(aligned)) {
                    // Reported, not enforced - for the same reason as the check above: the criterion disagrees
                    // with the station a player sees, and a rule that refuses legal journeys on the strength of a
                    // criterion already caught being wrong is worse than no rule at all. It stays in the log so
                    // that a genuinely misaligned approach is still visible.
                    RailDispatch.debug("Station '{}' reads as facing against our arrival "
                            + "(criterion not trusted; route allowed)", destinationStation.name);
                }
            }
            // A route is only worth granting if something on it will let the vehicle proceed.
            //
            // Without this the vehicle could be sent down a line carrying no signal that faces its direction, and
            // would either run unchecked or stop partway with nothing to obey - which from the cab looks like the
            // route being granted and then never completing. Requiring one along the whole path is the weakest
            // sensible rule: it says the route is signalable, not that every section is.
            // Fatal again now that the side mapping has been checked against a real layout: a path carrying only
            // signals that face the other way must not be granted, because the vehicle would either obey signals
            // addressed to nobody on it or run the whole stretch ungoverned. The earlier refusal-everything was the
            // inverted side mapping, not this rule.
            if (!ignoreOpposingSignals && !pathHasAlignedSignal(graph, path)) {
                RailDispatch.debug("No signal on this route faces our direction of travel; refusing it");
                return Optional.empty();
            }
            if (ignoreOpposingSignals) {
                RailDispatch.debug("Shunting plan: accepted a route with no signal facing this "
                        + "direction; low-speed movement may run against the signal direction");
            }
            return Optional.of(new PlannedRoute(from, to, waypoints, junctions));
        } catch (Throwable throwable) {
            warn("planning a route", throwable);
            return Optional.empty();
        }
    }

    @Override
    public boolean lockRoute(ServerLevel level, UUID vehicle, PlannedRoute route) {
        if (route == null) {
            return false;
        }
        Set<UUID> wanted = sectionsOf(level, route);
        Map<UUID, Set<UUID>> levelLocks =
                RESERVATIONS.computeIfAbsent(level.dimension(), key -> new HashMap<>());
        for (Map.Entry<UUID, Set<UUID>> entry : levelLocks.entrySet()) {
            if (entry.getKey().equals(vehicle)) {
                continue;
            }
            for (UUID section : wanted) {
                if (entry.getValue().contains(section)) {
                    return false;
                }
            }
        }
        levelLocks.put(vehicle, wanted);
        return true;
    }

    @Override
    public void unlockRoute(ServerLevel level, UUID vehicle) {
        Map<UUID, Set<UUID>> levelLocks = RESERVATIONS.get(level.dimension());
        if (levelLocks != null) {
            levelLocks.remove(vehicle);
        }
    }

    /**
     * The sections a route occupies: the Create signal edge group of every edge along the way, and -
     * because an ungrouped edge has no id to compare - the junction positions themselves, hashed into
     * a stable id so both kinds live in one set.
     */
    private static Set<UUID> sectionsOf(ServerLevel level, PlannedRoute route) {
        Set<UUID> sections = new HashSet<>();
        for (BlockPos junction : route.junctions()) {
            sections.add(sectionId(junction));
        }
        TrackGraph graph = graphNear(level, route.from()).orElse(null);
        if (graph == null) {
            return sections;
        }
        TrackNode previous = null;
        for (Vec3 waypoint : route.waypoints()) {
            TrackNode node = graph.locateNode(level, waypoint);
            if (node == null) {
                continue;
            }
            if (previous != null) {
                Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(previous);
                TrackEdge edge = connections == null ? null : connections.get(node);
                if (edge != null) {
                    UUID groupId = edge.getEdgeData().getEffectiveEdgeGroupId(graph);
                    sections.add(groupId != null ? groupId : sectionId(BlockPos.containing(waypoint)));
                }
            }
            previous = node;
        }
        // A route with no waypoints still has to exclude somebody; fall back to its endpoints.
        if (sections.isEmpty()) {
            sections.add(sectionId(BlockPos.containing(route.from())));
            sections.add(sectionId(BlockPos.containing(route.to())));
        }
        return sections;
    }

    private static UUID sectionId(BlockPos pos) {
        return UUID.nameUUIDFromBytes(("raildispatch:section:" + pos.asLong())
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Points every switch along a granted route at the branch that route takes.
     *
     * <p>This is the step whose absence made the whole feature look broken: a route could be planned, granted
     * and reserved while every switch stayed wherever it happened to be, so a vehicle that followed its route
     * faithfully still went wherever the rails led.
     *
     * <p>The target for each junction is simply the next waypoint after it, because that is the question a
     * switch can actually answer - which of its states leads to this node. Only positions that a registered
     * driver claims are ever touched, so a junction without switch hardware is left entirely alone.
     */
    @Override
    public int alignRoute(ServerLevel level, PlannedRoute route) {
        if (route == null) {
            return 0;
        }
        return alignSwitches(level, route);
    }

    private int alignSwitches(ServerLevel level, PlannedRoute route) {
        List<Vec3> waypoints = route.waypoints();
        int driven = 0;
        // Logged even when zero: "this route has no junctions" and "the alignment never ran" look identical
        // from the outside otherwise, and that ambiguity has already cost a debugging round.
        RailDispatch.debug("Route has {} junction(s) to align", route.junctions().size());
        // Search forward only. A route can pass the same junction twice (a loop, a reversal), and a
        // global nearest-node search would aim the second pass at the first pass's branch.
        int searchFrom = 0;
        for (BlockPos junction : route.junctions()) {
            int routeIndex = indexOfNodeFrom(waypoints, junction, searchFrom);
            if (routeIndex < 0) {
                routeIndex = indexOfNode(waypoints, junction);
            }
            if (routeIndex < 0 || routeIndex + 1 >= waypoints.size()) {
                RailDispatch.debug("Junction {} is not followed by a node on this route; leaving its "
                        + "switch alone", junction);
                continue;
            }
            searchFrom = routeIndex + 1;
            // The target is the NEXT node along the route - not any neighbour that happens to lie on it.
            //
            // The previous node is on the route too, and the old neighbour scan had no way to tell the two
            // apart: it took the first neighbour whose position matched *any* waypoint, which was often the
            // node the vehicle had just come from. A switch asked "which of your states leads to this node"
            // then answered with the branch already behind the train - the switch was thrown, the log said
            // success, and the vehicle took the wrong road.
            Vec3 target = waypoints.get(routeIndex + 1);
            TrackGraph junctionGraph = graphContaining(level, waypoints.get(routeIndex), route.from());
            TrackNode junctionNode = junctionGraph == null
                    ? null
                    : junctionGraph.locateNode(level, waypoints.get(routeIndex));
            if (junctionNode == null) {
                // Exact lookup failing means this node is not on the graph at all. Falling back to "the
                // nearest node within four blocks" is how a switch on an adjoining track gets driven.
                RailDispatch.debug("Junction {} is on no track graph; leaving its switch alone", junction);
                continue;
            }
            // How many edges the graph currently exposes at this node. A switch has three exits, so two here
            // means the branch edge is INACTIVE - which would explain a route that must cross a switch failing
            // outright: the branch is simply not in the graph until the switch is set to it.
            RailDispatch.debug("Junction {} (route index {}) exposes {} edge(s); target node {}",
                    junction, routeIndex, junctionGraph.getConnectionsFrom(junctionNode).size(), target);
            // Drive the switch that actually belongs to this junction, found on the graph.
            //
            // Graph-point alignment lives in the soft-dependency class: Railways' switch types must not be
            // linked into this class, or a pack without Railways would fail to verify a method that otherwise
            // has nothing to do there. The lookup is reflective for the same reason - the class is only
            // resolvable when Railways is present. The target is passed, so the switch is asked the one
            // question that cannot be guessed: which of its states leads to the route's next node.
            int droveFromGraph = alignGraphSwitches(junctionGraph, junction, target);
            if (droveFromGraph > 0) {
                // Count graph-point alignment too. It was counted only in the block scan, so a route whose
                // switches WERE set still ended with "No switch hardware was found" - a log line that
                // actively misleads whoever is reading it.
                driven += droveFromGraph;
                continue;
            }
            // Switch hardware stands beside its junction rather than on it, hence a small neighbourhood.
            int tried = 0;
            int reachable = 0;
            // A switch block stands beside its junction and can be a good few blocks from it - wider than the first
            // radius allowed, which found only switches belonging to parallel tracks and never the right one. A
            // switch that cannot reach the target simply refuses, so casting a wider net costs nothing but a few
            // refused calls, and it is the only way to find hardware that is not the nearest thing to the junction.
            for (BlockPos candidate : BlockPos.betweenClosed(junction.offset(-10, -5, -10),
                    junction.offset(10, 5, 10))) {
                BlockPos at = candidate.immutable();
                for (SwitchDriver driver : RailServices.switchDrivers()) {
                    if (driver.canDrive(level, at)) {
                        tried++;
                        // The driver answers false when none of this switch's states leads to the node we asked
                        // for - which usually means the switch belongs to a different track that merely happens
                        // to be nearby. That is worth counting separately from a refusal caused by a lock, since
                        // only "nothing here can reach the route" says the junction itself is wrong.
                        if (driver.driveTowards(level, at, target)) {
                            reachable++;
                        }
                        driven++;
                    }
                }
            }
            // One line per junction, saying whether anything there could actually be aimed at the route's next
            // node. Without it the log is a row of individual refusals, and those cannot be told apart from
            // "the switches found here belong to another track and this route does not use this junction".
            if (tried == 0) {
                RailDispatch.debug("Junction {} has no switch hardware nearby; nothing to align", junction);
            } else if (reachable == 0) {
                RailDispatch.debug("No switch near junction {} leads to the route's next node {}; "
                        + "the hardware found there belongs to another track", junction, target);
            }
        }
        if (driven == 0 && !route.junctions().isEmpty()) {
            RailDispatch.debug("No switch hardware was found at any of this route's junctions");
        }
        return driven;
    }

    /**
     * Reflectively asks the soft-dependency class to align the switches that are graph edge points.
     *
     * <p>Reflection is not laziness here: this class must be loadable on a pack without Railways, and a
     * direct call would put Railways' {@code TrackSwitch} and {@code CREdgePointTypes} into this class's
     * constant pool. The failure would be a {@code NoClassDefFoundError} during verification, which no
     * amount of checking inside the method can catch. Resolving the driver by name keeps every Railways
     * type inside the one class that is only loaded when Railways is present.
     */
    private static int alignGraphSwitches(TrackGraph graph, BlockPos junction, Vec3 targetNode) {
        try {
            if (!graphSwitchAlignmentLookedUp) {
                graphSwitchAlignmentLookedUp = true;
                graphSwitchAlignment = Class.forName("com.raildispatch.train.railways.RailwaysSwitchDriver")
                        .getMethod("alignGraphSwitches", TrackGraph.class, BlockPos.class, Vec3.class);
            }
            if (graphSwitchAlignment == null) {
                return 0;
            }
            Object result = graphSwitchAlignment.invoke(null, graph, junction, targetNode);
            return result instanceof Integer count ? count : 0;
        } catch (Throwable throwable) {
            if (!warnedGraphSwitchAlignment) {
                warnedGraphSwitchAlignment = true;
                RailDispatch.debug("Graph switch alignment unavailable ({}); only switch blocks will be "
                        + "driven", throwable.toString());
            }
            return 0;
        }
    }

    /** Index of the route node nearest a position, or -1 when the route has no nodes. */
    private static int nearestWaypointIndex(List<Vec3> waypoints, Vec3 from) {
        int bestIndex = -1;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < waypoints.size(); i++) {
            double distance = waypoints.get(i).distanceToSqr(from);
            if (distance < best) {
                best = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    /**
     * The index of the node at {@code junction} within the route's node sequence, or -1 when the junction is not
     * one of the route's nodes at all.
     *
     * <p>The distance limit is deliberately tight. It is not a proximity search for "the closest node" - the
     * junction was itself derived from a node's position, so an exact match is expected, and anything further
     * away means the junction does not belong to this route and must not be guessed at.
     */
    private static int indexOfNode(List<Vec3> waypoints, BlockPos junction) {
        return indexOfNodeFrom(waypoints, junction, 0);
    }

    /**
     * The first route node at {@code junction} at or after {@code from}, or -1 when none is close enough.
     *
     * <p>The forward-only search is what makes a route that visits one junction twice work: each pass gets
     * its own following node, and the second pass cannot be aimed at the first pass's branch.
     */
    private static int indexOfNodeFrom(List<Vec3> waypoints, BlockPos junction, int from) {
        Vec3 at = Vec3.atCenterOf(junction);
        int bestIndex = -1;
        double best = Double.MAX_VALUE;
        for (int i = Math.max(0, from); i < waypoints.size(); i++) {
            double distance = waypoints.get(i).distanceToSqr(at);
            if (distance < best) {
                best = distance;
                bestIndex = i;
            }
        }
        return best <= 4.0D ? bestIndex : -1;
    }

    @Override
    public boolean setSwitch(ServerLevel level, BlockPos pos, boolean alternateBranch) {
        for (SwitchDriver driver : RailServices.switchDrivers()) {
            try {
                if (driver.canDrive(level, pos)) {
                    return driver.drive(level, pos, alternateBranch);
                }
            } catch (Throwable throwable) {
                RailDispatch.debug("Switch driver {} failed at {}", driver.id(), pos, throwable);
            }
        }
        return false;
    }

    @Override
    public RouteGrant requestRoute(ServerLevel level, UUID vehicle, String vehicleName, Vec3 from, Vec3 to,
                                   boolean allowLocalSwitches) {
        VehicleHandle handle = RailServices.physics().vehicleById(level, vehicle).orElse(null);
        Optional<PlannedRoute> planned = planRoute(level, from, to, handle);
        if (planned.isEmpty()) {
            return RouteGrant.denied("no path over the track graph");
        }
        PlannedRoute route = planned.get();

        if (RDConfig.preferDispatchCenter()) {
            Optional<RouteAuthority> authority = DispatchCenters.nearest(level, from,
                    RDConfig.dispatchCenterRange());
            if (authority.isPresent()) {
                RouteGrant grant = authority.get().request(level, vehicle, vehicleName, route);
                if (grant.granted() && !lockRoute(level, vehicle, route)) {
                    authority.get().release(vehicle);
                    return RouteGrant.denied("sections already reserved");
                }
                if (grant.granted()) {
                    alignSwitches(level, route);
                    rememberRoute(level, vehicle, route);
                }
                return grant;
            }
        }

        if (!allowLocalSwitches) {
            return RouteGrant.denied("no dispatch centre in range and local locking is disabled");
        }
        if (!lockRoute(level, vehicle, route)) {
            return RouteGrant.denied("route busy");
        }
        alignSwitches(level, route);
        rememberRoute(level, vehicle, route);
        return RouteGrant.granted("locked locally over "
                + String.format(java.util.Locale.ROOT, "%.0f", route.length()) + " blocks",
                route.junctions());
    }

    @Override
    public void releaseRoute(ServerLevel level, UUID vehicle) {
        unlockRoute(level, vehicle);
        Map<UUID, PlannedRoute> granted = GRANTED.get(level.dimension());
        if (granted != null) {
            granted.remove(vehicle);
        }
        // A dispatch centre arbitrates routes separately from our section table, so it has to be told
        // too - otherwise its table would keep a route nobody is using and refuse it to the next cab.
        DispatchCenters.release(level, vehicle);
    }

    /**
     * Remembers the node sequence of a granted route for the driver that holds it.
     *
     * <p>Deliberately only on success: a denied request must not leave a half-route behind that the
     * driver would then follow without authority.
     */
    private static void rememberRoute(ServerLevel level, UUID vehicle, PlannedRoute route) {
        GRANTED.computeIfAbsent(level.dimension(), key -> new HashMap<>()).put(vehicle, route);
    }

    @Override
    public Optional<PlannedRoute> grantedRoute(ServerLevel level, UUID vehicle) {
        Map<UUID, PlannedRoute> granted = GRANTED.get(level.dimension());
        return granted == null ? Optional.empty() : Optional.ofNullable(granted.get(vehicle));
    }

    /**
     * Re-asserts the switches of the junctions the vehicle is about to reach.
     *
     * <p>The route's own node list decides which branch each switch must take; this method only narrows
     * the set to "junctions still ahead", so the expensive part - asking every Railways switch which of
     * its states leads to a node - is paid once per junction per approach rather than for the whole line
     * on every tick.
     */
    @Override
    public int alignSwitchesAhead(ServerLevel level, UUID vehicle, Vec3 from, double ahead) {
        Optional<PlannedRoute> held = grantedRoute(level, vehicle);
        if (held.isEmpty() || ahead <= 0.0D) {
            return 0;
        }
        PlannedRoute route = held.get();
        double limit = ahead * ahead;
        // Only junctions still ahead along the route. A radius alone would include a turnout the vehicle
        // has already passed, and re-aiming that one can send the next train down this train's branch.
        int current = nearestWaypointIndex(route.waypoints(), from);
        List<BlockPos> near = new ArrayList<>();
        for (BlockPos junction : route.junctions()) {
            int at = indexOfNode(route.waypoints(), junction);
            if (at >= 0 && at < current) {
                continue;
            }
            if (Vec3.atCenterOf(junction).distanceToSqr(from) <= limit) {
                near.add(junction);
            }
        }
        if (near.isEmpty()) {
            return 0;
        }
        return alignSwitches(level, new PlannedRoute(route.from(), route.to(), route.waypoints(), near));
    }

    // ------------------------------------------------------------------ destinations

    @Override
    public List<String> knownDestinations(ServerLevel level) {
        List<String> names = new ArrayList<>();
        try {
            for (TrackGraph graph : graphs(level)) {
                for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                    if (station.name != null && !station.name.isEmpty()) {
                        names.add(station.name);
                    }
                }
            }
        } catch (Throwable throwable) {
            warn("listing stations", throwable);
        }
        names.addAll(RailTags.get(level).knownAddresses());
        names.addAll(RailTags.get(level).vehicleTagNames());
        for (String alias : StationSigns.aliases(level)) {
            if (!names.contains(alias)) {
                names.add(alias);
            }
        }
        return names;
    }

    @Override
    public Optional<Vec3> resolveDestination(ServerLevel level, String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        if (ConsistLocator.isConsistTarget(name)) {
            return ConsistLocator.approachPoint(level, ConsistLocator.serviceOf(name),
                    ConsistLocator.nearOf(name), ConsistLocator.couplerOf(level, name));
        }
        // A station sign is the player-facing destination name; a station's Create name is only a database
        // key. Try the sign aliases first, then fall back to station names so old schedules still work.
        Optional<StationSigns.StationTarget> sign = StationSigns.resolve(level, name);
        if (sign.isPresent()) {
            return Optional.of(sign.get().marker());
        }
        try {
            // Stations first, exactly like Create's DestinationInstruction does it: a per-graph scan,
            // because Create keeps no global name -> station index and names are not unique.
            for (TrackGraph graph : graphs(level)) {
                for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                    if (matches(station.name, name)) {
                        // The station's position ON THE TRACK - its marker - first; its block only as a fallback.
                        //
                        // A station is a point placed on the track, so its track position is what routes,
                        // distances and arrivals must be measured against. The block is merely where the entity
                        // happens to stand: it can stand away from the marker, and it need not exist at all. The
                        // same is true of signals, where measuring to the block put the distance in the wrong
                        // place and put signals that were not really ahead into the search.
                        Optional<Vec3> ghost = stationGhostPosition(graph, station);
                        if (ghost.isPresent()) {
                            return ghost;
                        }
                        Optional<Vec3> position = stationPosition(level, station);
                        if (position.isPresent()) {
                            return position;
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            warn("resolving a station", throwable);
        }

        RailTags tags = RailTags.get(level);
        List<GlobalPos> plates = tags.platesFor(name);
        for (GlobalPos plate : plates) {
            if (plate.dimension().equals(level.dimension())) {
                return Optional.of(Vec3.atCenterOf(plate.pos()));
            }
        }
        // A vehicle tag resolves to wherever that vehicle currently is.
        return tags.vehicleFor(name)
                .flatMap(id -> RailServices.physics().vehicleById(level, id))
                .map(handle -> handle.position());
    }

    private static boolean matches(String stationName, String wanted) {
        if (stationName == null) {
            return false;
        }
        if (stationName.equalsIgnoreCase(wanted)) {
            return true;
        }
        // Glob matching, so one destination can address several stations - the same idea as Create's own
        // destination filter, where "East*" covers every station whose name starts with East.
        //
        // Written out here rather than borrowed from another mod's internal utility: the rules are two characters
        // wide, and depending on a helper that is not part of anyone's contract is the worse trade. A name with no
        // wildcards still matches exactly, because every character is quoted.
        StringBuilder glob = new StringBuilder();
        for (char c : wanted.toCharArray()) {
            if (c == '*') {
                glob.append(".*");
            } else if (c == '?') {
                glob.append('.');
            } else {
                glob.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        if (glob.length() > 0 && stationName.matches(glob.toString())) {
            return true;
        }
        try {
            return stationName.matches(wanted);
        } catch (java.util.regex.PatternSyntaxException notARegex) {
            return false;
        }
    }

    /**
     * The station's marker on the track, or its block as a fallback. Public because station signs and the
     * yard router resolve a destination by sign text and need exactly the point the route must end at.
     */
    public static Optional<Vec3> markerPosition(ServerLevel level, TrackGraph graph, GlobalStation station) {
        return stationGhostPosition(graph, station).or(() -> stationPosition(level, station));
    }

    private static Optional<Vec3> stationPosition(ServerLevel level, GlobalStation station) {
        if (station.blockEntityPos == null) {
            return Optional.empty();
        }
        if (station.blockEntityDimension != null
                && !station.blockEntityDimension.equals(level.dimension())) {
            return Optional.empty();
        }
        return Optional.of(Vec3.atCenterOf(station.blockEntityPos));
    }

    // ------------------------------------------------------------------ graph helpers

    private static List<TrackGraph> graphs(ServerLevel level) {
        List<TrackGraph> result = new ArrayList<>();
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            result.add(graph);
        }
        return result;
    }

    private static Optional<TrackGraph> graphNear(ServerLevel level, Vec3 pos) {
        TrackGraph best = null;
        double bestDistance = GRAPH_REACH * GRAPH_REACH;
        for (TrackGraph graph : graphs(level)) {
            double distance = graph.distanceToLocationSqr(level, pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = graph;
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<TrackNode> nearestNode(ServerLevel level, Vec3 pos) {
        TrackGraph graph = graphNear(level, pos).orElse(null);
        if (graph == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(graph.locateNode(level, pos));
    }

    private static Vec3 nodePosition(TrackNode node) {
        TrackNodeLocation location = node.getLocation();
        return location == null ? null : location.getLocation();
    }

    private static Vec3 nodePositionOrSelf(TrackNode node) {
        Vec3 position = nodePosition(node);
        return position == null ? Vec3.ZERO : position;
    }

    private static void warn(String what, Throwable throwable) {
        RailDispatch.debug("Track network failed while {}; treating the railway as absent", what,
                throwable);
    }
}
