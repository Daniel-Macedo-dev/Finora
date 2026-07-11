package com.finora.api.goal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class GoalApiIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
    }

    private long createGoal(String body) throws Exception {
        String response = mockMvc.perform(post("/api/goals")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response).get("id").asLong();
    }

    @Test
    void createsGoalWithProgressFields() throws Exception {
        LocalDate targetDate = YearMonth.now().plusMonths(10).atDay(1);
        mockMvc.perform(post("/api/goals")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Reserva de emergência", "targetAmount": 10000.00,
                                 "currentAmount": 2000.00, "targetDate": "%s"}
                                """.formatted(targetDate)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingAmount").value(8000.00))
                .andExpect(jsonPath("$.percentAchieved").value(20.0))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.suggestedMonthlyContribution").value(800.00));
    }

    @Test
    void contributionUpdatesProgressAndCompletesGoal() throws Exception {
        long id = createGoal("""
                {"name": "Notebook", "targetAmount": 5000.00, "currentAmount": 4500.00}
                """);

        mockMvc.perform(post("/api/goals/{id}/contributions", id)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 500.00}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentAmount").value(5000.00))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.percentAchieved").value(100.0));
    }

    @Test
    void rejectsWithdrawalBeyondBalance() throws Exception {
        long id = createGoal("""
                {"name": "Viagem", "targetAmount": 3000.00, "currentAmount": 100.00}
                """);

        mockMvc.perform(post("/api/goals/{id}/contributions", id)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": -200.00}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GOAL_BALANCE_NEGATIVE"));
    }
}
