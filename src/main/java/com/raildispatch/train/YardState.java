package com.raildispatch.train;

import com.raildispatch.api.RailServices;
import com.raildispatch.api.VehicleHandle;
import com.raildispatch.block.entity.AutopilotConsoleBlockEntity;
import com.raildispatch.block.entity.PhysicsCabBlockEntity;
import com.raildispatch.cab.CabRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Shared state for the yard features: which sub-levels form one train, whether that train is under
 * traction, and which destination its Simulated nameplates carry.
 *
 * <p>"Free rolling" means no controller is driving it: no physics cab is engaged, no autopilot console
 * is engaged, and no {@link CabRegistry} entry owns any of its coupled sub-levels. A free wagon may still
 * have an idle cab or console fitted; only an active controller makes it a train that must keep its route.
 */
public final class YardState {

    private YardState() {}

    /** The root sub-level plus every coupled sub-level, resolved and de-duplicated. */
    public static List<VehicleHandle> handles(ServerLevel level, VehicleHandle root) {
        List<VehicleHandle> result = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        add(level, root, result, seen);
        for (UUID id : root.connectedVehicleIds()) {
            RailServices.physics().vehicleById(level, id)
                    .ifPresent(handle -> add(level, handle, result, seen));
        }
        return result;
    }

    private static void add(ServerLevel level, VehicleHandle handle, List<VehicleHandle> out, Set<UUID> seen) {
        if (handle != null && seen.add(handle.id())) {
            out.add(handle);
        }
    }

    public static boolean isFree(ServerLevel level, VehicleHandle vehicle) {
        for (VehicleHandle handle : handles(level, vehicle)) {
            if (CabRegistry.controllerOf(level, handle.id()).isPresent()) {
                return false;
            }
            for (BlockEntity actor : handle.actorsOfType(BlockEntity.class)) {
                if (actor instanceof AutopilotConsoleBlockEntity console && console.isEngaged()) {
                    return false;
                }
                if (actor instanceof PhysicsCabBlockEntity cab && cab.isEngaged()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The first non-blank name on a Simulated nameplate fitted to this train. */
    public static Optional<String> nameplate(ServerLevel level, VehicleHandle vehicle) {
        for (VehicleHandle handle : handles(level, vehicle)) {
            for (String text : handle.nameplates()) {
                if (text != null && !text.isBlank()) {
                    return Optional.of(text.trim());
                }
            }
        }
        return Optional.empty();
    }

    /** The hand-held terminal assignment for this train, with the legacy block as a fallback. */
    public static Optional<com.raildispatch.api.TerminalInfo> terminal(ServerLevel level, VehicleHandle vehicle) {
        for (VehicleHandle handle : handles(level, vehicle)) {
            com.raildispatch.api.TerminalInfo assigned =
                    com.raildispatch.data.YardAssignments.get(level).get(handle.id());
            if (assigned.destination() != null && !assigned.destination().isBlank()) {
                return Optional.of(assigned);
            }
            for (com.raildispatch.api.TerminalInfo info : handle.terminals()) {
                if (info.destination() != null && !info.destination().isBlank()) {
                    return Optional.of(info);
                }
            }
        }
        return Optional.empty();
    }
}
