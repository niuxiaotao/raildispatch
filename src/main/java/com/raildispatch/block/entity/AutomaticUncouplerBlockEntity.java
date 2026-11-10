package com.raildispatch.block.entity;

import com.raildispatch.RailDispatch;
import com.raildispatch.api.RailServices;
import com.raildispatch.api.VehicleHandle;
import com.raildispatch.registry.RDPoints;
import com.raildispatch.train.AutomaticUncouplerPoint;
import com.raildispatch.train.YardState;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Opens the nearest Simurail automatic coupler when a vehicle passes the rail point selected for this
 * block. Simurail's coupler classes are accessed reflectively so this feature is not a hard dependency.
 */
public class AutomaticUncouplerBlockEntity extends SmartBlockEntity {

    /** Filled in by {@link #addBehaviours}; links this block to its point on the track graph. */
    public TrackTargetingBehaviour<AutomaticUncouplerPoint> edgePoint;

    private int cooldown;
    /**
     * Couplers held open after the split. Simurail's coupler reconnects on its next tick unless its
     * block state is POWERED, so removing the partner alone only produces one tick of separation.
     */
    private final Set<BlockEntity> heldOpen =
            Collections.newSetFromMap(new IdentityHashMap<>());

    public AutomaticUncouplerBlockEntity(BlockPos pos, BlockState state) {
        super(com.raildispatch.registry.RDBlockEntities.AUTOMATIC_UNCOUPLER.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        edgePoint = new TrackTargetingBehaviour<>(this, RDPoints.AUTOMATIC_UNCOUPLER);
        behaviours.add(edgePoint);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        ensureEdgePoint();
        BlockPos target = targetTrack();
        if (target == null) {
            return;
        }
        Vec3 marker = Vec3.atCenterOf(target);
        if (server.getGameTime() % 20L == 0L) {
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                    marker.x, marker.y + 0.15D, marker.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        releaseDistantOpenCouplers(marker);
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        Vec3 centre = Vec3.atCenterOf(target);
        for (VehicleHandle vehicle : RailServices.physics().vehicles(server)) {
            Vec3 position = vehicle.position();
            if (Math.abs(position.y - centre.y) > 2.5D) {
                continue;
            }
            double dx = position.x - centre.x;
            double dz = position.z - centre.z;
            if (dx * dx + dz * dz > 4.0D) {
                continue;
            }
            if (uncoupleNearest(server, vehicle, centre)) {
                cooldown = 20;
                return;
            }
        }
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

    private boolean uncoupleNearest(ServerLevel server, VehicleHandle vehicle, Vec3 centre) {
        Candidate best = null;
        for (VehicleHandle handle : YardState.handles(server, vehicle)) {
            for (BlockEntity actor : handle.actorsOfType(BlockEntity.class)) {
                if (!actor.getClass().getName().contains("AutomaticCouplerBlockEntity")) {
                    continue;
                }
                try {
                    Method hasPartner = actor.getClass().getMethod("hasCouplerPartner");
                    if (!Boolean.TRUE.equals(hasPartner.invoke(actor))) {
                        continue;
                    }
                    Vec3 end = globalEnd(actor);
                    double distance = end == null ? Double.MAX_VALUE : end.distanceToSqr(centre);
                    if (best == null || distance < best.distance) {
                        best = new Candidate(actor, distance);
                    }
                } catch (Throwable ignored) {
                    // A coupler type we cannot inspect is skipped.
                }
            }
        }
        if (best == null) {
            return false;
        }
        try {
            BlockEntity partner = null;
            try {
                Object partnerPos = best.actor.getClass().getMethod("getPartner").invoke(best.actor);
                if (partnerPos instanceof BlockPos pos && best.actor.getLevel() != null) {
                    partner = best.actor.getLevel().getBlockEntity(pos);
                }
            } catch (Throwable ignored) {
                // The partner is optional; the near coupler is still opened.
            }
            Method remove = best.actor.getClass().getMethod("removeCouplerPartner");
            remove.invoke(best.actor);
            // Hold both ends open, otherwise Simurail's next tick finds the other car still within range
            // and reconnects immediately.
            holdOpen(best.actor);
            if (partner != null) {
                holdOpen(partner);
            }
            Method hasPartner = best.actor.getClass().getMethod("hasCouplerPartner");
            boolean still = Boolean.TRUE.equals(hasPartner.invoke(best.actor));
            RailDispatch.debug("Automatic uncoupler at {}: opened the coupler on vehicle {} "
                    + "(still connected: {})", worldPosition, vehicle.id(), still);
            return !still;
        } catch (Throwable throwable) {
            RailDispatch.LOGGER.warn("Automatic uncoupler at {}: could not open the coupler",
                    worldPosition, throwable);
            return false;
        }
    }

    private void holdOpen(BlockEntity coupler) {
        if (coupler == null || coupler.isRemoved() || coupler.getLevel() == null) {
            return;
        }
        BlockState state = coupler.getBlockState();
        if (!state.hasProperty(BlockStateProperties.POWERED)) {
            return;
        }
        heldOpen.add(coupler);
        if (!state.getValue(BlockStateProperties.POWERED)) {
            // Flag 2: update clients but do not let a neighbour update immediately clear POWERED again.
            coupler.getLevel().setBlock(coupler.getBlockPos(),
                    state.setValue(BlockStateProperties.POWERED, true), 2);
        }
    }

    private void releaseDistantOpenCouplers(Vec3 centre) {
        Iterator<BlockEntity> iterator = heldOpen.iterator();
        while (iterator.hasNext()) {
            BlockEntity coupler = iterator.next();
            if (coupler == null || coupler.isRemoved() || coupler.getLevel() == null) {
                iterator.remove();
                continue;
            }
            Vec3 end = globalEnd(coupler);
            if (end != null && end.distanceToSqr(centre) <= 36.0D) {
                continue;
            }
            BlockState state = coupler.getBlockState();
            if (state.hasProperty(BlockStateProperties.POWERED)
                    && state.getValue(BlockStateProperties.POWERED)) {
                coupler.getLevel().setBlock(coupler.getBlockPos(),
                        state.setValue(BlockStateProperties.POWERED, false), 2);
            }
            iterator.remove();
        }
    }

    private static Vec3 globalEnd(BlockEntity coupler) {
        Class<?> type = coupler.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField("globalEndPos");
                field.setAccessible(true);
                Object value = field.get(coupler);
                if (value instanceof Vector3d end) {
                    return new Vec3(end.x, end.y, end.z);
                }
            } catch (Throwable ignored) {
                // Try the superclass.
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private record Candidate(BlockEntity actor, double distance) {}
}
