package com.finora.api.statementimport;

/**
 * Lifecycle of one statement row inside a batch.
 *
 * <pre>
 * parse:        READY | INVALID
 * confirmation: READY → IMPORTED | FAILED | SKIPPED
 * retry:        FAILED → IMPORTED | FAILED
 * undo:         IMPORTED → UNDONE (terminal)
 * </pre>
 *
 * <p>Inclusion/exclusion is a separate user decision ({@code included}), not
 * a lifecycle state: an excluded READY row simply never materializes.
 * {@code SKIPPED} records a duplicate deliberately left out at confirmation.
 */
public enum StatementImportItemStatus {
    READY,
    INVALID,
    IMPORTED,
    FAILED,
    SKIPPED,
    UNDONE
}
