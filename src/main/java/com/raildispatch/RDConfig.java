package com.raildispatch;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common configuration. Every value here is read through the static accessors so that a config
 * reload is picked up without restarting.
 */
public final class RDConfig {

    private RDConfig() {}

    public static final ModConfigSpec SPEC;

    // ---- physics / driving ----
    private static final ModConfigSpec.DoubleValue MAX_SPEED;
    private static final ModConfigSpec.DoubleValue DRIVE_GAIN;
    private static final ModConfigSpec.DoubleValue BRAKE_GAIN;
    private static final ModConfigSpec.DoubleValue STEER_GAIN;
    private static final ModConfigSpec.DoubleValue ARRIVE_RADIUS;
    private static final ModConfigSpec.DoubleValue SMART_RETARDER_GAP;

    // ---- signals ----
    private static final ModConfigSpec.DoubleValue SIGNAL_LOOKAHEAD;
    private static final ModConfigSpec.DoubleValue SIGNAL_BRAKE_MARGIN;
    private static final ModConfigSpec.BooleanValue STOP_ON_DANGER;
    private static final ModConfigSpec.BooleanValue CREEP_ON_CAUTION;
    private static final ModConfigSpec.IntValue CREEP_AFTER_TICKS;
    private static final ModConfigSpec.DoubleValue CREEP_SPEED;

    // ---- dispatch ----
    private static final ModConfigSpec.BooleanValue PREFER_DISPATCH_CENTER;
    private static final ModConfigSpec.BooleanValue ALLOW_LOCAL_SWITCH_CONTROL;
    private static final ModConfigSpec.IntValue DISPATCH_CENTER_RANGE;
    private static final ModConfigSpec.IntValue ROUTE_TIMEOUT_TICKS;

    // ---- tags ----
    private static final ModConfigSpec.IntValue MAX_TAG_LENGTH;
    private static final ModConfigSpec.BooleanValue CONSUME_TAGS;
    private static final ModConfigSpec.DoubleValue COUPLE_RANGE;

    // ---- pathfinding ----
    private static final ModConfigSpec.BooleanValue NATIVE_STATION_ROUTING;
    private static final ModConfigSpec.BooleanValue DEBUG_LOGGING;

    // ---- autopilot console ----
    private static final ModConfigSpec.DoubleValue AUTOPILOT_FULL_SIGNAL_SPEED;
    private static final ModConfigSpec.DoubleValue AUTOPILOT_PID_KP;
    private static final ModConfigSpec.DoubleValue AUTOPILOT_PID_KI;
    private static final ModConfigSpec.DoubleValue AUTOPILOT_PID_KD;
    private static final ModConfigSpec.DoubleValue AUTOPILOT_PID_INTEGRAL_LIMIT;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("How the physics cab drives a vehicle's bogeys.").push("driving");
        MAX_SPEED = b.comment("Target cruise speed in blocks per second when running under a clear signal.")
                .defineInRange("maxSpeed", 12.0D, 0.5D, 200.0D);
        DRIVE_GAIN = b.comment("Multiplier applied to the traction command sent to the bogeys (0..1 command scale).")
                .defineInRange("driveGain", 1.0D, 0.05D, 4.0D);
        BRAKE_GAIN = b.comment("Multiplier applied to the braking command sent to the bogeys.")
                .defineInRange("brakeGain", 1.0D, 0.05D, 4.0D);
        STEER_GAIN = b.comment("Multiplier applied to the steering command sent by the reverser.")
                .defineInRange("steerGain", 1.0D, 0.05D, 4.0D);
        ARRIVE_RADIUS = b.comment("Horizontal distance, in blocks, at which a destination counts as reached. "
                        + "This has to be generous and must ignore height: a Create station block stands "
                        + "beside the track rather than on it, and the vehicle's position is its centre of "
                        + "mass, so the closest the two ever come can be several blocks.")
                .defineInRange("arriveRadius", 2.0D, 0.5D, 32.0D);
        SMART_RETARDER_GAP = b.comment("Centre-to-centre gap, in blocks, that a smart retarder keeps "
                        + "between a free wagon and the wagon ahead of it.")
                .defineInRange("smartRetarderGap", 8.0D, 2.0D, 32.0D);
        b.pop();

