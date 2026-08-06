package com.finora.api.budget;

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

/**
 * A budget whose category holds foreign spending cannot be called HEALTHY.
 *
 * <p>Budgets are denominated in the owner's base currency. Spending in another
 * currency cannot be brought in without rates, and treating it as zero is the
 * failure mode that matters: it leaves a genuinely blown budget reporting as
 * comfortable, which is exactly the reassurance somebody would act on.
 */
class BudgetCurrencyCompletenessIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long foodId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        foodId = categoryId(user, "Alimentação", CategoryType.EXPENSE);
    }

    private long createAccount(String body) throws Exception {
        String response = mockMvc.perform(post("/api/accounts")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response).get("id").asLong();
    }

    private void createBudget(String limit) throws Exception {
        mockMvc.perform(post("/api/budgets")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"month": "2026-07", "categoryId": %d, "limitAmount": %s}
                                """.formatted(foodId, limit)))
                .andExpect(status().isCreated());
    }

    private void createExpense(String amount, Long accountId, String currency) throws Exception {
        String account = accountId == null ? "" : ", \"accountId\": %d".formatted(accountId);
        String money = currency == null ? "" : ", \"currency\": \"%s\"".formatted(currency);
        mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": %s, "description": "Mercado",
                                 "date": "2026-07-05", "categoryId": %d%s%s}
                                """.formatted(amount, foodId, account, money)))
                .andExpect(status().isCreated());
    }

    @Test
    void aBaseCurrencyBudgetKeepsEveryFigureItAlwaysHad() throws Exception {
        createBudget("1000.00");
        createExpense("400.00", null, null);

        mockMvc.perform(get("/api/budgets").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgets[0].limitAmount").value(1000.00))
                .andExpect(jsonPath("$.budgets[0].currency").value("BRL"))
                .andExpect(jsonPath("$.budgets[0].consumedAmount").value(400.00))
                .andExpect(jsonPath("$.budgets[0].remainingAmount").value(600.00))
                .andExpect(jsonPath("$.budgets[0].percentUsed").value(40.0))
                .andExpect(jsonPath("$.budgets[0].status").value("HEALTHY"))
                .andExpect(jsonPath("$.incompleteCount").value(0))
                .andExpect(jsonPath("$.totalRemaining").value(600.00))
                .andExpect(jsonPath("$.percentUsed").value(40.0));
    }

    @Test
    void aForeignTransactionMakesTheBudgetIncompleteRatherThanHealthy() throws Exception {
        long usd = createAccount("""
                {"name": "Checking USD", "type": "CHECKING", "openingBalance": 0,
                 "currency": "USD"}
                """);
        createBudget("1000.00");
        createExpense("400.00", null, null);
        createExpense("300.00", usd, "USD");

        mockMvc.perform(get("/api/budgets").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgets[0].status").value("INCOMPLETE"))
                // What is known is still reported: the foreign spending is not
                // treated as zero, and the BRL part is not hidden either.
                .andExpect(jsonPath("$.budgets[0].consumedAmount").value(400.00))
                .andExpect(jsonPath("$.budgets[0].consumedTotals.byCurrency[0].amount").value(400.00))
                .andExpect(jsonPath("$.budgets[0].consumedTotals.byCurrency[1].currency").value("USD"))
                .andExpect(jsonPath("$.budgets[0].consumedTotals.byCurrency[1].amount").value(300.00))
                .andExpect(jsonPath("$.budgets[0].consumedTotals.unconvertedCurrencies[0]").value("USD"))
                // An understated remaining amount and percentage read as
                // complete ones, so neither is offered.
                .andExpect(jsonPath("$.budgets[0].remainingAmount").doesNotExist())
                .andExpect(jsonPath("$.budgets[0].percentUsed").doesNotExist())
                .andExpect(jsonPath("$.incompleteCount").value(1))
                .andExpect(jsonPath("$.totalRemaining").doesNotExist())
                .andExpect(jsonPath("$.percentUsed").doesNotExist());
    }

    @Test
    void aForeignCardInstallmentAlsoMakesTheBudgetIncomplete() throws Exception {
        String cardResponse = mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Card USD", "brand": "VISA", "creditLimit": 5000.00,
                                 "closingDay": 20, "dueDay": 28, "currency": "USD"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long cardId = objectMapper.readTree(cardResponse).get("id").asLong();

        createBudget("1000.00");
        createExpense("400.00", null, null);
        mockMvc.perform(post("/api/credit-cards/{id}/purchases", cardId)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Groceries", "totalAmount": 120.00,
                                 "purchaseDate": "2026-07-05", "installmentCount": 1,
                                 "categoryId": %d}
                                """.formatted(foodId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/budgets").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgets[0].status").value("INCOMPLETE"))
                .andExpect(jsonPath("$.budgets[0].consumedAmount").value(400.00))
                .andExpect(jsonPath("$.budgets[0].consumedTotals.unconvertedCurrencies[0]").value("USD"))
                .andExpect(jsonPath("$.budgets[0].percentUsed").doesNotExist());
    }

    @Test
    void anExceededBaseCurrencyBudgetIsStillExceeded() throws Exception {
        createBudget("500.00");
        createExpense("600.00", null, null);

        mockMvc.perform(get("/api/budgets").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgets[0].status").value("EXCEEDED"))
                .andExpect(jsonPath("$.budgets[0].percentUsed").value(120.0))
                .andExpect(jsonPath("$.exceededCount").value(1));
    }

    @Test
    void singleBudgetReadReportsTheSameCompleteness() throws Exception {
        long usd = createAccount("""
                {"name": "Checking USD", "type": "CHECKING", "openingBalance": 0,
                 "currency": "USD"}
                """);
        createBudget("1000.00");
        createExpense("300.00", usd, "USD");

        String summary = mockMvc.perform(get("/api/budgets")
                        .cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = objectMapper.readTree(summary).get("budgets").get(0).get("id").asLong();

        mockMvc.perform(get("/api/budgets/{id}", id).cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INCOMPLETE"))
                .andExpect(jsonPath("$.consumedAmount").value(0.00))
                .andExpect(jsonPath("$.consumedTotals.byCurrency[0].currency").value("USD"))
                .andExpect(jsonPath("$.remainingAmount").doesNotExist());
    }

    @Test
    void anotherUsersForeignSpendingCannotMakeMyBudgetIncomplete() throws Exception {
        createBudget("1000.00");
        createExpense("400.00", null, null);

        TestUser other = registerUser();
        Long otherFood = categoryId(other, "Alimentação", CategoryType.EXPENSE);
        mockMvc.perform(post("/api/accounts")
                        .cookie(other.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Checking USD", "type": "CHECKING",
                                 "openingBalance": 0, "currency": "USD"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/transactions")
                        .cookie(other.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 900.00, "description": "Dele",
                                 "date": "2026-07-05", "categoryId": %d, "currency": "USD"}
                                """.formatted(otherFood)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/budgets").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgets[0].status").value("HEALTHY"))
                .andExpect(jsonPath("$.budgets[0].consumedAmount").value(400.00))
                .andExpect(jsonPath("$.incompleteCount").value(0));
    }
}
