package com.raildispatch.block.entity;

import com.raildispatch.RDConfig;
import com.raildispatch.RailDispatch;
import com.raildispatch.api.RailServices;
import com.raildispatch.api.VehicleHandle;
import com.raildispatch.registry.RDPoints;
import com.raildispatch.train.SmartRetarderPoint;
import com.raildispatch.train.YardState;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Smart retarder: keeps a gap behind the wagon ahead.
 *
 * <p>For every free wagon approaching the selected point, find the nearest other wagon in front of it
 * on the same track and brake it when the centre-to-centre distance drops below the configured gap.
 * The further the gap is below the target, the harder the brake; at very small gaps the wagon is
 * stopped dead rather than given a proportional impulse that could kick it on jitter.
 */
public class SmartRetarderBlockEntity extends SmartBlockEntity {

    /** Only wagons this close to the selected point are controlled; keep it short and local. */
    private static final double ACTION_RADIUS = 3.0D;
    /** Same-track tolerance, in blocks. Wider than this is a neighbouring line. */
    private static final double LATERAL_TOLERANCE = 1.0D;

    public TrackTargetingBehaviour<SmartRetarderPoint> edgePoint;

    /** Vehicles this retarder is currently holding, so a released one can be handed back explicitly. */
    private final java.util.Map<UUID, Long> held = new java.util.HashMap<>();

    public SmartRetarderBlockEntity(BlockPos pos, BlockState state) {
        super(com.raildispatch.registry.RDBlockEntities.SMART_RETARDER.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        edgePoint = new TrackTargetingBehaviour<>(this, RDPoints.SMART_RETARDER);
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
        if (server.getGameTime() % 20L == 0L) {
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                    centre.x, centre.y + 0.15D, centre.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        List<VehicleHandle> vehicles = RailServices.physics().vehicles(server);
        double wantedGap = Math.max(2.0D, RDConfig.smartRetarderGap());
        for (VehicleHandle self : vehicles) {
            if (!YardState.isFree(server, self)) {
                continue;
            }
            Vec3 position = self.position();
            double toPointX = position.x - centre.x;
            double toPointZ = position.z - centre.z;
            if (toPointX * toPointX + toPointZ * toPointZ > ACTION_RADIUS * ACTION_RADIUS) {
                continue;
            }
            if (Math.abs(position.y - centre.y) > 2.5D) {
                continue;
            }
            Vec3 direction = horizontal(self.velocity());
            if (direction == null) {
                direction = horizontal(self.lastTravelledDirection());
            }
            if (direction == null) {
                continue;
            }

            Set<UUID> ownTrain = new HashSet<>();
            for (VehicleHandle own : YardState.handles(server, self)) {
                ownTrain.add(own.id());
            }

            VehicleHandle ahead = null;
            double aheadDistance = Double.MAX_VALUE;
            for (VehicleHandle other : vehicles) {
                if (other == self || ownTrain.contains(other.id())) {
                    continue;
                }
                Vec3 relative = other.position().subtract(position);
                double along = relative.dot(direction);
                if (along <= 0.5D || along > wantedGap + 4.0D) {
                    continue;
                }
                Vec3 lateral = relative.subtract(direction.scale(along));
                if (lateral.lengthSqr() > LATERAL_TOLERANCE * LATERAL_TOLERANCE) {
                    continue;
                }
                if (along < aheadDistance) {
                    aheadDistance = along;
                    ahead = other;
                }
            }
            if (ahead == null || aheadDistance >= wantedGap) {
                release(self);
                continue;
            }

            // Never latch a full stop just because the wagon is already slow. Calling emergencyStop()
            // whenever speed < 0.05 killed the velocity and left the wagon parked after the gap ahead
            // had opened. Only a real near-collision stops it dead.
            if (aheadDistance < 1.5D) {
                self.emergencyStop();
                held.put(self.id(), server.getGameTime());
            } else {
                double force = Math.min(1.0D, Math.max(0.05D, 1.0D - aheadDistance / wantedGap));
                self.command(worldPosition, 0.0D, force, 0.0D);
                held.put(self.id(), server.getGameTime());
            }
            if (server.getGameTime() % 40L == 0L) {
                RailDispatch.debug("Smart retarder at {}: holding vehicle {} at gap {} blocks "
                                + "(target {}), wagon ahead {}",
                        worldPosition, self.id(),
                        String.format(java.util.Locale.ROOT, "%.1f", aheadDistance),
                        String.format(java.util.Locale.ROOT, "%.1f", wantedGap), ahead.id());
            }
        }
    }

    /** Hand a wagon back to free rolling once the gap has opened. */
    private void release(VehicleHandle vehicle) {
        if (held.remove(vehicle.id()) == null) {
            return;
        }
        vehicle.command(worldPosition, 0.0D, 0.0D, 0.0D);
        vehicle.releaseControl(worldPosition);
        RailDispatch.debug("Smart retarder at {}: released vehicle {} after the gap opened",
                worldPosition, vehicle.id());
    }

    private static Vec3 horizontal(Vec3 vector) {
        if (vector == null) {
            return null;
        }
        Vec3 flat = new Vec3(vector.x, 0.0D, vector.z);
        return flat.lengthSqr() < 1.0E-4D ? null : flat.normalize();
    }

    private void ensureEdgePoint() {
        try {
            if (edgePoint != null && edgePoint.hasValidTrack() && edgePoint.getEdgePoint() == null) {
                edgePoint.createEdgePoint();
            }
        } catch (Throwable ignored) {
            // The targeting behaviour will retry on its own.
        }
    }

    private BlockPos targetTrack() {
        try {
            if (edgePoint != null && edgePoint.hasValidTrack()) {
                return edgePoint.getGlobalPosition();
            }
        } catch (Throwable ignored) {
            // Not linked yet.
        }
        return null;
    }
}
