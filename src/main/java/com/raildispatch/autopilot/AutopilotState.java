package com.raildispatch.autopilot;

import java.util.Locale;
import net.minecraft.network.chat.Component;

/**
 * What the autopilot console is doing.
 *
 * <p>Deliberately a shorter list than the physics cab's: the console has no schedule, so it has no
 * entries to wait out and nothing to couple to. What remains is the journey itself - plan, run, slow,
 * hold, arrive - and every state maps to a distinct comparator level so a redstone circuit can tell
 * them apart without a screen.
 */
public enum AutopilotState {

    /** No destination, not started, or a physics cab has taken the vehicle over. */
    IDLE(0),
    /** Asking the ground layer for a route to the destination. */
    PLANNING(4),
    /** Running under a clear signal. */
    RUNNING(8),
    /** Slowing for a signal, a turn, or the end of the route. */
    APPROACHING(11),
    /** Stopped: a red signal, a redstone input, or a route that could not be granted. */
    HOLDING(15),
    /** The destination has been reached. */
    ARRIVED(2);

    private final int comparatorOutput;

    AutopilotState(int comparatorOutput) {
        this.comparatorOutput = comparatorOutput;
    }

    public int comparatorOutput() {
        return comparatorOutput;
    }

    public Component translation() {
        return Component.translatable("raildispatch.autopilot.state." + name().toLowerCase(Locale.ROOT));
    }
}
