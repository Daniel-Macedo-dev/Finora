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
 * The central accounting invariant, end to end: card spending is recognized
 * through installments in their invoice months — in budgets and dashboard —
 * and paying the invoice moves cash without ever creating a second expense.
 */
class CardAccountingIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long categoryId;
    private long cardId;
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        categoryId = categoryId(user, "Compras", CategoryType.EXPENSE);

        MvcResult account = mockMvc.perform(post("/api/accounts")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Conta Principal", "type": "CHECKING", "openingBalance": 5000}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        accountId = objectMapper.readTree(
                        account.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();

        MvcResult card = mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Cartão Contábil", "brand": "VISA", "creditLimit": 10000,
                                 "closingDay": 10, "dueDay": 17}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        cardId = objectMapper.readTree(
                        card.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    private void createBudget(String month, String limit) throws Exception {
        mockMvc.perform(post("/api/budgets")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"month": "%s", "categoryId": %d, "limitAmount": %s}
                                """.formatted(month, categoryId, limit)))
                .andExpect(status().isCreated());
    }

    private long createPurchase(String amount, int installments, String date) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/credit-cards/%d/purchases".formatted(cardId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Compra parcelada", "categoryId": %d,
                                 "purchaseDate": "%s", "totalAmount": %s, "installmentCount": %d}
                                """.formatted(categoryId, date, amount, installments)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    @Test
    void installmentsConsumeBudgetsInTheirInvoiceMonthsExactlyOnce() throws Exception {
        createBudget("2031-03", "500.00");
        createBudget("2031-04", "500.00");
        // Purchase before closing day 10 → first invoice 2031-03, then 2031-04, 2031-05.
        createPurchase("300.00", 3, "2031-03-05");

        mockMvc.perform(get("/api/budgets").cookie(user.session()).param("month", "2031-03"))
                .andExpect(jsonPath("$.budgets[0].consumedAmount").value(100.00));
        mockMvc.perform(get("/api/budgets").cookie(user.session()).param("month", "2031-04"))
                .andExpect(jsonPath("$.budgets[0].consumedAmount").value(100.00));

        // Paying the March invoice must not change any budget consumption.
        MvcResult invoices = mockMvc.perform(get("/api/credit-cards/%d/invoices".formatted(cardId))
                        .cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        long marchInvoice = objectMapper.readTree(
                        invoices.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get(0).get("id").asLong();
        mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/payments"
                        .formatted(cardId, marchInvoice))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId": %d, "amount": 100.00, "paidOn": "2031-03-15"}
                                """.formatted(accountId)))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/budgets").cookie(user.session()).param("month", "2031-03"))
                .andExpect(jsonPath("$.budgets[0].consumedAmount").value(100.00));
    }

    @Test
    void cancellationRemovesBudgetConsumption() throws Exception {
        createBudget("2031-03", "500.00");
        long purchaseId = createPurchase("300.00", 3, "2031-03-05");
        mockMvc.perform(post("/api/credit-cards/%d/purchases/%d/cancel"
                        .formatted(cardId, purchaseId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/budgets").cookie(user.session()).param("month", "2031-03"))
                .andExpect(jsonPath("$.budgets[0].consumedAmount").value(0.00));
    }

    @Test
    void anotherUsersInstallmentsNeverTouchMyBudget() throws Exception {
        createBudget("2031-03", "500.00");
        createPurchase("300.00", 3, "2031-03-05");

        TestUser other = registerUser("Vizinho");
        mockMvc.perform(post("/api/budgets")
                        .cookie(other.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"month": "2031-03", "categoryId": %d, "limitAmount": 500.00}
                                """.formatted(categoryId(other, "Compras", CategoryType.EXPENSE))))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/budgets").cookie(other.session()).param("month", "2031-03"))
                .andExpect(jsonPath("$.budgets[0].consumedAmount").value(0.00));
    }

    @Test
    void dashboardCountsInstallmentsOnceAndPaymentNever() throws Exception {
        createPurchase("900.00", 3, "2031-03-05");

        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2031-03"))
                .andExpect(jsonPath("$.expense").value(300.00))
                .andExpect(jsonPath("$.topCategories[0].amount").value(300.00))
                .andExpect(jsonPath("$.cards.monthCardExpense").value(300.00))
                .andExpect(jsonPath("$.cards.totalOutstanding").value(900.00))
                .andExpect(jsonPath("$.cards.totalAvailableLimit").value(9100.00))
                .andExpect(jsonPath("$.totalBalance").value(5000.00));

        // Full payment of the March invoice: cash drops, expense stays.
        MvcResult invoices = mockMvc.perform(get("/api/credit-cards/%d/invoices".formatted(cardId))
                        .cookie(user.session()))
                .andReturn();
        long marchInvoice = objectMapper.readTree(
                        invoices.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get(0).get("id").asLong();
        mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/payments"
                        .formatted(cardId, marchInvoice))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId": %d, "amount": 300.00, "paidOn": "2031-03-15"}
                                """.formatted(accountId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2031-03"))
                .andExpect(jsonPath("$.expense").value(300.00))
                .andExpect(jsonPath("$.totalBalance").value(4700.00))
                .andExpect(jsonPath("$.cards.totalOutstanding").value(600.00));
    }

    @Test
    void rejectsNewGenericCreditTransaction() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 50.00, "description": "Crédito genérico",
                                 "date": "2031-03-05", "categoryId": %d, "paymentMethod": "CREDIT"}
                                """.formatted(categoryId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("USE_CREDIT_CARD_PURCHASE"));
    }
}
