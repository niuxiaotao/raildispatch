package com.raildispatch.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The ground layer: Create's track graph, its signals, and the reservation of track sections that
 * keeps two vehicles out of the same piece of railway.
 *
 * <p>Two implementations satisfy this contract:
 * <ul>
 *   <li>the dispatch centre, which calculates routes centrally for every cab in range;</li>
 *   <li>the local fallback, where a cab plans and locks the route it needs itself.</li>
 * </ul>
 */
public interface TrackNetwork {

    boolean isAvailable();

    /**
     * The most restrictive signal governing a vehicle travelling from {@code from} along
     * {@code heading}, within {@code lookahead} blocks, together with its distance.
     */
    SignalInfo signalAhead(ServerLevel level, Vec3 from, Vec3 heading, double lookahead);

    /**
     * Drive a ground switch, if the pack provides one. Create itself has no switch block - junctions
     * choose their own branch from the vehicle's route - so this goes through the
     * {@link SwitchDriver} extension point and reports false when no driver handles the position.
     */
    boolean setSwitch(ServerLevel level, BlockPos pos, boolean alternateBranch);

    /** Ask for a complete route: planning plus locking, with a dispatch centre as arbiter if present. */
    RouteGrant requestRoute(ServerLevel level, UUID vehicle, String vehicleName, Vec3 from, Vec3 to,
                            boolean allowLocalSwitches);

    /** Release a previously granted route. */
    void releaseRoute(ServerLevel level, UUID vehicle);

    /** Plan a path over the track graph and collect the junctions it passes through. */
    Optional<PlannedRoute> planRoute(ServerLevel level, Vec3 from, Vec3 to);

    /**
     * Plan a path for a specific vehicle, so the native search can start from its leading axle rather
     * than from a projected centre point. Used by yard routing for free wagons.
     */
    default Optional<PlannedRoute> planRoute(ServerLevel level, Vec3 from, Vec3 to, VehicleHandle vehicle) {
        return planRoute(level, from, to);
    }

    /**
     * Drive every switch on a planned route without granting or locking it. Used for a free wagon whose
     * destination comes from a nameplate: it has no driver to hold authority, but the yard still has to
     * throw its turnouts before it rolls over them.
     */
    default int alignRoute(ServerLevel level, PlannedRoute route) {
        return 0;
    }

    /**
     * The next signal along a held route, rather than the next signal "in that direction".
     *
     * <p>A direction is not enough at a junction or a curve. The straight line to the next node can
     * point at a different branch than the route takes, and a search that picks branches by direction
     * then reads a signal belonging to another line - or fails to align with either branch and reports
     * "no signal" while a red light stands plainly ahead. The route already says which edges are being
     * traversed; this asks the graph along <b>those</b> edges, in order, and takes the side of each
     * boundary from the node the route enters it through.
     *
     * @param route     the held route
     * @param fromIndex the index of the route node the vehicle is heading to (0-based), as reported by
     *                  the route follower. The search starts one node back, because a signal can stand
     *                  on the edge the vehicle is currently traversing.
     * @param from      the vehicle's position
     * @param lookahead the furthest a signal may be and still count, in blocks
     */
    default SignalInfo signalAheadAlong(ServerLevel level, PlannedRoute route, int fromIndex, Vec3 from,
                                       double lookahead) {
        return signalAhead(level, from, null, lookahead);
    }

    /**
     * The route a vehicle currently holds, as a sequence of graph nodes.
     *
     * <p>Exist so that a driver can <em>follow</em> the route it was granted instead of steering at the
     * destination in a straight line. Create's own {@code DiscoveredPath} is the same shape - an ordered
     * list of node pairs - and its trains follow it for exactly this reason: the final point of a curved
     * or switch-heavy itinerary is a poor thing to aim at, while the next node is always on the rails.
     *
     * <p>A default returning empty keeps every other implementation compiling; a driver that gets nothing
     * back falls back to driving at the destination and says so in its log.
     */
    default Optional<PlannedRoute> grantedRoute(ServerLevel level, UUID vehicle) {
        return Optional.empty();
    }

