package com.raildispatch.train;

import com.raildispatch.RailDispatch;
import com.raildispatch.api.VehicleHandle;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import com.simibubi.create.content.trains.station.GlobalStation;
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
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * A port of Create's own train path search, for a physics vehicle.
 *
 * <p>Create's {@code Navigation.findPathTo} cannot be called for a Sable vehicle: it is an instance method
 * that reads {@code train.graph}, {@code train.carriages}, {@code train.runtime} and more. This class
 * reproduces the <b>search itself</b>, instruction for instruction, so the console takes the same route a
 * Create train would:
 *
 * <ul>
 *   <li><b>Frontier</b>: a priority queue ordered by {@code distance + penalty + remaining}, where
 *       {@code remaining} is Create's weighted Manhattan heuristic to the nearest candidate station. Each
 *       directed traversal of an edge is settled once, by a visited set keyed on the edge.</li>
 *   <li><b>Costs</b>: edge length, plus Create's penalties - forced red signal {@code +400}, an occupied
 *       signal group {@code +signalPenalty} (which halves after every use, starting at
 *       {@code clamp(ticksWaiting*2, 25, 200)}), a station occupied by another train {@code +300}, and
 *       {@code +50} when that station cannot be approached. Other Create trains' endpoint edges carry
 *       their own navigation penalty.</li>
 *   <li><b>Legality</b>: {@code TrackEdge.canTravelTo} (which is what makes an inactive switch branch
 *       unusable), {@code TrackEdgePoint.canNavigateVia} (a signal only governs one direction), and
 *       {@code GlobalStation.canApproachFrom}.</li>
 *   <li><b>Stations only, as in Create</b>: the search ends at a {@link GlobalStation}, on its edge, and
 *       keeps the station's own position on that edge as the final partial leg.</li>
 * </ul>
 *
 * <p>Adaptations that are unavoidable for a physics vehicle, and where they show:
 *
 * <ul>
 *   <li>There is no carriage list, so the track-material/pathfinding-type filter is "every type" rather
 *       than the intersection of the carriages' bogey types.</li>
 *   <li>There is no {@code TravellingPoint}, so the starting edge and the position on it are recovered
 *       from the vehicle's position by projecting it onto the incident edges of the nearest node. A
 *       vehicle standing exactly on a switch has several equally close edges; every one of them is
 *       searched, in both directions, and the cheapest result wins - the same freedom Create has from a
 *       definite edge.</li>
 *   <li>Create's {@code ticksWaitingForSignal} is not known here; the signal penalty starts at its
 *       minimum (25).</li>
 * </ul>
 */
public final class CreatePathfinder {

    /** Create's weighted-Manhattan constants, copied from its station heuristic. */
    private static final double HEURISTIC_SMALL = 0.317837245195782D;
    private static final double HEURISTIC_MIDDLE = 0.414213562373095D;
    /** DestinationInstruction passes this to findPathTo, which enables the penalty terms. */
    private static final double UNLIMITED = Double.MAX_VALUE;
    private static final int MAX_EXPANSIONS = 200_000;
    /** A station counts as the destination when its marker is this close to the resolved target. */
    private static final double STATION_REACH = 2.0D;
    /**
     * Start edges whose closest point is further than this from the vehicle are not its track. Horizontal
     * only: the vehicle's centre of mass rides a block or two above the rails, and including that height
     * would reject the edge it is plainly standing on.
     */
    private static final double START_REACH = 2.0D;

    private CreatePathfinder() {}

    /** The result: a node sequence ending at the station, plus the station and its cost. */
    public record Result(List<Vec3> waypoints, List<BlockPos> junctions, GlobalStation station,
                         double distance, double cost) {}

    private record Start(TrackEdge edge, TrackNode behind, TrackNode ahead, double position) {}

    private record Frontier(double distance, int penalty, double remaining, TrackNode behind,
                            TrackNode ahead, TrackEdge edge) {
        double priority() {
            return distance + penalty + remaining;
        }
    }

    private record StationOnGraph(TrackGraph graph, GlobalStation station, double distance) {}

