package com.finora.api.commitment;

/** What a materialized occurrence becomes. */
public enum RecurrenceTarget {
    /** Planning data only — occurrences are never materialized. */
    PROJECTION_ONLY,
    /** Each occurrence becomes one real account transaction. */
    ACCOUNT_TRANSACTION,
    /** Each occurrence becomes one real credit-card purchase. */
    CREDIT_CARD_PURCHASE
}
