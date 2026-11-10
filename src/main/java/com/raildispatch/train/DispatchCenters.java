package com.raildispatch.train;

import com.raildispatch.api.RouteAuthority;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Which dispatch centres are currently loaded, so a cab can find one without scanning chunks.
 *
 * <p>Searching for the nearest authority by walking loaded block entities would be O(chunks) per
 * request; instead every dispatch centre announces itself when its chunk loads and withdraws when it
 * unloads, which is the same lifecycle the block entity already has.
 */
public final class DispatchCenters {

    private DispatchCenters() {}

    private record Entry(ResourceKey<Level> dimension, BlockPos pos, RouteAuthority authority) {}

    private static final List<Entry> CENTRES = new ArrayList<>();

    public static void register(ServerLevel level, BlockPos pos, RouteAuthority authority) {
        unregister(level.dimension(), pos);
        CENTRES.add(new Entry(level.dimension(), pos.immutable(), authority));
    }

    public static void unregister(ResourceKey<Level> dimension, BlockPos pos) {
        CENTRES.removeIf(entry -> entry.dimension().equals(dimension) && entry.pos().equals(pos));
    }

    /** The closest dispatch centre within {@code range}, if any is loaded. */
    public static Optional<RouteAuthority> nearest(ServerLevel level, net.minecraft.world.phys.Vec3 from,
                                                   double range) {
        RouteAuthority best = null;
        double bestDistance = range * range;
        for (Entry entry : CENTRES) {
            if (!entry.dimension().equals(level.dimension())) {
                continue;
            }
            // Verify against the level rather than trusting the list: a centre that was broken or
            // unloaded may still be listed if a removal hook was missed, and handing out routes for a
            // block that no longer exists would deny them to everybody else. This makes the registry
            // self-healing.
            if (level.getBlockEntity(entry.pos()) != entry.authority()) {
                continue;
            }
            double distance = net.minecraft.world.phys.Vec3.atCenterOf(entry.pos()).distanceToSqr(from);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = entry.authority();
            }
        }
        return Optional.ofNullable(best);
    }

    /** Number of centres currently loaded, for diagnostics. */
    public static int loaded() {
        return CENTRES.size();
    }

    /**
     * Tells every loaded centre in a dimension to drop a vehicle's route.
     *
     * <p>Necessary because a route is released from the vehicle's side, and the vehicle has no idea
     * which centre arbitrated it. Several centres may be loaded at once, and only the one holding the
     * route will see any change.
     */
    public static void release(ServerLevel level, java.util.UUID vehicle) {
        for (Entry entry : CENTRES) {
            if (entry.dimension().equals(level.dimension())) {
                entry.authority().release(vehicle);
            }
        }
    }
}
