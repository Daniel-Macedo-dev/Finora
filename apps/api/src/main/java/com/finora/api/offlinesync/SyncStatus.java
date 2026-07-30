package com.finora.api.offlinesync;

/**
 * Outcome of one mutation inside a batch. Every input mutation produces exactly
 * one result with one of these statuses, in the order it was submitted.
 *
 * <p>Transport failures are <em>not</em> represented here: a lost connection or
 * a 5xx never reaches this enum, and the client retry engine treats those as
 * temporary and replays the same {@code clientMutationId}.
 */
public enum SyncStatus {

    /** The domain mutation and its idempotency receipt committed together. */
    APPLIED,

    /**
     * This exact mutation had already been applied — the stored receipt is
     * returned unchanged and no side effect is repeated. The client must treat
     * it as success.
     */
    ALREADY_APPLIED,

    /** Server state moved on; the user must decide. Never auto-retried. */
    CONFLICT,

    /** Permanent domain or ownership failure. Never auto-retried. */
    REJECTED,

    /**
     * A referenced parent created offline has not been applied yet. The client
     * normally prevents sending these by ordering dependencies locally.
     */
    DEPENDENCY_MISSING
}
