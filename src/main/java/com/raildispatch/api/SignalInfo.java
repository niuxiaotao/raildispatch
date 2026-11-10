package com.raildispatch.api;

import net.minecraft.world.phys.Vec3;

/**
 * What the driver sees: the governing aspect, how far away it is, and why it is red.
 *
 * @param aspect           the governing aspect
 * @param distance         distance from the vehicle to the signal, in blocks; negative when unknown
 * @param position         where the signal is, when it could be located
 * @param occupiedByTrain  true when the section this signal protects is occupied by a Create train.
 *     This is what separates "red because somebody else is in the block" - which must never be passed
 *     - from "red and the block looks empty", which may be the vehicle's own occupancy.
 */
public record SignalInfo(SignalAspect aspect, double distance, Vec3 position, boolean occupiedByTrain) {

    public static final SignalInfo NONE = new SignalInfo(SignalAspect.NONE, -1.0D, null, false);

    public boolean hasDistance() {
        return distance >= 0.0D;
    }

    public boolean isPermissive() {
        return aspect.isPermissive();
    }

    /** A red that is known to be somebody else's train: absolutely not to be passed. */
    public boolean isHeldByAnotherTrain() {
        return aspect == SignalAspect.DANGER && occupiedByTrain;
    }
}
