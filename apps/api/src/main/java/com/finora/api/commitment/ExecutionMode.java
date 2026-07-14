package com.finora.api.commitment;

/** How due occurrences of a recurring definition are executed. */
public enum ExecutionMode {
    /** The user materializes each occurrence explicitly. */
    MANUAL,
    /** Due occurrences are materialized by the background processor. */
    AUTOMATIC
}
