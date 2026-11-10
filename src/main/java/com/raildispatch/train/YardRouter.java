package com.raildispatch.train;

import com.raildispatch.RailDispatch;
import com.raildispatch.api.RailServices;
import com.raildispatch.api.TrackNetwork;
import com.raildispatch.api.VehicleHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Routes free wagons by the nameplate fitted to them.
 *
 * <p>A car with a Simulated nameplate ("to 2") and no engaged cab or console is a free-rolling wagon.
 * Every second the router matches that text against the signs on Create stations and drives the turnouts
 * on the route, so a hump or gravity yard sends the wagon to the platform whose sign carries the same
 * text. A vehicle under traction is deliberately left alone: its own console or cab already owns its
 * switches, and the yard must not fight it.
 */
public final class YardRouter {

    private YardRouter() {}

    private static int tickCounter;
    private static final Map<UUID, String> LAST_STATUS = new HashMap<>();

    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter % 20 != 0) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (!RailServices.track().isAvailable()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            try {
                routeLevel(level);
            } catch (Throwable throwable) {
                RailDispatch.LOGGER.warn("Yard routing failed in {}", level.dimension(), throwable);
            }
        }
    }

    private static void routeLevel(ServerLevel level) {
        List<Candidate> candidates = new ArrayList<>();
        for (VehicleHandle vehicle : RailServices.physics().vehicles(level)) {
            Optional<com.raildispatch.api.TerminalInfo> terminal = YardState.terminal(level, vehicle);
            boolean free = YardState.isFree(level, vehicle);
            if (terminal.isPresent() && !free) {
                logOnce(vehicle, "traction:" + terminal.get().destination(),
                        "Yard: vehicle {} terminal destination '" + terminal.get().destination()
                                + "' (service '" + terminal.get().service() + "', consist '"
                                + terminal.get().consist() + "') is under traction; "
                                + "the yard will not move its switches");
                continue;
            }
            if (!free) {
                continue;
            }
            if (terminal.isEmpty()) {
                logOnce(vehicle, "no-terminal", "Yard: free vehicle {} has no yard terminal with a "
                        + "destination (place a Yard Terminal on the car and set its destination)");
                continue;
            }
            String destination = terminal.get().destination();
            Optional<StationSigns.StationTarget> target = StationSigns.resolve(level, destination,
                    vehicle.trackGraph());
            if (target.isEmpty()) {
                logOnce(vehicle, "no-sign:" + destination,
                        "Yard: free vehicle {} terminal destination '" + destination
                                + "' has no matching station sign (attach a sign with exactly that text "
                                + "to the station block itself)");
                continue;
            }
            Vec3 from = vehicle.position();
            Optional<TrackNetwork.PlannedRoute> route = RailServices.track()
                    .planRoute(level, from, target.get().marker(), vehicle);
            if (route.isEmpty()) {
                logOnce(vehicle, "no-route:" + destination,
                        "Yard: free vehicle {} terminal destination '" + destination
                                + "' found a station sign but no track route to it (vehicle graph "
                                + (vehicle.trackGraph() == null ? "none" : vehicle.trackGraph().id)
                                + ", station graph " + target.get().graphId()
                                + "; the car must be on the same connected track graph)");
                continue;
            }
            candidates.add(new Candidate(vehicle, destination, target.get(), route.get(), terminal.get()));
        }

        // Several free cars can plan over the same junction. Aligning every route in a row made the
        // switches oscillate: A's route set a switch one way, B's route set it back in the same second,
        // so whichever car was not chosen saw its turnouts left wrong. Each junction is therefore owned
        // by the car physically closest to it; only that car's route may drive it this pass.
        Map<BlockPos, Candidate> owners = new HashMap<>();
        Map<BlockPos, Double> ownerDistance = new HashMap<>();
        for (Candidate candidate : candidates) {
            Vec3 position = candidate.vehicle().position();
            for (BlockPos junction : candidate.route().junctions()) {
                double distance = position.distanceToSqr(Vec3.atCenterOf(junction));
                if (distance < ownerDistance.getOrDefault(junction, Double.MAX_VALUE)) {
                    ownerDistance.put(junction, distance);
                    owners.put(junction, candidate);
                }
            }
        }

        for (Candidate candidate : candidates) {
            List<BlockPos> mine = new ArrayList<>();
            for (BlockPos junction : candidate.route().junctions()) {
                if (owners.get(junction) == candidate) {
                    mine.add(junction);
                }
            }
            int driven = 0;
            if (!mine.isEmpty()) {
                TrackNetwork.PlannedRoute filtered = new TrackNetwork.PlannedRoute(
                        candidate.route().from(), candidate.route().to(),
                        candidate.route().waypoints(), mine);
                driven = RailServices.track().alignRoute(level, filtered);
            }
            logOnce(candidate.vehicle(),
                    "routed:" + candidate.destination() + ":" + candidate.target().station().name
                            + ":" + mine.size() + ":" + driven,
                    "Yard: free vehicle {} terminal destination '" + candidate.destination()
                            + "' (service '" + candidate.terminal().service() + "', consist '"
                            + candidate.terminal().consist() + "') routed to station sign '"
                            + candidate.destination() + "' on station '"
                            + candidate.target().station().name + "' (" + driven
                            + " switch(es), " + mine.size() + " owned junction(s))");
        }
    }

    private record Candidate(VehicleHandle vehicle, String destination,
                             StationSigns.StationTarget target,
                             TrackNetwork.PlannedRoute route,
                             com.raildispatch.api.TerminalInfo terminal) {}

    private static void logOnce(VehicleHandle vehicle, String status, String message) {
        if (!status.equals(LAST_STATUS.get(vehicle.id()))) {
            LAST_STATUS.put(vehicle.id(), status);
            RailDispatch.debug(message, vehicle.id());
        }
    }
}
