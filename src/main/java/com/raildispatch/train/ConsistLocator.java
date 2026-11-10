package com.raildispatch.train;

import com.raildispatch.api.CouplerInfo;
import com.raildispatch.api.RailServices;
import com.raildispatch.api.TerminalInfo;
import com.raildispatch.api.VehicleHandle;
import com.raildispatch.data.YardAssignments;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Finds a physically coupled consist by service number and picks one of its free automatic couplers.
 *
 * <p>A consist has a coupler at both ends of every car. The valid targets are the unconnected couplers
 * on the consist, and among those the one whose end is nearest to one of the locomotive's own free
 * couplers. The route ends just outside that coupler, so the train approaches from the nearest side
 * instead of aiming at the middle of a car.
 */
public final class ConsistLocator {

    private ConsistLocator() {}

    public record Target(List<UUID> vehicles, Vec3 centre, String service) {
    }

    public static Optional<Target> byService(ServerLevel level, String service) {
        if (service == null || service.isBlank()) {
            return Optional.empty();
        }
        Set<String> seen = new HashSet<>();
        for (VehicleHandle vehicle : RailServices.physics().vehicles(level)) {
            List<VehicleHandle> group = YardState.handles(level, vehicle);
            if (group.size() < 2) {
                continue;
            }
            List<UUID> ids = new ArrayList<>();
            for (VehicleHandle handle : group) {
                ids.add(handle.id());
            }
            ids.sort(UUID::compareTo);
            String key = ids.get(0).toString();
            if (!seen.add(key)) {
                continue;
            }
            String found = "";
            for (UUID id : ids) {
                TerminalInfo info = YardAssignments.get(level).get(id);
                if (!info.service().isBlank()) {
                    found = info.service();
                    break;
                }
            }
            if (found.equalsIgnoreCase(service)) {
                Vec3 centre = Vec3.ZERO;
                for (VehicleHandle handle : group) {
                    centre = centre.add(handle.position());
                }
                centre = centre.scale(1.0D / group.size());
                return Optional.of(new Target(List.copyOf(ids), centre, found));
            }
        }
        return Optional.empty();
    }

    public static Optional<UUID> nearestVehicle(ServerLevel level, String service, Vec3 from) {
        Optional<Target> target = byService(level, service);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        VehicleHandle best = null;
        double bestDistance = Double.MAX_VALUE;
        for (VehicleHandle vehicle : RailServices.physics().vehicles(level)) {
            if (!target.get().vehicles().contains(vehicle.id())) {
                continue;
            }
            double distance = vehicle.position().distanceToSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = vehicle;
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.id());
    }

    /**
     * Picks the free target coupler whose end is closest to one of this train's free couplers.
     * Returns empty when either side has no free coupler.
     */
    public static Optional<CouplerInfo> nearestCoupler(ServerLevel level, String service,
                                                       VehicleHandle locomotive) {
        Optional<Target> target = byService(level, service);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        List<CouplerInfo> ours = new ArrayList<>();
        for (VehicleHandle handle : YardState.handles(level, locomotive)) {
            for (CouplerInfo coupler : handle.couplers()) {
                if (!coupler.connected()) {
                    ours.add(coupler);
                }
            }
        }
        List<CouplerInfo> theirs = new ArrayList<>();
        for (VehicleHandle vehicle : RailServices.physics().vehicles(level)) {
            if (!target.get().vehicles().contains(vehicle.id())) {
                continue;
            }
            for (CouplerInfo coupler : vehicle.couplers()) {
                if (!coupler.connected()) {
                    theirs.add(coupler);
                }
            }
        }
        CouplerInfo best = null;
        double bestDistance = Double.MAX_VALUE;
        for (CouplerInfo targetCoupler : theirs) {
            double distance;
            if (ours.isEmpty()) {
                distance = targetCoupler.worldEnd().distanceToSqr(locomotive.position());
            } else {
                distance = Double.MAX_VALUE;
                for (CouplerInfo own : ours) {
                    distance = Math.min(distance, own.worldEnd().distanceToSqr(targetCoupler.worldEnd()));
                }
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = targetCoupler;
            }
        }
        return best == null ? Optional.empty() : Optional.of(best);
    }

