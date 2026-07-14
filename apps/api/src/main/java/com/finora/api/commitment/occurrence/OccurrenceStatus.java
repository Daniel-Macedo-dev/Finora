package com.finora.api.commitment.occurrence;

/** Lifecycle of one persisted recurring occurrence. */
public enum OccurrenceStatus {
    /** Persisted identity awaiting materialization (or skip/reschedule). */
    SCHEDULED,
    /** A real transaction or card purchase was generated exactly once. */
    MATERIALIZED,
    /** Deliberately not executed; never regenerated automatically. */
    SKIPPED,
    /** Last materialization attempt failed; visible and retryable. */
    FAILED,
    /** Materialized artifact was undone; terminal, never reprocessed. */
    REVERSED
}
