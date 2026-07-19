package com.finora.api.statementimport;

/**
 * Duplicate classification of a statement row against the user's existing
 * data and the rest of its own file.
 *
 * <ul>
 *   <li>{@code EXACT_DUPLICATE} — same strong identity (external id) already
 *       imported into the same account, or identical row already
 *       materialized; blocked from importing again.</li>
 *   <li>{@code POSSIBLE_DUPLICATE} — content fingerprint (or near-date
 *       equivalence) matches an existing transaction; requires an explicit
 *       user decision to skip or import anyway.</li>
 *   <li>{@code DUPLICATE_WITHIN_FILE} — repeats an earlier row of the same
 *       upload; surfaced before confirmation, never auto-deleted.</li>
 * </ul>
 */
public enum DuplicateStatus {
    UNIQUE,
    EXACT_DUPLICATE,
    POSSIBLE_DUPLICATE,
    DUPLICATE_WITHIN_FILE
}