        b.comment("Reading Create train signals from a physics vehicle.").push("signals");
        SIGNAL_LOOKAHEAD = b.comment("How far ahead, in blocks, the cab looks for a signal.")
                .defineInRange("lookahead", 64.0D, 4.0D, 512.0D);
        SIGNAL_BRAKE_MARGIN = b.comment("Extra distance kept in front of a red signal before stopping.")
                .defineInRange("brakeMargin", 6.0D, 0.0D, 128.0D);
        STOP_ON_DANGER = b.comment("Stop the vehicle when the signal ahead is red.")
                .define("stopOnDanger", true);
        CREEP_ON_CAUTION = b.comment("Limit the vehicle to a crawl when the signal ahead is yellow.")
                .define("creepOnCaution", true);
        CREEP_AFTER_TICKS = b.comment("OFF by default, and that default matters: creeping past a red was "
                        + "how the vehicle came to run one. When set above zero, a red that has not cleared "
                        + "for this many ticks while the gap is no longer closing is passed at creepSpeed - "
                        + "but only if the protected section is empty of Create trains, so a red caused by "
                        + "another train is never passed. Leave this at 0 unless you specifically want "
                        + "call-on behaviour.")
                .defineInRange("creepAfterTicks", 0, 0, 24000);
        CREEP_SPEED = b.comment("Restricted speed used to leave a block the vehicle itself is occupying, "
                        + "in blocks per second.")
                .defineInRange("creepSpeed", 2.0D, 0.2D, 20.0D);
        b.pop();

        b.comment("Ground layer: dispatch centre and switches.").push("dispatch");
        PREFER_DISPATCH_CENTER = b.comment("Ask a dispatch centre for a route when one is in range.")
                .define("preferDispatchCenter", true);
        ALLOW_LOCAL_SWITCH_CONTROL = b.comment(
                        "When no dispatch centre is in range, let the cab lock a route and drive the switches itself.")
                .define("allowLocalSwitchControl", true);
        DISPATCH_CENTER_RANGE = b.comment("Range, in blocks, in which a dispatch centre serves a cab.")
                .defineInRange("dispatchCenterRange", 256, 16, 4096);
        ROUTE_TIMEOUT_TICKS = b.comment("A granted route is released after this many ticks without the vehicle moving.")
                .defineInRange("routeTimeoutTicks", 600, 20, 24000);
        b.pop();

        b.comment("Vehicle and address tags.").push("tags");
        MAX_TAG_LENGTH = b.comment("Maximum length of a tag name.").defineInRange("maxTagLength", 48, 1, 256);
        CONSUME_TAGS = b.comment("Consume a tag item when it is bound to a vehicle or an address plate.")
                .define("consumeTags", false);
        COUPLE_RANGE = b.comment("Maximum distance, in blocks, at which two vehicles may be coupled.")
                .defineInRange("coupleRange", 8.0D, 1.0D, 64.0D);
        b.pop();

        b.comment("Verbose diagnostics for the control loops. OFF by default: publishing, bogie and "
                        + "path-search lines can run several times per tick.").push("debug");
        DEBUG_LOGGING = b.comment("Enable high-frequency control-loop logging.")
                .define("debugLogging", false);
        b.pop();

        b.comment("Route planning. Station destinations use a port of Create's own Navigation.search; "
                        + "address plates and vehicle tags use the geometric fallback.")
                .push("pathfinding");
        NATIVE_STATION_ROUTING = b.comment("Use the native-equivalent station search. Turn this off to compare "
                        + "with the geometric planner, or to fall back if a pack's track graph makes the "
                        + "port misbehave.")
                .define("nativeStationRouting", true);
        b.pop();

