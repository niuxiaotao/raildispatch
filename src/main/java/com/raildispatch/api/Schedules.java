package com.raildispatch.api;

import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/**
 * Access to Create train schedules, installed by {@code train.CreateTrackNetwork} when Create is
 * present. Everything else in the mod reads schedules through here, so the cab never imports a
 * Create class and keeps working (with an empty schedule) if Create is missing.
 */
public final class Schedules {

    private Schedules() {}

    public interface Reader {

        /** True when this stack is something a cab can execute. */
        boolean isScheduleItem(ItemStack stack);

        Optional<ScheduleView> read(HolderLookup.Provider registries, ItemStack stack);

        /** Advances the schedule stored in the stack, writing the new state back into the item. */
        Optional<ScheduleView> advance(HolderLookup.Provider registries, ItemStack stack);
    }

    private static volatile Reader reader;

    public static void installReader(Reader installed) {
        reader = installed;
    }

    public static boolean isInstalled() {
        return reader != null;
    }

    public static boolean isScheduleItem(ItemStack stack) {
        Reader current = reader;
        return current != null && !stack.isEmpty() && current.isScheduleItem(stack);
    }

    public static Optional<ScheduleView> read(HolderLookup.Provider registries, ItemStack stack) {
        Reader current = reader;
        if (current == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return current.read(registries, stack);
    }

    public static Optional<ScheduleView> advance(HolderLookup.Provider registries, ItemStack stack) {
        Reader current = reader;
        if (current == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return current.advance(registries, stack);
    }
}
