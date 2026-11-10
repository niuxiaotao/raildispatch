package com.raildispatch.physics;

import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import com.raildispatch.RailDispatch;
import com.raildispatch.api.CouplerBridge;
import com.raildispatch.api.VehicleHandle;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Coupling through Simurail's automatic couplers.
 *
 * <p>Create has no runtime API to couple two trains - a train's carriage list is fixed when it is
 * assembled at a station - so joining two physics vehicles has to happen at the physics layer.
 * Simurail's coupler holds a Sable constraint between two structures and is, by name and by design,
 * <i>automatic</i>: it looks for a partner and forms the joint itself. What this class can honestly do
 * is find the couplers, report whether the pair is actually in contact, and hand the join over to
 * Simurail's own logic.
 *
 * <p>One caller-visible consequence of the plot coordinate space: the two structures live in
 * <b>different</b> plots, so their local block positions are not comparable and must not be compared.
 * Every distance in here is therefore computed after mapping both endpoints into world space through
 * the structure's pose.
 */
final class SimurailCouplers implements CouplerBridge {

    /** Two couplers closer than this are considered touching, i.e. able to form a joint. */
    private static final double CONTACT_RANGE = 2.5D;

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<BlockPos> couplersOf(VehicleHandle vehicle) {
        List<BlockPos> positions = new ArrayList<>();
        for (AutomaticCouplerBlockEntity coupler : couplersIn(vehicle)) {
            positions.add(coupler.getBlockPos());
        }
        return positions;
    }

    @Override
    public Optional<String> couple(VehicleHandle a, VehicleHandle b) {
        if (!(a instanceof SableVehicle first) || !(b instanceof SableVehicle second)) {
            return Optional.of("no coupler available");
        }
        if (first.id().equals(second.id())) {
            return Optional.of("already one structure");
        }

        List<AutomaticCouplerBlockEntity> couplersA = couplersIn(a);
        List<AutomaticCouplerBlockEntity> couplersB = couplersIn(b);
        if (couplersA.isEmpty() || couplersB.isEmpty()) {
            return Optional.of("no coupler available");
        }

        // World space, because the two structures are in different plots: their local coordinates are
        // incomparable and comparing them would pick an arbitrary pair.
        AutomaticCouplerBlockEntity bestA = null;
        AutomaticCouplerBlockEntity bestB = null;
        double bestDistance = Double.MAX_VALUE;
        for (AutomaticCouplerBlockEntity candidateA : couplersA) {
            Vec3 worldA = SablePhysicsBridge.worldPositionOf(first.subLevel(), candidateA.getBlockPos());
            for (AutomaticCouplerBlockEntity candidateB : couplersB) {
                Vec3 worldB = SablePhysicsBridge.worldPositionOf(second.subLevel(), candidateB.getBlockPos());
                double distance = worldA.distanceTo(worldB);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestA = candidateA;
                    bestB = candidateB;
                }
            }
        }
        if (bestA == null || bestB == null) {
            return Optional.of("no coupler pair found");
        }

        if (bestDistance > CONTACT_RANGE) {
            return Optional.of(String.format(java.util.Locale.ROOT,
                    "couplers %.1f blocks apart - close the gap first", bestDistance));
        }

        // Same structure means both couplers share one coordinate space, so a partner position is
        // unambiguous. Simurail 0.1.0 names the setters setCouplerPartner / setCouplerPartnerReverse,
        // and the pair is asymmetric on purpose: one side records the forward link and the other the
        // reverse, which is why both are called rather than the same setter twice.
        try {
            bestA.setCouplerPartner(bestB.getBlockPos());
            bestB.setCouplerPartnerReverse(bestA.getBlockPos());
        } catch (Throwable throwable) {
            RailDispatch.LOGGER.warn("Coupler refused the join between {} and {}", bestA.getBlockPos(),
                    bestB.getBlockPos(), throwable);
            return Optional.of("coupler refused the join");
        }
        return Optional.of(String.format(java.util.Locale.ROOT,
                "couplers in contact (%.1f blocks); the joint is Simurail's to form", bestDistance));
    }

    private static List<AutomaticCouplerBlockEntity> couplersIn(VehicleHandle vehicle) {
        List<AutomaticCouplerBlockEntity> found = new ArrayList<>();
        if (!(vehicle instanceof SableVehicle sable)) {
            return found;
        }
        ServerSubLevel subLevel = sable.subLevel();
        LevelPlot plot = subLevel.getPlot();
        if (plot == null) {
            return found;
        }
        for (BlockEntitySubLevelActor actor : plot.getBlockEntityActors()) {
            if (actor instanceof AutomaticCouplerBlockEntity coupler) {
                found.add(coupler);
            }
        }
        return found;
    }
}
