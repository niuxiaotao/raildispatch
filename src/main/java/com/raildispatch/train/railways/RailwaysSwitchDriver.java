package com.raildispatch.train.railways;

import com.raildispatch.RailDispatch;
import com.raildispatch.api.SwitchDriver;
import com.railwayteam.railways.content.switches.TrackSwitch;
import com.railwayteam.railways.content.switches.TrackSwitchBlock;
import com.railwayteam.railways.content.switches.TrackSwitchBlockEntity;
import com.railwayteam.railways.registry.CREdgePointTypes;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Drives Create: Railways track switches.
 *
 * <p>Create itself has no switch block - a junction is a node of the track graph and a vehicle picks
 * its branch from its own route - so switches only exist when Railways is installed. Railways is
 * therefore a <b>soft</b> dependency and this class is deliberately kept in its own package: it is
 * only ever instantiated behind a {@code ModList.isLoaded("railways")} check, so on a pack without
 * Railways the class is never loaded and none of the Railways types above are ever resolved.
 *
 * <p>Mechanically, setting a Railways switch to {@code NORMAL} or to a diverging state is what
 * enables the corresponding branch's edges, so the vehicle's own path following then takes the branch
 * the dispatcher chose.
 */
public final class RailwaysSwitchDriver implements SwitchDriver {

    @Override
    public String id() {
        return "railways:track_switch";
    }

    @Override
    public boolean canDrive(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof TrackSwitchBlockEntity;
    }

    @Override
    public boolean drive(ServerLevel level, BlockPos pos, boolean alternateBranch) {
        if (!(level.getBlockEntity(pos) instanceof TrackSwitchBlockEntity blockEntity)) {
            return false;
        }
        TrackSwitch trackSwitch = blockEntity.getSwitch();
        if (trackSwitch == null) {
            return false;
        }
        if (trackSwitch.isLocked()) {
            // A locked switch is a deliberate player decision; routing around it is the dispatcher's
            // problem, not something to override silently.
            RailDispatch.debug("Switch at {} is locked; refusing to drive it", pos);
            return false;
        }
        TrackSwitchBlock.SwitchState target = alternateBranch
                ? divergingBranch(trackSwitch)
                : TrackSwitchBlock.SwitchState.NORMAL;
        if (trackSwitch.getSwitchState() == target) {
            return true;
        }
        return trackSwitch.trySetSwitchState(target);
    }

