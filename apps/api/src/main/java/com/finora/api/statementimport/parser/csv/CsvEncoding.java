package com.finora.api.statementimport.parser.csv;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Character encodings accepted for CSV statements. */
public enum CsvEncoding {
    UTF_8(StandardCharsets.UTF_8),
    WINDOWS_1252(Charset.forName("windows-1252"));

    private final Charset charset;

    CsvEncoding(Charset charset) {
        this.charset = charset;
    }

    public Charset charset() {
        return charset;
    }
}