    public static String destination(String service, UUID nearVehicle, CouplerInfo coupler) {
        return "@consist:" + service + "|" + (nearVehicle == null ? "" : nearVehicle) + "|"
                + (coupler == null ? 0L : coupler.pos().asLong());
    }

    public static boolean isConsistTarget(String destination) {
        return destination != null && destination.startsWith("@consist:");
    }

    public static String serviceOf(String destination) {
        if (!isConsistTarget(destination)) {
            return "";
        }
        String rest = destination.substring("@consist:".length());
        int bar = rest.indexOf('|');
        return bar < 0 ? rest : rest.substring(0, bar);
    }

    public static String nearOf(String destination) {
        if (!isConsistTarget(destination)) {
            return "";
        }
        String rest = destination.substring("@consist:".length());
        int first = rest.indexOf('|');
        if (first < 0) {
            return "";
        }
        int second = rest.indexOf('|', first + 1);
        return second < 0 ? rest.substring(first + 1) : rest.substring(first + 1, second);
    }

    public static CouplerInfo couplerOf(ServerLevel level, String destination) {
        if (!isConsistTarget(destination)) {
            return null;
        }
        String rest = destination.substring("@consist:".length());
        int first = rest.indexOf('|');
        int second = first < 0 ? -1 : rest.indexOf('|', first + 1);
        if (second < 0) {
            return null;
        }
        try {
            BlockPos pos = BlockPos.of(Long.parseLong(rest.substring(second + 1)));
            for (VehicleHandle vehicle : RailServices.physics().vehicles(level)) {
                for (CouplerInfo coupler : vehicle.couplers()) {
                    if (coupler.pos().equals(pos)) {
                        return coupler;
                    }
                }
            }
        } catch (NumberFormatException ignored) {
            // No coupler encoded.
        }
        return null;
    }

    public static Optional<Vec3> approachPoint(ServerLevel level, String service, String nearId,
                                               CouplerInfo coupler) {
        if (coupler != null) {
            Vec3 outward = coupler.outward();
            if (outward.lengthSqr() < 1.0E-4D) {
                Vec3 radial = coupler.worldEnd().subtract(
                        byService(level, service).map(Target::centre).orElse(coupler.worldEnd()));
                outward = new Vec3(radial.x, 0.0D, radial.z);
            }
            if (outward.lengthSqr() > 1.0E-4D) {
                return Optional.of(coupler.worldEnd().add(outward.normalize().scale(1.5D)));
            }
            return Optional.of(coupler.worldEnd());
        }
        if (nearId != null && !nearId.isBlank()) {
            try {
                UUID id = UUID.fromString(nearId);
                for (VehicleHandle vehicle : RailServices.physics().vehicles(level)) {
                    if (vehicle.id().equals(id)) {
                        return Optional.of(vehicle.position());
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // Fall through.
            }
        }
        return byService(level, service).map(Target::centre);
    }

    /** Closest distance from the locomotive to the chosen target coupler, or -1 if absent. */
    public static double distanceTo(ServerLevel level, String destination, VehicleHandle vehicle) {
        if (!isConsistTarget(destination)) {
            return -1.0D;
        }
        CouplerInfo coupler = couplerOf(level, destination);
        if (coupler != null) {
            return vehicle.position().distanceTo(coupler.worldEnd());
        }
        Optional<Target> target = byService(level, serviceOf(destination));
        if (target.isEmpty()) {
            return -1.0D;
        }
        double best = Double.MAX_VALUE;
        for (VehicleHandle other : RailServices.physics().vehicles(level)) {
            if (target.get().vehicles().contains(other.id())) {
                best = Math.min(best, vehicle.position().distanceTo(other.position()));
            }
        }
        return best == Double.MAX_VALUE ? -1.0D : best;
    }
}
