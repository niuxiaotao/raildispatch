package com.raildispatch;

import com.mojang.logging.LogUtils;
import com.raildispatch.compat.RailCompat;
import com.raildispatch.registry.RDBlockEntities;
import com.raildispatch.registry.RDBlocks;
import com.raildispatch.registry.RDItems;
import com.raildispatch.registry.RDMenus;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Aeronautics Rail Dispatch.
 *
 * <p>Brings Create's train automation model to physics vehicles: a schedule-driven cab that obeys
 * signals and asks the ground layer for a route, a reverser that steers a physics bogie, and two
 * kinds of tags used for addressing vehicles and shunting destinations.
 */
@Mod(RailDispatch.MOD_ID)
public class RailDispatch {

    public static final String MOD_ID = "raildispatch";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** High-frequency control-loop output, disabled unless the debug config says otherwise. */
    public static void debug(String message, Object... arguments) {
        if (RDConfig.debugLogging()) {
            LOGGER.info(message, arguments);
        }
    }

    public RailDispatch(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, RDConfig.SPEC);

        // Must happen before any track graph is loaded: a graph resolves its points by looking their
        // type up by id, so the dispatch centre's edge point type has to be registered by now.
        try {
            com.raildispatch.registry.RDPoints.register();
        } catch (Throwable throwable) {
            LOGGER.error("Could not register the dispatch centre edge point type; it will not be "
                    + "placeable against a track", throwable);
        }

        RDBlocks.register(modBus);
        RDItems.register(modBus);
        RDBlockEntities.register(modBus);
        RDMenus.register(modBus);
        RailCompat.install(modBus);
        com.raildispatch.registry.RDTabs.register(modBus);

        // Server-side yard automation: nameplate routing for free wagons. The feature is inert when no
        // nameplates or station signs are present, so it costs one scan per second otherwise.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                com.raildispatch.train.YardRouter::onServerTick);

        LOGGER.info("Rail Dispatch loaded: physics-vehicle automation for Create: Aeronautics");
    }
}
