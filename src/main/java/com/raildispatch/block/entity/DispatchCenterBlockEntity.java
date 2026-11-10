package com.raildispatch.block.entity;

import com.raildispatch.RDConfig;
import com.raildispatch.api.RailServices;
import com.raildispatch.api.RouteAuthority;
import com.raildispatch.api.TrackNetwork;
import com.raildispatch.registry.RDBlockEntities;
import com.raildispatch.registry.RDPoints;
import com.raildispatch.train.DispatchCenterPoint;
import com.raildispatch.train.DispatchCenters;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The dispatch centre: the authority that decides which vehicle may have which piece of railway.
 *
 * <p>It is a Create {@code SmartBlockEntity} rather than a plain one because it carries a
 * {@link TrackTargetingBehaviour}, which is what ties it to a position on the track graph. Being on
 * the graph is the point: it means the centre knows which railway it was placed against instead of
 * inferring it from a radius.
 *
 * <p>Routes are session state, not world state - a route that outlived a server restart would lock
 * switches for a vehicle that no longer exists - so this block entity deliberately does not persist
 * them. It also means there is nothing to write in {@code write}/{@code read}: everything is rebuilt
 * from live vehicles within a second of loading.
 */
public class DispatchCenterBlockEntity extends SmartBlockEntity implements RouteAuthority {

    /** Two routes closer than this at any shared waypoint are treated as overlapping. */
    private static final double OVERLAP_DISTANCE = 4.0D;
    /** A route is considered abandoned when its vehicle has not moved for this many ticks. */
    private static final int STALL_GRACE = 40;

    /** Filled in by {@link #addBehaviours}; links this block to a point on the track graph. */
    public TrackTargetingBehaviour<DispatchCenterPoint> edgePoint;

    private static final class HeldRoute {
        final String vehicleName;
        final TrackNetwork.PlannedRoute route;
        final long grantedTick;
        Vec3 lastPosition;
        long lastMovedTick;

        HeldRoute(String vehicleName, TrackNetwork.PlannedRoute route, long now, Vec3 position) {
            this.vehicleName = vehicleName;
            this.route = route;
            this.grantedTick = now;
            this.lastPosition = position;
            this.lastMovedTick = now;
        }
    }

    private final Map<UUID, HeldRoute> routes = new HashMap<>();
    private long ticks;

    public DispatchCenterBlockEntity(BlockPos pos, BlockState state) {
        super(RDBlockEntities.DISPATCH_CENTER.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        edgePoint = new TrackTargetingBehaviour<>(this, RDPoints.DISPATCH_CENTER);
        behaviours.add(edgePoint);
    }

    @Override
    public void initialize() {
        super.initialize();
        if (level instanceof ServerLevel server) {
            DispatchCenters.register(server, worldPosition, this);
        }
    }

    // SmartBlockEntity.setRemoved() is final, so removal is hooked through these two instead. They are
    // both handled because a chunk unload and a block break take different paths.
    @Override
    public void invalidate() {
        unregister();
        super.invalidate();
    }

    @Override
    public void onChunkUnloaded() {
        unregister();
        super.onChunkUnloaded();
    }

    private void unregister() {
        if (level instanceof ServerLevel server) {
            DispatchCenters.unregister(server.dimension(), worldPosition);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level instanceof ServerLevel server) {
            serverTick(server);
        }
    }

    public void serverTick(ServerLevel server) {
        ticks++;
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, HeldRoute> entry : routes.entrySet()) {
            HeldRoute held = entry.getValue();
            Vec3 current = currentPosition(server, entry.getKey());
            if (current == null) {
                expired.add(entry.getKey());
                continue;
            }
            if (current.distanceToSqr(held.lastPosition) > 0.0025D) {
                held.lastPosition = current;
                held.lastMovedTick = ticks;
            }
            if (ticks - held.lastMovedTick > RDConfig.routeTimeoutTicks() + STALL_GRACE) {
                expired.add(entry.getKey());
            }
        }
        for (UUID vehicle : expired) {
            release(vehicle);
        }
    }

    private Vec3 currentPosition(ServerLevel server, UUID vehicle) {
        return RailServices.physics().vehicleById(server, vehicle)
                .map(handle -> handle.position())
                .orElse(null);
    }

    /** The rail this centre was placed against, when the targeting resolved to a real track. */
    public Optional<BlockPos> targetTrackPos() {
        try {
            if (edgePoint != null && edgePoint.hasValidTrack()) {
                return Optional.ofNullable(edgePoint.getGlobalPosition());
            }
        } catch (Throwable throwable) {
            // A targeting behaviour that cannot resolve is not fatal: the centre still arbitrates.
        }
        return Optional.empty();
    }

    @Override
    public TrackNetwork.RouteGrant request(ServerLevel level, UUID vehicle, String vehicleName,
                                           TrackNetwork.PlannedRoute route) {
        if (route == null) {
            return TrackNetwork.RouteGrant.denied("nothing to plan");
        }
        if (Vec3.atCenterOf(worldPosition).distanceTo(route.from()) > RDConfig.dispatchCenterRange()) {
            return TrackNetwork.RouteGrant.denied("outside dispatch range");
        }
        for (Map.Entry<UUID, HeldRoute> entry : routes.entrySet()) {
            if (entry.getKey().equals(vehicle)) {
                continue;
            }
            if (overlaps(route, entry.getValue().route)) {
                return TrackNetwork.RouteGrant.denied("route busy: " + entry.getValue().vehicleName);
            }
        }
        routes.put(vehicle, new HeldRoute(vehicleName, route, ticks, route.from()));
        setChanged();
        return TrackNetwork.RouteGrant.granted("granted by dispatch centre", route.junctions());
    }

    @Override
    public void release(UUID vehicle) {
        if (routes.remove(vehicle) != null) {
            setChanged();
        }
    }

    @Override
    public int activeRoutes() {
        return routes.size();
    }

    private boolean overlaps(TrackNetwork.PlannedRoute first, TrackNetwork.PlannedRoute second) {
        for (BlockPos a : first.junctions()) {
            for (BlockPos b : second.junctions()) {
                if (a.equals(b) || a.closerThan(b, OVERLAP_DISTANCE)) {
                    return true;
                }
            }
        }
        for (Vec3 a : first.waypoints()) {
            for (Vec3 b : second.waypoints()) {
                if (a.distanceToSqr(b) < OVERLAP_DISTANCE * OVERLAP_DISTANCE) {
                    return true;
                }
            }
        }
        return false;
    }

    public void reportTo(Player player) {
        player.displayClientMessage(Component.translatable("raildispatch.dispatch_center.status",
                routes.size(), RDConfig.dispatchCenterRange()), false);
        targetTrackPos().ifPresent(track -> player.displayClientMessage(
                Component.translatable("raildispatch.dispatch_center.track", track.toShortString()), false));
        for (Map.Entry<UUID, HeldRoute> entry : routes.entrySet()) {
            player.displayClientMessage(Component.literal(" - " + entry.getValue().vehicleName
                    + " -> " + String.format(java.util.Locale.ROOT, "%.0f blocks",
                    entry.getValue().route.length())), false);
        }
    }
}
