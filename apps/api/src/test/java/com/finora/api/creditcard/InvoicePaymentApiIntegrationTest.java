package com.finora.api.creditcard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Invoice settlement: payments reduce the account balance exactly once, never
 * count as expense, restore card limit, and are undone through auditable
 * reversals. Fixed future dates keep the derived statuses stable.
 */
class InvoicePaymentApiIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long categoryId;
    private long cardId;
    private long accountId;
    private long invoiceId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        categoryId = categoryId(user, "Compras", CategoryType.EXPENSE);
        accountId = createAccount("Conta Corrente", "3000.00");
        cardId = createCard("Cartão Pagável", "5000.00");
        // One purchase of R$ 1.000,00 → single March/2031 invoice.
        createPurchase("1000.00", 1, "2031-03-05");
        invoiceId = firstInvoiceId();
    }

    private long createAccount(String name, String openingBalance) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "type": "CHECKING", "openingBalance": %s}
                                """.formatted(name, openingBalance)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    private long createCard(String name, String limit) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "brand": "VISA", "creditLimit": %s,
                                 "closingDay": 10, "dueDay": 17}
                                """.formatted(name, limit)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    private void createPurchase(String amount, int installments, String date) throws Exception {
        mockMvc.perform(post("/api/credit-cards/%d/purchases".formatted(cardId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Compra teste", "categoryId": %d,
                                 "purchaseDate": "%s",
                                 "totalAmount": %s, "installmentCount": %d}
                                """.formatted(categoryId, date, amount, installments)))
                .andExpect(status().isCreated());
    }

    private long firstInvoiceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/credit-cards/%d/invoices".formatted(cardId))
                        .cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get(0).get("id").asLong();
    }

    private MvcResult pay(String amount) throws Exception {
        return mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/payments"
                        .formatted(cardId, invoiceId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId": %d, "amount": %s, "paidOn": "2031-03-15"}
                                """.formatted(accountId, amount)))
                .andReturn();
    }

    @Test
    void cardPurchaseAloneNeverTouchesTheBankAccount() throws Exception {
        mockMvc.perform(get("/api/accounts/" + accountId).cookie(user.session()))
                .andExpect(jsonPath("$.currentBalance").value(3000.00));
    }

    @Test
    void fullPaymentSettlesInvoiceReducesBalanceOnceAndRestoresLimit() throws Exception {
        pay("1000.00");

        mockMvc.perform(get("/api/credit-cards/%d/invoices/%d".formatted(cardId, invoiceId))
                        .cookie(user.session()))
                .andExpect(jsonPath("$.invoice.status").value("PAID"))
                .andExpect(jsonPath("$.invoice.amountPaid").value(1000.00))
                .andExpect(jsonPath("$.invoice.outstandingAmount").value(0.00))
                .andExpect(jsonPath("$.payments.length()").value(1));

        // Balance drops exactly once; the expense was the installment, not the payment.
        mockMvc.perform(get("/api/accounts/" + accountId).cookie(user.session()))
                .andExpect(jsonPath("$.currentBalance").value(2000.00));

        // Limit is restored.
        mockMvc.perform(get("/api/credit-cards/" + cardId).cookie(user.session()))
                .andExpect(jsonPath("$.limit.usedLimit").value(0.00))
                .andExpect(jsonPath("$.limit.availableLimit").value(5000.00));
    }

    @Test
    void partialPaymentsAccumulateUntilSettled() throws Exception {
        pay("400.00");
        mockMvc.perform(get("/api/credit-cards/%d/invoices/%d".formatted(cardId, invoiceId))
                        .cookie(user.session()))
                .andExpect(jsonPath("$.invoice.status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.invoice.outstandingAmount").value(600.00));

        pay("600.00");
        mockMvc.perform(get("/api/credit-cards/%d/invoices/%d".formatted(cardId, invoiceId))
                        .cookie(user.session()))
                .andExpect(jsonPath("$.invoice.status").value("PAID"))
                .andExpect(jsonPath("$.payments.length()").value(2));

        mockMvc.perform(get("/api/accounts/" + accountId).cookie(user.session()))
                .andExpect(jsonPath("$.currentBalance").value(2000.00));
    }

    @Test
    void rejectsOverpayment() throws Exception {
        mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/payments"
                        .formatted(cardId, invoiceId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId": %d, "amount": 1000.01, "paidOn": "2031-03-15"}
                                """.formatted(accountId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYMENT_EXCEEDS_OUTSTANDING"));
    }

    @Test
    void rejectsArchivedAccountAndForeignAccount() throws Exception {
        long archived = createAccount("Conta Arquivada", "100.00");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/accounts/" + archived)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Conta Arquivada", "type": "CHECKING",
                                 "openingBalance": 100.00, "archived": true}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/payments"
                        .formatted(cardId, invoiceId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId": %d, "amount": 10.00, "paidOn": "2031-03-15"}
                                """.formatted(archived)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ARCHIVED"));

        TestUser other = registerUser("Outra Pessoa");
        MvcResult foreign = mockMvc.perform(post("/api/accounts")
                        .cookie(other.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Conta Alheia", "type": "CHECKING", "openingBalance": 500}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long foreignId = objectMapper.readTree(
                        foreign.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
        mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/payments"
                        .formatted(cardId, invoiceId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId": %d, "amount": 10.00, "paidOn": "2031-03-15"}
                                """.formatted(foreignId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void reversalRestoresEverythingExactlyOnceAndIsNotRepeatable() throws Exception {
        MvcResult payment = pay("1000.00");
        long paymentId = objectMapper.readTree(
                        payment.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();

        mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/payments/%d/reverse"
                        .formatted(cardId, invoiceId, paymentId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"))
                .andExpect(jsonPath("$.reversedAt").isNotEmpty())
                .andExpect(jsonPath("$.invoiceOutstandingAmount").value(1000.00));

        // Account balance restored exactly once; limit consumed again.
        mockMvc.perform(get("/api/accounts/" + accountId).cookie(user.session()))
                .andExpect(jsonPath("$.currentBalance").value(3000.00));
        mockMvc.perform(get("/api/credit-cards/" + cardId).cookie(user.session()))
                .andExpect(jsonPath("$.limit.usedLimit").value(1000.00));

        // History is preserved, not deleted.
        mockMvc.perform(get("/api/credit-cards/%d/invoices/%d".formatted(cardId, invoiceId))
                        .cookie(user.session()))
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].status").value("REVERSED"));

        // Reversing twice is explicit nonsense.
        mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/payments/%d/reverse"
                        .formatted(cardId, invoiceId, paymentId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_REVERSED"));
    }

    @Test
    void paidInvoiceBlocksPurchaseCancellation() throws Exception {
        pay("1000.00");
        MvcResult purchases = mockMvc.perform(get("/api/credit-cards/%d/purchases"
                        .formatted(cardId)).cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        long purchaseId = objectMapper.readTree(
                        purchases.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("content").get(0).get("id").asLong();

        mockMvc.perform(post("/api/credit-cards/%d/purchases/%d/cancel"
                        .formatted(cardId, purchaseId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PURCHASE_INVOICE_ALREADY_PAID"));
    }

    @Test
    void debitAdjustmentIncreasesInvoiceAndCreditReducesIt() throws Exception {
        mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/adjustments"
                        .formatted(cardId, invoiceId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind": "INTEREST", "description": "Juros rotativo",
                                 "amount": 45.00, "categoryId": %d}
                                """.formatted(categoryId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/credit-cards/%d/invoices/%d".formatted(cardId, invoiceId))
                        .cookie(user.session()))
                .andExpect(jsonPath("$.invoice.adjustmentsNet").value(45.00))
                .andExpect(jsonPath("$.invoice.invoiceTotal").value(1045.00));

        MvcResult credit = mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/adjustments"
                        .formatted(cardId, invoiceId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind": "REFUND", "description": "Estorno da loja", "amount": 45.00}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        mockMvc.perform(get("/api/credit-cards/%d/invoices/%d".formatted(cardId, invoiceId))
                        .cookie(user.session()))
                .andExpect(jsonPath("$.invoice.invoiceTotal").value(1000.00));

        // A credit larger than the outstanding amount would fabricate cash.
        mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/adjustments"
                        .formatted(cardId, invoiceId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind": "CREDIT", "description": "Crédito impossível",
                                 "amount": 99999.00}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CREDIT_EXCEEDS_OUTSTANDING"));

        // Debit adjustments demand an expense category.
        mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/adjustments"
                        .formatted(cardId, invoiceId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind": "FEE", "description": "Anuidade", "amount": 30.00}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ADJUSTMENT_CATEGORY_REQUIRED"));

        // Reversal restores the invoice and is auditable.
        long creditId = objectMapper.readTree(
                        credit.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
        mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/adjustments/%d/reverse"
                        .formatted(cardId, invoiceId, creditId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));
        mockMvc.perform(get("/api/credit-cards/%d/invoices/%d".formatted(cardId, invoiceId))
                        .cookie(user.session()))
                .andExpect(jsonPath("$.invoice.invoiceTotal").value(1045.00));
    }

    @Test
    void overdueUnpaidInvoiceIsReportedAsOverdue() throws Exception {
        // A separate card keeps this scenario isolated; 2025 dates are always past.
        long overdueCard = createCard("Cartão Atrasado", "2000.00");
        mockMvc.perform(post("/api/credit-cards/%d/purchases".formatted(overdueCard))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Compra antiga", "categoryId": %d,
                                 "purchaseDate": "2025-01-05",
                                 "totalAmount": 200.00, "installmentCount": 1}
                                """.formatted(categoryId)))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/credit-cards/%d/invoices".formatted(overdueCard))
                        .cookie(user.session()))
                .andExpect(jsonPath("$[0].status").value("OVERDUE"));
    }

    @Test
    void anotherUserCannotPayReverseOrReadInvoices() throws Exception {
        TestUser intruder = registerUser("Intruso");
        mockMvc.perform(get("/api/credit-cards/%d/invoices".formatted(cardId))
                        .cookie(intruder.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/credit-cards/%d/invoices/%d".formatted(cardId, invoiceId))
                        .cookie(intruder.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/payments"
                        .formatted(cardId, invoiceId))
                        .cookie(intruder.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId": %d, "amount": 10.00, "paidOn": "2031-03-15"}
                                """.formatted(accountId)))
                .andExpect(status().isNotFound());
    }
}
