package com.finora.api.goal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class GoalContributionEdgeTest extends AbstractIntegrationTest {

    private TestUser user;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
    }

    @Test
    void targetDateInCurrentMonthSuggestsFullRemainingAmount() throws Exception {
        // Months until target rounds to zero -> clamped to one installment.
        LocalDate endOfThisMonth = YearMonth.now().atEndOfMonth();
        mockMvc.perform(post("/api/goals")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Meta urgente", "targetAmount": 1000.00,
                                 "currentAmount": 400.00, "targetDate": "%s"}
                                """.formatted(endOfThisMonth)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.suggestedMonthlyContribution").value(600.00));
    }

    @Test
    void pastTargetDateYieldsNoSuggestion() throws Exception {
        mockMvc.perform(post("/api/goals")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Meta atrasada", "targetAmount": 1000.00,
                                 "currentAmount": 100.00, "targetDate": "2020-01-01"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.suggestedMonthlyContribution").doesNotExist());
    }
}
