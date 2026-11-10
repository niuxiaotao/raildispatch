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
 * A schedule entry that means "go and couple to the vehicle carrying this vehicle tag".
 *
 * <p>It extends {@link TextScheduleInstruction} so Create's own schedule screen gives it a text box
 * for free: the base class reads and writes the {@code "Text"} key of the entry's data, which is
 * exactly where the tag lives.
 *
 * <p>{@link #start} deliberately returns null. These entries are executed by our own physics cab,
 * which does the addressing and the approach itself; Create's {@code ScheduleRuntime} has no concept
 * of addressing a vehicle by name, and returning null is the contract Create's own instructions use
 * to signal "I cannot start this", so it is safe even if a player puts this schedule on a real train.
 */
public class CoupleVehicleInstruction extends TextScheduleInstruction {

    public static final String PATH = "couple_vehicle";
    private static final ResourceLocation ID = ResourceLocation.parse("raildispatch:" + PATH);
    /** The key {@link TextScheduleInstruction} reads; kept in one place because both writer and reader use it. */
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

    /** The tag written in this entry, if the instruction is one of ours. */
    public static Optional<String> tagOf(ScheduleInstruction instruction) {
        if (!(instruction instanceof CoupleVehicleInstruction)) {
            return Optional.empty();
        }
        return readText(instruction);
    }

    static Optional<String> readText(ScheduleInstruction instruction) {
        CompoundTag data = instruction.getData();
        if (data == null) {
            return Optional.empty();
        }
        String text = data.getString(TEXT_KEY);
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.trim());
    }
}
