package com.finora.api.legacyconversion;

/**
 * Lifecycle of a legacy-credit conversion. A preview is never persisted, so
 * there is no draft state: a row exists only once the conversion committed.
 */
public enum ConversionStatus {
    /** The generated card purchase is the expense source; the original transaction is inactive. */
    ACTIVE,
    /** The generated purchase was cancelled and the original transaction restored. */
    REVERSED
}
