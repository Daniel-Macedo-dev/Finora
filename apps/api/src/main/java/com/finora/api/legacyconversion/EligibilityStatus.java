package com.finora.api.legacyconversion;

/**
 * Structured eligibility of one transaction for legacy-credit conversion.
 * {@link #REVERSED_CONVERSION} is informational: a reversed source is
 * convertible again — the previous conversion stays in the audit trail.
 */
public enum EligibilityStatus {
    ELIGIBLE,
    ALREADY_CONVERTED,
    REVERSED_CONVERSION,
    INCOMPATIBLE_SOURCE,
    BLOCKED;

    /** Whether a new conversion may be started from this status. */
    public boolean convertible() {
        return this == ELIGIBLE || this == REVERSED_CONVERSION;
    }
}
