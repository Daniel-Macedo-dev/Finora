package com.finora.api.commitment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Due processing and catch-up: occurrence identities derive from the
 * calendar, so a backlog accumulated while the application was offline is
 * found and executed exactly once — regardless of how many processors race.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecurringProcessingTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long expenseCategory;
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        expenseCategory = categoryId(user, "Assinaturas", CategoryType.EXPENSE);
        MvcResult account = mockMvc.perform(post("/api/accounts")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Conta Catch-up", "type": "CHECKING",
                                 "openingBalance": 1000.00}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        accountId = objectMapper.readTree(
                        account.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    /** Automatic monthly expense that started three months ago. */
    private long automaticBackloggedCommitment() throws Exception {
        LocalDate start = LocalDate.now().minusMonths(3).withDayOfMonth(1);
        MvcResult result = mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Assinatura atrasada", "amount": 50.00,
                                 "categoryId": %d, "cadence": "MONTHLY", "dueDay": 1,
                                 "startDate": "%s",
                                 "executionMode": "AUTOMATIC",
                                 "targetKind": "ACCOUNT_TRANSACTION", "accountId": %d}
                                """.formatted(expenseCategory, start, accountId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    @Test
    void processDueCatchesUpTheBacklogAndNeverDuplicates() throws Exception {
        automaticBackloggedCommitment();

        // First run materializes every occurrence due through today (3–4,
        // depending on whether today is on/after this month's due day).
        MvcResult first = mockMvc.perform(post("/api/commitments/process-due")
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        int materialized = objectMapper.readTree(
                        first.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("materialized").asInt();
        assertThat(materialized).isBetween(3, 4);

        // Rerunning finds everything already processed.
        mockMvc.perform(post("/api/commitments/process-due")
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materialized").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.alreadyProcessed").value(materialized));

        // Exactly one transaction per due date.
        mockMvc.perform(get("/api/transactions?size=100").cookie(user.session()))
                .andExpect(jsonPath("$.content.length()").value(materialized));
    }

    @Test
    void skippedOccurrenceIsNeverPickedUpByProcessing() throws Exception {
        long id = automaticBackloggedCommitment();
        LocalDate firstDue = LocalDate.now().minusMonths(3).withDayOfMonth(1);

        mockMvc.perform(post("/api/commitments/%d/occurrences/%s/skip".formatted(id, firstDue))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk());

        MvcResult run = mockMvc.perform(post("/api/commitments/process-due")
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        int materialized = objectMapper.readTree(
                        run.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("materialized").asInt();

        // The skipped identity stays skipped.
        MvcResult history = mockMvc.perform(
                        get("/api/commitments/%d/occurrences/history".formatted(id))
                                .cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        var rows = objectMapper.readTree(
                history.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("content");
        String skippedStatus = null;
        for (var row : rows) {
            if (row.get("scheduledDate").asString().equals(firstDue.toString())) {
                skippedStatus = row.get("status").asString();
            }
        }
        assertThat(skippedStatus).isEqualTo("SKIPPED");
        assertThat(materialized).isBetween(2, 3);
    }

    @Test
    void manualDefinitionIsNeverProcessedAutomatically() throws Exception {
        LocalDate start = LocalDate.now().minusMonths(2).withDayOfMonth(1);
        mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Manual atrasado", "amount": 70.00,
                                 "categoryId": %d, "cadence": "MONTHLY", "dueDay": 1,
                                 "startDate": "%s",
                                 "targetKind": "ACCOUNT_TRANSACTION", "accountId": %d}
                                """.formatted(expenseCategory, start, accountId)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/commitments/process-due")
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materialized").value(0));

        mockMvc.perform(get("/api/transactions?size=100").cookie(user.session()))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void legacyCreditProjectionStaysUntouchedByProcessing() throws Exception {
        // A pre-automation commitment paying by CREDIT stays projection-only.
        LocalDate start = LocalDate.now().minusMonths(2).withDayOfMonth(1);
        mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Assinatura no crédito legado", "amount": 39.90,
                                 "categoryId": %d, "cadence": "MONTHLY", "dueDay": 1,
                                 "startDate": "%s", "paymentMethod": "CREDIT"}
                                """.formatted(expenseCategory, start)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.legacyProjectionOnly").value(true));

        mockMvc.perform(post("/api/commitments/process-due")
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materialized").value(0));
    }

    @Test
    void concurrentProcessorsCannotDuplicateAnOccurrence() throws Exception {
        long id = automaticBackloggedCommitment();
        LocalDate due = LocalDate.now().minusMonths(1).withDayOfMonth(1);

        CountDownLatch startSignal = new CountDownLatch(1);
        Callable<Integer> attempt = () -> {
            startSignal.await();
            return mockMvc.perform(
                            post("/api/commitments/%d/occurrences/%s/materialize".formatted(id, due))
                                    .cookie(user.session()).with(csrf()))
                    .andReturn().getResponse().getStatus();
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results =
                    List.of(executor.submit(attempt), executor.submit(attempt));
            startSignal.countDown();
            List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
            // One materializes; the other converges on the same identity.
            assertThat(statuses).containsExactlyInAnyOrder(200, 422);
        } finally {
            executor.shutdown();
        }

        mockMvc.perform(get("/api/transactions?size=100").cookie(user.session()))
                .andExpect(jsonPath("$.content.length()").value(1));
    }
}
