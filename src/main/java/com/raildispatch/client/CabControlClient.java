package com.raildispatch.client;

import com.raildispatch.network.CabControlPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sends the player's movement keys to the cab they have taken control of.
 *
 * <p>The client decides which cab that is from its own gestures rather than waiting to be told: the same
 * right-click that engages the cab on the server also runs here, so the cab the player last interacted
 * with is simply remembered. The server remains the authority - it ignores input from anyone who is not
 * actually holding that cab's controls.
 *
 * <p>Input is only sent when it changes, and a release is sent when control is handed back, so a player
 * who walks away or lets go cannot leave the vehicle running on a stale command.
 */
@EventBusSubscriber(modid = "raildispatch", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class CabControlClient {

    private static BlockPos cab;
    private static boolean active;
    private static boolean lastForward;
    private static boolean lastBackward;
    private static boolean lastBrake;

    private CabControlClient() {}

    /** Called from the block when the player right-clicks it with an empty hand. */
    public static void onInteract(BlockPos pos) {
        if (active && pos.equals(cab)) {
            active = false;
            send(false, false, false);
            cab = null;
            return;
        }
        cab = pos.immutable();
        active = true;
        lastForward = false;
        lastBackward = false;
        lastBrake = false;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (!active || cab == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        // While a screen is open the keys belong to it, not to the vehicle.
        if (minecraft.screen != null) {
            return;
        }
        boolean forward = minecraft.options.keyUp.isDown();
        boolean backward = minecraft.options.keyDown.isDown();
        // Space is the vanilla jump key, which is exactly the key a driver means by "brake".
        boolean brake = minecraft.options.keyJump.isDown();
        if (forward == lastForward && backward == lastBackward && brake == lastBrake) {
            return;
        }
        lastForward = forward;
        lastBackward = backward;
        lastBrake = brake;
        send(forward, backward, brake);
    }

    /**
     * Stops the player walking while they are driving.
     *
     * <p>The impulses are zeroed here, after the engine has computed them and before they are applied.
     * Doing it this way rather than cancelling the movement server side matters: the server route produces
     * the classic rubber-band, whereas this simply means the player stands still. It also cannot cost us
     * our own input, because the keys are read straight from {@code options.keyUp}/{@code keyDown} rather
     * than from these impulses.
     */
    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!active || cab == null) {
            return;
        }
        Input input = event.getInput();
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
        // Space is the brake while driving, so it must not also make the driver hop: one key cannot both stop
        // the vehicle and launch the person stopping it.
        input.jumping = false;
    }

    private static void send(boolean forward, boolean backward, boolean brake) {
        if (cab != null) {
            PacketDistributor.sendToServer(new CabControlPayload(cab, forward, backward, brake));
        }
    }
}