    /**
     * Drives the switch to the state that leads towards {@code targetNode}.
     *
     * <p>This is the exact form of the request, and the only one that can be answered honestly for Railways:
     * a switch's exits are private and its exit nodes cannot be enumerated from outside that mod, so a driver
     * asked merely "straight or diverging" has to guess a handedness. Asking "which of your states leads to
     * this node" is a question Railways answers itself.
     */
    @Override
    public boolean driveTowards(ServerLevel level, BlockPos pos, Vec3 targetNode) {
        if (!(level.getBlockEntity(pos) instanceof TrackSwitchBlockEntity blockEntity)) {
            return false;
        }
        TrackSwitch trackSwitch = blockEntity.getSwitch();
        if (trackSwitch == null) {
            return false;
        }
        if (trackSwitch.isLocked()) {
            // A locked switch is a deliberate decision by the player; a route that needs it must be refused,
            // not silently overridden.
            RailDispatch.debug("Switch at {} is locked; refusing to drive it towards {}",
                    pos, targetNode);
            return false;
        }
        // Resolve the target node in the switch's own graph first. A block switch can be several
        // blocks from its junction, and a parallel line can have a node at exactly the same coordinates
        // as the route's next node. Picking the first global match made the correct switch refuse.
        TrackNodeLocation target = null;
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            boolean belongs = false;
            for (TrackSwitch candidate : graph.getPoints(CREdgePointTypes.SWITCH)) {
                if (candidate == trackSwitch) {
                    belongs = true;
                    break;
                }
            }
            if (!belongs) {
                continue;
            }
            target = nodeLocationAt(graph, targetNode);
            if (target != null) {
                break;
            }
        }
        if (target == null) {
            target = nodeLocationAt(targetNode);
        }
        if (target == null) {
            RailDispatch.LOGGER.warn("No track node found at {} to aim the switch at {}", targetNode, pos);
            return false;
        }
        TrackSwitchBlock.SwitchState state = targetStateNear(trackSwitch, targetNode, target);
        if (state == null) {
            RailDispatch.LOGGER.warn("Switch at {} has no state leading to {}; leaving it alone",
                    pos, targetNode);
            return false;
        }
        if (trackSwitch.getSwitchState() == state) {
            // Logged rather than silent. An unlogged no-op cannot be told apart from "the driver never ran",
            // which is exactly how a switch that was already correctly set came to look like one that had
            // stopped being driven at all.
            RailDispatch.debug("Switch at {} is already set for {}", pos, targetNode);
            return true;
        }
        boolean accepted = trackSwitch.trySetSwitchState(state);
        if (!accepted) {
            RailDispatch.debug("Switch at {} refused the command to reach state {}", pos, state);
        }
        return accepted;
    }

    /**
     * The switch state for the route's next node, or for a node immediately around it. The exact node
     * lookup can miss when the pathfinder reports a point on the far side of a short edge from the
     * switch's own exit node; asking the exits near that point is the same question, one step out.
     */
    private static TrackSwitchBlock.SwitchState targetStateNear(TrackSwitch railSwitch, Vec3 targetNode,
                                                                TrackNodeLocation target) {
        TrackSwitchBlock.SwitchState exact = railSwitch.getTargetState(target);
        if (exact != null) {
            return exact;
        }
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (TrackNodeLocation location : graph.getNodes()) {
                Vec3 at = location.getLocation();
                if (at == null || at.distanceToSqr(targetNode) > 400.0D) {
                    continue;
                }
                TrackSwitchBlock.SwitchState state = railSwitch.getTargetState(location);
                if (state != null) {
                    return state;
                }
            }
        }
        return null;
    }

    /**
     * The node at a world position.
     *
     * <p>Matching by distance is safe here in a way it was not when locating a node from a vehicle's position:
     * the position given is a node's own position, so this is an exact hit rather than a search that has to
     * tolerate a body floating a metre above the rails.
     */
    private static TrackNodeLocation nodeLocationAt(Vec3 target) {
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            TrackNodeLocation found = nodeLocationAt(graph, target);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The node at {@code target} in one specific graph. Two separate lines can have nodes at exactly
     * the same coordinates; taking the first global match made a switch on this graph refuse a target
     * that belonged to a neighbouring track, which is how a junction ended up "belonging to another
     * track" while the correct switch stood right beside it.
     */
    private static TrackNodeLocation nodeLocationAt(TrackGraph graph, Vec3 target) {
        if (graph == null) {
            return null;
        }
        for (TrackNodeLocation location : graph.getNodes()) {
            Vec3 at = location.getLocation();
            if (at != null && at.distanceToSqr(target) < 0.25D) {
                return location;
            }
        }
        return null;
    }

    /**
     * Aligns the Railways switches that are <b>edge points of the graph</b> at one junction.
     *
     * <p>This is the exact form of the alignment, and it is kept here - not in the track network - because
     * {@code TrackSwitch} and {@code CREdgePointTypes} only exist when Railways is installed. The network
     * calls this method reflectively (see {@code CreateTrackNetwork#alignGraphSwitches}), so a pack without
     * Railways never links these types at all.
     *
     * <p>The switch is asked exactly one question: <b>which of your states leads to the route's next
     * node?</b> Anything else is a guess, and guessing is how the wrong branch gets thrown. In
     * particular this method no longer probes "nodes near the junction":
     *
     * <ul>
     *   <li>the route's <em>previous</em> node is also near the junction, and a probe could pick it -
     *       throwing the switch back down the branch the train had just come from;</li>
     *   <li>a node 8 blocks away can belong to an adjoining track, so the probe could throw a switch
     *       that is not even on this route.</li>
     * </ul>
     *
     * <p>A switch with no exit towards the target is left alone, and the caller's block-scan fallback is
     * then given the same target. Returning zero is the honest answer there.
     *
     * @return how many switches were driven (including ones already in the right state)
     */
    public static int alignGraphSwitches(TrackGraph graph, BlockPos junction, Vec3 targetNode) {
        TrackNodeLocation target = nodeLocationAt(graph, targetNode);
        if (target == null) {
            target = nodeLocationAt(targetNode);
        }
        if (target == null) {
            RailDispatch.LOGGER.warn("No track node at {} to aim a switch at (junction {})", targetNode, junction);
            return 0;
        }
        int drove = 0;
        for (TrackSwitch railSwitch : graph.getPoints(CREdgePointTypes.SWITCH)) {
            if (railSwitch.edgeLocation == null) {
                continue;
            }
            TrackSwitchBlock.SwitchState state = targetStateNear(railSwitch, targetNode, target);
            if (state == null) {
                // No exit towards the route's next node or any node immediately around it.
                continue;
            }
            if (railSwitch.isLocked()) {
                RailDispatch.debug("Switch at junction {} is locked; refusing to set it towards {}",
                        junction, targetNode);
                continue;
            }
            if (railSwitch.getSwitchState() == state) {
                // Already correct. Counted so the caller does not fall through to a block scan that would
                // ask the same switch again.
                drove++;
                continue;
            }
            if (railSwitch.trySetSwitchState(state)) {
                drove++;
                RailDispatch.debug("Switch at junction {} set to {} for target node {}",
                        junction, state, targetNode);
            } else {
                RailDispatch.LOGGER.warn("Switch at junction {} refused state {} for target node {} (locked {})",
                        junction, state, targetNode, railSwitch.isLocked());
            }
        }
        return drove;
    }

    /**
     * One step of a two-switch probe that spans several planning attempts.
     *
     * <p>The route that matters here needs <b>two</b> switches moved: the one at the vehicle
     * {@code (4.5,-12)} and the one at the approach {@code (8.5,18)}. Probing them one at a time and
     * restoring the first - the obvious implementation - can never find that route. Probing <b>every</b>
     * switch and keeping the changes is worse: it also throws switches on other lines and, in the observed
     * run, set {@code (8.5,10.5)} and {@code (4.5,8)} to diverging, blocking the straight route it was
     * looking for.
     *
     * <p>So the probe pairs exactly two switches: the one nearest the vehicle, and the one nearest the
     * straight line from the vehicle to the station. Their state combinations are tried together, and every
     * other switch is left alone. A Railways switch animates and its graph is rebuilt after the animation,
     * so each combination is left in place for {@link #SETTLE_MILLIS}.
     *
     * @return true when a combination was set and the caller should retry later
     */
    public static boolean advanceProbe(ServerLevel level, Vec3 from, Vec3 to, double radius) {
        long now = System.currentTimeMillis();
        if (now < COOLDOWN_UNTIL) {
            return false;
        }
        if (PAIR_A != null) {
            if (now - PAIR_CHANGED < SETTLE_MILLIS) {
                return true;
            }
            if (nextPairCombination(now)) {
                return true;
            }
            restore(PAIR_A, PAIR_A_ORIGINAL);
            restore(PAIR_B, PAIR_B_ORIGINAL);
            RailDispatch.debug("Native path search: tried every state pair of {} and {}; restored both",
                    PAIR_A_AT, PAIR_B_AT);
            clearPair();
            COOLDOWN_UNTIL = now + 60_000L;
            return false;
        }
        TrackSwitch first = nearestSwitch(from, radius);
        TrackSwitch second = nearestSwitchOnLine(from, to, radius, first);
        if (first == null || second == null) {
            return false;
        }
        if (first.isLocked() || second.isLocked()) {
            RailDispatch.debug("Native path search: switch at {} or {} is locked; not probing",
                    midpoint(first), midpoint(second));
            return false;
        }
        PAIR_A = first;
        PAIR_B = second;
        PAIR_A_ORIGINAL = first.getSwitchState();
        PAIR_B_ORIGINAL = second.getSwitchState();
        PAIR_A_STATES = states(first);
        PAIR_B_STATES = states(second);
        PAIR_A_INDEX = 0;
        PAIR_B_INDEX = 0;
        PAIR_A_AT = midpoint(first);
        PAIR_B_AT = midpoint(second);
        PAIR_CHANGED = now;
        if (nextPairCombination(now)) {
            return true;
        }
        clearPair();
        return false;
    }

    private static boolean nextPairCombination(long now) {
        for (; PAIR_A_INDEX < PAIR_A_STATES.size(); PAIR_A_INDEX++) {
            for (; PAIR_B_INDEX < PAIR_B_STATES.size(); PAIR_B_INDEX++) {
                TrackSwitchBlock.SwitchState a = PAIR_A_STATES.get(PAIR_A_INDEX);
                TrackSwitchBlock.SwitchState b = PAIR_B_STATES.get(PAIR_B_INDEX);
                if (a == PAIR_A_ORIGINAL && b == PAIR_B_ORIGINAL) {
                    continue;
                }
                if (!PAIR_A.trySetSwitchState(a)) {
                    continue;
                }
                if (!PAIR_B.trySetSwitchState(b)) {
                    restore(PAIR_A, PAIR_A_ORIGINAL);
                    continue;
                }
                PAIR_CHANGED = now;
                PAIR_B_INDEX++;
                RailDispatch.debug("Native path search: switch pair {}={} and {}={}; the next planning "
                                + "attempt will search the rebuilt graph", PAIR_A_AT, a, PAIR_B_AT, b);
                return true;
            }
            PAIR_B_INDEX = 0;
        }
        return false;
    }

    private static List<TrackSwitchBlock.SwitchState> states(TrackSwitch railSwitch) {
        List<TrackSwitchBlock.SwitchState> states = new ArrayList<>();
        states.add(TrackSwitchBlock.SwitchState.NORMAL);
        if (railSwitch.hasLeftExit()) {
            states.add(TrackSwitchBlock.SwitchState.REVERSE_LEFT);
        }
        if (railSwitch.hasRightExit()) {
            states.add(TrackSwitchBlock.SwitchState.REVERSE_RIGHT);
        }
        return states;
    }

    private static void restore(TrackSwitch railSwitch, TrackSwitchBlock.SwitchState original) {
        if (railSwitch != null && original != null) {
            railSwitch.trySetSwitchState(original);
        }
    }

    private static TrackSwitch nearestSwitch(Vec3 at, double radius) {
        TrackSwitch best = null;
        double bestDistance = radius * radius;
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (TrackSwitch railSwitch : graph.getPoints(CREdgePointTypes.SWITCH)) {
                Vec3 position = midpoint(railSwitch);
                if (position == null) {
                    continue;
                }
                double distance = position.distanceToSqr(at);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = railSwitch;
                }
            }
        }
        return best;
    }

    /**
     * The switch nearest the station that lies on the vehicle-to-station line.
     *
     * <p>"Nearest the line" alone is wrong: it picked {@code (8.5,10.5)}, a mid-line switch that must stay
     * straight, instead of the turnout at {@code (8.5,18)} that the route actually has to take. Among the
     * switches between the vehicle and the station that are close to that line, the one nearest the station
     * is the approach turnout. Switches beyond the station are excluded.
     */
    private static TrackSwitch nearestSwitchOnLine(Vec3 from, Vec3 to, double radius, TrackSwitch skip) {
        TrackSwitch best = null;
        double bestStationDistance = radius * radius;
        double abx = to.x - from.x;
        double abz = to.z - from.z;
        double lengthSqr = abx * abx + abz * abz;
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (TrackSwitch railSwitch : graph.getPoints(CREdgePointTypes.SWITCH)) {
                if (railSwitch == skip) {
                    continue;
                }
                Vec3 position = midpoint(railSwitch);
                if (position == null) {
                    continue;
                }
                if (lengthSqr > 1.0E-6D) {
                    double t = ((position.x - from.x) * abx + (position.z - from.z) * abz) / lengthSqr;
                    if (t < 0.05D || t > 1.0D) {
                        // Behind the vehicle or beyond the station: not the approach turnout.
                        continue;
                    }
                }
                if (segmentDistanceSqr(position, from, to) > 8.0D * 8.0D) {
                    continue;
                }
                double stationDistance = position.distanceToSqr(to);
                if (stationDistance < bestStationDistance) {
                    bestStationDistance = stationDistance;
                    best = railSwitch;
                }
            }
        }
        return best;
    }

    private static double segmentDistanceSqr(Vec3 point, Vec3 a, Vec3 b) {
        double abx = b.x - a.x;
        double abz = b.z - a.z;
        double lengthSqr = abx * abx + abz * abz;
        if (lengthSqr < 1.0E-6D) {
            return point.distanceToSqr(a);
        }
        double t = ((point.x - a.x) * abx + (point.z - a.z) * abz) / lengthSqr;
        t = Math.max(0.0D, Math.min(1.0D, t));
        double x = a.x + abx * t;
        double z = a.z + abz * t;
        double dx = point.x - x;
        double dz = point.z - z;
        return dx * dx + dz * dz;
    }

    /** Keeps whatever state produced a successful route; drops the probe bookkeeping. */
    public static void clearProbes() {
        clearPair();
        COOLDOWN_UNTIL = 0L;
    }

    private static void clearPair() {
        PAIR_A = null;
        PAIR_B = null;
        PAIR_A_STATES = null;
        PAIR_B_STATES = null;
        PAIR_A_INDEX = 0;
        PAIR_B_INDEX = 0;
    }

    /** Time for a Railways switch animation and the track-graph rebuild that follows it. */
    private static final long SETTLE_MILLIS = 3000L;

    private static TrackSwitch PAIR_A;
    private static TrackSwitch PAIR_B;
    private static TrackSwitchBlock.SwitchState PAIR_A_ORIGINAL;
    private static TrackSwitchBlock.SwitchState PAIR_B_ORIGINAL;
    private static List<TrackSwitchBlock.SwitchState> PAIR_A_STATES;
    private static List<TrackSwitchBlock.SwitchState> PAIR_B_STATES;
    private static int PAIR_A_INDEX;
    private static int PAIR_B_INDEX;
    private static long PAIR_CHANGED;
    private static long COOLDOWN_UNTIL;
    private static Vec3 PAIR_A_AT;
    private static Vec3 PAIR_B_AT;

    private static Vec3 midpoint(TrackSwitch railSwitch) {
        if (railSwitch.edgeLocation == null) {
            return null;
        }
        Vec3 first = railSwitch.edgeLocation.getFirst() == null
                ? null : railSwitch.edgeLocation.getFirst().getLocation();
        Vec3 second = railSwitch.edgeLocation.getSecond() == null
                ? null : railSwitch.edgeLocation.getSecond().getLocation();
        if (first != null && second != null) {
            return first.add(second).scale(0.5D);
        }
        return first != null ? first : second;
    }

    private static TrackSwitchBlock.SwitchState divergingBranch(TrackSwitch trackSwitch) {
        // Which diverging state exists depends on how the turnout was built, so ask the switch rather
        // than assuming a handedness.
        if (trackSwitch.hasRightExit()) {
            return TrackSwitchBlock.SwitchState.REVERSE_RIGHT;
        }
        if (trackSwitch.hasLeftExit()) {
            return TrackSwitchBlock.SwitchState.REVERSE_LEFT;
        }
        return TrackSwitchBlock.SwitchState.NORMAL;
    }
}
