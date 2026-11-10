package com.raildispatch.network;

import com.raildispatch.block.entity.AutopilotConsoleBlockEntity;
import com.raildispatch.block.entity.PhysicsCabBlockEntity;
import com.raildispatch.api.TerminalInfo;
import com.raildispatch.data.YardAssignments;
import com.raildispatch.menu.AutopilotConsoleMenu;
import com.raildispatch.menu.YardTerminalMenu;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Network registration for the mod.
 *
 * <p>Registered through the annotation rather than from the mod constructor, so the client and server
 * halves cannot drift apart: the payload type is declared once, in the packet class.
 */
@EventBusSubscriber(modid = "raildispatch", bus = EventBusSubscriber.Bus.MOD)
public final class RDNetwork {

    private RDNetwork() {}

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        // One registrar for the whole mod. Asking the event for a second registrar with the same version
        // is not a second call to the same registry; it is a second channel negotiation, and only one of
        // them may define a version.
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(CabControlPayload.TYPE, CabControlPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)) {
                        return;
                    }
                    // There is deliberately no distance check here. The cab's own position is a plot
                    // coordinate - an assembled structure's blocks live millions of blocks from the
                    // origin, observed at x = 20481032 - so comparing it against a player standing in the
                    // world is always false, and every packet was being discarded. Authority comes from
                    // the cab instead: setManualInput only accepts input from the player who is actually
                    // holding that cab's controls, which is the check that matters.
                    if (player.level().getBlockEntity(payload.cab()) instanceof PhysicsCabBlockEntity cab) {
                        cab.setManualInput(player, payload.forward(), payload.backward(), payload.brake());
                    }
                }));

        registrar.playToServer(AutopilotCommandPayload.TYPE, AutopilotCommandPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)) {
                        return;
                    }
                    // Same reasoning as the cab's packet: no distance check, because an assembled
                    // structure's block position is a plot coordinate and any position comparison with
                    // the player standing in the world is false. The authority is instead "this player has
                    // this console's screen open", which is the state the buttons are pressed in - a
                    // forged packet without the menu is ignored.
                    if (!(player.containerMenu instanceof AutopilotConsoleMenu open)
                            || !open.pos().equals(payload.console())) {
                        return;
                    }
                    if (player.level().getBlockEntity(payload.console())
                            instanceof AutopilotConsoleBlockEntity console) {
                        console.handleCommand(player, payload.destination(), payload.action(), payload.clock());
                    }
                }));

        registrar.playToServer(AutopilotTuningPayload.TYPE, AutopilotTuningPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)) {
                        return;
                    }
                    if (!(player.containerMenu instanceof AutopilotConsoleMenu open)
                            || !open.pos().equals(payload.console())) {
                        return;
                    }
                    if (player.level().getBlockEntity(payload.console())
                            instanceof AutopilotConsoleBlockEntity console) {
                        console.applyTuning(payload.maxSpeed(), payload.acceleration(),
                                payload.brakeDecel(), payload.approach(), payload.smoothing());
                    }
                }));

        registrar.playToServer(YardTerminalPayload.TYPE, YardTerminalPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)) {
                        return;
                    }
                    if (!(player.containerMenu instanceof YardTerminalMenu)) {
                        return;
                    }
                    YardAssignments assignments = YardAssignments.get(player.serverLevel());
                    for (YardTerminalPayload.Entry entry : payload.entries()) {
                        assignments.set(entry.vehicle(),
                                new TerminalInfo(entry.destination(), entry.consist(),
                                        entry.service(), entry.type()));
                    }
                }));
    }
}
