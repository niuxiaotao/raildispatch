package com.raildispatch.client;

import com.raildispatch.RailDispatch;
import com.raildispatch.registry.RDMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * Client-only mod entry point.
 *
 * <p>A separate {@code @Mod} class restricted to the client is preferred here over
 * {@code @EventBusSubscriber}, because the bus selector of that annotation is deprecated in this
 * NeoForge line and this form also keeps every client class out of the dedicated server.
 */
@Mod(value = RailDispatch.MOD_ID, dist = Dist.CLIENT)
public class RailDispatchClient {

    public RailDispatchClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::registerScreens);
    }

    private void registerScreens(RegisterMenuScreensEvent event) {
        event.register(RDMenus.PHYSICS_CAB.get(), PhysicsCabScreen::new);
        event.register(RDMenus.AUTOPILOT_CONSOLE.get(), AutopilotConsoleScreen::new);
        event.register(RDMenus.YARD_TERMINAL.get(), YardTerminalScreen::new);
    }
}
