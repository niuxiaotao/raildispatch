package com.raildispatch.api;

import java.util.List;
import java.util.Optional;

/**
 * Coupling two physics vehicles together.
 *
 * <p>Create has no runtime API to couple two trains - a train's carriage list is fixed when the
 * train is assembled at a station - so coupling is done at the physics layer instead: Simurail's
 * automatic couplers hold a Sable constraint between two sub-levels, and setting the two couplers as
 * partners is what actually joins the vehicles.
 *
 * <p>No level is passed in: couplers are found by walking the structure's own blocks, which live in
 * the physics engine's coordinate space rather than the world's.
 */
public interface CouplerBridge {

    boolean isAvailable();

    /**
     * Join two vehicles.
     *
     * @return a short human readable result, suitable for a chat message or the GUI
     */
    Optional<String> couple(VehicleHandle a, VehicleHandle b);

    /** Look for couplers on a vehicle that could be used for a join. */
    List<net.minecraft.core.BlockPos> couplersOf(VehicleHandle vehicle);

    CouplerBridge NONE = new CouplerBridge() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Optional<String> couple(VehicleHandle a, VehicleHandle b) {
            return Optional.empty();
        }

        @Override
        public List<net.minecraft.core.BlockPos> couplersOf(VehicleHandle vehicle) {
            return List.of();
        }
    };
}
