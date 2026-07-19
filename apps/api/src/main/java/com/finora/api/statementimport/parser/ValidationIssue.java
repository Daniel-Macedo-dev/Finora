package com.finora.api.statementimport.parser;

/** One blocking problem found in a statement row (stable code + pt-BR text). */
public record ValidationIssue(String code, String message) {
}
