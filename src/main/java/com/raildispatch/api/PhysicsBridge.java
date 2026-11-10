package com.raildispatch.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Everything this mod needs from the physics engine (Sable) and the physics railway addon
 * (Simurail's physics bogies). The implementation is installed by {@code physics.SablePhysicsBridge};
 * if that mod is missing, the no-op instance below keeps the rest of the mod functional.
 *
 * <p>Note the parameter type: {@link Level}, not {@code ServerLevel}. An assembled structure's blocks
 * live in a plot that is not the level the player walks around in, and a block entity inside a
 * structure reports that plot level. Requiring a {@code ServerLevel} here would silently exclude
 * exactly the blocks this mod is about.
 */
public interface PhysicsBridge {

    boolean isAvailable();

    /**
     * The vehicle that owns the block at {@code pos}.
     *
     * <p>Prefer this over any geometric test: the physics engine can answer it directly from a block
     * entity, including for a block entity inside a plot, where a world-position lookup would fail.
     */
    Optional<VehicleHandle> vehicleOf(Level level, BlockPos pos);

    Optional<VehicleHandle> vehicleById(Level level, UUID id);

    /** Every assembled vehicle currently simulated in this level. */
    List<VehicleHandle> vehicles(Level level);

    PhysicsBridge NONE = new PhysicsBridge() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Optional<VehicleHandle> vehicleOf(Level level, BlockPos pos) {
            return Optional.empty();
        }

        @Override
        public Optional<VehicleHandle> vehicleById(Level level, UUID id) {
            return Optional.empty();
        }

        @Override
        public List<VehicleHandle> vehicles(Level level) {
            return List.of();
        }
    };
}
