package com.raildispatch.api;

import java.util.Optional;

/**
 * A read-only view of a Create train schedule held in an item, narrowed down to what a driver
 * needs. Keeping this an interface means the cab logic never touches Create classes directly.
 */
public interface ScheduleView {

    int entryCount();

    int currentEntry();

    boolean cyclic();

    /** The destination text of the current entry, if it is a destination instruction. */
    Optional<String> destination();

    /** The tag of a "couple vehicle" entry, when that is the current instruction. */
    Optional<String> coupleTag();

    /** The destination code of a "shunt by address" entry, when that is the current instruction. */
    Optional<String> shuntAddress();

    /** The service number of a "couple consist" entry, when that is the current instruction. */
    default Optional<String> coupleConsist() {
        return Optional.empty();
    }

    /** The destination of the schedule entry after the current one. */
    default Optional<String> nextDestination() {
        return Optional.empty();
    }

    /** A speed limit in blocks per second set by this entry; empty when it is not a limit entry. */
    default Optional<Double> speedLimit() {
        return Optional.empty();
    }

    /** SHUNTING_ENTER or SHUNTING_EXIT when this entry changes shunting mode, otherwise 0. */
    default int shuntingChange() {
        return 0;
    }

    int SHUNTING_ENTER = 1;
    int SHUNTING_EXIT = -1;

    /** True when the current entry is our "uncouple vehicle" task. */
    default boolean uncouple() {
        return false;
    }

    /** Extra ticks to wait at the destination before moving on. */
    int waitTicks();
}
