package com.finora.api.statementimport.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.api.statementimport.parser.ofx.OfxStatementParser;
import com.finora.api.transaction.TransactionType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Pure OFX parsing tests over synthetic fixtures only. */
class OfxStatementParserTest {

    private static final String SGML_HEADER = """
            OFXHEADER:100
            DATA:OFXSGML
            VERSION:102
            SECURITY:NONE
            ENCODING:USASCII
            CHARSET:1252
            COMPRESSION:NONE
            OLDFILEUID:NONE
            NEWFILEUID:NONE

            """;

    private final TimeZone originalZone = TimeZone.getDefault();

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(originalZone);
    }

    private static byte[] sgml(String transactions) {
        return sgmlDeclaring("<CURDEF>BRL\n", transactions);
    }

    /**
     * The same synthetic statement with an arbitrary statement-level metadata
     * block, so CURDEF can be omitted, repeated, conflicting or malformed
     * without duplicating the whole envelope.
     */
    private static byte[] sgmlDeclaring(String statementMetadata, String transactions) {
        return (SGML_HEADER + """
                <OFX>
                <BANKMSGSRSV1>
                <STMTTRNRS>
                <STMTRS>
                """ + statementMetadata + """
                <BANKACCTFROM>
                <BANKID>0260
                <ACCTID>12345-6789
                <ACCTTYPE>CHECKING
                </BANKACCTFROM>
                <BANKTRANLIST>
                """ + transactions + """
                </BANKTRANLIST>
                </STMTRS>
                </STMTTRNRS>
                </BANKMSGSRSV1>
                </OFX>
                """).getBytes(StandardCharsets.UTF_8);
    }

    private static final String ONE_TRANSACTION = """
            <STMTTRN>
            <TRNTYPE>DEBIT
            <DTPOSTED>20260601
            <TRNAMT>-10.00
            <FITID>CUR-1
            <NAME>Mercado
            </STMTTRN>
            """;

    @Test
    void parsesSgmlWithMultipleTransactions() {
        var result = OfxStatementParser.parse(sgml("""
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260605
                <TRNAMT>-25.90
                <FITID>FIT-001
                <NAME>Padaria São João
                <MEMO>Compra no débito
                </STMTTRN>
                <STMTTRN>
                <TRNTYPE>CREDIT
                <DTPOSTED>20260606120000[-3:BRT]
                <TRNAMT>5200.00
                <FITID>FIT-002
                <NAME>Salário
                </STMTTRN>
                """));

        assertThat(result.entries()).hasSize(2);
        var expense = result.entries().getFirst();
        assertThat(expense.valid()).isTrue();
        assertThat(expense.externalId()).isEqualTo("FIT-001");
        assertThat(expense.postedDate()).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(expense.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(expense.absoluteAmount()).isEqualByComparingTo("25.90");
        assertThat(expense.description()).isEqualTo("Padaria São João");
        assertThat(expense.memo()).isEqualTo("Compra no débito");
        assertThat(expense.sourceType()).isEqualTo("DEBIT");
        var income = result.entries().get(1);
        assertThat(income.type()).isEqualTo(TransactionType.INCOME);
        assertThat(income.postedDate()).isEqualTo(LocalDate.of(2026, 6, 6));
        // Masked account hint: never the full number.
        assertThat(result.accountHint()).contains("0260").contains("•••6789")
                .doesNotContain("12345-6789");
    }

    @Test
    void parsesXmlVariant() {
        byte[] content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <?OFX OFXHEADER="200" VERSION="211" SECURITY="NONE"?>
                <OFX>
                  <BANKMSGSRSV1>
                    <STMTTRNRS>
                      <STMTRS>
                        <BANKACCTFROM>
                          <BANKID>0341</BANKID>
                          <ACCTID>98765</ACCTID>
                          <ACCTTYPE>SAVINGS</ACCTTYPE>
                        </BANKACCTFROM>
                        <BANKTRANLIST>
                          <STMTTRN>
                            <TRNTYPE>PAYMENT</TRNTYPE>
                            <DTPOSTED>20260610</DTPOSTED>
                            <TRNAMT>-99.99</TRNAMT>
                            <FITID>X-1</FITID>
                            <MEMO>Pagamento &amp; taxas</MEMO>
                          </STMTTRN>
                        </BANKTRANLIST>
                      </STMTRS>
                    </STMTTRNRS>
                  </BANKMSGSRSV1>
                </OFX>
                """.getBytes(StandardCharsets.UTF_8);
        var result = OfxStatementParser.parse(content);
        assertThat(result.entries()).hasSize(1);
        var entry = result.entries().getFirst();
        // NAME missing: MEMO becomes the description (entities decoded).
        assertThat(entry.description()).isEqualTo("Pagamento & taxas");
        assertThat(entry.memo()).isNull();
        assertThat(entry.valid()).isTrue();
    }

    @Test
    void missingFitidYieldsNullExternalIdAndChecknumEnrichesMemo() {
        var result = OfxStatementParser.parse(sgml("""
                <STMTTRN>
                <TRNTYPE>CHECK
                <DTPOSTED>20260607
                <TRNAMT>-150.00
                <NAME>Cheque compensado
                <CHECKNUM>000123
                </STMTTRN>
                """));
        var entry = result.entries().getFirst();
        assertThat(entry.externalId()).isNull();
        assertThat(entry.memo()).contains("000123");
        assertThat(entry.valid()).isTrue();
    }

    @Test
    void datesAreDeterministicAcrossSystemTimezones() {
        byte[] content = sgml("""
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260601000000[-3:BRT]
                <TRNAMT>-1.00
                <FITID>TZ-1
                <NAME>Meia-noite
                </STMTTRN>
                """);
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
        LocalDate ahead = OfxStatementParser.parse(content).entries().getFirst().postedDate();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"));
        LocalDate local = OfxStatementParser.parse(content).entries().getFirst().postedDate();
        assertThat(ahead).isEqualTo(local).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void handlesMonthAndYearBoundaries() {
        var result = OfxStatementParser.parse(sgml("""
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20251231235959
                <TRNAMT>-1.00
                <FITID>Y-1
                <NAME>Réveillon
                </STMTTRN>
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260229
                <TRNAMT>-2.00
                <FITID>Y-2
                <NAME>Data impossível
                </STMTTRN>
                """));
        assertThat(result.entries().getFirst().postedDate())
                .isEqualTo(LocalDate.of(2025, 12, 31));
        // 2026 is not a leap year: invalid date is a row issue, not a crash.
        assertThat(result.entries().get(1).issues())
                .extracting(ValidationIssue::code)
                .containsExactly("STATEMENT_ROW_INVALID_DATE");
    }

    @Test
    void zeroAmountAndMissingFieldsAreRowIssues() {
        var result = OfxStatementParser.parse(sgml("""
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260601
                <TRNAMT>0.00
                <FITID>Z-1
                <NAME>Zerado
                </STMTTRN>
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260601
                <TRNAMT>-1.00
                <FITID>Z-2
                </STMTTRN>
                """));
        assertThat(result.entries().get(0).issues())
                .extracting(ValidationIssue::code)
                .containsExactly("STATEMENT_ROW_ZERO_AMOUNT");
        assertThat(result.entries().get(1).issues())
                .extracting(ValidationIssue::code)
                .containsExactly("STATEMENT_ROW_MISSING_DESCRIPTION");
    }

    @Test
    void rejectsMalformedOfx() {
        assertThatThrownBy(() -> OfxStatementParser.parse(
                "isto não é um OFX".getBytes(StandardCharsets.UTF_8)))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_OFX_MALFORMED");
        assertThatThrownBy(() -> OfxStatementParser.parse(
                "<OFX><STMTTRN><um tag inválido!!".getBytes(StandardCharsets.UTF_8)))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_OFX_MALFORMED");
    }

    @Test
    void blocksLikelyCreditCardStatement() {
        byte[] content = """
                <OFX>
                <CREDITCARDMSGSRSV1>
                <CCSTMTTRNRS>
                <CCSTMTRS>
                <CCACCTFROM><ACCTID>4111</ACCTID></CCACCTFROM>
                </CCSTMTRS>
                </CCSTMTTRNRS>
                </CREDITCARDMSGSRSV1>
                </OFX>
                """.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> OfxStatementParser.parse(content))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_CARD_NOT_SUPPORTED");
    }

    @Test
    void rejectsUnsupportedAccountType() {
        byte[] content = """
                <OFX>
                <BANKACCTFROM><ACCTTYPE>CREDITLINE</ACCTTYPE></BANKACCTFROM>
                </OFX>
                """.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> OfxStatementParser.parse(content))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_OFX_ACCOUNT_TYPE");
    }

    @Test
    void rejectsDoctypeAndExternalEntities() {
        byte[] doctype = """
                <?xml version="1.0"?>
                <!DOCTYPE OFX [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <OFX>&xxe;</OFX>
                """.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> OfxStatementParser.parse(doctype))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_OFX_DTD");
        // Unknown named entities are inert data, never resolved.
        var result = OfxStatementParser.parse(sgml("""
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260601
                <TRNAMT>-1.00
                <FITID>E-1
                <NAME>Loja &desconhecida; ltda
                </STMTTRN>
                """));
        assertThat(result.entries().getFirst().description())
                .isEqualTo("Loja &desconhecida; ltda");
    }

    @Test
    void enforcesEntryAndFieldLimits() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= StatementLimits.MAX_ENTRIES; i++) {
            many.append("<STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20260601<TRNAMT>-1.00<FITID>N-")
                    .append(i).append("<NAME>Linha ").append(i).append("</STMTTRN>\n");
        }
        assertThatThrownBy(() -> OfxStatementParser.parse(sgml(many.toString())))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_TOO_MANY_ROWS");

        String longField = "<STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20260601<TRNAMT>-1.00<NAME>"
                + "x".repeat(StatementLimits.MAX_FIELD_LENGTH + 1) + "</STMTTRN>";
        assertThatThrownBy(() -> OfxStatementParser.parse(sgml(longField)))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_OFX_FIELD_TOO_LONG");
    }

    /* ---------- declared currency (CURDEF) ---------- */

    @Test
    void readsDeclaredCurrencyFromSgmlStatementMetadata() {
        var result = OfxStatementParser.parse(sgml(ONE_TRANSACTION));
        assertThat(result.declaredCurrency()).isEqualTo("BRL");
        // Reading CURDEF changed nothing about the rows themselves.
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().getFirst().absoluteAmount()).isEqualByComparingTo("10.00");
        assertThat(result.accountHint()).contains("•••6789").doesNotContain("12345-6789");
    }

    @Test
    void readsDeclaredCurrencyFromXmlStatementMetadata() {
        byte[] content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <?OFX OFXHEADER="200" VERSION="211" SECURITY="NONE"?>
                <OFX>
                  <BANKMSGSRSV1>
                    <STMTTRNRS>
                      <STMTRS>
                        <CURDEF>USD</CURDEF>
                        <BANKACCTFROM>
                          <BANKID>0341</BANKID>
                          <ACCTID>98765</ACCTID>
                          <ACCTTYPE>CHECKING</ACCTTYPE>
                        </BANKACCTFROM>
                        <BANKTRANLIST>
                          <STMTTRN>
                            <TRNTYPE>DEBIT</TRNTYPE>
                            <DTPOSTED>20260610</DTPOSTED>
                            <TRNAMT>-12.34</TRNAMT>
                            <FITID>X-1</FITID>
                            <NAME>Coffee</NAME>
                          </STMTTRN>
                        </BANKTRANLIST>
                      </STMTRS>
                    </STMTTRNRS>
                  </BANKMSGSRSV1>
                </OFX>
                """.getBytes(StandardCharsets.UTF_8);
        var result = OfxStatementParser.parse(content);
        assertThat(result.declaredCurrency()).isEqualTo("USD");
        assertThat(result.entries()).hasSize(1);
    }

    @Test
    void normalizesDeclaredCurrencyCase() {
        assertThat(OfxStatementParser.parse(sgmlDeclaring("<CURDEF>usd\n", ONE_TRANSACTION))
                .declaredCurrency()).isEqualTo("USD");
        assertThat(OfxStatementParser.parse(sgmlDeclaring("<CURDEF>uSd\n", ONE_TRANSACTION))
                .declaredCurrency()).isEqualTo("USD");
    }

    @Test
    void normalizesDeclaredCurrencyWhitespace() {
        assertThat(OfxStatementParser.parse(sgmlDeclaring("<CURDEF>   USD  \n", ONE_TRANSACTION))
                .declaredCurrency()).isEqualTo("USD");
        assertThat(OfxStatementParser.parse(
                sgmlDeclaring("<CURDEF> \t eur \t </CURDEF>\n", ONE_TRANSACTION))
                .declaredCurrency()).isEqualTo("EUR");
    }

    @Test
    void acceptsRepeatedIdenticalDeclaredCurrency() {
        // Multi-statement files legitimately restate the same CURDEF.
        var result = OfxStatementParser.parse(
                sgmlDeclaring("<CURDEF>EUR\n<CURDEF>eur\n<CURDEF> EUR \n", ONE_TRANSACTION));
        assertThat(result.declaredCurrency()).isEqualTo("EUR");
    }

    @Test
    void rejectsConflictingDeclaredCurrencies() {
        // Neither the first nor the last value may win: the document is
        // ambiguous about real money, so it is refused.
        assertThatThrownBy(() -> OfxStatementParser.parse(
                sgmlDeclaring("<CURDEF>USD\n<CURDEF>EUR\n", ONE_TRANSACTION)))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_CURRENCY_CONFLICT");
        assertThatThrownBy(() -> OfxStatementParser.parse(
                sgmlDeclaring("<CURDEF>BRL\n<CURDEF>BRL\n<CURDEF>JPY\n", ONE_TRANSACTION)))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_CURRENCY_CONFLICT");
    }

    @Test
    void reportsAbsentDeclaredCurrencyAsNull() {
        assertThat(OfxStatementParser.parse(sgmlDeclaring("", ONE_TRANSACTION))
                .declaredCurrency()).isNull();
        // An empty element declares nothing either — it must not become "".
        assertThat(OfxStatementParser.parse(sgmlDeclaring("<CURDEF></CURDEF>\n", ONE_TRANSACTION))
                .declaredCurrency()).isNull();
    }

    @Test
    void transactionLevelCurrencyNeverBecomesStatementCurrency() {
        // A CURDEF tag inside a STMTTRN, a per-transaction CURRENCY aggregate
        // and the literal word in a MEMO are all transaction-scope data: none
        // may override (or invent) the statement's declaration.
        var declared = OfxStatementParser.parse(sgmlDeclaring("<CURDEF>BRL\n", """
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260601
                <TRNAMT>-10.00
                <FITID>M-1
                <NAME>Mercado
                <MEMO>CURDEF USD conforme <CURDEF>JPY
                <CURRENCY>
                <CURRATE>1.00
                <CURSYM>JPY
                </CURRENCY>
                </STMTTRN>
                """));
        assertThat(declared.declaredCurrency()).isEqualTo("BRL");

        var undeclared = OfxStatementParser.parse(sgmlDeclaring("", """
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260601
                <TRNAMT>-10.00
                <FITID>M-2
                <NAME>Mercado
                <CURDEF>USD
                </STMTTRN>
                """));
        assertThat(undeclared.declaredCurrency()).isNull();
    }

    @Test
    void rejectsMalformedDeclaredCurrencyWithoutEchoingIt() {
        // Not a three-letter code: refused with a stable code, and the raw
        // file content is never reflected back in the message.
        assertThatThrownBy(() -> OfxStatementParser.parse(
                sgmlDeclaring("<CURDEF>DOLLARS\n", ONE_TRANSACTION)))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_CURRENCY_INVALID")
                .hasMessageNotContaining("DOLLARS");
        assertThatThrownBy(() -> OfxStatementParser.parse(
                sgmlDeclaring("<CURDEF>U$D\n", ONE_TRANSACTION)))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_CURRENCY_INVALID");
        assertThatThrownBy(() -> OfxStatementParser.parse(
                sgmlDeclaring("<CURDEF>12\n", ONE_TRANSACTION)))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_CURRENCY_INVALID");
    }

    @Test
    void declaredCurrencyStaysWithinTheExistingFieldLimit() {
        String oversized = "<CURDEF>" + "U".repeat(StatementLimits.MAX_FIELD_LENGTH + 1) + "\n";
        assertThatThrownBy(() -> OfxStatementParser.parse(
                sgmlDeclaring(oversized, ONE_TRANSACTION)))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_OFX_FIELD_TOO_LONG");
    }

    @Test
    void curdefNearMaliciousDeclarationsDoesNotWeakenSecurityBoundaries() {
        // CURDEF adjacent to a DOCTYPE/ENTITY must not create a path that
        // reaches the scanner before the declaration check rejects the file.
        byte[] doctype = """
                <?xml version="1.0"?>
                <!DOCTYPE OFX [<!ENTITY moeda SYSTEM "file:///etc/passwd">]>
                <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS>
                <CURDEF>&moeda;</CURDEF>
                </STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
                """.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> OfxStatementParser.parse(doctype))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_OFX_DTD");

        byte[] entityOnly = ("""
                <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS>
                <!ENTITY moeda "USD">
                <CURDEF>BRL
                """ + ONE_TRANSACTION + """
                </STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
                """).getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> OfxStatementParser.parse(entityOnly))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_OFX_DTD");

        // An unresolvable named entity inside CURDEF stays inert text, and
        // inert text is not a currency code.
        assertThatThrownBy(() -> OfxStatementParser.parse(
                sgmlDeclaring("<CURDEF>&moeda;\n", ONE_TRANSACTION)))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_CURRENCY_INVALID");
    }

    @Test
    void malformedNestingAroundCurdefStillFails() {
        assertThatThrownBy(() -> OfxStatementParser.parse(
                sgmlDeclaring("<CURDEF!!>USD\n", ONE_TRANSACTION)))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_OFX_MALFORMED");
        assertThatThrownBy(() -> OfxStatementParser.parse(
                ("<OFX><STMTRS><CURDEF>USD<BANKACCTFROM" ).getBytes(StandardCharsets.UTF_8)))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_OFX_MALFORMED");
    }

    @Test
    void creditCardStatementIsStillRejectedEvenWhenItDeclaresACurrency() {
        byte[] content = """
                <OFX>
                <CREDITCARDMSGSRSV1>
                <CCSTMTTRNRS>
                <CCSTMTRS>
                <CURDEF>USD
                <CCACCTFROM><ACCTID>4111</ACCTID></CCACCTFROM>
                </CCSTMTRS>
                </CCSTMTTRNRS>
                </CREDITCARDMSGSRSV1>
                </OFX>
                """.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> OfxStatementParser.parse(content))
                .hasFieldOrPropertyWithValue("code", "STATEMENT_CARD_NOT_SUPPORTED");
    }
}
