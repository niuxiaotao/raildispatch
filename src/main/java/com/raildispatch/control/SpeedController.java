package com.raildispatch.control;

/**
 * The speed loop of the autopilot console: feed-forward plus a PID trim.
 *
 * <p>Why feed-forward first: the actuator is a directional gearbox whose redstone strength sets a
 * <em>speed</em>, not a force. At full signal a vehicle settles at {@code fullSignalSpeed}, so the main
 * term is simply {@code target / fullSignalSpeed}. A pure PID on an actuator of that kind saturates as
 * soon as the error is large, and a saturated signal means full speed - which is why a standing vehicle
 * was once commanded straight to full throttle and shot past its target. The PID's job here is only the
 * trim: remove a standing error (I), react to the trend (D), and correct the remaining difference (P).
 *
 * <p>The derivative acts on the <b>measurement</b>, not on the error. Differentiating the error puts a
 * spike into the output every time the target changes - most visibly when a red signal appears - and a
 * spike on this actuator is a full-throttle pulse. With the measurement as the input, a step in the
 * target produces no derivative at all, which is the behaviour a driver expects.
 *
 * <p>Anti-windup is both a clamp and a conditional integration: held at a red light, or dragging a load
 * it cannot move, the integral would otherwise grow for as long as the error lasts and then spend the
 * whole correction in one lurch. While the output is saturated further in the error's own direction,
 * the integral is not advanced.
 */
public final class SpeedController {

    private final double kp;
    private final double ki;
    private final double kd;
    private final double integralLimit;

    private double integral;
    private double lastMeasurement;
    private double lastDerivative;
    private long lastTick = -1L;
    private double lastOutput;

    public SpeedController(double kp, double ki, double kd, double integralLimit) {
        this.kp = Math.max(0.0D, kp);
        this.ki = Math.max(0.0D, ki);
        this.kd = Math.max(0.0D, kd);
        this.integralLimit = Math.max(0.0D, integralLimit);
    }

    /** Forgets the accumulated correction; call this whenever a new leg starts. */
    public void reset() {
        integral = 0.0D;
        lastMeasurement = 0.0D;
        lastDerivative = 0.0D;
        lastTick = -1L;
        lastOutput = 0.0D;
    }

    /**
     * One control step.
     *
     * @param now             game time in ticks, so the integral and derivative are independent of the
     *                        control period
     * @param targetSpeed     desired speed in blocks per second
     * @param measuredSpeed   measured speed in blocks per second
     * @param fullSignalSpeed the speed the vehicle settles at with the throttle wide open; used only as
     *                        the denominator of the feed-forward ratio
     * @return signed command in -1..1; positive is traction, negative is braking
     */
    public double update(long now, double targetSpeed, double measuredSpeed, double fullSignalSpeed) {
        double dt = lastTick < 0L ? 0.25D : Math.max(0.05D, Math.min(1.0D, (now - lastTick) / 20.0D));
        if (lastTick < 0L) {
            // No derivative kick on the first sample: lastMeasurement starts at zero, so a vehicle already
            // rolling at 5 b/s would otherwise produce a huge positive derivative and a braking pulse.
            lastMeasurement = measuredSpeed;
        }
        lastTick = now;

        double error = targetSpeed - measuredSpeed;
        // Feed-forward only while accelerating. It models "this redstone level settles at this speed";
        // adding it while the vehicle is ABOVE the target asks for throttle to hold a speed it is already
        // exceeding, and the PID's negative trim then has to cancel it first. That is why a train closing
        // on a station at 7.2 b/s with a 5.3 b/s target produced an output of -0.03 - almost no brake -
        // and overran the platform.
        double feedForward = measuredSpeed < targetSpeed
                ? targetSpeed / Math.max(0.5D, fullSignalSpeed) : 0.0D;

        // Derivative on measurement: positive when the vehicle is speeding up, and subtracted, so it
        // damps acceleration instead of amplifying it.
        double rawDerivative = (measuredSpeed - lastMeasurement) / dt;
        lastDerivative = lastDerivative + (rawDerivative - lastDerivative) * 0.5D;
        lastMeasurement = measuredSpeed;

        double candidate = clamp(integral + error * dt, -integralLimit, integralLimit);
        // While above target, a positive integral is acceleration banked from an earlier climb; it fights
        // the brake exactly when stopping matters most. Only a negative integral may assist an overspeed
        // correction.
        double integralUsed = error < 0.0D ? Math.min(candidate, 0.0D) : candidate;
        double output = feedForward + kp * error + ki * integralUsed - kd * lastDerivative;
        if (saturatedFurther(output, error)) {
            // Do not bank correction that cannot be spent: it would be released in one jump the moment
            // the obstruction clears.
            output = feedForward + kp * error + ki * integral - kd * lastDerivative;
        } else {
            integral = candidate;
        }

        lastOutput = clamp(output, -1.0D, 1.0D);
        return lastOutput;
    }

    public double integral() {
        return integral;
    }

    public double derivative() {
        return lastDerivative;
    }

    public double lastOutput() {
        return lastOutput;
    }

    private static boolean saturatedFurther(double output, double error) {
        return (output > 1.0D && error > 0.0D) || (output < -1.0D && error < 0.0D);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
