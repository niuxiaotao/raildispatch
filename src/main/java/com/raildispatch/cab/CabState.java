package com.raildispatch.cab;

import net.minecraft.network.chat.Component;

/** What a physics cab is currently doing. Drives the GUI label and the block's lit state. */
public enum CabState {
    /** No vehicle, or no usable schedule. */
    IDLE,
    /** Waiting for the ground layer to grant a route. */
    REQUESTING_ROUTE,
    /** Running under a clear signal. */
    RUNNING,
    /** Slowing down for a signal or a destination. */
    APPROACHING,
    /** Stopped short of a red signal. */
    HOLDING,
    /** Stopped at a destination, waiting out the schedule's delay. */
    WAITING,
    /** The destination has been reached and the entry is finished. */
    ARRIVED,
    /** Closing in on a tagged vehicle to couple to it. */
    COUPLING;

    public Component translation() {
        return Component.translatable("raildispatch.cab.state." + name().toLowerCase(java.util.Locale.ROOT));
    }
}
