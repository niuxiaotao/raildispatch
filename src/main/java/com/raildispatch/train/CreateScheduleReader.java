package com.raildispatch.train;

import com.raildispatch.api.ScheduleView;
import com.raildispatch.api.Schedules;
import com.raildispatch.schedule.CoupleConsistInstruction;
import com.raildispatch.schedule.CoupleVehicleInstruction;
import com.raildispatch.schedule.EnterShuntingInstruction;
import com.raildispatch.schedule.ExitShuntingInstruction;
import com.raildispatch.schedule.ShuntByAddressInstruction;
import com.raildispatch.schedule.SpeedLimitInstruction;
import com.raildispatch.schedule.UncoupleInstruction;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.ScheduleItem;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/**
 * Reads Create train schedules out of an item, and advances them.
 *
 * <p>Advancing writes the modified schedule straight back into the item stack through
 * {@link AllDataComponents#TRAIN_SCHEDULE}, which is the same component Create's own schedule menu
 * edits, so a cab advancing a schedule and a player editing it in the Create screen cannot disagree
 * about what the schedule is.
 */
public final class CreateScheduleReader implements Schedules.Reader {

    @Override
    public boolean isScheduleItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        // Test the item type first, and only fall back to the data component. A freshly crafted Train
        // Schedule carries no TRAIN_SCHEDULE component until its editor has been used, so a component
        // test alone rejects the very item a player is holding - which is what made the cab's slot and
        // its shift-right-click install refuse everything.
        return stack.getItem() instanceof ScheduleItem || stack.has(AllDataComponents.TRAIN_SCHEDULE);
    }

    @Override
    public Optional<ScheduleView> read(HolderLookup.Provider registries, ItemStack stack) {
        Schedule schedule = safeSchedule(registries, stack);
        if (schedule == null || schedule.entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new View(schedule));
    }

    @Override
    public Optional<ScheduleView> advance(HolderLookup.Provider registries, ItemStack stack) {
        Schedule schedule = safeSchedule(registries, stack);
        if (schedule == null || schedule.entries.isEmpty()) {
            return Optional.empty();
        }
        int next = schedule.savedProgress + 1;
        if (next >= schedule.entries.size()) {
            if (!schedule.cyclic) {
                return Optional.empty();
            }
            next = 0;
        }
        schedule.savedProgress = next;
        stack.set(AllDataComponents.TRAIN_SCHEDULE, schedule.write(registries));
        return Optional.of(new View(schedule));
    }

    private static Schedule safeSchedule(HolderLookup.Provider registries, ItemStack stack) {
        try {
            return ScheduleItem.getSchedule(registries, stack);
        } catch (Throwable throwable) {
            // A malformed or half-edited schedule must not take the server down; an idle cab is fine.
            com.raildispatch.RailDispatch.LOGGER.warn("Could not read a Create schedule from an item",
                    throwable);
            return null;
        }
    }

    /** A snapshot of the entry a driver is currently executing. */
    private static final class View implements ScheduleView {

        private final Schedule schedule;
        private final ScheduleEntry entry;

        View(Schedule schedule) {
            this.schedule = schedule;
            int index = Math.max(0, Math.min(schedule.savedProgress, schedule.entries.size() - 1));
            this.entry = schedule.entries.get(index);
        }

        @Override
        public int entryCount() {
            return schedule.entries.size();
        }

        @Override
        public int currentEntry() {
            return Math.max(0, schedule.savedProgress);
        }

        @Override
        public boolean cyclic() {
            return schedule.cyclic;
        }

        @Override
        public Optional<String> destination() {
            if (entry.instruction instanceof DestinationInstruction destination) {
                String filter = destination.getFilter();
                return filter == null || filter.isBlank() ? Optional.empty() : Optional.of(filter.trim());
            }
            return Optional.empty();
        }

        @Override
        public Optional<String> coupleTag() {
            return CoupleVehicleInstruction.tagOf(entry.instruction);
        }

        @Override
        public Optional<String> shuntAddress() {
            return ShuntByAddressInstruction.addressOf(entry.instruction);
        }

        @Override
        public Optional<String> coupleConsist() {
            return CoupleConsistInstruction.serviceOf(entry.instruction);
        }

        @Override
        public Optional<Double> speedLimit() {
            return SpeedLimitInstruction.limitOf(entry.instruction);
        }

        @Override
        public int shuntingChange() {
            if (entry.instruction instanceof EnterShuntingInstruction) {
                return ScheduleView.SHUNTING_ENTER;
            }
            if (entry.instruction instanceof ExitShuntingInstruction) {
                return ScheduleView.SHUNTING_EXIT;
            }
            return 0;
        }

        @Override
        public Optional<String> nextDestination() {
            int next = schedule.savedProgress + 1;
            if (next >= schedule.entries.size()) {
                if (!schedule.cyclic) {
                    return Optional.empty();
                }
                next = 0;
            }
            ScheduleEntry nextEntry = schedule.entries.get(next);
            if (nextEntry.instruction instanceof DestinationInstruction destination) {
                String filter = destination.getFilter();
                return filter == null || filter.isBlank() ? Optional.empty() : Optional.of(filter.trim());
            }
            return Optional.empty();
        }

        @Override
        public boolean uncouple() {
            return entry.instruction instanceof UncoupleInstruction;
        }

        @Override
        public int waitTicks() {
            // conditions is a list of alternatives, and each alternative is a list of conditions that
            // must all hold. The entry is complete when ANY alternative is satisfied, so the timed wait
            // is the SHORTEST alternative: the smallest, over the groups, of the longest timed wait in
            // that group. Adding every timed condition together - which is what this did - treated the
            // alternatives as if they all had to be met, and made the wait as long as all of them summed.
            List<List<ScheduleWaitCondition>> conditionGroups = entry.conditions;
            if (conditionGroups == null || conditionGroups.isEmpty()) {
                return 0;
            }
            int shortest = Integer.MAX_VALUE;
            for (List<ScheduleWaitCondition> group : conditionGroups) {
                if (group == null || group.isEmpty()) {
                    continue;
                }
                int longestInGroup = 0;
                for (ScheduleWaitCondition condition : group) {
                    if (condition instanceof TimedWaitCondition timed) {
                        longestInGroup = Math.max(longestInGroup, Math.max(0, timed.totalWaitTicks()));
                    }
                }
                shortest = Math.min(shortest, longestInGroup);
            }
            return shortest == Integer.MAX_VALUE ? 0 : shortest;
        }
    }
}
