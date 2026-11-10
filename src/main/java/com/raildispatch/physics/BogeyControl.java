package com.raildispatch.physics;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import net.minecraft.core.BlockPos;

/**
 * Writes a controller's command into a Simurail physics bogey.
 *
 * <p>Which channel to use was decided by reading the runtime version's bytecode, not by guessing:
 *
 * <ul>
 *   <li>{@code PhysicsBogeyBlockEntity.tick()} calls {@code computerOverrides.reset()} every tick
 *       whenever no ComputerCraft computer is attached. Writing the computer override fields is
 *       therefore pointless for us - it is erased before it can do anything.</li>
 *   <li>The <b>remote override</b> API added in Simurail 0.1.0 - {@code setRemoteBrakeOverride},
 *       {@code setRemoteStrengthOverride}, {@code setRemoteLeftSteerOverride},
 *       {@code setRemoteRightSteerOverride} and {@code removeRemoteController} - is a separate set of
 *       maps keyed by the controlling block's position. Nothing resets them, and the controller
 *       position is exactly the argument they expect: this API exists for a block elsewhere driving a
 *       bogie, which is precisely what a cab and a reverser are.</li>
 * </ul>
 *
 * <p>Values are redstone-style signals (0..15) because that is what the remote API takes: traction and
 * braking are a single 0..15 level, and steering is a left/right pair, mirroring how a bogie's own
 * redstone steering inputs work.
 */
final class BogeyControl {

    private BogeyControl() {}

    private static final int MAX_SIGNAL = 15;

    /**
     * @param drive 0..1, or {@link Double#NaN} to leave unchanged
     * @param brake 0..1, or {@link Double#NaN} to leave unchanged
     * @param steer -1..1, or {@link Double#NaN} to leave unchanged
     */
    static void command(PhysicsBogeyBlockEntity bogey, BlockPos controller, double drive, double brake,
                        double steer) {
        try {
            if (!Double.isNaN(drive)) {
                bogey.setRemoteStrengthOverride(controller, toSignal(drive));
            }
            if (!Double.isNaN(brake)) {
                bogey.setRemoteBrakeOverride(controller, toSignal(brake));
            }
            if (!Double.isNaN(steer)) {
                double clamped = Math.max(-1.0D, Math.min(1.0D, steer));
                // Left and right are separate inputs, so a steer command is one of them lit and the
                // other cleared - otherwise a stale signal from the previous tick would keep pulling
                // the bogie the other way.
                bogey.setRemoteLeftSteerOverride(controller, clamped < 0.0D ? toSignal(-clamped) : 0);
                bogey.setRemoteRightSteerOverride(controller, clamped > 0.0D ? toSignal(clamped) : 0);
            }
        } catch (Throwable throwable) {
            com.raildispatch.RailDispatch.LOGGER.warn("Could not command a physics bogey at {}",
                    bogey.getBlockPos(), throwable);
        }
    }

    /** Removes this controller's hold, so the bogie falls back to redstone and its kinetics. */
    static void release(PhysicsBogeyBlockEntity bogey, BlockPos controller) {
        try {
            bogey.removeRemoteController(controller);
        } catch (Throwable throwable) {
            com.raildispatch.RailDispatch.LOGGER.warn("Could not release a physics bogey at {}",
                    bogey.getBlockPos(), throwable);
        }
    }

    private static int toSignal(double value01) {
        double clamped = Math.max(0.0D, Math.min(1.0D, value01));
        return (int) Math.round(clamped * MAX_SIGNAL);
    }
}
