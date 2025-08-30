package com.helphub.common;

/**
 * Defines a type-safe enumeration for message priority levels.
 * <p>
 * This enum is used throughout the application to handle and sort messages based on their urgency.
 * Each priority level is associated with a numeric integer value, which allows for efficient
 * sorting and storage in the database.
 */
public enum Priority {
    /** The lowest priority, for non-urgent or background messages. */
    LOW(0),

    /** The default priority for standard chat messages. */
    NORMAL(1),

    /** The highest priority, reserved for urgent or emergency messages (e.g., SOS). */
    HIGH(2);

    /** The integer representation of the priority level. */
    public final int level;

    /**
     * Private constructor to associate an integer level with each enum constant.
     * @param level The numeric level for the priority.
     */
    Priority(int level) {
        this.level = level;
    }

    /**
     * A static factory method to convert a numeric level back into a {@link Priority} enum constant.
     * This is useful when retrieving data from the database.
     * <p>
     * If the provided level does not match any known priority, it safely defaults to {@code NORMAL}.
     *
     * @param level The integer level of the priority.
     * @return The corresponding {@code Priority} constant, or {@code NORMAL} if not found.
     */
    public static Priority fromLevel(int level) {
        // Iterate through all possible enum values
        for (Priority p : Priority.values()) {
            if (p.level == level) {
                return p;
            }
        }
        // Return a safe default if the level is unknown
        return NORMAL;
    }
}