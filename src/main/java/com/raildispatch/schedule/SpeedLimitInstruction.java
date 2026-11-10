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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * Schedule entry: set the autopilot's speed limit for the following entries.
 *
 * <p>The text is blocks per second, matching the console's speed readout. "0" clears the limit. This
 * is a no-movement entry: the console applies it and advances immediately, exactly like the shunting
 * mode entries.
 */
public class SpeedLimitInstruction extends TextScheduleInstruction {

    public static final String PATH = "speed_limit";
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
        return Pair.of(new ItemStack(Items.CLOCK),
                Component.translatable("raildispatch.schedule.instruction." + PATH));
    }

    /** The configured limit in blocks per second, or empty when this is not a speed-limit entry. */
    public static Optional<Double> limitOf(ScheduleInstruction instruction) {
        if (!(instruction instanceof SpeedLimitInstruction)) {
            return Optional.empty();
        }
        CompoundTag data = instruction.getData();
        if (data == null) {
            return Optional.empty();
        }
        String text = data.getString(TEXT_KEY);
        if (text == null || text.isBlank()) {
            return Optional.of(0.0D);
        }
        try {
            double value = Double.parseDouble(text.trim().replace(',', '.'));
            if (!Double.isFinite(value)) {
                return Optional.of(0.0D);
            }
            return Optional.of(Math.max(0.0D, Math.min(30.0D, value)));
        } catch (NumberFormatException exception) {
            return Optional.of(0.0D);
        }
    }
}
