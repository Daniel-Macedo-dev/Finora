package com.finora.api.creditcard;

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
 * Cards bill in one immutable currency, and every charge, invoice and
 * settlement stays inside it. Finora has no exchange rates, so a mismatch is
 * refused rather than converted at some invented rate.
 */
class CreditCardCurrencyIntegrationTest extends AbstractIntegrationTest {

    @Test
    void cardWithoutCurrencyUsesBaseCurrency() throws Exception {
        TestUser user = registerUser();
        long card = createCard(user, "Cartão", null, null);

        mockMvc.perform(get("/api/credit-cards/{id}", card).cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.limit.currency").value("BRL"));
    }

    @Test
    void cardKeepsAnExplicitForeignCurrency() throws Exception {
        TestUser user = registerUser();
        long card = createCard(user, "Cartão USD", "USD", null);

        mockMvc.perform(get("/api/credit-cards/{id}", card).cookie(user.session()))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.limit.currency").value("USD"));
    }

    @Test
    void unsupportedCardCurrencyIsRejected() throws Exception {
        TestUser user = registerUser();

        createCardExpecting(user, "Cartão", "BTC", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_UNSUPPORTED"));
    }

    @Test
    void defaultPaymentAccountMustShareTheCardCurrency() throws Exception {
        TestUser user = registerUser();
        long brlAccount = createAccount(user, "Conta BRL", "BRL");

        createCardExpecting(user, "Cartão USD", "USD", brlAccount)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_CURRENCY_MISMATCH"));
    }

    @Test
    void matchingDefaultPaymentAccountIsAccepted() throws Exception {
        TestUser user = registerUser();
        long usdAccount = createAccount(user, "Conta USD", "USD");

        long card = createCard(user, "Cartão USD", "USD", usdAccount);
        mockMvc.perform(get("/api/credit-cards/{id}", card).cookie(user.session()))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.defaultPaymentAccountId").value((int) usdAccount));
    }

    @Test
    void cardCurrencyCannotBeChanged() throws Exception {
        TestUser user = registerUser();
        long card = createCard(user, "Cartão USD", "USD", null);

        mockMvc.perform(put("/api/credit-cards/{id}", card)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardBody("Cartão USD", "BRL", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_IMMUTABLE"));
    }

    @Test
    void updateWithoutCurrencyKeepsTheCardCurrency() throws Exception {
        TestUser user = registerUser();
        long card = createCard(user, "Cartão USD", "USD", null);

        mockMvc.perform(put("/api/credit-cards/{id}", card)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardBody("Cartão USD renomeado", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void purchaseAndInvoiceStayInTheCardCurrency() throws Exception {
        TestUser user = registerUser();
        long card = createCard(user, "Cartão USD", "USD", null);
        createPurchase(user, card, "1200.00", 3).andExpect(status().isCreated());

        mockMvc.perform(get("/api/credit-cards/{id}/invoices", card).cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].currency").value("USD"));
    }

    @Test
    void jpyCardRejectsFractionalPurchase() throws Exception {
        TestUser user = registerUser();
        long card = createCard(user, "Cartão JPY", "JPY", null);

        createPurchase(user, card, "1200.50", 1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_FRACTION_INVALID"));
    }

    @Test
    void invoiceCannotBePaidFromAnAccountInAnotherCurrency() throws Exception {
        TestUser user = registerUser();
        long card = createCard(user, "Cartão USD", "USD", null);
        long brlAccount = createAccount(user, "Conta BRL", "BRL");
        createPurchase(user, card, "300.00", 1).andExpect(status().isCreated());
        long invoice = firstInvoiceId(user, card);

        mockMvc.perform(post("/api/credit-cards/{c}/invoices/{i}/payments", card, invoice)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId": %d, "amount": 100.00, "paidOn": "2026-07-12"}
                                """.formatted(brlAccount)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVOICE_PAYMENT_CURRENCY_MISMATCH"));

        // Atomicity: the rejection happened before anything financial moved.
        mockMvc.perform(get("/api/credit-cards/{c}/invoices/{i}", card, invoice)
                        .cookie(user.session()))
                .andExpect(jsonPath("$.invoice.amountPaid").value(0))
                .andExpect(jsonPath("$.invoice.outstandingAmount").value(300.00))
                .andExpect(jsonPath("$.payments").isEmpty());
        mockMvc.perform(get("/api/accounts/{id}", brlAccount).cookie(user.session()))
                .andExpect(jsonPath("$.currentBalance").value(1000.00));
    }

    @Test
    void invoiceIsPaidFromAnAccountOfTheSameCurrency() throws Exception {
        TestUser user = registerUser();
        long usdAccount = createAccount(user, "Conta USD", "USD");
        long card = createCard(user, "Cartão USD", "USD", usdAccount);
        createPurchase(user, card, "300.00", 1).andExpect(status().isCreated());
        long invoice = firstInvoiceId(user, card);

        mockMvc.perform(post("/api/credit-cards/{c}/invoices/{i}/payments", card, invoice)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId": %d, "amount": 100.00, "paidOn": "2026-07-12"}
                                """.formatted(usdAccount)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.invoiceOutstandingAmount").value(200.00));
    }

    @Test
    void anotherUsersAccountCannotSettleThisUsersInvoice() throws Exception {
        TestUser owner = registerUser("Dono");
        long foreignAccount = createAccount(owner, "Conta do dono", "USD");

        TestUser user = registerUser("Outro");
        long card = createCard(user, "Cartão USD", "USD", null);
        createPurchase(user, card, "300.00", 1).andExpect(status().isCreated());
        long invoice = firstInvoiceId(user, card);

        // Same currency, but not this user's account: it must look absent.
        mockMvc.perform(post("/api/credit-cards/{c}/invoices/{i}/payments", card, invoice)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId": %d, "amount": 100.00, "paidOn": "2026-07-12"}
                                """.formatted(foreignAccount)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherUsersAccountCannotBecomeADefaultPaymentAccount() throws Exception {
        TestUser owner = registerUser("Dono");
        long foreignAccount = createAccount(owner, "Conta do dono", "USD");

        TestUser user = registerUser("Outro");
        createCardExpecting(user, "Cartão USD", "USD", foreignAccount)
                .andExpect(status().isNotFound());
    }

    // --- helpers ---------------------------------------------------------

    private long createAccount(TestUser user, String name, String currency) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "type": "CHECKING", "currency": "%s",
                                 "openingBalance": 1000.00}
                                """.formatted(name, currency)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private long createCard(TestUser user, String name, String currency, Long accountId)
            throws Exception {
        MvcResult result = createCardExpecting(user, name, currency, accountId)
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private org.springframework.test.web.servlet.ResultActions createCardExpecting(
            TestUser user, String name, String currency, Long accountId) throws Exception {
        return mockMvc.perform(post("/api/credit-cards")
                .cookie(user.session())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(cardBody(name, currency, accountId)));
    }

    private static String cardBody(String name, String currency, Long accountId) {
        String currencyField = currency == null ? "" : "\"currency\": \"%s\", ".formatted(currency);
        String accountField = accountId == null
                ? ""
                : "\"defaultPaymentAccountId\": %d, ".formatted(accountId);
        return """
                {"name": "%s", "brand": "VISA", "creditLimit": 10000.00,
                 "closingDay": 5, "dueDay": 12, %s%s"lastFourDigits": "1234"}
                """.formatted(name, currencyField, accountField);
    }

    private org.springframework.test.web.servlet.ResultActions createPurchase(
            TestUser user, long cardId, String amount, int installments) throws Exception {
        return mockMvc.perform(post("/api/credit-cards/{id}/purchases", cardId)
                .cookie(user.session())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"description": "Compra", "categoryId": %d, "purchaseDate": "2026-07-01",
                         "totalAmount": %s, "installmentCount": %d}
                        """.formatted(categoryId(user, "Compras", CategoryType.EXPENSE),
                        amount, installments)));
    }

    private long firstInvoiceId(TestUser user, long cardId) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/credit-cards/{id}/invoices", cardId).cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get(0).get("id").asLong();
    }

    private long idOf(MvcResult result) throws Exception {
        return objectMapper
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }
}
