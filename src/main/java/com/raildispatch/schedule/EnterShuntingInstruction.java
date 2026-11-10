package com.raildispatch.schedule;

import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction;
import net.createmod.catnip.data.Pair;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Schedule entry: enter shunting mode. Signals may be passed until the matching exit entry. */
public class EnterShuntingInstruction extends ScheduleInstruction {

    public static final String PATH = "enter_shunting";
    private static final ResourceLocation ID = ResourceLocation.parse("raildispatch:" + PATH);

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public boolean supportsConditions() {
        return false;
    }

    @Override
    public DiscoveredPath start(ScheduleRuntime runtime, Level level) {
        return null;
    }

    @Override
    public Pair<ItemStack, Component> getSummary() {
        return Pair.of(new ItemStack(com.raildispatch.registry.RDItems.AUTOPILOT_CONSOLE_ITEM.get()),
                Component.translatable("raildispatch.schedule.instruction." + PATH));
    }
}
