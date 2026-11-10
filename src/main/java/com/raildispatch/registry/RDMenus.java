package com.raildispatch.registry;

import com.raildispatch.RailDispatch;
import com.raildispatch.menu.AutopilotConsoleMenu;
import com.raildispatch.menu.PhysicsCabMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Menu types. */
public final class RDMenus {

    private RDMenus() {}

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, RailDispatch.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<PhysicsCabMenu>> PHYSICS_CAB =
            MENUS.register("physics_cab", () -> IMenuTypeExtension.create(PhysicsCabMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<AutopilotConsoleMenu>> AUTOPILOT_CONSOLE =
            MENUS.register("autopilot_console", () -> IMenuTypeExtension.create(AutopilotConsoleMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<com.raildispatch.menu.YardTerminalMenu>>
            YARD_TERMINAL = MENUS.register("yard_terminal",
                    () -> IMenuTypeExtension.create(com.raildispatch.menu.YardTerminalMenu::new));

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}
