package com.helphub.common;

/**
 * Defines the priority levels for messages.
 * Stored in the database as integers for efficient sorting.
 */
public enum Priority {
    LOW(0),
    NORMAL(1),
    HIGH(2);

    public final int level;

    Priority(int level) {
        this.level = level;
    }

    public static Priority fromLevel(int level) {
        for (Priority p : values()) {
            if (p.level == level) {
                return p;
            }
        }
        return NORMAL;
    }
}