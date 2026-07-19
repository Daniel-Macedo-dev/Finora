package com.finora.api.statementimport;

/**
 * Lifecycle of an import batch.
 *
 * <pre>
 * CSV without complete mapping:  NEEDS_MAPPING → PREVIEW_READY
 * OFX / fully mapped CSV:        PREVIEW_READY
 * confirmation:                  PREVIEW_READY → COMPLETED | PARTIALLY_COMPLETED
 * undo of every imported item:   COMPLETED | PARTIALLY_COMPLETED → UNDONE
 * </pre>
 *
 * <p>{@code PARTIALLY_COMPLETED} means at least one item imported while
 * others failed, were skipped or remain pending — retrying eligible items can
 * still move the batch to {@code COMPLETED}.
 */
public enum StatementImportStatus {
    NEEDS_MAPPING,
    PREVIEW_READY,
    COMPLETED,
    PARTIALLY_COMPLETED,
    UNDONE
}
