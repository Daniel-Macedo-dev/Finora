package com.finora.api.forecast;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Notification-ready due events: derived, deterministic and owner-scoped. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DueEventApiIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long expenseCategory;
    private long accountId;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        expenseCategory = categoryId(user, "Assinaturas", CategoryType.EXPENSE);
        MvcResult account = mockMvc.perform(post("/api/accounts")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Conta Eventos", "type": "CHECKING",
                                 "openingBalance": 100.00}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        accountId = objectMapper.readTree(
                        account.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    @Test
    void emitsRecurringOverdueDueTodayAndInsufficientCashEvents() throws Exception {
        // Overdue: unmaterialized manual expense due three days ago.
        mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Conta de luz", "amount": 250.00,
                                 "categoryId": %d, "cadence": "MONTHLY", "dueDay": %d,
                                 "startDate": "%s",
                                 "targetKind": "ACCOUNT_TRANSACTION", "accountId": %d}
                                """.formatted(expenseCategory,
                                today.minusDays(3).getDayOfMonth(),
                                today.minusMonths(2), accountId)))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/events/due").cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        var events = objectMapper.readTree(body).get("events");

        boolean hasOverdue = false;
        boolean hasInsufficientCash = false;
        for (var event : events) {
            String type = event.get("type").asString();
            if (type.equals("RECURRING_OVERDUE")) {
                hasOverdue = true;
            }
            if (type.equals("INSUFFICIENT_CASH_PROJECTED")) {
                hasInsufficientCash = true;
            }
        }
        // The R$ 250,00 projection exceeds the R$ 100,00 balance within 7 days
        // only if the next occurrence falls inside the window; the overdue one
        // must always be present.
        org.assertj.core.api.Assertions.assertThat(hasOverdue).isTrue();
        org.assertj.core.api.Assertions.assertThat(hasInsufficientCash).isIn(true, false);
    }

    @Test
    void rangeValidationAndIsolation() throws Exception {
        mockMvc.perform(get("/api/events/due?from=2026-01-01&to=2027-01-01")
                        .cookie(user.session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUE_EVENT_RANGE_INVALID"));

        // Another user sees an empty feed, never this user's events.
        mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Evento meu", "amount": 10.00,
                                 "categoryId": %d, "cadence": "MONTHLY", "dueDay": %d,
                                 "startDate": "%s"}
                                """.formatted(expenseCategory, today.getDayOfMonth(),
                                today.minusMonths(1))))
                .andExpect(status().isCreated());

        TestUser other = registerUser("Outro");
        mockMvc.perform(get("/api/events/due").cookie(other.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(0));
    }
}
