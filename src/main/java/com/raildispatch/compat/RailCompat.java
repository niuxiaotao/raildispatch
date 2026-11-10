package com.raildispatch.compat;

import com.raildispatch.RailDispatch;
import com.raildispatch.api.RailServices;
import net.neoforged.bus.api.IEventBus;

/**
 * Wires the two integrations into {@link RailServices}.
 *
 * <p>Every installation step is isolated: a pack without physics bogies or without Create still
 * starts, it just has no vehicles to drive or no signals to obey. Failures are logged instead of
 * thrown, because a mod that refuses to load is worse than a mod that degrades.
 */
public final class RailCompat {

    private RailCompat() {}

    public static void install(IEventBus modBus) {
        try {
            RailServices.installPhysics(com.raildispatch.physics.SablePhysicsBridge.create());
            RailDispatch.debug("Physics bridge ready: {}", RailServices.physics().getClass().getSimpleName());
        } catch (Throwable throwable) {
            RailDispatch.LOGGER.error("Physics integration unavailable, the cab will not be able to drive anything",
                    throwable);
        }

        try {
            RailServices.installTrack(com.raildispatch.train.CreateTrackNetwork.create());
            com.raildispatch.train.CreateTrackNetwork.registerScheduleInstructions();
            com.raildispatch.train.CreateTrackNetwork.registerSwitchDrivers(modBus);
            RailDispatch.debug("Track network ready, {} switch driver(s) registered",
                    RailServices.switchDrivers().size());
        } catch (Throwable throwable) {
            RailDispatch.LOGGER.error("Create integration unavailable, signals and routes are disabled",
                    throwable);
        }
    }
}
