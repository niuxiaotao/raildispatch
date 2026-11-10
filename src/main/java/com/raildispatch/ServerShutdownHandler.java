package com.raildispatch;

import com.raildispatch.block.entity.AutopilotConsoleBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Clears live Drive By Wire outputs before Sable starts saving and tearing down sub-levels. */
@EventBusSubscriber(modid = "raildispatch", bus = EventBusSubscriber.Bus.GAME)
public final class ServerShutdownHandler {

    private ServerShutdownHandler() {}

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        AutopilotConsoleBlockEntity.shutdownAll(event.getServer());
    }
}
