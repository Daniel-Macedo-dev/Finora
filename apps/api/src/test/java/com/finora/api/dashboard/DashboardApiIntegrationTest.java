package com.finora.api.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;

class DashboardApiIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long salaryId;
    private Long foodId;
    private Long leisureId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        salaryId = categoryId(user, "Salário", CategoryType.INCOME);
        foodId = categoryId(user, "Alimentação", CategoryType.EXPENSE);
        leisureId = categoryId(user, "Lazer", CategoryType.EXPENSE);
    }

    private void createTransaction(Cookie session, String type, String amount,
                                   String description, String date, Long categoryId) throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "%s", "amount": %s, "description": "%s",
                                 "date": "%s", "categoryId": %d}
                                """.formatted(type, amount, description, date, categoryId)))
                .andExpect(status().isCreated());
    }

    @Test
    void aggregatesMonthTotalsAndCategoryShares() throws Exception {
        createTransaction(user.session(), "INCOME", "5000.00", "Salário", "2026-07-01", salaryId);
        createTransaction(user.session(), "EXPENSE", "1200.00", "Mercado", "2026-07-05", foodId);
        createTransaction(user.session(), "EXPENSE", "300.00", "Cinema", "2026-07-08", leisureId);
        createTransaction(user.session(), "EXPENSE", "1000.00", "Junho", "2026-06-15", foodId);

        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income").value(5000.00))
                .andExpect(jsonPath("$.expense").value(1500.00))
                .andExpect(jsonPath("$.monthResult").value(3500.00))
                .andExpect(jsonPath("$.savingsRate").value(70.0))
                .andExpect(jsonPath("$.previousMonthExpense").value(1000.00))
                .andExpect(jsonPath("$.expenseVariationPercent").value(50.0))
                .andExpect(jsonPath("$.topCategories[0].categoryName").value("Alimentação"))
                .andExpect(jsonPath("$.topCategories[0].percentOfTotal").value(80.0))
                .andExpect(jsonPath("$.recentTransactions.length()").value(4))
                .andExpect(jsonPath("$.trend.length()").value(6));
    }

    @Test
    void emptyMonthDistinguishesZeroFromUnavailable() throws Exception {
        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income").value(0.00))
                .andExpect(jsonPath("$.expense").value(0.00))
                .andExpect(jsonPath("$.savingsRate").doesNotExist())
                .andExpect(jsonPath("$.expenseVariationPercent").doesNotExist())
                .andExpect(jsonPath("$.topCategories.length()").value(0));
    }

    @Test
    void generatesDeterministicInsights() throws Exception {
        createTransaction(user.session(), "EXPENSE", "1000.00", "Junho", "2026-06-10", foodId);
        createTransaction(user.session(), "EXPENSE", "2000.00", "Julho mercado", "2026-07-10", foodId);

        mockMvc.perform(get("/api/insights").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insights[?(@.type == 'EXPENSE_INCREASE')]").exists())
                .andExpect(jsonPath("$.insights[?(@.type == 'CATEGORY_DOMINANT')]").exists());
    }

    @Test
    void producesNoInsightsWithoutData() throws Exception {
        mockMvc.perform(get("/api/insights").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insights.length()").value(0));
    }

    @Test
    void dashboardsOfTwoUsersWithOppositeProfilesStayIndependent() throws Exception {
        // User A: high income, low expense.
        createTransaction(user.session(), "INCOME", "10000.00", "Salário A", "2026-07-01", salaryId);
        createTransaction(user.session(), "EXPENSE", "1000.00", "Gasto A", "2026-07-05", foodId);

        // User B: low income, high expense.
        TestUser other = registerUser("Usuário B");
        Long otherSalary = categoryId(other, "Salário", CategoryType.INCOME);
        Long otherFood = categoryId(other, "Alimentação", CategoryType.EXPENSE);
        createTransaction(other.session(), "INCOME", "1000.00", "Salário B", "2026-07-01", otherSalary);
        createTransaction(other.session(), "EXPENSE", "5000.00", "Gasto B", "2026-07-05", otherFood);

        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income").value(10000.00))
                .andExpect(jsonPath("$.expense").value(1000.00))
                .andExpect(jsonPath("$.monthResult").value(9000.00))
                .andExpect(jsonPath("$.recentTransactions.length()").value(2));

        mockMvc.perform(get("/api/dashboard").cookie(other.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income").value(1000.00))
                .andExpect(jsonPath("$.expense").value(5000.00))
                .andExpect(jsonPath("$.monthResult").value(-4000.00))
                .andExpect(jsonPath("$.recentTransactions.length()").value(2));
    }

    @Test
    void anotherUsersDataCannotCreateInsightsForMe() throws Exception {
        // Spike belongs to the other user only.
        TestUser other = registerUser("Usuário B");
        Long otherFood = categoryId(other, "Alimentação", CategoryType.EXPENSE);
        createTransaction(other.session(), "EXPENSE", "1000.00", "Junho B", "2026-06-10", otherFood);
        createTransaction(other.session(), "EXPENSE", "5000.00", "Julho B", "2026-07-10", otherFood);

        mockMvc.perform(get("/api/insights").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insights.length()").value(0));
    }
}
