package com.raildispatch.api;

import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackGraph;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * A physics vehicle: one assembled structure of the physics engine, with the bogies that can drive
 * and steer it. Implementations live in {@code com.raildispatch.physics}; everything else in this
 * mod only talks to this interface, so a change in the physics mod cannot break the cab logic.
 */
public interface VehicleHandle {

    UUID id();

    /** Custom name if the vehicle was named, otherwise a generated label. */
    String name();

    Vec3 position();

    Vec3 velocity();

    /**
     * The last direction this vehicle was seen travelling, or {@code null} if it has never moved.
     *
     * <p>Unlike {@link #velocity()}, this survives coming to a stop, which is what makes it usable as "which way
     * is this vehicle pointing" at the moment a new leg begins and the vehicle is standing still.
     */
    default Vec3 lastTravelledDirection() {
        return null;
    }

    /**
     * True when the vehicle has come off the rails.
     *
     * <p>Read straight from the physics bogies, which derive it from whether each axle still holds a track
     * segment. Note that it is not the negation of "has any track": one axle off is already derailed while
     * the others still hold.
     */
    default boolean isDerailed() {
        return false;
    }

    /**
     * True when every physics bogie has a Create kinetic network. A bogie without one makes Simurail's
     * own tick throw in updateStressFor, so the console must not energise it.
     */
    default boolean hasKineticPower() {
        return true;
    }

    /**
     * What the vehicle's own track probe reports ahead of it, or {@code null} when nothing can be read.
     *
     * <p>Preferred over deriving signal and station geometry here: the engine already probes along the rails
     * and knows whether a signal is aligned with the direction of travel.
     */
    default TrackProbe probeAhead() {
        return null;
    }

    /**
     * The leading axle's own Create travelling point: the exact {@code edge} and {@code position} the
     * physics bogie is on, in the same form Create's {@code Train} uses for pathfinding.
     *
     * <p>This is deliberately not derived from {@link #position()}. A long vehicle can straddle a switch
     * with its body centre over a short spur while its leading axle is already on the connected track; a
     * search started from the centre then sees a dead end that does not exist for the train. The axle's
     * travelling point says where the steel actually is.
     *
     * @return the leading axle's point, or {@code null} when no axle currently holds a track
     */
    default TravellingPoint leadingTrackPoint() {
        return null;
    }

    /** The track graph the leading axle is on, or {@code null} when it holds no track. */
    default TrackGraph trackGraph() {
        return null;
    }

    /**
     * Sub-levels physically coupled to this one (another car of the same train).
     *
     * <p>A multi-car physics train is several sub-levels, so {@link #actorsOfType} on this handle sees
     * only the cab on this car. Syncing a schedule or choosing the cab that faces the destination has to
     * look across the coupled sub-levels, or it silently does nothing on a two-car train.
     */
    default List<UUID> connectedVehicleIds() {
        return List.of();
    }

    /**
     * Text on the nameplates fitted to this sub-level. Simulated's nameplates are block entities, not
     * Sable actors, so they are read from the sub-level's plot chunks rather than from the actor list.
     */
    default List<String> nameplates() {
        return List.of();
    }

    /** Yard terminals fitted to this sub-level, read from its plot chunks. */
    default List<TerminalInfo> terminals() {
        return List.of();
    }

    /** Simurail automatic couplers on this sub-level, with their world-space end positions. */
    default List<CouplerInfo> couplers() {
        return List.of();
    }

    /**
     * The block entities on this vehicle that are instances of the given type, as objects.
     *
     * <p>This is how one block on a vehicle finds another. It has to be done this way: a block inside an
     * assembled structure has a position in the structure's plot, millions of blocks from the origin, so
     * looking a sibling up by position in the world finds nothing at all. The structure's own actor list is
     * the only honest source.
     */
    default <T extends net.minecraft.world.level.block.entity.BlockEntity> List<T> actorsOfType(Class<T> type) {
        return List.of();
    }

    /**
     * Rotates a direction expressed in the structure's own frame into world space.
     *
     * <p>This exists because a block inside an assembled structure has a facing that means nothing in the
     * world: the structure sits in a plot far from the origin and is rotated by the physics engine. Anything
     * that compares a block's facing against a world direction - deciding which of two cabs faces the
     * destination, for instance - has to go through this, or it compares two different coordinate spaces
     * and gets a confident wrong answer.
     *
     * @return the world-space direction, or {@code null} when the orientation is not known yet
     */
    default Vec3 localToWorld(Vec3 local) {
        return null;
    }

    /** Magnitude of {@link #velocity()}, in blocks per tick. */
    default double speed() {
        return velocity().length();
    }

    /** How many physics bogeys this vehicle has. */
    int bogeyCount();

    /**
     * Bogey positions, for display and diagnostics only.
     *
     * <p>These are in the coordinate space the physics engine stores the structure in, which is
     * <b>not</b> world space: an assembled structure lives in a plot far from the origin. Never feed
     * them to a world lookup.
     */
    List<BlockPos> bogeyPositions();

    /**
     * Drive, brake and steer this vehicle.
     *
     * @param controller the block doing the commanding. It is part of the contract on purpose: an
     *     implementation hands this position to the bogies so that several controllers cannot fight
     *     over one bogey, and so that a controller can give up its hold on its own without disturbing
     *     anybody else's.
     * @param drive 0..1, or {@link Double#NaN} to leave traction alone
     * @param brake 0..1, or {@link Double#NaN} to leave braking alone
     * @param steer -1 = full left, 0 = straight, 1 = full right, or {@link Double#NaN} to leave
     *     steering alone
     */
    void command(BlockPos controller, double drive, double brake, double steer);

    /**
     * Immediately remove all linear momentum. Used by a retarder at full redstone, where 15 means
     * "stop dead" rather than the proportional brake of 1-14. Best effort: a removed or invalid body
     * simply does nothing.
     */
    default void emergencyStop() {
    }

    /** Gives up this controller's hold, handing the vehicle back to redstone and its kinetics. */
    void releaseControl(BlockPos controller);

    boolean isValid();
}