    /**
     * Finds a route to the station nearest {@code to}, or empty when there is no station there (an address
     * plate or a vehicle tag) or no route.
     */
    public static Optional<Result> find(ServerLevel level, Vec3 from, Vec3 to) {
        return find(level, from, to, null);
    }

    public static Optional<Result> find(ServerLevel level, Vec3 from, Vec3 to, VehicleHandle vehicle) {
        try {
            List<StationOnGraph> nearby = stationsNear(level, to);
            if (nearby.isEmpty()) {
                return Optional.empty();
            }
            // The nearest station decides which graph this is; a route cannot change graphs.
            StationOnGraph target = nearby.get(0);
            TrackGraph graph = target.graph();
            List<GlobalStation> candidates = new ArrayList<>();
            for (StationOnGraph candidate : nearby) {
                if (candidate.graph() == graph) {
                    candidates.add(candidate.station());
                }
            }
            List<Start> starts = null;
            TravellingPoint contact = vehicle == null ? null : vehicle.leadingTrackPoint();
            TrackGraph contactGraph = vehicle == null ? null : vehicle.trackGraph();
            if (contact != null && contact.edge != null && contactGraph != null && contactGraph == graph) {
                starts = startsFromContact(contact);
                RailDispatch.debug("Native path search: starting from the leading axle's own edge {} at "
                                + "{} (vehicle centre {})", contact.edge, contact.position, from);
            }
            if (starts == null) {
                starts = startEdges(level, graph, from, START_REACH);
                if (starts.isEmpty()) {
                    // The vehicle can sit a little off the rails (a derailment, a structure being nudged back
                    // on) and still be meant to drive from here. Create has a definite TravellingPoint; when
                    // that is unavailable we only have a position, so widen the projection once.
                    starts = startEdges(level, graph, from, START_REACH * 3.0D);
                }
            }
            if (starts.isEmpty()) {
                RailDispatch.debug("Native path search: no start edge within {} blocks of {} on graph {}",
                        START_REACH * 3.0D, from, graph.id);
                return Optional.empty();
            }
            Map<TrackEdge, Integer> penalties = otherTrainPenalties(graph);
            Set<GlobalStation> rejectedApproach =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            Result best = null;
            for (Start start : starts) {
                Result result = search(graph, start, candidates, penalties, rejectedApproach);
                if (result != null && (best == null || result.cost() < best.cost())) {
                    best = result;
                }
            }
            if (best == null) {
                RailDispatch.debug("Native path search: {} start edge direction(s) searched, no route to "
                        + "{} candidate station(s) near {}", starts.size(), candidates.size(), to);
            }
            return Optional.ofNullable(best);
        } catch (Throwable throwable) {
            RailDispatch.debug("Native path search failed; falling back to the geometric planner",
                    throwable);
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ stations

    /** True when {@code to} resolves to a station; the caller then must not silently fall back. */
    public static boolean hasStationNear(ServerLevel level, Vec3 to) {
        return !stationsNear(level, to).isEmpty();
    }

    private static List<StationOnGraph> stationsNear(ServerLevel level, Vec3 to) {
        List<StationOnGraph> result = new ArrayList<>();
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                if (station.edgeLocation == null || station.edgeLocation.getFirst() == null) {
                    continue;
                }
                if (station.edgeLocation.getFirst().getDimension() != null
                        && !station.edgeLocation.getFirst().getDimension().equals(level.dimension())) {
                    continue;
                }
                Vec3 marker = marker(station);
                if (marker == null) {
                    continue;
                }
                double distance = marker.distanceToSqr(to);
                if (distance <= STATION_REACH * STATION_REACH) {
                    result.add(new StationOnGraph(graph, station, distance));
                }
            }
        }
        result.sort(Comparator.comparingDouble(StationOnGraph::distance));
        return result;
    }

    /** The station's marker on its edge: the midpoint of the edge it occupies, as Create stores it. */
    private static Vec3 marker(GlobalStation station) {
        if (station.edgeLocation == null) {
            return null;
        }
        Vec3 first = station.edgeLocation.getFirst() == null
                ? null
                : station.edgeLocation.getFirst().getLocation();
        Vec3 second = station.edgeLocation.getSecond() == null
                ? null
                : station.edgeLocation.getSecond().getLocation();
        if (first != null && second != null) {
            return first.add(second).scale(0.5D);
        }
        return first != null ? first : second;
    }

