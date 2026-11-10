package com.raildispatch.api;

/**
 * Service locator for the two optional integrations. Implementations are installed by
 * {@code com.raildispatch.compat.RailCompat} during mod construction; until then, and whenever a
 * dependency is missing, the no-op instances are used and the mod degrades gracefully instead of
 * crashing.
 */
public final class RailServices {

    private RailServices() {}

    private static volatile PhysicsBridge physics = PhysicsBridge.NONE;
    private static volatile TrackNetwork track = TrackNetwork.NONE;
    private static volatile CouplerBridge coupler = CouplerBridge.NONE;
    private static final java.util.List<SwitchDriver> SWITCH_DRIVERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static PhysicsBridge physics() {
        return physics;
    }

    public static TrackNetwork track() {
        return track;
    }

    public static CouplerBridge coupler() {
        return coupler;
    }

    public static void installCoupler(CouplerBridge bridge) {
        coupler = bridge == null ? CouplerBridge.NONE : bridge;
    }

    /** Registers a driver for a switch block added by another mod. */
    public static void registerSwitchDriver(SwitchDriver driver) {
        if (driver != null) {
            SWITCH_DRIVERS.add(driver);
        }
    }

    public static java.util.List<SwitchDriver> switchDrivers() {
        return java.util.List.copyOf(SWITCH_DRIVERS);
    }

    public static void installPhysics(PhysicsBridge bridge) {
        physics = bridge == null ? PhysicsBridge.NONE : bridge;
    }

    public static void installTrack(TrackNetwork network) {
        track = network == null ? TrackNetwork.NONE : network;
    }

    /** True when both the physics engine and the physics bogies are usable. */
    public static boolean physicsAvailable() {
        return physics.isAvailable();
    }

    /** True when Create's track graph is present, regardless of whether a dispatch centre exists. */
    public static boolean trackAvailable() {
        return track.isAvailable();
    }
}
