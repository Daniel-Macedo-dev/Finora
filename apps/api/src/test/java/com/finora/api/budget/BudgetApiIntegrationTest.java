package com.finora.api.budget;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryRepository;
import com.finora.api.category.CategoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class BudgetApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categories;

    private Long foodCategoryId;
    private Long salaryCategoryId;

    @BeforeEach
    void setUp() {
        foodCategoryId = categories.findByNameIgnoreCaseAndType("Alimentação", CategoryType.EXPENSE)
                .orElseThrow().getId();
        salaryCategoryId = categories.findByNameIgnoreCaseAndType("Salário", CategoryType.INCOME)
                .orElseThrow().getId();
    }

    @Test
    void createsBudgetAndComputesConsumption() throws Exception {
        mockMvc.perform(post("/api/budgets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"month": "2026-07", "categoryId": %d, "limitAmount": 800.00}
                                """.formatted(foodCategoryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.consumedAmount").value(0.00))
                .andExpect(jsonPath("$.status").value("HEALTHY"));

        // 700 of 800 spent -> 87.5% -> WARNING
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 700.00, "description": "Mercado",
                                 "date": "2026-07-10", "categoryId": %d}
                                """.formatted(foodCategoryId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/budgets").param("month", "2026-07"))
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"month": "2026-07", "categoryId": %d, "limitAmount": 100.00}
                                """.formatted(foodCategoryId)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 150.00, "description": "Restaurante",
                                 "date": "2026-07-20", "categoryId": %d}
                                """.formatted(foodCategoryId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/budgets").param("month", "2026-07"))
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
        mockMvc.perform(post("/api/budgets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/budgets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUDGET_ALREADY_EXISTS"));
    }

    @Test
    void rejectsBudgetForIncomeCategory() throws Exception {
        mockMvc.perform(post("/api/budgets")
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"month": "2026-07", "categoryId": %d, "limitAmount": 400.00}
                                """.formatted(foodCategoryId)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 999.00, "description": "Junho",
                                 "date": "2026-06-30", "categoryId": %d}
                                """.formatted(foodCategoryId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/budgets").param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgets[0].consumedAmount").value(0.00));
    }
}
