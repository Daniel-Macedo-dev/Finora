package com.finora.api.statementimport.parser;

/**
 * Hard bounds applied to every uploaded statement before and during parsing.
 * They protect memory and the database from hostile or malformed files;
 * legitimate Brazilian bank exports sit far below all of them.
 */
public final class StatementLimits {

    /** Maximum accepted upload size, in bytes (5 MB). */
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    /** Maximum number of parsed statement entries per file. */
    public static final int MAX_ENTRIES = 10_000;

    /** Maximum length of one CSV line, in characters. */
    public static final int MAX_LINE_LENGTH = 10_000;

    /** Maximum length of one parsed field, in characters. */
    public static final int MAX_FIELD_LENGTH = 500;

    /** Maximum length of a sanitized upload filename. */
    public static final int MAX_FILENAME_LENGTH = 255;

    /** Maximum description length carried into a Finora transaction. */
    public static final int MAX_DESCRIPTION_LENGTH = 200;

    private StatementLimits() {
    }
}
