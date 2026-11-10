package com.raildispatch.autopilot;

import net.minecraft.nbt.CompoundTag;

/**
 * One departure in the autopilot console's timetable.
 *
 * <p>The destination is the name the console would otherwise have been given by hand: a station, an
 * address plate or a tagged vehicle. The time is the <b>departure</b> time from wherever the vehicle is
 * standing when the entry becomes current - the previous stop, or the place the timetable was started.
 *
 * <p>Times are Minecraft day ticks (0..23999, where 0 is 06:00), not wall-clock seconds, so an entry keeps
 * its meaning across a day/night cycle. The display mapping is the one players already read from a clock:
 * 06:00 at tick 0, 18:00 at tick 12000.
 */
public record TimetableEntry(String destination, int departureTicks) {

    public static final int MAX_DESTINATION_LENGTH = 96;
    public static final int DAY = 24000;
    public static final int TICKS_PER_HOUR = 1000;

    public TimetableEntry {
        destination = destination == null ? "" : destination.trim();
        if (destination.length() > MAX_DESTINATION_LENGTH) {
            destination = destination.substring(0, MAX_DESTINATION_LENGTH);
        }
        departureTicks = Math.floorMod(departureTicks, DAY);
    }

    /** The clock reading shown to the player, {@code HH:MM}. */
    public String clock() {
        return formatDayTicks(departureTicks);
    }

    public static String formatDayTicks(int ticks) {
        int minutes = Math.floorMod(Math.round(ticks * 60.0F / TICKS_PER_HOUR) + 6 * 60, 24 * 60);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /**
     * Parses {@code HH:MM}, {@code H:MM} or {@code HHMM} into the packed integer the screen sends.
     *
     * @return {@code HH*100+MM}, or -1 when the text is not a clock reading
     */
    public static int parseClock(String text) {
        if (text == null) {
            return -1;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return -1;
        }
        int hours;
        int minutes;
        int colon = value.indexOf(':');
        try {
            if (colon >= 0) {
                hours = Integer.parseInt(value.substring(0, colon).trim());
                minutes = Integer.parseInt(value.substring(colon + 1).trim());
            } else if (value.length() <= 2) {
                hours = Integer.parseInt(value);
                minutes = 0;
            } else if (value.length() == 3) {
                hours = Integer.parseInt(value.substring(0, 1));
                minutes = Integer.parseInt(value.substring(1));
            } else if (value.length() == 4) {
                hours = Integer.parseInt(value.substring(0, 2));
                minutes = Integer.parseInt(value.substring(2));
            } else {
                return -1;
            }
        } catch (NumberFormatException exception) {
            return -1;
        }
        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
            return -1;
        }
        return hours * 100 + minutes;
    }

    /** Converts the packed {@code HHMM} clock to day ticks; -1 stays -1. */
    public static int ticksFromPackedClock(int packed) {
        if (packed < 0) {
            return -1;
        }
        int hours = packed / 100;
        int minutes = packed % 100;
        if (hours > 23 || minutes > 59) {
            return -1;
        }
        return Math.floorMod((hours - 6) * TICKS_PER_HOUR + minutes * TICKS_PER_HOUR / 60, DAY);
    }

    public static int packedClockFromTicks(int ticks) {
        String formatted = formatDayTicks(ticks);
        return Integer.parseInt(formatted.substring(0, 2)) * 100 + Integer.parseInt(formatted.substring(3));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Destination", destination);
        tag.putInt("Departure", departureTicks);
        return tag;
    }

    public static TimetableEntry load(CompoundTag tag) {
        return new TimetableEntry(tag.getString("Destination"), tag.getInt("Departure"));
    }
}
