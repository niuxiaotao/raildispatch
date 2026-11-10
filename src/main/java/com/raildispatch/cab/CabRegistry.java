package com.raildispatch.cab;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Which cab is currently driving which vehicle.
 *
 * <p>This exists so that two controllers never fight over the same bogie: a reverser only steers
 * when no cab has taken the vehicle, and a dispatch centre only talks to the cab that owns a
 * vehicle rather than to the vehicle directly.
 */
public final class CabRegistry {

    private CabRegistry() {}

    private static final Map<ResourceKey<Level>, Map<UUID, BlockPos>> CONTROLLED = new HashMap<>();

    public static void claim(ServerLevel level, UUID vehicle, BlockPos cabPos) {
        CONTROLLED.computeIfAbsent(level.dimension(), key -> new HashMap<>()).put(vehicle, cabPos);
    }

    public static void release(ServerLevel level, UUID vehicle) {
        Map<UUID, BlockPos> map = CONTROLLED.get(level.dimension());
        if (map != null) {
            map.remove(vehicle);
        }
    }

    public static void releaseAt(ServerLevel level, BlockPos cabPos) {
        Map<UUID, BlockPos> map = CONTROLLED.get(level.dimension());
        if (map != null) {
            map.values().removeIf(cabPos::equals);
        }
    }

    public static Optional<BlockPos> controllerOf(ServerLevel level, UUID vehicle) {
        Map<UUID, BlockPos> map = CONTROLLED.get(level.dimension());
        return map == null ? Optional.empty() : Optional.ofNullable(map.get(vehicle));
    }

    public static boolean isControlled(ServerLevel level, UUID vehicle) {
        return controllerOf(level, vehicle).isPresent();
    }

    /** Forgets everything about a level, used when a server stops. */
    public static void clear(ServerLevel level) {
        CONTROLLED.remove(level.dimension());
    }
}
