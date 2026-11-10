package com.raildispatch.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Extension point for driving ground switches.
 *
 * <p>Create itself has no switch block: a junction is a node of the track graph and the vehicle
 * chooses its branch from its own route. Packs that do add switch hardware can implement this
 * interface and register the driver in {@link RailServices}; the dispatch centre will then drive
 * those switches as part of granting a route.
 */
public interface SwitchDriver {

    /** For diagnostics, e.g. {@code "my_pack:track_switch"}. */
    String id();

    boolean canDrive(ServerLevel level, BlockPos pos);

    /**
     * @param alternateBranch true to select the diverging branch, false for the straight one
     * @return true when the switch accepted the command
     * @deprecated Prefer {@link #driveTowards}: a switch's branches are not a boolean. Which state
     *     corresponds to a diverging branch depends on how the turnout was built, and a driver asked only
     *     "straight or not" has to guess - the very guess that can send a vehicle down the wrong road.
     */
    @Deprecated
    boolean drive(ServerLevel level, BlockPos pos, boolean alternateBranch);

    /**
     * Drives the switch so that the branch leading towards {@code targetNode} is the open one.
     *
     * <p>This is the honest form of the request. A route is a sequence of track nodes, so at a junction the
     * next node <em>is</em> the branch, and a driver only has to answer "which of your states leads there".
     * Railways can answer that exactly, through its own {@code TrackSwitch#getTargetState}; asked only
     * "straight or diverging" it would have to guess, because the exits themselves are not readable from
     * outside that mod.
     *
     * @param targetNode the world position of the node the vehicle is heading to
     * @return true when the switch accepted the command
     */
    default boolean driveTowards(ServerLevel level, BlockPos pos, net.minecraft.world.phys.Vec3 targetNode) {
        return false;
    }
}