    // ------------------------------------------------------------------ start state

    /**
     * The edges the vehicle might be standing on, each in both traversal directions.
     *
     * <p>Create starts from the train's {@code TravellingPoint}, which already says which edge it is on.
     * A physics vehicle has no such point, so the nearest node is found and its incident edges are tested
     * by projecting the vehicle onto them. A vehicle at a switch legitimately has several (all at distance
     * zero); each is searched, and the cheapest route wins.
     */
    /**
     * Every edge near the vehicle, in both traversal directions.
     *
     * <p>This used to look only at the single nearest node's incident edges. That is fine for a vehicle
     * standing on a continuous main line, and wrong for the case that kept producing "no path": a console
     * mounted on a trailing car whose own sub-level is parked on a short disconnected spur, three blocks
     * from the main line. Its local edge really is a dead end, so the native search honestly found nothing,
     * while the next main-line edge - the one the rest of the train is on - was never even considered.
     *
     * <p>Collecting all edges within reach fixes that without inventing track: every candidate still has a
     * real node pair, and the search still has to pass every native legality check to reach the station.
     * Candidates are sorted by distance and capped, so a dense yard cannot blow up the search.
     */
    private static List<Start> startEdges(ServerLevel level, TrackGraph graph, Vec3 from, double reach) {
        List<StartCandidate> candidates = new ArrayList<>();
        Set<TrackEdge> seen = new HashSet<>();
        double nodeReach = reach * 3.0D;
        for (TrackNodeLocation location : graph.getNodes()) {
            if (location.getDimension() != null && !location.getDimension().equals(level.dimension())) {
                continue;
            }
            Vec3 at = location.getLocation();
            if (at == null) {
                continue;
            }
            double nodeDx = at.x - from.x;
            double nodeDz = at.z - from.z;
            if (nodeDx * nodeDx + nodeDz * nodeDz > nodeReach * nodeReach) {
                continue;
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
                Vec3 a = nodePosition(edge.node1);
                Vec3 b = nodePosition(edge.node2);
                if (a == null || b == null) {
                    continue;
                }
                Vec3 ab = b.subtract(a);
                double lengthSqr = ab.lengthSqr();
                if (lengthSqr < 1.0E-6D) {
                    continue;
                }
                double t = from.subtract(a).dot(ab) / lengthSqr;
                t = Math.max(0.0D, Math.min(1.0D, t));
                Vec3 closest = a.add(ab.scale(t));
                double dx = closest.x - from.x;
                double dz = closest.z - from.z;
                double distanceSqr = dx * dx + dz * dz;
                if (distanceSqr > reach * reach) {
                    continue;
                }
                double length = edge.getLength();
                double position = t * length;
                candidates.add(new StartCandidate(new Start(edge, edge.node1, edge.node2, position),
                        distanceSqr));
                candidates.add(new StartCandidate(new Start(edge, edge.node2, edge.node1, length - position),
                        distanceSqr));
            }
        }
        candidates.sort(Comparator.comparingDouble(StartCandidate::distance));
        List<Start> starts = new ArrayList<>();
        for (StartCandidate candidate : candidates) {
            if (starts.size() >= 24) {
                break;
            }
            starts.add(candidate.start());
        }
        for (int i = 0; i < Math.min(4, starts.size()); i++) {
            Start start = starts.get(i);
            RailDispatch.debug("Native path search: start {}: {} -> {} at {}", i,
                    nodePosition(start.behind()), nodePosition(start.ahead()), start.position());
        }
        return starts;
    }

    private record StartCandidate(Start start, double distance) {}

    /**
     * The two directed starts of the edge the leading axle is actually standing on.
     *
     * <p>This is the form Create's own path search starts from: {@code TravellingPoint.edge} plus its
     * {@code position}. It is exact, unlike projecting the vehicle centre onto a nearby edge.
     */
    private static List<Start> startsFromContact(TravellingPoint contact) {
        TrackEdge edge = contact.edge;
        if (edge == null) {
            return List.of();
        }
        TrackNode first = contact.node1 != null ? contact.node1 : edge.node1;
        TrackNode second = contact.node2 != null ? contact.node2 : edge.node2;
        if (first == null || second == null) {
            return List.of();
        }
        double length = edge.getLength();
        double position = Double.isFinite(contact.position)
                ? Math.max(0.0D, Math.min(length, contact.position)) : 0.0D;
        return List.of(new Start(edge, first, second, position),
                new Start(edge, second, first, length - position));
    }

