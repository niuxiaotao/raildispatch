package com.raildispatch.physics;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.raildispatch.RailDispatch;
import com.raildispatch.api.PhysicsBridge;
import com.raildispatch.api.RailServices;
import com.raildispatch.api.VehicleHandle;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Physics integration over Sable sub-levels and Simurail physics bogies.
 *
 * <p>A "vehicle" is a Sable {@link ServerSubLevel}: the assembled structure. Its UUID is the identity
 * a vehicle tag binds to, and its rigid body is what a cab pushes.
 *
 * <p><b>The important detail is coordinate space.</b> Once a structure is assembled its blocks are
 * moved into a plot - a region far from the origin of the level, described by {@link LevelPlot} - so
 * the structure's block entities report <i>plot</i> coordinates, not the world position where the
 * vehicle appears to be. Two consequences follow, and both were bugs in the first version of this
 * class:
 *
 * <ul>
 *   <li>Searching for bogies in a box around the vehicle's world position finds nothing. The bogies
 *       are instead read straight out of the structure's own actor list,
 *       {@link LevelPlot#getBlockEntityActors()}, and held as object references - there is no reason
 *       to go through positions at all.</li>
 *   <li>Looking a block's owner up by world position fails for a block inside a plot. The exact test
 *       is "does any structure's actor list contain this very block entity?".</li>
 * </ul>
 *
 * <p>Everything here is wrapped in guards that log once. These are optional-in-practice APIs on a
 * fast-moving mod, and a server that keeps running with an idle cab is strictly better than one that
 * dies because a field moved.
 */
public final class SablePhysicsBridge implements PhysicsBridge {

    private static final long CACHE_TICKS = 40L;
    private static final int CACHE_LIMIT = 256;

    private record CachedVehicle(UUID id, long expiresAt) {}

    private final Map<BlockPos, CachedVehicle> vehicleByPos = new HashMap<>();
    /**
     * Handles are cached per vehicle, not rebuilt per query.
     *
     * <p>This is load-bearing rather than a micro-optimisation: a handle measures travel speed from the
     * change in position since its own previous sample, so a freshly built handle has no previous
     * sample and reports a speed of zero. Rebuilding it on every decision therefore reported a
     * permanently stationary vehicle, which left the controller commanding full drive for ever and -
     * because braking derives its direction from that same measurement - disabled the brakes entirely.
     */
    private final Map<UUID, SableVehicle> handles = new HashMap<>();
    private boolean warnedNoBogeys;
    private boolean warnedResolved;
    private boolean diagnosedEmpty;
    private boolean warned;

    private SablePhysicsBridge() {}

    /**
     * Builds the bridge and installs the coupler alongside it. Returns the no-op bridge rather than
     * throwing if the physics mods are absent, so the rest of the mod still loads.
     */
    public static PhysicsBridge create() {
        try {
            SablePhysicsBridge bridge = new SablePhysicsBridge();
            RailServices.installCoupler(new SimurailCouplers());
            return bridge;
        } catch (Throwable throwable) {
            RailDispatch.LOGGER.error("Sable/Simurail physics bridge could not be created", throwable);
            return PhysicsBridge.NONE;
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Optional<VehicleHandle> vehicleOf(Level level, BlockPos pos) {
        try {
            long now = level.getGameTime();
            CachedVehicle cached = vehicleByPos.get(pos);
            if (cached != null) {
                if (cached.expiresAt() > now) {
                    Optional<VehicleHandle> stillThere = vehicleById(level, cached.id());
                    if (stillThere.isPresent()) {
                        return stillThere;
                    }
                }
                vehicleByPos.remove(pos);
            }

            ServerSubLevel owner = ownerOf(level, pos);
            if (owner == null) {
                diagnoseEmpty(level, pos);
                return Optional.empty();
            }
            if (vehicleByPos.size() > CACHE_LIMIT) {
                vehicleByPos.clear();
            }
            vehicleByPos.put(pos.immutable(), new CachedVehicle(owner.getUniqueId(), now + CACHE_TICKS));
            return Optional.of(handleFor(level, owner));
        } catch (Throwable throwable) {
            warn("locating the vehicle of a block", throwable);
            return Optional.empty();
        }
    }

    /**
     * Which structure owns the block at {@code pos}.
     *
     * <p>Tried in order of reliability. The actor-list test comes first among the exact tests because
     * it is the only one that works for a block entity inside a plot; the companion lookups are kept
     * because they also cover a block that sits in the world on top of a structure without having been
     * assembled into it.
     */
    private static ServerSubLevel ownerOf(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            SubLevelAccess viaBlockEntity = SableCompanion.INSTANCE.getContaining(blockEntity);
            if (viaBlockEntity instanceof ServerSubLevel server) {
                return server;
            }
        }
        ServerSubLevel viaActor = ownerOfActor(level, pos);
        if (viaActor != null) {
            return viaActor;
        }
        SubLevelAccess viaPosition = SableCompanion.INSTANCE.getContaining(level, pos);
        return viaPosition instanceof ServerSubLevel server ? server : null;
    }

    private static ServerSubLevel ownerOfActor(Level level, BlockPos pos) {
        for (SubLevel subLevel : container(level)) {
            if (!(subLevel instanceof ServerSubLevel server)) {
                continue;
            }
            for (BlockEntitySubLevelActor actor : actors(server)) {
                if (actor instanceof BlockEntity blockEntity && blockEntity.getBlockPos().equals(pos)) {
                    return server;
                }
            }
        }
        return null;
    }

    @Override
    public Optional<VehicleHandle> vehicleById(Level level, UUID id) {
        try {
            SubLevel found = SubLevelContainer.getContainer(level).getSubLevel(id);
            if (found instanceof ServerSubLevel server) {
                return Optional.of(handleFor(level, server));
            }
        } catch (Throwable throwable) {
            warn("looking a vehicle up by id", throwable);
        }
        return Optional.empty();
    }

    @Override
    public List<VehicleHandle> vehicles(Level level) {
        List<VehicleHandle> result = new ArrayList<>();
        try {
            for (SubLevel subLevel : container(level)) {
                if (subLevel instanceof ServerSubLevel server) {
                    result.add(handleFor(level, server));
                }
            }
        } catch (Throwable throwable) {
            warn("enumerating the vehicles in a level", throwable);
        }
        return result;
    }

    private SableVehicle wrap(Level level, ServerSubLevel subLevel) {
        List<PhysicsBogeyBlockEntity> bogeys = bogeysOf(subLevel);
        if (!warnedResolved) {
            warnedResolved = true;
            RailDispatch.debug("Resolved physics vehicle '{}' ({}): {} bogie(s), {} sub-level actor(s) "
                            + "in total.",
                    subLevel.getName(), subLevel.getUniqueId(), bogeys.size(), countActors(subLevel));
        }
        if (bogeys.isEmpty() && !warnedNoBogeys) {
            warnedNoBogeys = true;
            // Report the two possible causes separately: an empty actor list means the structure's
            // parts could not be reached at all, whereas actors-but-no-bogies means the structure
            // genuinely has no Simurail bogie in it. Guessing between those two from outside is
            // impossible, and they need completely different fixes.
            RailDispatch.LOGGER.warn("Physics vehicle '{}' lists {} sub-level actor(s) and {} Simurail physics "
                            + "bogie(s). If the actor count is 0 the structure's parts could not be reached; if it "
                            + "is non-zero the structure really has no physics bogies in it.",
                    subLevel.getName(), countActors(subLevel), bogeys.size());
        }
        return new SableVehicle(level, subLevel, bogeys);
    }

    /** Returns the long-lived handle for a sub-level, creating it on first use. */
    private SableVehicle handleFor(Level level, ServerSubLevel subLevel) {
        SableVehicle existing = handles.get(subLevel.getUniqueId());
        if (existing != null) {
            return existing;
        }
        if (handles.size() > CACHE_LIMIT) {
            handles.clear();
        }
        SableVehicle created = wrap(level, subLevel);
        handles.put(subLevel.getUniqueId(), created);
        return created;
    }

    private static int countActors(ServerSubLevel subLevel) {
        int count = 0;
        for (BlockEntitySubLevelActor ignored : actors(subLevel)) {
            count++;
        }
        return count;
    }

    /**
     * One-shot report for the silent failure: no vehicle at all was found for the querying block.
     *
     * <p>This case is deliberately loud, because it is the one that produces <i>no other symptom</i> -
     * the cab simply shows "idle" and counts no bogies. Listing the structures the container knows
     * about, with their actor counts and poses, distinguishes the three candidates: no sub-levels at
     * all, a sub-level whose parts cannot be enumerated, and a query position that simply does not
     * belong to any of them.
     */
    private void diagnoseEmpty(Level level, BlockPos pos) {
        if (diagnosedEmpty) {
            return;
        }
        diagnosedEmpty = true;
        List<? extends SubLevel> known = container(level);
        StringBuilder detail = new StringBuilder();
        int shown = 0;
        for (SubLevel subLevel : known) {
            if (shown++ >= 4) {
                detail.append("\n  ... and ").append(known.size() - 4).append(" more");
                break;
            }
            detail.append("\n  - '").append(subLevel.getName()).append("' ").append(subLevel.getUniqueId());
            if (subLevel instanceof ServerSubLevel server) {
                detail.append(" actors=").append(countActors(server));
            }
            detail.append(" worldPos=").append(subLevel.logicalPose().position());
        }
        RailDispatch.LOGGER.warn("Could not resolve a physics vehicle for the block at {} ({} in {}). "
                        + "Sub-levels this level knows about: {}{}. The block entity is {}present.",
                pos, level.getClass().getSimpleName(), level.dimension().location(),
                known.size(), detail, level.getBlockEntity(pos) != null ? "" : "NOT ");
    }

    /** The structure's own block entities - the only reliable way to find its parts. */
    private static Iterable<BlockEntitySubLevelActor> actors(ServerSubLevel subLevel) {
        LevelPlot plot = subLevel.getPlot();
        return plot == null ? List.of() : plot.getBlockEntityActors();
    }

    private static List<PhysicsBogeyBlockEntity> bogeysOf(ServerSubLevel subLevel) {
        List<PhysicsBogeyBlockEntity> found = new ArrayList<>();
        for (BlockEntitySubLevelActor actor : actors(subLevel)) {
            if (actor instanceof PhysicsBogeyBlockEntity bogey) {
                found.add(bogey);
            }
        }
        return found;
    }

    private static List<? extends SubLevel> container(Level level) {
        try {
            return SubLevelContainer.getContainer(level).getAllSubLevels();
        } catch (Throwable throwable) {
            return List.of();
        }
    }

    /** World-space position of a block of a structure, for comparisons between two structures. */
    static Vec3 worldPositionOf(ServerSubLevel subLevel, BlockPos local) {
        org.joml.Vector3d point = new org.joml.Vector3d(
                local.getX() + 0.5D, local.getY() + 0.5D, local.getZ() + 0.5D);
        org.joml.Vector3dc world = subLevel.logicalPose().transformPosition(point);
        return new Vec3(world.x(), world.y(), world.z());
    }

    private void warn(String what, Throwable throwable) {
        if (!warned) {
            warned = true;
            RailDispatch.LOGGER.warn("Physics bridge failed while {}; further physics warnings are suppressed",
                    what, throwable);
        }
    }
}
