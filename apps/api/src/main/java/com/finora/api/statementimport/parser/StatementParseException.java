package com.finora.api.statementimport.parser;

/**
 * Safe, user-facing parse failure: a stable code plus a Portuguese message.
 * Never wraps parser internals — stack traces, file paths or statement
 * content must not leak through it.
 */
public class StatementParseException extends RuntimeException {

    private final String code;

    public StatementParseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
