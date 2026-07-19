package com.finora.api.statementimport.parser.csv;

/** Field delimiters accepted for CSV statements. */
public enum CsvDelimiter {
    COMMA(','),
    SEMICOLON(';');

    private final char symbol;

    CsvDelimiter(char symbol) {
        this.symbol = symbol;
    }

    public char symbol() {
        return symbol;
    }
}
