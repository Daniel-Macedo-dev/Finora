package com.finora.api.dashboard;

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
 * The dashboard must never add two currencies, and must never derive a ratio
 * from a subset while presenting it as a statement about the month.
 */
class DashboardMixedCurrencyIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long salaryId;
    private Long foodId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        salaryId = categoryId(user, "Salário", CategoryType.INCOME);
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

    private void createTransaction(String type, String amount, String description,
            String date, Long categoryId, Long accountId, String currency) throws Exception {
        String account = accountId == null ? "" : ", \"accountId\": %d".formatted(accountId);
        String money = currency == null ? "" : ", \"currency\": \"%s\"".formatted(currency);
        mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "%s", "amount": %s, "description": "%s",
                                 "date": "%s", "categoryId": %d%s%s}
                                """.formatted(type, amount, description, date,
                                categoryId, account, money)))
                .andExpect(status().isCreated());
    }

    @Test
    void aBaseCurrencyOnlyMonthKeepsEveryScalarItAlwaysHad() throws Exception {
        createTransaction("INCOME", "5000.00", "Salário", "2026-07-01", salaryId, null, null);
        createTransaction("EXPENSE", "1000.00", "Mercado", "2026-07-05", foodId, null, null);

        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income.baseTotal").value(5000.00))
                .andExpect(jsonPath("$.expense.baseTotal").value(1000.00))
                .andExpect(jsonPath("$.monthResult.baseTotal").value(4000.00))
                .andExpect(jsonPath("$.savingsRate").value(80.0))
                .andExpect(jsonPath("$.futureCash.available").value(true));
    }

    @Test
    void mixedIncomeAndExpensesAreGroupedAndNeverConsolidated() throws Exception {
        long usd = createAccount("""
                {"name": "Checking USD", "type": "CHECKING", "openingBalance": 0,
                 "currency": "USD"}
                """);
        createTransaction("INCOME", "5000.00", "Salário", "2026-07-01", salaryId, null, null);
        createTransaction("INCOME", "1000.00", "Consulting", "2026-07-02", salaryId, usd, "USD");
        createTransaction("EXPENSE", "1000.00", "Mercado", "2026-07-05", foodId, null, null);
        createTransaction("EXPENSE", "200.00", "Groceries", "2026-07-06", foodId, usd, "USD");

        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income.byCurrency[0].currency").value("BRL"))
                .andExpect(jsonPath("$.income.byCurrency[0].amount").value(5000.00))
                .andExpect(jsonPath("$.income.byCurrency[1].currency").value("USD"))
                .andExpect(jsonPath("$.income.byCurrency[1].amount").value(1000.00))
                .andExpect(jsonPath("$.income.baseTotal").doesNotExist())
                .andExpect(jsonPath("$.income.nativeTotal").doesNotExist())
                .andExpect(jsonPath("$.expense.baseTotal").doesNotExist())
                .andExpect(jsonPath("$.monthResult.baseTotal").doesNotExist())
                // 5000 − 1000 in BRL, 1000 − 200 in USD, kept apart and signed.
                .andExpect(jsonPath("$.monthResult.byCurrency[0].amount").value(4000.00))
                .andExpect(jsonPath("$.monthResult.byCurrency[1].amount").value(800.00));
    }

    @Test
    void aMixedMonthHasNoSavingsRateAndNoVariation() throws Exception {
        long usd = createAccount("""
                {"name": "Checking USD", "type": "CHECKING", "openingBalance": 0,
                 "currency": "USD"}
                """);
        createTransaction("EXPENSE", "1000.00", "Junho", "2026-06-15", foodId, null, null);
        createTransaction("INCOME", "5000.00", "Salário", "2026-07-01", salaryId, null, null);
        createTransaction("EXPENSE", "200.00", "Groceries", "2026-07-06", foodId, usd, "USD");

        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                // Income is complete in BRL, but expenses are not: a rate
                // computed from the BRL slice alone would read as the month's.
                .andExpect(jsonPath("$.savingsRate").doesNotExist())
                .andExpect(jsonPath("$.expenseVariationPercent").doesNotExist())
                .andExpect(jsonPath("$.previousMonthExpense.baseTotal").value(1000.00));
    }

    @Test
    void categorySharesStayWithinTheirOwnCurrency() throws Exception {
        long usd = createAccount("""
                {"name": "Checking USD", "type": "CHECKING", "openingBalance": 0,
                 "currency": "USD"}
                """);
        Long leisure = categoryId(user, "Lazer", CategoryType.EXPENSE);
        createTransaction("EXPENSE", "800.00", "Mercado", "2026-07-05", foodId, null, null);
        createTransaction("EXPENSE", "200.00", "Cinema", "2026-07-06", leisure, null, null);
        createTransaction("EXPENSE", "300.00", "Groceries", "2026-07-07", foodId, usd, "USD");

        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                // 800 of 1000 BRL, not 800 of a mixed 1300.
                .andExpect(jsonPath("$.topCategories[?(@.currency == 'BRL' && @.categoryName == 'Alimentação')].percentOfTotal")
                        .value(80.0))
                // The USD spending is its own row, at 100% of USD expenses.
                .andExpect(jsonPath("$.topCategories[?(@.currency == 'USD')].amount").value(300.00))
                .andExpect(jsonPath("$.topCategories[?(@.currency == 'USD')].percentOfTotal")
                        .value(100.0));
    }

    @Test
    void trendBecomesOneHomogeneousSeriesPerCurrency() throws Exception {
        long usd = createAccount("""
                {"name": "Checking USD", "type": "CHECKING", "openingBalance": 0,
                 "currency": "USD"}
                """);
        createTransaction("EXPENSE", "1000.00", "Mercado", "2026-07-05", foodId, null, null);
        createTransaction("EXPENSE", "300.00", "Groceries", "2026-07-07", foodId, usd, "USD");

        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trend.length()").value(2))
                .andExpect(jsonPath("$.trend[0].currency").value("BRL"))
                .andExpect(jsonPath("$.trend[1].currency").value("USD"))
                // Both series span the same window so points stay aligned.
                .andExpect(jsonPath("$.trend[0].points.length()").value(6))
                .andExpect(jsonPath("$.trend[1].points.length()").value(6))
                .andExpect(jsonPath("$.trend[1].points[5].expense").value(300.00));
    }

    @Test
    void accountBalancesAreGroupedOnTheDashboardToo() throws Exception {
        createAccount("""
                {"name": "Conta Corrente", "type": "CHECKING", "openingBalance": 8000.00}
                """);
        createAccount("""
                {"name": "Checking USD", "type": "CHECKING", "openingBalance": 1200.00,
                 "currency": "USD"}
                """);

        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountBalances.byCurrency.length()").value(2))
                .andExpect(jsonPath("$.accountBalances.baseTotal").doesNotExist())
                .andExpect(jsonPath("$.accountBalances.nativeTotal").doesNotExist());
    }

    @Test
    void aForeignBalanceMakesTheProjectedCashUnavailableRatherThanWrong() throws Exception {
        createAccount("""
                {"name": "Conta Corrente", "type": "CHECKING", "openingBalance": 8000.00}
                """);
        createAccount("""
                {"name": "Checking USD", "type": "CHECKING", "openingBalance": 1200.00,
                 "currency": "USD"}
                """);

        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.futureCash.available").value(false))
                .andExpect(jsonPath("$.futureCash.projectedBalance30d").doesNotExist())
                .andExpect(jsonPath("$.futureCash.firstNegativeDate").doesNotExist())
                .andExpect(jsonPath("$.futureCash.currency").doesNotExist());
    }

    @Test
    void anAccountlessForeignTransactionAlsoWithholdsTheProjection() throws Exception {
        // No foreign account or card exists, so nothing else in the read model
        // would disclose this.
        createAccount("""
                {"name": "Conta Corrente", "type": "CHECKING", "openingBalance": 8000.00}
                """);
        createTransaction("EXPENSE", "50.00", "App", "2026-07-05", foodId, null, "USD");

        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.futureCash.available").value(false))
                .andExpect(jsonPath("$.futureCash.projectedBalance30d").doesNotExist());
    }
}