    /**
     * Re-drives the switches of a held route that lie within {@code ahead} blocks of {@code from}.
     *
     * <p>Alignment at grant time is not enough on a railway that other vehicles, players or automatic
     * switches also touch: a turnout that was moved after the route was granted would send this vehicle
     * down the wrong line. Re-asserting the states just before each junction is reached is what makes
     * "the route I hold" mean something for the whole journey.
     *
     * @return how many switches were driven, or 0 when no driver handled any of them
     */
    default int alignSwitchesAhead(ServerLevel level, UUID vehicle, Vec3 from, double ahead) {
        return 0;
    }

    /**
     * Lock the track sections of a planned route so no other vehicle is routed into them.
     *
     * @return true when every section could be taken; false means the route must not be used
     */
    boolean lockRoute(ServerLevel level, UUID vehicle, PlannedRoute route);

    /** Hand the sections back. */
    void unlockRoute(ServerLevel level, UUID vehicle);

    /**
     * The reserved section a position falls in, or {@code null} when this network does not track sections.
     *
     * <p>Exists so that a route can be released section by section as the vehicle advances, keeping the stretch it
     * is standing on reserved. Releasing a whole route on arrival - which is what happens without this - leaves the
     * vehicle's own stretch open for another train to be granted while it still sits there.
     *
     * <p>A default returning null keeps every existing implementation compiling, and callers must treat null as
     * "cannot tell" and fall back to releasing nothing rather than guessing.
     */
    default UUID sectionAt(ServerLevel level, Vec3 position) {
        return null;
    }

    /** Destination names the network knows about (Create stations and address plates). */
    List<String> knownDestinations(ServerLevel level);

    /** World position of a named destination, if the network can resolve it. */
    Optional<Vec3> resolveDestination(ServerLevel level, String name);

    /** A path over the track graph. */
    record PlannedRoute(Vec3 from, Vec3 to, List<Vec3> waypoints, List<BlockPos> junctions) {

        public double length() {
            double total = 0.0D;
            Vec3 previous = from;
            for (Vec3 point : waypoints) {
                total += previous.distanceTo(point);
                previous = point;
            }
            return total + previous.distanceTo(to);
        }
    }

    record RouteGrant(boolean granted, String detail, List<BlockPos> switches) {

        public static RouteGrant granted(String detail, List<BlockPos> switches) {
            return new RouteGrant(true, detail, switches == null ? List.of() : switches);
        }

        public static RouteGrant denied(String detail) {
            return new RouteGrant(false, detail, List.of());
        }
    }

    TrackNetwork NONE = new TrackNetwork() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public SignalInfo signalAhead(ServerLevel level, Vec3 from, Vec3 heading, double lookahead) {
            return SignalInfo.NONE;
        }

        @Override
        public Optional<PlannedRoute> planRoute(ServerLevel level, Vec3 from, Vec3 to) {
            return Optional.empty();
        }

        @Override
        public boolean lockRoute(ServerLevel level, UUID vehicle, PlannedRoute route) {
            return false;
        }

        @Override
        public void unlockRoute(ServerLevel level, UUID vehicle) {
        }

        @Override
        public boolean setSwitch(ServerLevel level, BlockPos pos, boolean alternateBranch) {
            return false;
        }

        @Override
        public RouteGrant requestRoute(ServerLevel level, UUID vehicle, String vehicleName, Vec3 from, Vec3 to,
                                       boolean allowLocalSwitches) {
            return RouteGrant.denied("no track network");
        }

        @Override
        public void releaseRoute(ServerLevel level, UUID vehicle) {
        }

        @Override
        public List<String> knownDestinations(ServerLevel level) {
            return List.of();
        }

        @Override
        public Optional<Vec3> resolveDestination(ServerLevel level, String name) {
            return Optional.empty();
        }
    };
}
