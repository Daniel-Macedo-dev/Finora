package com.finora.api.statementimport.parser.csv;

import com.finora.api.statementimport.parser.StatementLimits;
import com.finora.api.statementimport.parser.StatementParseException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded CSV decoding: byte-level content checks, deterministic encoding
 * handling and an RFC 4180-style tokenizer (quoted values, escaped quotes,
 * embedded delimiters and line breaks inside quotes, CRLF and LF, blank
 * lines skipped). Every cell is data — nothing is ever evaluated.
 */
public final class CsvDecoder {

    private CsvDecoder() {
    }

    /**
     * Rejects content that is clearly not a text statement: NUL bytes or the
     * magic numbers of common binary containers (ZIP, GZIP, PDF, OLE). MIME
     * type and extension are never trusted — only the bytes.
     */
    public static void rejectBinary(byte[] content) {
        if (startsWith(content, 0x50, 0x4B, 0x03, 0x04) || startsWith(content, 0x50, 0x4B, 0x05, 0x06)) {
            throw new StatementParseException("STATEMENT_FILE_COMPRESSED",
                    "Arquivos compactados não são aceitos. Envie o extrato CSV ou OFX original.");
        }
        if (startsWith(content, 0x1F, 0x8B)
                || startsWith(content, 0x25, 0x50, 0x44, 0x46)
                || startsWith(content, 0xD0, 0xCF, 0x11, 0xE0)) {
            throw new StatementParseException("STATEMENT_FILE_BINARY",
                    "O arquivo não parece ser um extrato em texto (CSV ou OFX).");
        }
        for (byte b : content) {
            if (b == 0) {
                throw new StatementParseException("STATEMENT_FILE_BINARY",
                        "O arquivo não parece ser um extrato em texto (CSV ou OFX).");
            }
        }
    }

    /**
     * Decodes the bytes with the given encoding. A UTF-8 BOM always wins and
     * is stripped. Choosing UTF-8 for a file that is not valid UTF-8 fails
     * with a safe error suggesting Windows-1252 instead of silently
     * corrupting accented text.
     */
    public static String decode(byte[] content, CsvEncoding encoding) {
        byte[] body = content;
        boolean hasBom = startsWith(content, 0xEF, 0xBB, 0xBF);
        if (hasBom) {
            body = new byte[content.length - 3];
            System.arraycopy(content, 3, body, 0, body.length);
        }
        if (hasBom || encoding == CsvEncoding.UTF_8) {
            try {
                CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                CharBuffer chars = strict.decode(ByteBuffer.wrap(body));
                return chars.toString();
            } catch (CharacterCodingException e) {
                throw new StatementParseException("STATEMENT_CSV_ENCODING",
                        "O arquivo não está em UTF-8 válido. Selecione a codificação "
                                + "Windows-1252 e tente novamente.");
            }
        }
        return new String(body, encoding.charset());
    }

    /**
     * Detects the most plausible encoding: BOM or valid strict UTF-8 means
     * UTF-8; otherwise Windows-1252 (which decodes any byte sequence).
     */
    public static CsvEncoding detectEncoding(byte[] content) {
        if (startsWith(content, 0xEF, 0xBB, 0xBF)) {
            return CsvEncoding.UTF_8;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return CsvEncoding.UTF_8;
        } catch (CharacterCodingException e) {
            return CsvEncoding.WINDOWS_1252;
        }
    }

    /**
     * Suggests the delimiter by counting candidates outside quoted regions in
     * the first lines — a starting point for the user-confirmed mapping,
     * never a silent decision.
     */
    public static CsvDelimiter detectDelimiter(String text) {
        int semicolons = 0;
        int commas = 0;
        boolean quoted = false;
        int lines = 0;
        for (int i = 0; i < text.length() && lines < 20; i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == ';') {
                semicolons++;
            } else if (!quoted && c == ',') {
                commas++;
            } else if (!quoted && c == '\n') {
                lines++;
            }
        }
        return semicolons >= commas ? CsvDelimiter.SEMICOLON : CsvDelimiter.COMMA;
    }

    /**
     * Tokenizes the decoded text into rows of cells. Enforces the line,
     * field and row-count limits; skips lines that are entirely blank.
     *
     * @throws StatementParseException on unterminated quotes, oversized
     *                                 lines/fields or too many rows
     */
    public static List<List<String>> tokenize(String text, CsvDelimiter delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int lineLength = 0;
        char sep = delimiter.symbol();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            lineLength++;
            if (lineLength > StatementLimits.MAX_LINE_LENGTH) {
                throw new StatementParseException("STATEMENT_CSV_LINE_TOO_LONG",
                        "O arquivo contém uma linha longa demais para um extrato válido.");
            }
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.isEmpty()) {
                inQuotes = true;
            } else if (c == sep) {
                endField(row, field);
            } else if (c == '\r') {
                // CRLF: consumed with the following \n (a bare \r is ignored).
                continue;
            } else if (c == '\n') {
                endRow(rows, row, field);
                row = new ArrayList<>();
                lineLength = 0;
            } else {
                field.append(c);
            }
            if (field.length() > StatementLimits.MAX_FIELD_LENGTH) {
                throw new StatementParseException("STATEMENT_CSV_FIELD_TOO_LONG",
                        "O arquivo contém um campo longo demais para um extrato válido.");
            }
        }
        if (inQuotes) {
            throw new StatementParseException("STATEMENT_CSV_MALFORMED",
                    "O arquivo CSV contém aspas não fechadas e não pôde ser lido.");
        }
        endRow(rows, row, field);
        return rows;
    }

    private static void endField(List<String> row, StringBuilder field) {
        row.add(field.toString());
        field.setLength(0);
    }

    private static void endRow(List<List<String>> rows, List<String> row, StringBuilder field) {
        if (!field.isEmpty() || !row.isEmpty()) {
            endField(row, field);
            boolean blank = row.stream().allMatch(cell -> cell == null || cell.isBlank());
            if (!blank) {
                if (rows.size() >= StatementLimits.MAX_ENTRIES + 1) {
                    throw new StatementParseException("STATEMENT_TOO_MANY_ROWS",
                            "O arquivo excede o limite de %d linhas por importação."
                                    .formatted(StatementLimits.MAX_ENTRIES));
                }
                rows.add(List.copyOf(row));
            }
            row.clear();
        }
    }

    private static boolean startsWith(byte[] content, int... prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((content[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