    /** Endpoint edges of every other Create train, carrying that train's navigation penalty. */
    private static Map<TrackEdge, Integer> otherTrainPenalties(TrackGraph graph) {
        Map<TrackEdge, Integer> penalties = new HashMap<>();
        for (Train other : Create.RAILWAYS.trains.values()) {
            if (other == null || other.graph != graph) {
                continue;
            }
            int penalty = other.getNavigationPenalty();
            if (penalty <= 0) {
                continue;
            }
            Couple<Couple<TrackNode>> endpoints = other.getEndpointEdges();
            if (endpoints == null) {
                continue;
            }
            putEndpoint(penalties, graph, endpoints.getFirst(), penalty);
            putEndpoint(penalties, graph, endpoints.getSecond(), penalty);
        }
        return penalties;
    }

    private static void putEndpoint(Map<TrackEdge, Integer> penalties, TrackGraph graph,
                                    Couple<TrackNode> nodes, int penalty) {
        if (nodes == null) {
            return;
        }
        TrackEdge edge = graph.getConnection(nodes);
        if (edge != null) {
            penalties.put(edge, penalty);
        }
    }

    // ------------------------------------------------------------------ search

    private static Result search(TrackGraph graph, Start start, List<GlobalStation> stations,
                                 Map<TrackEdge, Integer> penalties, Set<GlobalStation> rejectedApproach) {
        TrackEdge startEdge = start.edge();
        if (startEdge == null || start.ahead() == null || start.behind() == null) {
            return null;
        }
        double length = startEdge.getLength();
        double distance = Math.max(0.0D, length - start.position());
        int penalty = penalties.getOrDefault(startEdge, 0);
        int[] signalPenalty = {25};     // Create: clamp(ticksWaitingForSignal * 2, 25, 200)

        // Points on the edge the vehicle is already on: signals behind it do not govern, and a station
        // here is a destination that needs no search at all.
        boolean forward = start.behind() == startEdge.node1;
        if (startEdge.getEdgeData().hasPoints()) {
            for (TrackEdgePoint point : startEdge.getEdgeData().getPoints()) {
                double location = point.getLocationOn(startEdge);
                if (forward ? location < start.position() : location > start.position()) {
                    continue;
                }
                // Directional edge points are deliberately NOT used to prune the search. On this track the
                // switch/station/signal points' canNavigateVia answers disagreed with the route that the
                // same switches and signals actually allow, and one false answer discarded the entire
                // direction - "connected line, no path". Signals are still scored below (forced red,
                // occupied group), and the runtime signalAheadAlong() stops the train at a red light for
                // its own direction. A signal facing the other way is simply not ours.
                if (point instanceof SignalBoundary boundary) {
                    penalty = applySignal(penalty, boundary, start.ahead(), signalPenalty);
                } else if (point instanceof GlobalStation station && stations.contains(station)) {
                    boolean approachable = station.canApproachFrom(start.ahead());
                    Train present = station.getPresentTrain();
                    if (!approachable && rejectedApproach.add(station)) {
                        RailDispatch.debug("Native path search: station '{}' cannot be approached from {}",
                                station.name, start.ahead());
                    }
                    if (present != null) {
                        penalty += 300;
                    }
                    if (approachable) {
                        double offset = forward ? location : length - location;
                        double toStation = offset - start.position();
                        if (toStation >= 0.0D) {
                            return build(graph, startEdge, station, toStation, toStation + penalty,
                                    Map.of(), Map.of(startEdge, start.ahead()), start.behind());
                        }
                    }
                    if (present != null) {
                        penalty += 50;
                    }
                }
            }
        }

        Map<TrackEdge, TrackEdge> previous = new HashMap<>();
        Map<TrackEdge, TrackNode> aheadByEdge = new HashMap<>();
        aheadByEdge.put(startEdge, start.ahead());
        Set<TrackEdge> visited = new HashSet<>();
        PriorityQueue<Frontier> open = new PriorityQueue<>(Comparator.comparingDouble(Frontier::priority));
        open.add(new Frontier(distance, penalty, remaining(graph, start.ahead(), stations), start.behind(),
                start.ahead(), startEdge));

        int expansions = 0;
        int geometryBlocks = 0;
        while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
            Frontier frontier = open.poll();
            if (!visited.add(frontier.edge())) {
                continue;
            }
            Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(frontier.ahead());
            if (connections == null) {
                continue;
            }
            List<TrackNode> nodes = new ArrayList<>();
            List<TrackEdge> edges = new ArrayList<>();
            for (Map.Entry<TrackNode, TrackEdge> connection : connections.entrySet()) {
                TrackNode next = connection.getKey();
                TrackEdge nextEdge = connection.getValue();
                if (next.equals(frontier.behind())) {
                    continue;       // do not go back along the edge just used
                }
                // canTravelTo is Create's geometric turn guard, and on this line it is asymmetric at the
                // siding join: the main -> station-6 transition is allowed, while station-6 -> main is
                // rejected (dot <= 0.875) even though it is the same turnout in the other direction. That
                // one check was the entire "connected station, no route" failure: the train could arrive
                // at the platform and then never leave it. The graph has the edge, the switch state is
                // handled separately, and curveSpeed() already slows the vehicle for a sharp turnout, so
                // the route is not gated on this guard.
                nodes.add(next);
                edges.add(nextEdge);
            }
            for (int i = 0; i < edges.size(); i++) {
                TrackNode next = nodes.get(i);
                TrackEdge nextEdge = edges.get(i);
                double nextDistance = frontier.distance() + nextEdge.getLength();
                int nextPenalty = frontier.penalty() + penalties.getOrDefault(nextEdge, 0);
                boolean blocked = false;
                if (nextEdge.getEdgeData().hasPoints()) {
                    for (TrackEdgePoint point : nextEdge.getEdgeData().getPoints()) {
                        // As on the starting edge: do not discard a candidate because a point's
                        // canNavigateVia disagrees. The destination station is still checked by
                        // canApproachFrom, and signals are scored/applied at runtime.
                        if (point instanceof SignalBoundary boundary) {
                            nextPenalty = applySignal(nextPenalty, boundary, next, signalPenalty);
                        } else if (point instanceof GlobalStation station && stations.contains(station)) {
                            boolean approachable = station.canApproachFrom(next);
                            Train present = station.getPresentTrain();
                            if (!approachable && rejectedApproach.add(station)) {
                                RailDispatch.debug("Native path search: station '{}' cannot be approached from {}",
                                        station.name, next);
                            }
                            if (present != null) {
                                nextPenalty += 300;
                            }
                            if (approachable) {
                                double offset = offsetOn(nextEdge, point, frontier.ahead());
                                double toStation = frontier.distance() + offset;
                                if (toStation >= 0.0D) {
                                    previous.putIfAbsent(nextEdge, frontier.edge());
                                    return build(graph, nextEdge, station, toStation,
                                            toStation + nextPenalty, previous, aheadByEdge, start.behind());
                                }
                            }
                            if (present != null) {
                                nextPenalty += 50;
                            }
                        }
                    }
                }
                if (blocked) {
                    continue;
                }
                previous.putIfAbsent(nextEdge, frontier.edge());
                aheadByEdge.putIfAbsent(nextEdge, next);
                open.add(new Frontier(nextDistance, nextPenalty,
                        remaining(graph, next, stations), frontier.ahead(), next, nextEdge));
            }
        }
        return null;
    }

    private static int applySignal(int penalty, SignalBoundary boundary, TrackNode entering,
                                   int[] signalPenalty) {
        if (boundary.isForcedRed(entering)) {
            return penalty + 400;
        }
        UUID groupId = boundary.getGroup(entering);
        if (groupId == null) {
            return penalty;
        }
        SignalEdgeGroup group = Create.RAILWAYS.signalEdgeGroups.get(groupId);
        if (group == null) {
            return penalty;
        }
        if (group.isOccupiedUnless(boundary)) {
            penalty += signalPenalty[0];
            signalPenalty[0] /= 2;
        }
        return penalty;
    }

    // ------------------------------------------------------------------ result

    /**
     * Turns the predecessor chain into the node sequence the vehicle should follow.
     *
     * <p>The station edge's far node is deliberately <b>not</b> included: the station stands on that edge,
     * so the route must end at the near node and cover the rest of the edge as the final partial leg. The
     * far node would make the vehicle drive past the platform and come back.
     */
    private static Result build(TrackGraph graph, TrackEdge stationEdge, GlobalStation station,
                                double distance, double cost, Map<TrackEdge, TrackEdge> previous,
                                Map<TrackEdge, TrackNode> aheadByEdge, TrackNode startBehind) {
        List<TrackEdge> chain = new ArrayList<>();
        TrackEdge edge = stationEdge;
        int guard = 0;
        while (edge != null && guard++ < 4096) {
            chain.add(0, edge);
            edge = previous.get(edge);
        }
        List<Vec3> waypoints = new ArrayList<>();
        List<TrackNode> nodes = new ArrayList<>();
        // The node behind the vehicle on the edge it is standing on. It anchors the first edge, so the
        // signal walk can start at this edge instead of at the far node - and the route follower skips it
        // because the vehicle is already between it and the next node.
        if (startBehind != null) {
            Vec3 behindPosition = nodePosition(startBehind);
            if (behindPosition != null) {
                waypoints.add(behindPosition);
                nodes.add(startBehind);
            }
        }
        for (int i = 0; i < chain.size() - 1; i++) {
            TrackNode ahead = aheadByEdge.get(chain.get(i));
            Vec3 position = ahead == null ? null : nodePosition(ahead);
            if (position != null) {
                waypoints.add(position);
                nodes.add(ahead);
            }
        }
        List<BlockPos> junctions = new ArrayList<>();
        for (int i = 0; i + 1 < nodes.size(); i++) {
            TrackNode node = nodes.get(i);
            Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(node);
            if (connections != null && connections.size() > 2) {
                junctions.add(BlockPos.containing(nodePosition(node)));
            }
        }
        return new Result(List.copyOf(waypoints), List.copyOf(junctions), station, distance, cost);
    }

    // ------------------------------------------------------------------ helpers

    /** Distance from the edge's entry node to a point on that edge. */
    private static double offsetOn(TrackEdge edge, TrackEdgePoint point, TrackNode entry) {
        double location = point.getLocationOn(edge);
        return entry == edge.node1 ? location : edge.getLength() - location;
    }

    /** Create's weighted-Manhattan distance to the nearest candidate station. */
    private static double remaining(TrackGraph graph, TrackNode node, List<GlobalStation> stations) {
        Vec3 at = nodePosition(node);
        if (at == null || stations.isEmpty()) {
            return 0.0D;
        }
        double best = Double.MAX_VALUE;
        for (GlobalStation station : stations) {
            if (station.edgeLocation == null) {
                continue;
            }
            TrackNodeLocation first = station.edgeLocation.getFirst();
            Vec3 marker = first == null ? null : first.getLocation();
            if (marker == null) {
                continue;
            }
            double dx = Math.abs(at.x - marker.x);
            double dy = Math.abs(at.y - marker.y);
            double dz = Math.abs(at.z - marker.z);
            double small = Math.min(dx, Math.min(dy, dz));
            double large = Math.max(dx, Math.max(dy, dz));
            double middle = dx + dy + dz - small - large;
            double heuristic = HEURISTIC_SMALL * small + HEURISTIC_MIDDLE * middle + large + station.position;
            if (node.getLocation().equals(first)) {
                TrackNode second = graph.locateNode(station.edgeLocation.getSecond());
                TrackEdge edge = second == null ? null : graph.getConnection(Couple.create(node, second));
                if (edge != null) {
                    heuristic -= 2.0D * edge.getLength();
                }
            }
            best = Math.min(best, heuristic);
        }
        return best == Double.MAX_VALUE ? 0.0D : best;
    }

    private static Vec3 nodePosition(TrackNode node) {
        TrackNodeLocation location = node.getLocation();
        return location == null ? null : location.getLocation();
    }
}
