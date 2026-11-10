package com.raildispatch.schedule;

import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction;
import com.simibubi.create.content.trains.schedule.destination.TextScheduleInstruction;
import java.util.Optional;
import net.createmod.catnip.data.Pair;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A schedule entry that means "shunt by destination code": send this vehicle down the track whose
 * address plate carries the given code.
 *
 * <p>This is the hump-yard half of the addressing story. An {@code address_tag} written onto a wagon
 * says where that wagon has to go; an {@code address_plate} says which track carries a destination;
 * this entry is how a driver is told to honour a code, and {@code RailTags} is the book that matches
 * the two.
 *
 * <p>Like {@link CoupleVehicleInstruction}, {@link #start} returns null because our own cab executes
 * the entry - see that class for why null is the safe answer.
 */
public class ShuntByAddressInstruction extends TextScheduleInstruction {

    public static final String PATH = "shunt_by_address";
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
        return Pair.of(new ItemStack(com.raildispatch.registry.RDItems.ADDRESS_TAG.get()),
                Component.translatable("raildispatch.schedule.instruction." + PATH));
    }

    /** The destination code written in this entry, if the instruction is one of ours. */
    public static Optional<String> addressOf(ScheduleInstruction instruction) {
        if (!(instruction instanceof ShuntByAddressInstruction)) {
            return Optional.empty();
        }
        return CoupleVehicleInstruction.readText(instruction);
    }
}
