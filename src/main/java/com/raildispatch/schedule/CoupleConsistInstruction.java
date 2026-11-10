package com.raildispatch.schedule;

import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction;
import com.simibubi.create.content.trains.schedule.destination.TextScheduleInstruction;
import java.util.Optional;
import net.createmod.catnip.data.Pair;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * "Couple to the consist with this service number." The text box holds the service number assigned in
 * the hand-held yard terminal. The approach side is taken from the next destination in the schedule.
 */
public class CoupleConsistInstruction extends TextScheduleInstruction {

    public static final String PATH = "couple_consist";
    private static final ResourceLocation ID = ResourceLocation.parse("raildispatch:" + PATH);
    public static final String TEXT_KEY = "Text";

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
        return Pair.of(new ItemStack(com.raildispatch.registry.RDItems.VEHICLE_TAG.get()),
                Component.translatable("raildispatch.schedule.instruction." + PATH));
    }

    public static Optional<String> serviceOf(ScheduleInstruction instruction) {
        if (!(instruction instanceof CoupleConsistInstruction)) {
            return Optional.empty();
        }
        CompoundTag data = instruction.getData();
        if (data == null) {
            return Optional.empty();
        }
        String text = data.getString(TEXT_KEY);
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.trim());
    }
}
