package com.raildispatch.api;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * A block that arbitrates routes: the dispatch centre.
 *
 * <p>The track network does the planning (path over the graph, which junctions are involved); the
 * authority decides whether a vehicle may have that route right now, which is where mutual
 * exclusion between vehicles lives. Without an authority in range, a cab locks its own route.
 */
public interface RouteAuthority {

    /** Grants or refuses a planned route for one vehicle. */
    TrackNetwork.RouteGrant request(ServerLevel level, UUID vehicle, String vehicleName,
                                    TrackNetwork.PlannedRoute route);

    /** Gives a route back once the vehicle has cleared it. */
    void release(UUID vehicle);

    /** How many routes this authority currently holds. */
    int activeRoutes();
}
