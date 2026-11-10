package com.raildispatch.train;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.station.GlobalStation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Station destination signs.
 *
 * <p>A station's Create name is its database key; the player-facing destination is a sign placed on the
 * station itself. The sign's text is what a nameplate on a wagon has to match. Only signs in the six
 * blocks directly attached to the station block are considered - a sign on a nearby platform block is
 * not a station sign and must not attract a wagon to the wrong station.
 */
public final class StationSigns {

    private StationSigns() {}

    public record StationTarget(GlobalStation station, Vec3 marker, java.util.UUID graphId) {}

    public static Optional<StationTarget> resolve(ServerLevel level, String alias) {
        return resolve(level, alias, null);
    }

    /**
     * Resolve a sign, preferring a station on the vehicle's own graph. A route cannot change graphs,
     * so a same-named sign on a disconnected graph is not a destination the car can reach.
     */
    public static Optional<StationTarget> resolve(ServerLevel level, String alias, TrackGraph preferred) {
        String wanted = normalize(alias);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        try {
            if (Create.RAILWAYS == null) {
                return Optional.empty();
            }
            if (preferred != null) {
                Optional<StationTarget> local = findOn(level, preferred, wanted);
                if (local.isPresent()) {
                    return local;
                }
            }
            for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
                Optional<StationTarget> found = findOn(level, graph, wanted);
                if (found.isPresent()) {
                    return found;
                }
            }
        } catch (Throwable throwable) {
            com.raildispatch.RailDispatch.LOGGER.warn("Could not resolve a station sign '{}'", alias, throwable);
        }
        return Optional.empty();
    }

    /** Every destination text written on a station sign, for the console's destination suggestions. */
    public static List<String> aliases(ServerLevel level) {
        List<String> result = new ArrayList<>();
        try {
            if (Create.RAILWAYS == null) {
                return result;
            }
            for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
                for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                    BlockPos base = station.getBlockEntityPos();
                    if (base == null) {
                        continue;
                    }
                    for (BlockPos pos : attachedBlocks(base)) {
                        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
                            collect(sign, result);
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            com.raildispatch.RailDispatch.LOGGER.warn("Could not list station signs", throwable);
        }
        return result;
    }

    private static Optional<StationTarget> findOn(ServerLevel level, TrackGraph graph, String wanted) {
        for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
            if (!hasSign(level, station.getBlockEntityPos(), wanted)) {
                continue;
            }
            Optional<Vec3> marker = CreateTrackNetwork.markerPosition(level, graph, station);
            if (marker.isPresent()) {
                return Optional.of(new StationTarget(station, marker.get(), graph.id));
            }
        }
        return Optional.empty();
    }

    private static boolean hasSign(ServerLevel level, BlockPos base, String wanted) {
        if (base == null) {
            return false;
        }
        for (BlockPos pos : attachedBlocks(base)) {
            if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
                continue;
            }
            if (matches(sign, wanted)) {
                return true;
            }
        }
        return false;
    }

    /** The six blocks a sign must occupy when it is physically attached to the station block. */
    private static List<BlockPos> attachedBlocks(BlockPos base) {
        return List.of(base.above(), base.below(), base.north(), base.south(), base.east(), base.west());
    }

    private static boolean matches(SignBlockEntity sign, String wanted) {
        for (int line = 0; line < 4; line++) {
            if (normalize(sign.getFrontText().getMessage(line, false).getString()).equals(wanted)) {
                return true;
            }
            if (normalize(sign.getBackText().getMessage(line, false).getString()).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static void collect(SignBlockEntity sign, List<String> out) {
        for (int line = 0; line < 4; line++) {
            String front = sign.getFrontText().getMessage(line, false).getString().trim();
            String back = sign.getBackText().getMessage(line, false).getString().trim();
            if (!front.isEmpty() && !out.contains(front)) {
                out.add(front);
            }
            if (!back.isEmpty() && !out.contains(back)) {
                out.add(back);
            }
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
