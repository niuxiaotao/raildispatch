package com.raildispatch.api;

import java.util.List;

/**
 * What the vehicle's own track probe reports ahead of it.
 *
 * <p>This exists so the mod does not have to re-derive things the physics engine has already worked out.
 * Simurail's bogies run a probe along the rails and publish, among other things, how far the next signal is,
 * whether that signal is aligned with the direction of travel, and how far the next station is. Computing
 * that again from the track graph would be a second implementation of the same geometry, and the engine's
 * own answer is computed along the rails rather than as a straight line - so it is both less work and more
 * correct, especially on curves.
 *
 * @param signalDistance        blocks to the next signal, or a negative value when there is none
 * @param signalAligned         true when that signal governs the direction the vehicle is travelling in.
 *     This is Simurail's own computation, identical to Create's {@code SignalBoundary.canNavigateVia}
 *     against the node being approached
 * @param signalNames           names attached to that signal, possibly empty
 * @param stationDistance       blocks to the next station, or a negative value when there is none
 * @param stationName           the name of that station, or an empty string
 * @param discontinuityDistance blocks to the point where the track simply ends, or a negative value when the
 *     track continues. This distinguishes "there is no aligned target on this stretch" from "the rails run
 *     out", which are different failures and deserve different messages
 */
public record TrackProbe(double signalDistance, boolean signalAligned, List<String> signalNames,
                         double stationDistance, String stationName, double discontinuityDistance) {

    /** True when the probe reported something at all. */
    public boolean present() {
        return signalDistance >= 0.0D || stationDistance >= 0.0D || discontinuityDistance >= 0.0D;
    }
}
