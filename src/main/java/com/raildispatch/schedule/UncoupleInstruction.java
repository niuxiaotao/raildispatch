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
 * A schedule entry that uncouples the vehicle.
 *
 * <p>Create has no built-in uncouple instruction. This entry is executed by the autopilot console:
 * it pulses the console's redstone output and its Drive By Wire uncouple channel for one second, so a
 * Simurail automatic coupler wired to either opens.
 */
public class UncoupleInstruction extends TextScheduleInstruction {

    public static final String PATH = "uncouple";
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
        return Pair.of(new ItemStack(com.raildispatch.registry.RDItems.AUTOMATIC_UNCOUPLER_ITEM.get()),
                Component.translatable("raildispatch.schedule.instruction." + PATH));
    }

    public static Optional<String> textOf(ScheduleInstruction instruction) {
        if (!(instruction instanceof UncoupleInstruction)) {
            return Optional.empty();
        }
        CompoundTag data = instruction.getData();
        if (data == null) {
            return Optional.empty();
        }
        String text = data.getString("Text");
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.trim());
    }
}
