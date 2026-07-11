package com.finora.api.commitment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CommitmentApiIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long subscriptionsCategoryId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        subscriptionsCategoryId = categoryId(user, "Assinaturas", CategoryType.EXPENSE);
    }

    @Test
    void projectsUpcomingOccurrencesAcrossMonths() throws Exception {
        mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Streaming", "amount": 39.90, "categoryId": %d,
                                 "cadence": "MONTHLY", "dueDay": 10, "startDate": "2026-01-01"}
                                """.formatted(subscriptionsCategoryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nextDueDate").isNotEmpty());

        mockMvc.perform(get("/api/commitments/upcoming")
                        .cookie(user.session())
                        .param("from", "2026-07-01")
                        .param("months", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].dueDate").value("2026-07-10"))
                .andExpect(jsonPath("$.items[1].dueDate").value("2026-08-10"))
                .andExpect(jsonPath("$.totalAmount").value(79.80));
    }

    @Test
    void requiresDueDayForMonthlyCadence() throws Exception {
        mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Academia", "amount": 120.00, "categoryId": %d,
                                 "cadence": "MONTHLY", "startDate": "2026-01-01"}
                                """.formatted(subscriptionsCategoryId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COMMITMENT_DUE_DAY_REQUIRED"));
    }

    @Test
    void rejectsEndDateBeforeStartDate() throws Exception {
        mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Curso", "amount": 200.00, "categoryId": %d,
                                 "cadence": "MONTHLY", "dueDay": 5,
                                 "startDate": "2026-06-01", "endDate": "2026-05-01"}
                                """.formatted(subscriptionsCategoryId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COMMITMENT_INVALID_PERIOD"));
    }
}
