package com.finora.api.transaction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Native-currency rules for accounts and transactions: currency is derived from
 * the authoritative resource, never converted, and never edited afterwards.
 */
class TransactionCurrencyIntegrationTest extends AbstractIntegrationTest {

    @Test
    void accountKeepsItsCurrencyAndReportsItOnEveryRead() throws Exception {
        TestUser user = registerUser();
        long account = createAccount(user, "Conta USD", "USD", "1000.00");

        mockMvc.perform(get("/api/accounts/{id}", account).cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.openingBalance").value(1000.00));
    }

    @Test
    void omittedCurrencyMeansBaseCurrencyRatherThanAGuess() throws Exception {
        TestUser user = registerUser();
        long account = createAccount(user, "Conta padrão", null, "10.00");

        mockMvc.perform(get("/api/accounts/{id}", account).cookie(user.session()))
                .andExpect(jsonPath("$.currency").value("BRL"));
    }

    @Test
    void accountCurrencyCannotBeChanged() throws Exception {
        TestUser user = registerUser();
        long account = createAccount(user, "Conta USD", "USD", "1000.00");

        mockMvc.perform(put("/api/accounts/{id}", account)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Conta USD", "type": "CHECKING",
                                 "openingBalance": 1000.00, "currency": "BRL"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_IMMUTABLE"));
    }

    @Test
    void usdAccountAndUsdTransactionsProduceANativeBalance() throws Exception {
        TestUser user = registerUser();
        long account = createAccount(user, "Conta USD", "USD", "1000.00");
        createTransaction(user, account, null, "EXPENSE", "250.50")
                .andExpect(jsonPath("$.currency").value("USD"));

        mockMvc.perform(get("/api/accounts/{id}", account).cookie(user.session()))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.currentBalance").value(749.50));
    }

    @Test
    void transactionInheritsAccountCurrencyWhenOmitted() throws Exception {
        TestUser user = registerUser();
        long account = createAccount(user, "Conta EUR", "EUR", "500.00");

        createTransaction(user, account, null, "EXPENSE", "10.00")
                .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void transactionInAnotherCurrencyThanItsAccountIsRejected() throws Exception {
        TestUser user = registerUser();
        long account = createAccount(user, "Conta BRL", "BRL", "500.00");

        createTransactionExpecting(user, account, "USD", "EXPENSE", "10.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CURRENCY_MISMATCH"));
    }

    @Test
    void accountlessTransactionKeepsItsExplicitCurrency() throws Exception {
        TestUser user = registerUser();

        createTransaction(user, null, "EUR", "INCOME", "300.00")
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.account").doesNotExist());
    }

    @Test
    void accountlessTransactionWithoutCurrencyFallsBackToBaseCurrency() throws Exception {
        TestUser user = registerUser();

        createTransaction(user, null, null, "INCOME", "300.00")
                .andExpect(jsonPath("$.currency").value("BRL"));
    }

    @Test
    void transactionCurrencyCannotChangeOnUpdate() throws Exception {
        TestUser user = registerUser();
        long id = transactionId(createTransaction(user, null, "EUR", "INCOME", "300.00"));

        mockMvc.perform(put("/api/transactions/{id}", id)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "USD", "INCOME", "300.00",
                                categoryId(user, "Salário", CategoryType.INCOME))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_IMMUTABLE"));
    }

    @Test
    void aTransactionCannotBeMovedToAnAccountOfAnotherCurrency() throws Exception {
        TestUser user = registerUser();
        long usd = createAccount(user, "Conta USD", "USD", "1000.00");
        long id = transactionId(createTransaction(user, null, "EUR", "INCOME", "300.00"));

        mockMvc.perform(put("/api/transactions/{id}", id)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(usd, null, "INCOME", "300.00",
                                categoryId(user, "Salário", CategoryType.INCOME))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CURRENCY_MISMATCH"));
    }

    @Test
    void jpyRejectsFractionalAmounts() throws Exception {
        TestUser user = registerUser();

        createTransactionExpecting(user, null, "JPY", "INCOME", "100.50")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_FRACTION_INVALID"));
    }

    @Test
    void jpyAcceptsWholeAmounts() throws Exception {
        TestUser user = registerUser();

        createTransaction(user, null, "JPY", "INCOME", "1200")
                .andExpect(jsonPath("$.currency").value("JPY"))
                .andExpect(jsonPath("$.amount").value(1200));
    }

    @Test
    void unsupportedCurrencyIsRejected() throws Exception {
        TestUser user = registerUser();

        createTransactionExpecting(user, null, "BTC", "INCOME", "10.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_UNSUPPORTED"));
    }

    @Test
    void anotherUsersAccountStaysInvisibleRatherThanLeakingItsCurrency() throws Exception {
        TestUser owner = registerUser("Dono");
        long foreign = createAccount(owner, "Conta do dono", "USD", "100.00");

        TestUser attacker = registerUser("Invasor");
        createTransactionExpecting(attacker, foreign, "USD", "EXPENSE", "10.00")
                .andExpect(status().isNotFound());
    }

    // --- helpers ---------------------------------------------------------

    private long createAccount(TestUser user, String name, String currency, String opening)
            throws Exception {
        String currencyField = currency == null ? "" : "\"currency\": \"%s\", ".formatted(currency);
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "type": "CHECKING", %s"openingBalance": %s}
                                """.formatted(name, currencyField, opening)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions createTransaction(
            TestUser user, Long accountId, String currency, String type, String amount)
            throws Exception {
        return createTransactionExpecting(user, accountId, currency, type, amount)
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions createTransactionExpecting(
            TestUser user, Long accountId, String currency, String type, String amount)
            throws Exception {
        Long category = "INCOME".equals(type)
                ? categoryId(user, "Salário", CategoryType.INCOME)
                : categoryId(user, "Moradia", CategoryType.EXPENSE);
        return mockMvc.perform(post("/api/transactions")
                .cookie(user.session())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(accountId, currency, type, amount, category)));
    }

    private static String body(Long accountId, String currency, String type, String amount,
            Long categoryId) {
        String accountField = accountId == null ? "" : "\"accountId\": %d, ".formatted(accountId);
        String currencyField = currency == null ? "" : "\"currency\": \"%s\", ".formatted(currency);
        return """
                {"type": "%s", "amount": %s, "description": "Movimento", "date": "2026-07-01",
                 "categoryId": %d, %s%s"paymentMethod": "PIX"}
                """.formatted(type, amount, categoryId, accountField, currencyField);
    }

    private long transactionId(org.springframework.test.web.servlet.ResultActions actions)
            throws Exception {
        return objectMapper
                .readTree(actions.andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }
}
