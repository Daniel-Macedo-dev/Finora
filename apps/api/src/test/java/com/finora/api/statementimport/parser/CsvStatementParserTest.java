package com.finora.api.statementimport.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.api.statementimport.parser.csv.CsvDecoder;
import com.finora.api.statementimport.parser.csv.CsvDelimiter;
import com.finora.api.statementimport.parser.csv.CsvEncoding;
import com.finora.api.statementimport.parser.csv.CsvMappingConfig;
import com.finora.api.statementimport.parser.csv.CsvMappingConfig.Separator;
import com.finora.api.statementimport.parser.csv.CsvStatementParser;
import com.finora.api.transaction.TransactionType;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure CSV parsing tests over synthetic fixtures only — encodings,
 * delimiters, quoting, Brazilian locale formats, debit/credit columns and
 * every blocking row validation.
 */
class CsvStatementParserTest {

    private static CsvMappingConfig brazilianSigned(CsvEncoding encoding, CsvDelimiter delimiter,
                                                    boolean header) {
        return new CsvMappingConfig(encoding, delimiter, header, "dd/MM/yyyy",
                Separator.COMMA, Separator.DOT, 0, 1, 2, null, null, null, null);
    }

    @Test
    void parsesUtf8SemicolonDecimalCommaWithHeader() {
        byte[] content = """
                Data;Descrição;Valor
                05/06/2026;Padaria São João;-25,90
                06/06/2026;Salário;5.200,00
                """.getBytes(StandardCharsets.UTF_8);
        var result = CsvStatementParser.parse(content,
                brazilianSigned(CsvEncoding.UTF_8, CsvDelimiter.SEMICOLON, true));

        assertThat(result.entries()).hasSize(2);
        var expense = result.entries().getFirst();
        assertThat(expense.valid()).isTrue();
        assertThat(expense.postedDate()).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(expense.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(expense.absoluteAmount()).isEqualByComparingTo("25.90");
        assertThat(expense.description()).isEqualTo("Padaria São João");
        assertThat(expense.normalizedDescription()).isEqualTo("padaria sao joao");
        var income = result.entries().get(1);
        assertThat(income.type()).isEqualTo(TransactionType.INCOME);
        assertThat(income.absoluteAmount()).isEqualByComparingTo("5200.00");
    }

    @Test
    void parsesUtf8BomAndCrlf() {
        byte[] body = "Data,Desc,Valor\r\n05/06/2026,Mercado,-10.50\r\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[body.length + 3];
        content[0] = (byte) 0xEF;
        content[1] = (byte) 0xBB;
        content[2] = (byte) 0xBF;
        System.arraycopy(body, 0, content, 3, body.length);

        var config = new CsvMappingConfig(CsvEncoding.UTF_8, CsvDelimiter.COMMA, true,
                "dd/MM/yyyy", Separator.DOT, Separator.NONE, 0, 1, 2, null, null, null, null);
        var result = CsvStatementParser.parse(content, config);
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().getFirst().absoluteAmount()).isEqualByComparingTo("10.50");
    }

    @Test
    void parsesWindows1252Accents() {
        byte[] content = "05/06/2026;Açaí do Zé;-12,00\n"
                .getBytes(Charset.forName("windows-1252"));
        var result = CsvStatementParser.parse(content,
                brazilianSigned(CsvEncoding.WINDOWS_1252, CsvDelimiter.SEMICOLON, false));
        assertThat(result.entries().getFirst().description()).isEqualTo("Açaí do Zé");
    }

    @Test
    void utf8DecodingOfLatinBytesFailsSafely() {
        byte[] content = "05/06/2026;Açaí;-12,00\n".getBytes(Charset.forName("windows-1252"));
        assertThatThrownBy(() -> CsvStatementParser.parse(content,
                brazilianSigned(CsvEncoding.UTF_8, CsvDelimiter.SEMICOLON, false)))
                .isInstanceOf(StatementParseException.class)
                .hasFieldOrPropertyWithValue("code", "STATEMENT_CSV_ENCODING");
    }

