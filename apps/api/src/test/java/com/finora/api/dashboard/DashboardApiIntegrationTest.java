package com.finora.api.dashboard;

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

class DashboardApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categories;

    private Long salaryId;
    private Long foodId;
    private Long leisureId;

    @BeforeEach
    void setUp() {
        salaryId = categories.findByNameIgnoreCaseAndType("Salário", CategoryType.INCOME)
                .orElseThrow().getId();
        foodId = categories.findByNameIgnoreCaseAndType("Alimentação", CategoryType.EXPENSE)
                .orElseThrow().getId();
        leisureId = categories.findByNameIgnoreCaseAndType("Lazer", CategoryType.EXPENSE)
                .orElseThrow().getId();
    }

    private void createTransaction(String type, String amount, String description,
                                   String date, Long categoryId) throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "%s", "amount": %s, "description": "%s",
                                 "date": "%s", "categoryId": %d}
                                """.formatted(type, amount, description, date, categoryId)))
                .andExpect(status().isCreated());
    }

    @Test
    void aggregatesMonthTotalsAndCategoryShares() throws Exception {
        createTransaction("INCOME", "5000.00", "Salário", "2026-07-01", salaryId);
        createTransaction("EXPENSE", "1200.00", "Mercado", "2026-07-05", foodId);
        createTransaction("EXPENSE", "300.00", "Cinema", "2026-07-08", leisureId);
        createTransaction("EXPENSE", "1000.00", "Junho", "2026-06-15", foodId);

        mockMvc.perform(get("/api/dashboard").param("month", "2026-07"))
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
        mockMvc.perform(get("/api/dashboard").param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income").value(0.00))
                .andExpect(jsonPath("$.expense").value(0.00))
                // no income -> savings rate is unknown, not zero
                .andExpect(jsonPath("$.savingsRate").doesNotExist())
                // no previous expenses -> variation is unknown, not zero
                .andExpect(jsonPath("$.expenseVariationPercent").doesNotExist())
                .andExpect(jsonPath("$.topCategories.length()").value(0));
    }

    @Test
    void generatesDeterministicInsights() throws Exception {
        // Previous month: 1000 expense; current month: 2000 -> +100% (>= 20% threshold)
        createTransaction("EXPENSE", "1000.00", "Junho", "2026-06-10", foodId);
        createTransaction("EXPENSE", "2000.00", "Julho mercado", "2026-07-10", foodId);

        mockMvc.perform(get("/api/insights").param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insights[?(@.type == 'EXPENSE_INCREASE')]").exists())
                .andExpect(jsonPath("$.insights[?(@.type == 'CATEGORY_DOMINANT')]").exists());
    }

    @Test
    void producesNoInsightsWithoutData() throws Exception {
        mockMvc.perform(get("/api/insights").param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insights.length()").value(0));
    }
}
