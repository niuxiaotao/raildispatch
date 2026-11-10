package com.raildispatch.block.entity;

import com.raildispatch.api.RailServices;
import com.raildispatch.api.VehicleHandle;
import com.raildispatch.registry.RDPoints;
import com.raildispatch.train.RetarderPoint;
import com.raildispatch.train.YardState;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Applies the retarder brake at the rail point selected when the block was placed. Only free-rolling
 * vehicles are braked: a vehicle with an engaged cab or console is under a driver's control.
 */
public class RetarderBlockEntity extends SmartBlockEntity {

    /** Filled in by {@link #addBehaviours}; links this block to its point on the track graph. */
    public TrackTargetingBehaviour<RetarderPoint> edgePoint;

    public RetarderBlockEntity(BlockPos pos, BlockState state) {
        super(com.raildispatch.registry.RDBlockEntities.RETARDER.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        edgePoint = new TrackTargetingBehaviour<>(this, RDPoints.RETARDER);
        behaviours.add(edgePoint);
    }

    @Override
    public void tick() {
        super.tick();
        if (level instanceof ServerLevel server) {
            serverTick(server);
        }
    }

    private void serverTick(ServerLevel server) {
        ensureEdgePoint();
        BlockPos target = targetTrack();
        if (target == null) {
            return;
        }
        Vec3 centre = Vec3.atCenterOf(target);
        // Persistent marker for the track point. The Create placement preview is only shown while the
        // item is held, so without this the point appears to vanish as soon as the block is placed.
        if (server.getGameTime() % 20L == 0L) {
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                    centre.x, centre.y + 0.15D, centre.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        int signal = server.getBestNeighborSignal(worldPosition);
        if (signal <= 0) {
            return;
        }
        boolean hardStop = signal >= 15;
        double force = hardStop ? 1.0D : signal / 15.0D;
        for (VehicleHandle vehicle : RailServices.physics().vehicles(server)) {
            Vec3 position = vehicle.position();
            if (Math.abs(position.y - centre.y) > 2.5D) {
                continue;
            }
            double dx = position.x - centre.x;
            double dz = position.z - centre.z;
            // A retarder is a section, not a single pixel: 3 blocks around the selected point. The old
            // 1.5-block radius meant a car could be braked for less than one block of travel.
            if (dx * dx + dz * dz > 9.0D) {
                continue;
            }
            // A retarder is a track brake, not a routing authority: it brakes every vehicle that passes
            // the selected point, whether or not a cab happens to be engaged. A multi-car physics train
            // is several sub-levels, and the car standing on the retarder may be a trailer with no
            // bogie; braking that handle does nothing. Command every coupled sub-level that actually has
            // a Simurail bogie.
            int braked = 0;
            for (VehicleHandle handle : YardState.handles(server, vehicle)) {
                if (handle.bogeyCount() <= 0) {
                    continue;
                }
                if (hardStop || handle.speed() < 0.05D) {
                    // Signal 15 is a direct stop dead. At walking pace a proportional impulse has no
                    // reliable direction and can kick the wagon, so stop it the same safe way instead.
                    handle.emergencyStop();
                } else {
                    handle.command(worldPosition, 0.0D, force, 0.0D);
                }
                braked++;
            }
            if (server.getGameTime() % 40L == 0L) {
                com.raildispatch.RailDispatch.debug(
                        "Retarder at {}: {} vehicle {} with force {} at the selected rail point {} "
                                + "({} bogied sub-level(s))",
                        worldPosition, hardStop ? "hard-stopping" : "braking", vehicle.id(), force,
                        target, braked);
            }
        }
    }

    /** Recreates the graph point if it was lost, so the device does not silently become inert. */
    private void ensureEdgePoint() {
        try {
            if (edgePoint != null && edgePoint.hasValidTrack() && edgePoint.getEdgePoint() == null) {
                edgePoint.createEdgePoint();
            }
        } catch (Throwable ignored) {
            // The targeting behaviour will retry on its own.
        }
    }

    /** The rail block selected by the targeting item, or null when the block is not linked yet. */
    private BlockPos targetTrack() {
        try {
            if (edgePoint != null && edgePoint.hasValidTrack()) {
                return edgePoint.getGlobalPosition();
            }
        } catch (Throwable throwable) {
            // Not linked yet; the block simply does nothing.
        }
        return null;
    }
}
