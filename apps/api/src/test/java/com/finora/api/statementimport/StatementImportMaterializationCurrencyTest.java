package com.finora.api.statementimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The non-negotiable materialization invariant: an imported transaction is
 * denominated in its destination account's currency, always, explicitly.
 *
 * <p>Before this, {@code StatementMaterializationService} set the account but
 * never the currency, so the {@code Transaction} entity's BRL default survived.
 * With V15's composite foreign key in place that produced a rejected insert
 * rather than a mislabelled row — a foreign-currency import simply could not
 * complete — which is why these tests assert the whole path: the transaction
 * exists, and it names the account's currency rather than BRL or the user's
 * base currency.
 */
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class StatementImportMaterializationCurrencyTest extends AbstractIntegrationTest {

    /** Synthetic OFX declaring no currency, so any account may receive it. */
    private static String ofx(String amount, String fitId) {
        return """
                OFXHEADER:100
                DATA:OFXSGML

                <OFX>
                <BANKMSGSRSV1><STMTTRNRS><STMTRS>
                <BANKACCTFROM><BANKID>0260<ACCTID>12345-678<ACCTTYPE>CHECKING</BANKACCTFROM>
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
                """.formatted(amount, fitId);
    }

    @Test
    void importedTransactionUsesTheDestinationAccountCurrency() throws Exception {
        TestUser user = registerUser("Importadora USD");
        long account = createAccount(user, "Conta internacional", "USD");

        JsonNode transaction = importOneRow(user, account, ofx("25.90", "FIT-USD"));

        assertThat(transaction.get("currency").asString()).isEqualTo("USD");
        assertThat(transaction.get("amount").decimalValue()).isEqualByComparingTo("25.90");
        assertThat(transaction.get("imported").asBoolean()).isTrue();
    }

    @Test
    void accountCurrencyWinsOverTheUsersBaseCurrency() throws Exception {
        TestUser user = registerUser("Base EUR");
        // The user's base currency is deliberately a third currency: neither
        // the entity default nor the base setting may decide an import's
        // denomination, only the account the money moves in.
        setBaseCurrency(user, "EUR");
        long account = createAccount(user, "Conta libras", "GBP");

        JsonNode transaction = importOneRow(user, account, ofx("40.00", "FIT-GBP"));

        assertThat(transaction.get("currency").asString()).isEqualTo("GBP");
    }

    @Test
    void everyImportedTransactionAgreesWithItsAccountAcrossTheCatalogue() throws Exception {
        for (String currency : new String[] {"BRL", "USD", "EUR", "GBP", "CAD", "AUD", "CHF"}) {
            TestUser user = registerUser("Importadora " + currency);
            long account = createAccount(user, "Conta " + currency, currency);
            JsonNode transaction = importOneRow(user, account, ofx("31.50", "FIT-" + currency));
            assertThat(transaction.get("currency").asString())
                    .as("transaction currency for a %s account", currency)
                    .isEqualTo(currency);
            // The account is the source of truth, so both must agree.
            assertThat(accountCurrency(user, account)).isEqualTo(currency);
        }
    }

    @Test
    void zeroDecimalImportKeepsAWholeAmountAndTheAccountCurrency() throws Exception {
        TestUser user = registerUser("Importadora JPY");
        long account = createAccount(user, "Conta iene", "JPY");

        JsonNode transaction = importOneRow(user, account, ofx("1200.00", "FIT-JPY"));

        assertThat(transaction.get("currency").asString()).isEqualTo("JPY");
        // 1200 yen, with no meaningful fractional unit.
        assertThat(transaction.get("amount").decimalValue()).isEqualByComparingTo("1200");
        assertThat(transaction.get("amount").decimalValue().stripTrailingZeros().scale())
                .isLessThanOrEqualTo(0);
    }

    @Test
    void foreignImportedTransactionCanBeUndoneWithoutTouchingCurrencyState() throws Exception {
        TestUser user = registerUser("Desfaz USD");
        long account = createAccount(user, "Conta internacional", "USD");
        JsonNode batch = upload(user, account, ofx("77.70", "FIT-UNDO"));
        long batchId = batch.get("id").asLong();
        long itemId = batch.get("items").get(0).get("id").asLong();
        selectCategory(user, batchId, itemId);
        confirm(user, batchId);
        assertThat(transactions(user).get("totalElements").asLong()).isEqualTo(1);

        MvcResult undone = mockMvc.perform(post("/api/statement-imports/%d/undo".formatted(batchId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(undone).get("results").get(0).get("result").asString())
                .isEqualTo("UNDONE");

        // The financial effect is gone exactly once...
        assertThat(transactions(user).get("totalElements").asLong()).isZero();
        // ...the account keeps its immutable currency...
        assertThat(accountCurrency(user, account)).isEqualTo("USD");
        // ...and the batch's currency provenance is untouched by the undo.
        JsonNode after = detail(user, batchId);
        assertThat(after.get("currency").get("currencySource").asString())
                .isEqualTo("ACCOUNT_ASSUMED");
        assertThat(after.get("currency").get("accountCurrency").asString()).isEqualTo("USD");
        assertThat(after.get("items").get(0).get("status").asString()).isEqualTo("UNDONE");
    }

    /* ---------- helpers ---------- */

    private JsonNode importOneRow(TestUser user, long account, String ofx) throws Exception {
        JsonNode batch = upload(user, account, ofx);
        long batchId = batch.get("id").asLong();
        long itemId = batch.get("items").get(0).get("id").asLong();
        selectCategory(user, batchId, itemId);
        JsonNode confirmed = confirm(user, batchId);
        assertThat(confirmed.get("results").get(0).get("result").asString()).isEqualTo("SUCCESS");
        JsonNode page = transactions(user);
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        return page.get("content").get(0);
    }

    private JsonNode upload(TestUser user, long account, String content) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/statement-imports")
                        .file(new MockMultipartFile("file", "extrato.ofx",
                                "application/octet-stream",
                                content.getBytes(StandardCharsets.UTF_8)))
                        .param("accountId", String.valueOf(account))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result);
    }

    private JsonNode confirm(TestUser user, long batchId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/statement-imports/%d/confirm"
                        .formatted(batchId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acknowledgeAccountCurrency\": true}"))
                .andExpect(status().isOk())
                .andReturn();
        return json(result);
    }

    private JsonNode detail(TestUser user, long batchId) throws Exception {
        return json(mockMvc.perform(get("/api/statement-imports/" + batchId)
                        .cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private void selectCategory(TestUser user, long batchId, long itemId) throws Exception {
        long category = categoryId(user, "Alimentação", CategoryType.EXPENSE);
        mockMvc.perform(patch("/api/statement-imports/%d/items/%d".formatted(batchId, itemId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedCategoryId\": %d}".formatted(category)))
                .andExpect(status().isOk());
    }

    private long createAccount(TestUser user, String name, String currency) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "type": "CHECKING", "openingBalance": 1000,
                                 "currency": "%s"}
                                """.formatted(name, currency)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).get("id").asLong();
    }

    private String accountCurrency(TestUser user, long accountId) throws Exception {
        return json(mockMvc.perform(get("/api/accounts/" + accountId).cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn()).get("currency").asString();
    }

    private void setBaseCurrency(TestUser user, String currency) throws Exception {
        MvcResult current = mockMvc.perform(get("/api/settings").cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode settings = json(current);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/settings")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"minimumCashBuffer": %s,
                                 "maxInstallmentCommitmentRatio": %s,
                                 "monthlyOpportunityRate": %s,
                                 "budgetWarningThreshold": %s,
                                 "baseCurrency": "%s"}
                                """.formatted(
                                settings.get("minimumCashBuffer").asString(),
                                settings.get("maxInstallmentCommitmentRatio").asString(),
                                settings.get("monthlyOpportunityRate").asString(),
                                settings.get("budgetWarningThreshold").asString(),
                                currency)))
                .andExpect(status().isOk());
    }

    private JsonNode transactions(TestUser user) throws Exception {
        return json(mockMvc.perform(get("/api/transactions").cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
