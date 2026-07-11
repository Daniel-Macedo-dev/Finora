package com.finora.api.budget;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class BudgetApiIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long foodCategoryId;
    private Long salaryCategoryId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        foodCategoryId = categoryId(user, "Alimentação", CategoryType.EXPENSE);
        salaryCategoryId = categoryId(user, "Salário", CategoryType.INCOME);
    }

    private void createExpense(String amount, String description, String date) throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": %s, "description": "%s",
                                 "date": "%s", "categoryId": %d}
                                """.formatted(amount, description, date, foodCategoryId)))
                .andExpect(status().isCreated());
    }

    @Test
    void createsBudgetAndComputesConsumption() throws Exception {
        mockMvc.perform(post("/api/budgets")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"month": "2026-07", "categoryId": %d, "limitAmount": 800.00}
                                """.formatted(foodCategoryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.consumedAmount").value(0.00))
                .andExpect(jsonPath("$.status").value("HEALTHY"));

        // 700 of 800 spent -> 87.5% -> WARNING
        createExpense("700.00", "Mercado", "2026-07-10");

        mockMvc.perform(get("/api/budgets").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgets[0].consumedAmount").value(700.00))
                .andExpect(jsonPath("$.budgets[0].remainingAmount").value(100.00))
                .andExpect(jsonPath("$.budgets[0].percentUsed").value(87.5))
                .andExpect(jsonPath("$.budgets[0].status").value("WARNING"))
                .andExpect(jsonPath("$.warningCount").value(1));
    }

    @Test
    void flagsExceededBudget() throws Exception {
        mockMvc.perform(post("/api/budgets")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"month": "2026-07", "categoryId": %d, "limitAmount": 100.00}
                                """.formatted(foodCategoryId)))
                .andExpect(status().isCreated());
        createExpense("150.00", "Restaurante", "2026-07-20");

        mockMvc.perform(get("/api/budgets").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgets[0].status").value("EXCEEDED"))
                .andExpect(jsonPath("$.budgets[0].percentUsed").value(150.0))
                .andExpect(jsonPath("$.exceededCount").value(1));
    }

    @Test
    void rejectsDuplicateBudgetForSameMonthAndCategory() throws Exception {
        String body = """
                {"month": "2026-07", "categoryId": %d, "limitAmount": 500.00}
                """.formatted(foodCategoryId);
        mockMvc.perform(post("/api/budgets")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/budgets")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUDGET_ALREADY_EXISTS"));
    }

    @Test
    void rejectsBudgetForIncomeCategory() throws Exception {
        mockMvc.perform(post("/api/budgets")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"month": "2026-07", "categoryId": %d, "limitAmount": 500.00}
                                """.formatted(salaryCategoryId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUDGET_CATEGORY_NOT_EXPENSE"));
    }

    @Test
    void ignoresExpensesFromOtherMonths() throws Exception {
        mockMvc.perform(post("/api/budgets")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"month": "2026-07", "categoryId": %d, "limitAmount": 400.00}
                                """.formatted(foodCategoryId)))
                .andExpect(status().isCreated());
        createExpense("999.00", "Junho", "2026-06-30");

        mockMvc.perform(get("/api/budgets").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgets[0].consumedAmount").value(0.00));
    }
}
