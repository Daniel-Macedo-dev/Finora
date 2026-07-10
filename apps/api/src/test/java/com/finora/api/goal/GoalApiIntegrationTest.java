package com.finora.api.goal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

class GoalApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsGoalWithProgressFields() throws Exception {
        // Target 10 months from now: remaining 8000 over 10 months -> 800/month.
        LocalDate targetDate = YearMonth.now().plusMonths(10).atDay(1);
        mockMvc.perform(post("/api/goals")
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
        String body = mockMvc.perform(post("/api/goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Notebook", "targetAmount": 5000.00, "currentAmount": 4500.00}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long id = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(post("/api/goals/{id}/contributions", id)
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
        String body = mockMvc.perform(post("/api/goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Viagem", "targetAmount": 3000.00, "currentAmount": 100.00}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long id = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(post("/api/goals/{id}/contributions", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": -200.00}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GOAL_BALANCE_NEGATIVE"));
    }
}
