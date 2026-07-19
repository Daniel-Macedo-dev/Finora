package com.finora.api.statementimport;

/**
 * Deterministic, ReDoS-free matcher operations — plain text comparison over
 * the canonical normalized form, never user-supplied regex.
 */
public enum CategoryRuleOperation {
    EXACT,
    STARTS_WITH,
    CONTAINS
}
