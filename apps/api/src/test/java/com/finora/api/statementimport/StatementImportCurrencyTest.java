package com.finora.api.statementimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import tools.jackson.databind.JsonNode;

/**
 * Statement-import currency behaviour end to end through the public API: the
 * four currency sources, CSV inheritance, OFX {@code CURDEF} agreement,
 * zero-decimal precision, destination-account changes, the acknowledgement a
 * currency assumption requires and owner isolation.
 *
 * <p>Every fixture is synthetic. No real bank statement is used anywhere.
 */
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class StatementImportCurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestUser user;

    @BeforeEach
    void setUpOwner() throws Exception {
        user = registerUser("Importadora multi-moeda");
    }

    /* ---------- fixtures ---------- */

    /** Synthetic OFX; a null declaration omits CURDEF entirely. */
    private static String ofx(String declaredCurrency, String amount, String fitId) {
        return """
                OFXHEADER:100
                DATA:OFXSGML

                <OFX>
                <BANKMSGSRSV1><STMTTRNRS><STMTRS>
                %s<BANKACCTFROM><BANKID>0260<ACCTID>12345-678<ACCTTYPE>CHECKING</BANKACCTFROM>
                <BANKTRANLIST>
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260605
                <TRNAMT>-%s
                <FITID>%s
                <NAME>Assinatura mensal
                </STMTTRN>
                </BANKTRANLIST>
                </STMTRS></STMTTRNRS></BANKMSGSRSV1>
                </OFX>
                """.formatted(declaredCurrency == null ? ""
                : "<CURDEF>" + declaredCurrency + "\n", amount, fitId);
    }

    private static final String CSV_TWO_ROWS = """
            05/06/2026;Mercado Central;-120,50
            06/06/2026;Transferencia recebida;80,00
            """;

    /* ---------- CSV: the account is the contract ---------- */

    @Test
    void brlCsvKeepsWorkingExactlyAsBefore() throws Exception {
        long account = createAccount("Conta corrente", "BRL");
        JsonNode batch = uploadCsvAndParse(account, CSV_TWO_ROWS);

        assertCurrency(batch, "BRL", "ACCOUNT", null, false);
        assertThat(batch.get("items")).hasSize(2);
        assertThat(batch.get("totals").get("currency").asString()).isEqualTo("BRL");
        for (JsonNode item : batch.get("items")) {
            assertThat(item.get("currency").asString()).isEqualTo("BRL");
        }
    }

    @Test
    void csvPreviewInheritsAndDeclaresTheAccountCurrencyWithoutConversion() throws Exception {
        long account = createAccount("Conta internacional", "USD");

        // The candidate mapping preview already names the denomination.
        JsonNode uploaded = upload(account, "extrato.csv", CSV_TWO_ROWS, status().isCreated());
        long batchId = uploaded.get("id").asLong();
        assertCurrency(uploaded, "USD", "ACCOUNT", null, false);
        JsonNode mapping = csvMapping(batchId, MAPPING_NO_HEADER, status().isOk());
        assertThat(mapping.get("accountCurrency").asString()).isEqualTo("USD");

        JsonNode batch = reparse(batchId);
        assertCurrency(batch, "USD", "ACCOUNT", null, false);
        // No conversion, ever: the same numbers read in the account's currency.
        assertThat(batch.get("currency").get("valuesAreConverted").asBoolean()).isFalse();
        assertThat(batch.get("currency").get("effectiveCurrency").asString()).isEqualTo("USD");
        assertThat(itemByIndex(batch, 1).get("amount").decimalValue())
                .isEqualByComparingTo("120.50");
    }

    @Test
    void csvConfirmationNeedsNoAcknowledgementAndCreatesForeignTransactions() throws Exception {
        long account = createAccount("Conta internacional", "USD");
        JsonNode batch = uploadCsvAndParse(account, CSV_TWO_ROWS);
        long batchId = batch.get("id").asLong();
        assertThat(batch.get("currency").get("currencyAcknowledgementRequired").asBoolean())
                .isFalse();
        selectCategoryForAll(batchId);

        // Choosing the account IS the denomination decision, so a bare
        // confirmation without any acknowledgement succeeds.
        JsonNode confirmed = confirmRaw(batchId, null, status().isOk());
        assertThat(confirmed.get("totals").get("currency").asString()).isEqualTo("USD");
        for (JsonNode result : confirmed.get("results")) {
            assertThat(result.get("result").asString()).isEqualTo("SUCCESS");
        }
        for (JsonNode transaction : transactions().get("content")) {
            assertThat(transaction.get("currency").asString()).isEqualTo("USD");
        }
    }

    @Test
    void csvAccountChangeMovesTheEffectiveCurrencyAndRerunsAccountScopedState() throws Exception {
        long brl = createAccount("Conta BRL", "BRL");
        long usd = createAccount("Conta USD", "USD");
        JsonNode batch = uploadCsvAndParse(brl, CSV_TWO_ROWS);
        long batchId = batch.get("id").asLong();
        assertCurrency(batch, "BRL", "ACCOUNT", null, false);

        JsonNode moved = changeAccount(batchId, usd, status().isOk());

        assertCurrency(moved, "USD", "ACCOUNT", null, false);
        assertThat(moved.get("accountId").asLong()).isEqualTo(usd);
        assertThat(moved.get("totals").get("currency").asString()).isEqualTo("USD");
        for (JsonNode item : moved.get("items")) {
            assertThat(item.get("currency").asString()).isEqualTo("USD");
            // Duplicate classification is account-scoped and was recomputed
            // against the new destination.
            assertThat(item.get("duplicateStatus").asString()).isEqualTo("UNIQUE");
            // The values themselves were not touched: nothing was converted.
            assertThat(item.get("amount").decimalValue())
                    .isEqualByComparingTo(item.get("originalAmount").decimalValue());
        }
    }

    /* ---------- zero-decimal currencies ---------- */

    @Test
    void wholeYenAmountPreviewsAndImportsAsYen() throws Exception {
        long account = createAccount("Conta iene", "JPY");
        JsonNode batch = uploadCsvAndParse(account, "05/06/2026;Ramen;-1200\n");
        long batchId = batch.get("id").asLong();

        assertCurrency(batch, "JPY", "ACCOUNT", null, false);
        JsonNode item = itemByIndex(batch, 1);
        assertThat(item.get("status").asString()).isEqualTo("READY");
        assertThat(item.get("amount").decimalValue()).isEqualByComparingTo("1200");

        selectCategoryForAll(batchId);
        confirmRaw(batchId, null, status().isOk());
        JsonNode transaction = transactions().get("content").get(0);
        assertThat(transaction.get("currency").asString()).isEqualTo("JPY");
        assertThat(transaction.get("amount").decimalValue()).isEqualByComparingTo("1200");
        assertThat(transaction.get("amount").decimalValue().stripTrailingZeros().scale())
                .isLessThanOrEqualTo(0);
    }

    @Test
    void fractionalYenRowIsInvalidBeforeConfirmationAndRecoversWhenCorrected() throws Exception {
        long account = createAccount("Conta iene", "JPY");

        // The candidate mapping preview already refuses to promise the row.
        JsonNode uploaded = upload(account, "extrato.csv", "05/06/2026;Ramen;-100,50\n",
                status().isCreated());
        long batchId = uploaded.get("id").asLong();
        JsonNode mapping = csvMapping(batchId, MAPPING_NO_HEADER, status().isOk());
        assertThat(mapping.get("validCount").asInt()).isZero();
        assertThat(mapping.get("entries").get(0).get("validationCode").asString())
                .isEqualTo("CURRENCY_FRACTION_INVALID");

        JsonNode batch = reparse(batchId);
        JsonNode item = itemByIndex(batch, 1);
        long itemId = item.get("id").asLong();
        assertThat(item.get("status").asString()).isEqualTo("INVALID");
        assertThat(item.get("validationCode").asString()).isEqualTo("CURRENCY_FRACTION_INVALID");
        assertThat(item.get("importable").asBoolean()).isFalse();
        // 100,50 was not silently rounded into 101 yen.
        assertThat(item.get("amount").decimalValue()).isEqualByComparingTo("100.50");

        // Confirming imports nothing.
        selectCategoryForAll(batchId);
        confirmRaw(batchId, null, status().isOk());
        assertThat(transactions().get("totalElements").asLong()).isZero();

        // A fractional edit is refused rather than rounded...
        patchItem(batchId, itemId, "{\"amount\": 100.50}", status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CURRENCY_FRACTION_INVALID"));

        // ...and an integer edit restores importability through the ordinary
        // core-fields-complete path.
        patchItem(batchId, itemId, "{\"amount\": 101, \"included\": true}", status().isOk());
        JsonNode fixed = itemByIndex(detail(batchId), 1);
        assertThat(fixed.get("status").asString()).isEqualTo("READY");
        assertThat(fixed.get("validationCode").isNull()).isTrue();
        assertThat(fixed.get("importable").asBoolean()).isTrue();

        confirmRaw(batchId, null, status().isOk());
        JsonNode transaction = transactions().get("content").get(0);
        assertThat(transaction.get("currency").asString()).isEqualTo("JPY");
        assertThat(transaction.get("amount").decimalValue()).isEqualByComparingTo("101");
    }

    @Test
    void movingToAZeroDecimalAccountRevalidatesFractionalRowsBothWays() throws Exception {
        long usd = createAccount("Conta USD", "USD");
        long jpy = createAccount("Conta iene", "JPY");
        JsonNode batch = uploadCsvAndParse(usd, "05/06/2026;Assinatura;-12,34\n");
        long batchId = batch.get("id").asLong();
        assertThat(itemByIndex(batch, 1).get("status").asString()).isEqualTo("READY");

        JsonNode toYen = changeAccount(batchId, jpy, status().isOk());
        JsonNode invalid = itemByIndex(toYen, 1);
        assertThat(invalid.get("currency").asString()).isEqualTo("JPY");
        assertThat(invalid.get("status").asString()).isEqualTo("INVALID");
        assertThat(invalid.get("validationCode").asString())
                .isEqualTo("CURRENCY_FRACTION_INVALID");

        // Moving back makes the row importable again: the amount never changed,
        // only which currency could represent it.
        JsonNode backToDollars = changeAccount(batchId, usd, status().isOk());
        JsonNode restored = itemByIndex(backToDollars, 1);
        assertThat(restored.get("status").asString()).isEqualTo("READY");
        assertThat(restored.get("validationCode").isNull()).isTrue();
        assertThat(restored.get("amount").decimalValue()).isEqualByComparingTo("12.34");
    }

    /* ---------- OFX with a declared currency ---------- */

    @Test
    void matchingDeclaredCurrencyBecomesAFileSourcedBatch() throws Exception {
        for (String currency : new String[] {"BRL", "USD", "JPY"}) {
            TestUser owner = registerUser("Declara " + currency);
            long account = createAccount(owner, "Conta " + currency, currency);
            JsonNode batch = upload(owner, account, "extrato.ofx",
                    ofx(currency, "1200.00", "FIT-" + currency), status().isCreated());

            assertCurrency(batch, currency, "FILE", currency, false);
            assertThat(batch.get("currency").get("effectiveCurrency").asString())
                    .isEqualTo(currency);
            assertThat(batch.get("currency").get("valuesAreConverted").asBoolean()).isFalse();
            // A declaration Finora believed needs no confirmation from the user.
            assertThat(batch.get("currency").get("currencyAcknowledgementRequired").asBoolean())
                    .isFalse();
        }
    }

    @Test
    void lowercaseAndPaddedDeclarationsAreNormalizedNotRejected() throws Exception {
        long account = createAccount("Conta internacional", "USD");
        JsonNode batch = upload(account, "extrato.ofx", ofx("  usd  ", "10.00", "FIT-N"),
                status().isCreated());
        assertCurrency(batch, "USD", "FILE", "USD", false);
    }

    @Test
    void mismatchedDeclarationIsRejectedWithNoBatchOrTransactionSideEffect() throws Exception {
        long account = createAccount("Conta corrente", "BRL");

        MvcResult rejected = mockMvc.perform(multipart("/api/statement-imports")
                        .file(file("extrato.ofx", ofx("EUR", "10.00", "FIT-MM")))
                        .param("accountId", String.valueOf(account))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STATEMENT_CURRENCY_MISMATCH"))
                // The message names both currencies and offers no conversion.
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("EUR")))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("BRL")))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("converter"))))
                .andReturn();
        assertThat(rejected.getResponse().getStatus()).isEqualTo(422);

        // Nothing was created: no batch, no item, no transaction.
        assertThat(history().get("totalElements").asLong()).isZero();
        assertThat(transactions().get("totalElements").asLong()).isZero();
    }

    @Test
    void currencyOutsideTheCatalogueIsRejectedBeforeAnyBatchExists() throws Exception {
        long account = createAccount("Conta corrente", "BRL");

        mockMvc.perform(multipart("/api/statement-imports")
                        .file(file("extrato.ofx", ofx("CNY", "10.00", "FIT-UNS")))
                        .param("accountId", String.valueOf(account))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CURRENCY_UNSUPPORTED"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("CNY")));

        // Never silently remapped to the account currency.
        assertThat(history().get("totalElements").asLong()).isZero();
        assertThat(transactions().get("totalElements").asLong()).isZero();
    }

    @Test
    void conflictingDeclarationsAreRejected() throws Exception {
        long account = createAccount("Conta corrente", "BRL");
        String conflicting = ofx("BRL", "10.00", "FIT-C")
                .replace("<CURDEF>BRL\n", "<CURDEF>BRL\n<CURDEF>USD\n");

        mockMvc.perform(multipart("/api/statement-imports")
                        .file(file("extrato.ofx", conflicting))
                        .param("accountId", String.valueOf(account))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STATEMENT_CURRENCY_CONFLICT"));
        assertThat(history().get("totalElements").asLong()).isZero();
    }

    /* ---------- OFX without a declared currency ---------- */

    @Test
    void missingDeclarationPreviewsAsAnAssumptionThatMustBeAcknowledged() throws Exception {
        long account = createAccount("Conta internacional", "USD");
        JsonNode batch = upload(account, "extrato.ofx", ofx(null, "25.90", "FIT-A"),
                status().isCreated());
        long batchId = batch.get("id").asLong();

        // Absence of CURDEF is not an upload error: the preview exists.
        assertThat(batch.get("status").asString()).isEqualTo("PREVIEW_READY");
        assertCurrency(batch, "USD", "ACCOUNT_ASSUMED", null, true);
        assertThat(batch.get("items")).hasSize(1);
        selectCategoryForAll(batchId);

        // Confirming without consent is refused as an invalid request...
        confirmRaw(batchId, null, status().isUnprocessableContent());
        mockMvc.perform(post("/api/statement-imports/%d/confirm".formatted(batchId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(jsonPath("$.code").value("STATEMENT_CURRENCY_ACK_REQUIRED"));
        // ...creating nothing, and without blaming the rows: an item is not
        // FAILED because the request lacked a confirmation.
        assertThat(transactions().get("totalElements").asLong()).isZero();
        JsonNode untouched = itemByIndex(detail(batchId), 1);
        assertThat(untouched.get("status").asString()).isEqualTo("READY");
        assertThat(untouched.get("resultCode").isNull()).isTrue();

        // An explicit acknowledgement lets the ordinary engine run.
        JsonNode confirmed = confirmRaw(batchId, true, status().isOk());
        assertThat(confirmed.get("results").get(0).get("result").asString()).isEqualTo("SUCCESS");
        JsonNode transaction = transactions().get("content").get(0);
        assertThat(transaction.get("currency").asString()).isEqualTo("USD");

        // Acknowledgement is consent, not identity: repeating the confirmation
        // creates no second transaction.
        confirmRaw(batchId, true, status().isOk());
        confirmRaw(batchId, true, status().isOk());
        assertThat(transactions().get("totalElements").asLong()).isEqualTo(1);
        // And a finished batch no longer demands consent for work already done.
        assertThat(detail(batchId).get("currency").get("currencyAcknowledgementRequired")
                .asBoolean()).isFalse();
        assertThat(detail(batchId).get("currency").get("currencySource").asString())
                .isEqualTo("ACCOUNT_ASSUMED");
    }

    @Test
    void acknowledgementDoesNotChangeAnyFinancialIdentity() throws Exception {
        long account = createAccount("Conta internacional", "USD");
        // An import that needed consent to happen at all.
        JsonNode acknowledged = upload(account, "extrato.ofx", ofx(null, "25.90", "FIT-ID"),
                status().isCreated());
        assertThat(acknowledged.get("currency").get("currencySource").asString())
                .isEqualTo("ACCOUNT_ASSUMED");
        long assumedBatch = acknowledged.get("id").asLong();
        selectCategoryForAll(assumedBatch);
        confirmRaw(assumedBatch, true, status().isOk());

        // A different file, declaring its currency, carrying the same row. If
        // the acknowledgement had entered any identity — external id, content
        // fingerprint, fingerprint version — this row would look new.
        JsonNode declared = upload(account, "extrato.ofx", ofx("USD", "25.90", "FIT-ID"),
                status().isCreated());
        assertThat(declared.get("fileSha256").asString())
                .isNotEqualTo(acknowledged.get("fileSha256").asString());
        assertThat(declared.get("currency").get("currencySource").asString()).isEqualTo("FILE");
        assertThat(itemByIndex(declared, 1).get("duplicateStatus").asString())
                .isEqualTo("EXACT_DUPLICATE");
        assertThat(itemByIndex(declared, 1).get("importable").asBoolean()).isFalse();

        // Confirming it creates no second transaction.
        selectCategoryForAll(declared.get("id").asLong());
        confirmRaw(declared.get("id").asLong(), null, status().isOk());
        assertThat(transactions().get("totalElements").asLong()).isEqualTo(1);
    }

    /* ---------- destination-account changes ---------- */

    @Test
    void declaredCurrencyBatchMovesOnlyBetweenAccountsOfThatCurrency() throws Exception {
        long usd = createAccount("Conta USD", "USD");
        long otherUsd = createAccount("Outra conta USD", "USD");
        long brl = createAccount("Conta BRL", "BRL");
        JsonNode batch = upload(usd, "extrato.ofx", ofx("USD", "10.00", "FIT-CH"),
                status().isCreated());
        long batchId = batch.get("id").asLong();

        // Same currency: allowed.
        JsonNode moved = changeAccount(batchId, otherUsd, status().isOk());
        assertThat(moved.get("accountId").asLong()).isEqualTo(otherUsd);
        assertCurrency(moved, "USD", "FILE", "USD", false);

        // Another currency: refused, before anything is written.
        mockMvc.perform(patch("/api/statement-imports/" + batchId)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\": %d}".formatted(brl)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STATEMENT_CURRENCY_MISMATCH"));

        // The rejected change left the previous destination untouched.
        JsonNode after = detail(batchId);
        assertThat(after.get("accountId").asLong()).isEqualTo(otherUsd);
        assertCurrency(after, "USD", "FILE", "USD", false);
        for (JsonNode item : after.get("items")) {
            assertThat(item.get("currency").asString()).isEqualTo("USD");
        }
    }

    @Test
    void assumedCurrencyBatchFollowsTheNewAccountAndStillNeedsAcknowledgement() throws Exception {
        long usd = createAccount("Conta USD", "USD");
        long eur = createAccount("Conta EUR", "EUR");
        JsonNode batch = upload(usd, "extrato.ofx", ofx(null, "10.00", "FIT-AS"),
                status().isCreated());
        long batchId = batch.get("id").asLong();
        assertCurrency(batch, "USD", "ACCOUNT_ASSUMED", null, true);

        JsonNode moved = changeAccount(batchId, eur, status().isOk());

        // The assumption moved with the account; the provenance did not change.
        assertCurrency(moved, "EUR", "ACCOUNT_ASSUMED", null, true);
        selectCategoryForAll(batchId);
        confirmRaw(batchId, null, status().isUnprocessableContent());
        confirmRaw(batchId, true, status().isOk());
        assertThat(transactions().get("content").get(0).get("currency").asString())
                .isEqualTo("EUR");
    }

    /* ---------- pre-V16 batches ---------- */

    @Test
    void pendingLegacyBatchNeedsAcknowledgementWithoutClaimingTheFileOmittedCurdef()
            throws Exception {
        long account = createAccount("Conta internacional", "USD");
        JsonNode batch = upload(account, "extrato.ofx", ofx("USD", "10.00", "FIT-LEG"),
                status().isCreated());
        long batchId = batch.get("id").asLong();
        makeLegacy(batchId);

        JsonNode legacy = detail(batchId);
        assertCurrency(legacy, "USD", "LEGACY_UNKNOWN", null, true);
        // No invented declaration: the evidence is missing, not the CURDEF.
        assertThat(legacy.get("currency").get("declaredCurrency").isNull()).isTrue();

        selectCategoryForAll(batchId);
        confirmRaw(batchId, null, status().isUnprocessableContent());
        mockMvc.perform(post("/api/statement-imports/%d/confirm".formatted(batchId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(jsonPath("$.code").value("STATEMENT_CURRENCY_ACK_REQUIRED"))
                // The wording is about Finora's own history, not the file's.
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("antes de o Finora registrar")));

        confirmRaw(batchId, true, status().isOk());
        assertThat(transactions().get("content").get(0).get("currency").asString())
                .isEqualTo("USD");

        // Acknowledging did not rewrite the provenance into a claim.
        assertThat(detail(batchId).get("currency").get("currencySource").asString())
                .isEqualTo("LEGACY_UNKNOWN");
        assertThat(detail(batchId).get("currency").get("declaredCurrency").isNull()).isTrue();
    }

    @Test
    void completedLegacyBatchStaysReadableAndAsksForNothing() throws Exception {
        long account = createAccount("Conta internacional", "USD");
        JsonNode batch = upload(account, "extrato.ofx", ofx("USD", "10.00", "FIT-DONE"),
                status().isCreated());
        long batchId = batch.get("id").asLong();
        selectCategoryForAll(batchId);
        confirmRaw(batchId, null, status().isOk());
        makeLegacy(batchId);

        JsonNode legacy = detail(batchId);
        assertThat(legacy.get("status").asString()).isEqualTo("COMPLETED");
        assertCurrency(legacy, "USD", "LEGACY_UNKNOWN", null, false);
        assertThat(legacy.get("items").get(0).get("status").asString()).isEqualTo("IMPORTED");

        // History reports it truthfully too.
        JsonNode row = history().get("content").get(0);
        assertThat(row.get("currencySource").asString()).isEqualTo("LEGACY_UNKNOWN");
        assertThat(row.get("accountCurrency").asString()).isEqualTo("USD");
        assertThat(row.get("declaredCurrency").isNull()).isTrue();
    }

    /* ---------- owner isolation ---------- */

    @Test
    void anotherOwnersAccountBehavesAsMissingAndNeverLeaksItsCurrency() throws Exception {
        TestUser stranger = registerUser("Estranha");
        long strangerAccount = createAccount(stranger, "Conta secreta EUR", "EUR");

        // Uploading a EUR-declaring file into someone else's EUR account must
        // read as missing, not as a currency comparison of any kind.
        MvcResult probe = mockMvc.perform(multipart("/api/statement-imports")
                        .file(file("extrato.ofx", ofx("EUR", "10.00", "FIT-X")))
                        .param("accountId", String.valueOf(strangerAccount))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isNotFound())
                .andReturn();
        String body = probe.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("EUR").doesNotContain("Conta secreta");

        // The same for a mismatching file: still 404, still no currency.
        MvcResult mismatchProbe = mockMvc.perform(multipart("/api/statement-imports")
                        .file(file("extrato.ofx", ofx("JPY", "10.00", "FIT-Y")))
                        .param("accountId", String.valueOf(strangerAccount))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(mismatchProbe.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("EUR");

        // A stranger's batch and its items are unreachable in every direction.
        JsonNode theirs = upload(stranger, strangerAccount, "extrato.ofx",
                ofx("EUR", "10.00", "FIT-Z"), status().isCreated());
        long theirBatch = theirs.get("id").asLong();
        long theirItem = theirs.get("items").get(0).get("id").asLong();
        long myAccount = createAccount("Minha conta", "EUR");

        mockMvc.perform(get("/api/statement-imports/" + theirBatch).cookie(user.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/statement-imports/%d/confirm".formatted(theirBatch))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/statement-imports/%d/items/%d"
                        .formatted(theirBatch, theirItem))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 1}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/statement-imports/" + theirBatch)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\": %d}".formatted(myAccount)))
                .andExpect(status().isNotFound());
        assertThat(history().get("totalElements").asLong()).isZero();
    }

    /* ---------- helpers ---------- */

    private static final String MAPPING_NO_HEADER = """
            {"encoding": "UTF_8", "delimiter": "SEMICOLON", "hasHeader": false,
             "datePattern": "dd/MM/yyyy", "decimalSeparator": "COMMA",
             "thousandsSeparator": "DOT", "dateColumn": 0,
             "descriptionColumn": 1, "amountColumn": 2}
            """;

    /** Rewrites a batch as if it had been created before V16. */
    private void makeLegacy(long batchId) {
        jdbcTemplate.update("""
                UPDATE statement_import_batches
                   SET currency_source = 'LEGACY_UNKNOWN', declared_currency = NULL,
                       parser_version = 1
                 WHERE id = ?
                """, batchId);
    }

    private void assertCurrency(JsonNode batch, String accountCurrency, String source,
                                String declared, boolean acknowledgementRequired) {
        JsonNode currency = batch.get("currency");
        assertThat(currency.get("accountCurrency").asString()).isEqualTo(accountCurrency);
        assertThat(currency.get("currencySource").asString()).isEqualTo(source);
        if (declared == null) {
            assertThat(currency.get("declaredCurrency").isNull()).isTrue();
        } else {
            assertThat(currency.get("declaredCurrency").asString()).isEqualTo(declared);
        }
        assertThat(currency.get("effectiveCurrency").asString()).isEqualTo(accountCurrency);
        assertThat(currency.get("valuesAreConverted").asBoolean()).isFalse();
        assertThat(currency.get("currencyAcknowledgementRequired").asBoolean())
                .isEqualTo(acknowledgementRequired);
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "application/octet-stream",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode upload(long accountId, String name, String content, ResultMatcher expected)
            throws Exception {
        return upload(user, accountId, name, content, expected);
    }

    private JsonNode upload(TestUser owner, long accountId, String name, String content,
                            ResultMatcher expected) throws Exception {
        return json(mockMvc.perform(multipart("/api/statement-imports")
                        .file(file(name, content))
                        .param("accountId", String.valueOf(accountId))
                        .cookie(owner.session()).with(csrf()))
                .andExpect(expected)
                .andReturn());
    }

    private JsonNode uploadCsvAndParse(long accountId, String csv) throws Exception {
        long batchId = upload(accountId, "extrato.csv", csv, status().isCreated())
                .get("id").asLong();
        csvMapping(batchId, MAPPING_NO_HEADER, status().isOk());
        return reparse(batchId);
    }

    private JsonNode csvMapping(long batchId, String mapping, ResultMatcher expected)
            throws Exception {
        return json(mockMvc.perform(put("/api/statement-imports/%d/csv-mapping"
                        .formatted(batchId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapping))
                .andExpect(expected)
                .andReturn());
    }

    private JsonNode reparse(long batchId) throws Exception {
        return json(mockMvc.perform(post("/api/statement-imports/%d/reparse".formatted(batchId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode changeAccount(long batchId, long accountId, ResultMatcher expected)
            throws Exception {
        return json(mockMvc.perform(patch("/api/statement-imports/" + batchId)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\": %d}".formatted(accountId)))
                .andExpect(expected)
                .andReturn());
    }

    private JsonNode confirmRaw(long batchId, Boolean acknowledge, ResultMatcher expected)
            throws Exception {
        var request = post("/api/statement-imports/%d/confirm".formatted(batchId))
                .cookie(user.session()).with(csrf());
        if (acknowledge != null) {
            request = request.contentType(MediaType.APPLICATION_JSON)
                    .content("{\"acknowledgeAccountCurrency\": %s}".formatted(acknowledge));
        }
        return json(mockMvc.perform(request).andExpect(expected).andReturn());
    }

    private org.springframework.test.web.servlet.ResultActions patchItem(
            long batchId, long itemId, String body, ResultMatcher expected) throws Exception {
        return mockMvc.perform(patch("/api/statement-imports/%d/items/%d"
                        .formatted(batchId, itemId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(expected);
    }

    private void selectCategoryForAll(long batchId) throws Exception {
        long expense = categoryId(user, "Alimentação", CategoryType.EXPENSE);
        long income = categoryId(user, "Salário", CategoryType.INCOME);
        for (JsonNode item : detail(batchId).get("items")) {
            if (item.get("type").isNull()) {
                continue;
            }
            long category = "INCOME".equals(item.get("type").asString()) ? income : expense;
            mockMvc.perform(patch("/api/statement-imports/%d/items/%d"
                            .formatted(batchId, item.get("id").asLong()))
                            .cookie(user.session()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"selectedCategoryId\": %d}".formatted(category)))
                    .andExpect(status().isOk());
        }
    }

    private JsonNode detail(long batchId) throws Exception {
        return json(mockMvc.perform(get("/api/statement-imports/" + batchId)
                        .cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode history() throws Exception {
        return json(mockMvc.perform(get("/api/statement-imports").cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode transactions() throws Exception {
        return json(mockMvc.perform(get("/api/transactions").cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private static JsonNode itemByIndex(JsonNode batch, int sourceIndex) {
        for (JsonNode item : batch.get("items")) {
            if (item.get("sourceIndex").asInt() == sourceIndex) {
                return item;
            }
        }
        throw new AssertionError("item de origem " + sourceIndex + " não encontrado");
    }

    private long createAccount(String name, String currency) throws Exception {
        return createAccount(user, name, currency);
    }

    private long createAccount(TestUser owner, String name, String currency) throws Exception {
        return json(mockMvc.perform(post("/api/accounts")
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "type": "CHECKING", "openingBalance": 1000,
                                 "currency": "%s"}
                                """.formatted(name, currency)))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asLong();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
