package com.raildispatch.registry;

import com.raildispatch.RailDispatch;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The mod's own creative tab.
 *
 * <p>A tab of our own is not just decoration: the vanilla tab keys are private in this Minecraft
 * version, and adding to Create's tab would mean depending on a Create internal that addons are not
 * meant to touch.
 */
public final class RDTabs {

    private RDTabs() {}

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RailDispatch.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.raildispatch"))
                    .icon(() -> new ItemStack(RDItems.AUTOPILOT_CONSOLE_ITEM.get()))
                    .displayItems((parameters, output) -> {
                        var all = CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS;
                        // The old physical cab is intentionally not offered in creative any more. Its item
                        // and block stay registered so existing worlds and placed cabs keep loading; it is
                        // simply no longer the block to build new vehicles with.
                        output.accept(new ItemStack(RDItems.AUTOPILOT_CONSOLE_ITEM.get()), all);
                        output.accept(new ItemStack(RDItems.DISPATCH_CENTER_ITEM.get()), all);
                        output.accept(new ItemStack(RDItems.RETARDER_ITEM.get()), all);
                        output.accept(new ItemStack(RDItems.SMART_RETARDER_ITEM.get()), all);
                        output.accept(new ItemStack(RDItems.AUTOMATIC_UNCOUPLER_ITEM.get()), all);
                        output.accept(new ItemStack(RDItems.YARD_TERMINAL_ITEM.get()), all);
                        output.accept(new ItemStack(RDItems.BALLAST_ITEM.get()), all);
                        output.accept(new ItemStack(RDItems.BALLAST_STAIRS_ITEM.get()), all);
                        output.accept(new ItemStack(RDItems.BALLAST_SLAB_ITEM.get()), all);
                        output.accept(new ItemStack(RDItems.BALLAST_STEP_ITEM.get()), all);
                        output.accept(new ItemStack(RDItems.VEHICLE_TAG.get()), all);
                        output.accept(new ItemStack(RDItems.ADDRESS_TAG.get()), all);
                    })
                    .build());

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
