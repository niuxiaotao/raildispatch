package com.raildispatch.api;

import net.minecraft.network.chat.Component;

/** The four aspects a driver cares about, mapped from Create's track signals. */
public enum SignalAspect {
    /** No signal governs this stretch of track. */
    NONE,
    /** Green: proceed at line speed. */
    CLEAR,
    /** Yellow: proceed prepared to stop at the next signal. */
    CAUTION,
    /** Red: stop before the signal. */
    DANGER;

    public Component translation() {
        return Component.translatable("raildispatch.signal." + name().toLowerCase(java.util.Locale.ROOT));
    }

    public boolean isPermissive() {
        return this == CLEAR || this == NONE;
    }
}