        b.comment("Autopilot console: the speed loop that drives it. The proportional-integral-derivative "
                        + "gains are per block per second, and the feed-forward term divides the target speed "
                        + "by fullSignalSpeed - the speed the vehicle settles at with the throttle wide open.")
                .push("autopilot");
        AUTOPILOT_FULL_SIGNAL_SPEED = b.comment("The speed, in blocks per second, this vehicle settles at "
                        + "when the throttle channel is at full strength. Set it per vehicle: it is the ratio "
                        + "in the feed-forward term, so it is what makes one control law correct on a heavy "
                        + "locomotive and a light railcar alike.")
                .defineInRange("fullSignalSpeed", 12.0D, 1.0D, 200.0D);
        AUTOPILOT_PID_KP = b.comment("Proportional gain: command per block per second of error.")
                .defineInRange("pidKp", 0.22D, 0.0D, 20.0D);
        AUTOPILOT_PID_KI = b.comment("Integral gain: removes a standing speed error (a grade, a load).")
                .defineInRange("pidKi", 0.05D, 0.0D, 20.0D);
        AUTOPILOT_PID_KD = b.comment("Derivative gain: damps acceleration. Acts on the measurement, not on "
                        + "the error, so a step in the target speed produces no derivative kick.")
                .defineInRange("pidKd", 0.06D, 0.0D, 20.0D);
        AUTOPILOT_PID_INTEGRAL_LIMIT = b.comment("Clamp on the accumulated integral term. Keeps a long "
                        + "standstill - at a red signal, or dragging a load it cannot move - from banking a "
                        + "correction to spend all at once later.")
                .defineInRange("pidIntegralLimit", 3.0D, 0.0D, 100.0D);
        b.pop();

        SPEC = b.build();
    }

    public static double maxSpeed() { return MAX_SPEED.get(); }
    public static double driveGain() { return DRIVE_GAIN.get(); }
    public static double brakeGain() { return BRAKE_GAIN.get(); }
    public static double steerGain() { return STEER_GAIN.get(); }
    public static double arriveRadius() { return ARRIVE_RADIUS.get(); }
    public static double smartRetarderGap() { return SMART_RETARDER_GAP.get(); }

    public static double signalLookahead() { return SIGNAL_LOOKAHEAD.get(); }
    public static double signalBrakeMargin() { return SIGNAL_BRAKE_MARGIN.get(); }
    public static boolean stopOnDanger() { return STOP_ON_DANGER.get(); }
    public static boolean creepOnCaution() { return CREEP_ON_CAUTION.get(); }
    public static int creepAfterTicks() { return CREEP_AFTER_TICKS.get(); }
    public static double creepSpeed() { return CREEP_SPEED.get(); }

    public static boolean preferDispatchCenter() { return PREFER_DISPATCH_CENTER.get(); }
    public static boolean allowLocalSwitchControl() { return ALLOW_LOCAL_SWITCH_CONTROL.get(); }
    public static int dispatchCenterRange() { return DISPATCH_CENTER_RANGE.get(); }
    public static int routeTimeoutTicks() { return ROUTE_TIMEOUT_TICKS.get(); }

    public static int maxTagLength() { return MAX_TAG_LENGTH.get(); }
    public static boolean consumeTags() { return CONSUME_TAGS.get(); }
    public static double coupleRange() { return COUPLE_RANGE.get(); }

    public static boolean nativeStationRouting() { return NATIVE_STATION_ROUTING.get(); }
    public static boolean debugLogging() { return DEBUG_LOGGING.get(); }
    public static double autopilotFullSignalSpeed() { return AUTOPILOT_FULL_SIGNAL_SPEED.get(); }
    public static double autopilotPidKp() { return AUTOPILOT_PID_KP.get(); }
    public static double autopilotPidKi() { return AUTOPILOT_PID_KI.get(); }
    public static double autopilotPidKd() { return AUTOPILOT_PID_KD.get(); }
    public static double autopilotPidIntegralLimit() { return AUTOPILOT_PID_INTEGRAL_LIMIT.get(); }
}