    @Test
    void handlesQuotedDelimiterEscapedQuotesAndBlankLines() {
        byte[] content = ("05/06/2026;\"Mercado; frutas e \"\"cia\"\"\";-30,00\n"
                + "\n"
                + "06/06/2026;Outro;-1,00\n").getBytes(StandardCharsets.UTF_8);
        var result = CsvStatementParser.parse(content,
                brazilianSigned(CsvEncoding.UTF_8, CsvDelimiter.SEMICOLON, false));
        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries().getFirst().description())
                .isEqualTo("Mercado; frutas e \"cia\"");
    }

    @Test
    void supportsSeparateDebitAndCreditColumns() {
        byte[] content = """
                05/06/2026;Compra;30,00;
                06/06/2026;Depósito;;150,00
                07/06/2026;Ambíguo;10,00;20,00
                08/06/2026;Nenhum;;
                """.getBytes(StandardCharsets.UTF_8);
        var config = new CsvMappingConfig(CsvEncoding.UTF_8, CsvDelimiter.SEMICOLON, false,
                "dd/MM/yyyy", Separator.COMMA, Separator.DOT, 0, 1, null, 2, 3, null, null);
        var result = CsvStatementParser.parse(content, config);

        assertThat(result.entries().get(0).type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(result.entries().get(1).type()).isEqualTo(TransactionType.INCOME);
        assertThat(result.entries().get(2).issues())
                .extracting(ValidationIssue::code)
                .containsExactly("STATEMENT_ROW_AMBIGUOUS_AMOUNT");
        assertThat(result.entries().get(3).issues())
                .extracting(ValidationIssue::code)
                .containsExactly("STATEMENT_ROW_MISSING_AMOUNT");
    }

    @Test
    void flagsZeroAmountInvalidDateAndInvalidAmount() {
        byte[] content = """
                05/06/2026;Zero;0,00
                31/02/2026;Data impossível;-1,00
                05/06/2026;Valor estranho;abc
                ;Sem data;-2,00
                05/06/2026;;-3,00
                """.getBytes(StandardCharsets.UTF_8);
        var result = CsvStatementParser.parse(content,
                brazilianSigned(CsvEncoding.UTF_8, CsvDelimiter.SEMICOLON, false));
        assertThat(result.entries()).extracting(entry -> entry.issues().isEmpty())
                .containsExactly(false, false, false, false, false);
        assertThat(result.entries().get(0).issues().getFirst().code())
                .isEqualTo("STATEMENT_ROW_ZERO_AMOUNT");
        assertThat(result.entries().get(1).issues().getFirst().code())
                .isEqualTo("STATEMENT_ROW_INVALID_DATE");
        assertThat(result.entries().get(2).issues().getFirst().code())
                .isEqualTo("STATEMENT_ROW_INVALID_AMOUNT");
        assertThat(result.entries().get(3).issues().getFirst().code())
                .isEqualTo("STATEMENT_ROW_MISSING_DATE");
        assertThat(result.entries().get(4).issues().getFirst().code())
                .isEqualTo("STATEMENT_ROW_MISSING_DESCRIPTION");
    }

    @Test
    void parsesThousandsSeparatorsAndTrailingSign() {
        byte[] content = """
                05/06/2026;Grande;-1.234.567,89
                06/06/2026;Sinal atrás;123,45-
                """.getBytes(StandardCharsets.UTF_8);
        var result = CsvStatementParser.parse(content,
                brazilianSigned(CsvEncoding.UTF_8, CsvDelimiter.SEMICOLON, false));
        assertThat(result.entries().get(0).absoluteAmount()).isEqualByComparingTo("1234567.89");
        assertThat(result.entries().get(1).type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(result.entries().get(1).absoluteAmount()).isEqualByComparingTo("123.45");
    }

    @Test
    void readsExternalIdAndMemoColumns() {
        byte[] content = "05/06/2026;Compra;-1,00;TX-001;observação\n"
                .getBytes(StandardCharsets.UTF_8);
        var config = new CsvMappingConfig(CsvEncoding.UTF_8, CsvDelimiter.SEMICOLON, false,
                "dd/MM/yyyy", Separator.COMMA, Separator.DOT, 0, 1, 2, null, null, 3, 4);
        var entry = CsvStatementParser.parse(content, config).entries().getFirst();
        assertThat(entry.externalId()).isEqualTo("TX-001");
        assertThat(entry.memo()).isEqualTo("observação");
    }

    @Test
    void rejectsMalformedQuotesOversizedLineAndTooManyRows() {
        byte[] unterminated = "05/06/2026;\"aberto;-1,00\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> CsvStatementParser.parse(unterminated,
                brazilianSigned(CsvEncoding.UTF_8, CsvDelimiter.SEMICOLON, false)))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_CSV_MALFORMED");

        byte[] longLine = ("05/06/2026;" + "x".repeat(StatementLimits.MAX_LINE_LENGTH) + ";-1,00\n")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> CsvStatementParser.parse(longLine,
                brazilianSigned(CsvEncoding.UTF_8, CsvDelimiter.SEMICOLON, false)))
                .isInstanceOf(StatementParseException.class);

        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= StatementLimits.MAX_ENTRIES + 1; i++) {
            many.append("05/06/2026;linha ").append(i).append(";-1,00\n");
        }
        assertThatThrownBy(() -> CsvStatementParser.parse(
                many.toString().getBytes(StandardCharsets.UTF_8),
                brazilianSigned(CsvEncoding.UTF_8, CsvDelimiter.SEMICOLON, false)))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_TOO_MANY_ROWS");
    }

    @Test
    void rejectsBinaryAndCompressedContent() {
        assertThatThrownBy(() -> CsvDecoder.rejectBinary(new byte[] {0x50, 0x4B, 0x03, 0x04, 1}))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_FILE_COMPRESSED");
        assertThatThrownBy(() -> CsvDecoder.rejectBinary(new byte[] {(byte) 0x25, 0x50, 0x44, 0x46}))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_FILE_BINARY");
        assertThatThrownBy(() -> CsvDecoder.rejectBinary(new byte[] {65, 0, 66}))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_FILE_BINARY");
    }

    @Test
    void mappingRejectsContradictions() {
        assertThatThrownBy(() -> new CsvMappingConfig(CsvEncoding.UTF_8, CsvDelimiter.COMMA,
                false, "dd/MM/yyyy", Separator.COMMA, Separator.COMMA,
                0, 1, 2, null, null, null, null))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_MAPPING_INVALID");
        // Same column with two roles.
        assertThatThrownBy(() -> new CsvMappingConfig(CsvEncoding.UTF_8, CsvDelimiter.COMMA,
                false, "dd/MM/yyyy", Separator.COMMA, Separator.DOT,
                0, 0, 2, null, null, null, null))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_MAPPING_INVALID");
        // Signed amount together with debit/credit pair.
        assertThatThrownBy(() -> new CsvMappingConfig(CsvEncoding.UTF_8, CsvDelimiter.COMMA,
                false, "dd/MM/yyyy", Separator.COMMA, Separator.DOT,
                0, 1, 2, 3, 4, null, null))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_MAPPING_INVALID");
        // Unsupported date pattern.
        assertThatThrownBy(() -> new CsvMappingConfig(CsvEncoding.UTF_8, CsvDelimiter.COMMA,
                false, "ddMMyyyy HH", Separator.COMMA, Separator.DOT,
                0, 1, 2, null, null, null, null))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_MAPPING_INVALID");
    }

    @Test
    void missingMappedColumnBecomesRowIssueNotFailure() {
        byte[] content = "05/06/2026;Só duas colunas\n".getBytes(StandardCharsets.UTF_8);
        var result = CsvStatementParser.parse(content,
                brazilianSigned(CsvEncoding.UTF_8, CsvDelimiter.SEMICOLON, false));
        assertThat(result.entries().getFirst().issues())
                .extracting(ValidationIssue::code)
                .containsExactly("STATEMENT_ROW_MISSING_AMOUNT");
    }

    @Test
    void detectionSuggestsEncodingAndDelimiter() {
        byte[] latin = "a;é;b\n".getBytes(Charset.forName("windows-1252"));
        assertThat(CsvDecoder.detectEncoding(latin)).isEqualTo(CsvEncoding.WINDOWS_1252);
        assertThat(CsvDecoder.detectEncoding("a,é,b\n".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(CsvEncoding.UTF_8);
        assertThat(CsvDecoder.detectDelimiter("a;b;c\n1;2;3\n"))
                .isEqualTo(CsvDelimiter.SEMICOLON);
        assertThat(CsvDecoder.detectDelimiter("a,b,c\n1,2,3\n"))
                .isEqualTo(CsvDelimiter.COMMA);
    }

    @Test
    void rawPreviewIsBounded() {
        byte[] content = "a;b\nc;d\ne;f\n".getBytes(StandardCharsets.UTF_8);
        List<List<String>> rows = CsvStatementParser.rawPreview(content, CsvEncoding.UTF_8,
                CsvDelimiter.SEMICOLON, 2);
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst()).containsExactly("a", "b");
    }

    @Test
    void deterministicNormalizationAndFingerprints() {
        assertThat(TextNormalizer.canonical("  PADARIA   São  João "))
                .isEqualTo("padaria sao joao");
        assertThat(TextNormalizer.clean("a b\tc")).isEqualTo("a b c");
        String first = Fingerprints.contentFingerprint(1L, 2L, LocalDate.of(2026, 6, 5),
                TransactionType.EXPENSE, new java.math.BigDecimal("25.9"), "padaria sao joao");
        String second = Fingerprints.contentFingerprint(1L, 2L, LocalDate.of(2026, 6, 5),
                TransactionType.EXPENSE, new java.math.BigDecimal("25.90"), "padaria sao joao");
        assertThat(first).isEqualTo(second).hasSize(64);
        String otherAccount = Fingerprints.contentFingerprint(1L, 3L, LocalDate.of(2026, 6, 5),
                TransactionType.EXPENSE, new java.math.BigDecimal("25.90"), "padaria sao joao");
        assertThat(otherAccount).isNotEqualTo(first);
    }
}
