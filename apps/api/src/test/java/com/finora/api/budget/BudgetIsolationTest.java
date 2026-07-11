package com.finora.api.budget;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * A budget's consumed amount must reflect only the owner's transactions — a
 * second user spending in the same category name must not consume it.
 */
class BudgetIsolationTest extends AbstractIntegrationTest {

    @Test
    void anotherUsersExpensesDoNotConsumeMyBudget() throws Exception {
        TestUser alice = registerUser("Alice");
        Long aliceFood = categoryId(alice, "Alimentação", CategoryType.EXPENSE);
        mockMvc.perform(post("/api/budgets")
                        .cookie(alice.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"month": "2026-07", "categoryId": %d, "limitAmount": 500.00}
                                """.formatted(aliceFood)))
                .andExpect(status().isCreated());

        // Bruno spends heavily in HIS own "Alimentação" category.
        TestUser bruno = registerUser("Bruno");
        Long brunoFood = categoryId(bruno, "Alimentação", CategoryType.EXPENSE);
        mockMvc.perform(post("/api/transactions")
                        .cookie(bruno.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 999.00, "description": "Gasto do Bruno",
                                 "date": "2026-07-10", "categoryId": %d}
                                """.formatted(brunoFood)))
                .andExpect(status().isCreated());

        // Alice's budget is still untouched.
        mockMvc.perform(get("/api/budgets").cookie(alice.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgets[0].consumedAmount").value(0.00))
                .andExpect(jsonPath("$.budgets[0].status").value("HEALTHY"));
    }
}
