package com.finora.api.common.error;

/**
 * Violation of a domain rule that request-level validation cannot express,
 * e.g. deleting an account that still has transactions.
 */
public class BusinessRuleException extends RuntimeException {

    private final String code;

    public BusinessRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
