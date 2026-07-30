package com.finora.api.offlinesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Real races against a real PostgreSQL.
 *
 * <p>Single-threaded idempotency is the easy half. What actually protects a
 * user's money is the behaviour when two tabs, two devices or a retry and the
 * original arrive at the same instant — where the receipt lookup and the write
 * cannot be assumed to happen in a tidy order.
 */
class OfflineSyncConcurrencyTest extends OfflineSyncTestSupport {

    private TestUser user;
    private Long foodCategory;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        foodCategory = categoryId(user, "Alimentação", CategoryType.EXPENSE);
    }

    @Test
    void twoSimultaneousIdenticalCreatesProduceExactlyOneTransaction() throws Exception {
        String envelope = create(UUID.randomUUID(), "TRANSACTION", UUID.randomUUID(),
                transactionPayload(foodCategory, "42.00", "Simultânea"));

        List<JsonNode> outcomes = race(() -> syncOnce(envelope), () -> syncOnce(envelope));

        assertThat(outcomes).allSatisfy(result -> assertThat(result.get("status").stringValue())
                .isIn("APPLIED", "ALREADY_APPLIED"));
        assertThat(outcomes.get(0).get("resourceId").asLong())
                .isEqualTo(outcomes.get(1).get("resourceId").asLong());

        mockMvc.perform(get("/api/transactions").cookie(user.session()).param("month", "2026-07"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void twoSimultaneousCreatesSharingOneClientIdentityProduceOneResource() throws Exception {
        // Different mutation keys, same resource identity: the outbox would only
        // do this after a compaction bug, so the owner-scoped unique index has
        // to be the thing that stops it.
        UUID clientResourceId = UUID.randomUUID();
        String first = create(UUID.randomUUID(), "TRANSACTION", clientResourceId,
                transactionPayload(foodCategory, "42.00", "Uma"));
        String second = create(UUID.randomUUID(), "TRANSACTION", clientResourceId,
                transactionPayload(foodCategory, "42.00", "Outra"));

        race(() -> syncOnce(first), () -> syncOnce(second));

        mockMvc.perform(get("/api/transactions").cookie(user.session()).param("month", "2026-07"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void twoConflictingUpdatesOnOneBaseVersionLeaveExactlyOneWinner() throws Exception {
        long id = createTransactionOnline(user, foodCategory, "10.00", "Original");
        String left = update(UUID.randomUUID(), "TRANSACTION", id, 0,
                transactionPayload(foodCategory, "20.00", "Esquerda"));
        String right = update(UUID.randomUUID(), "TRANSACTION", id, 0,
                transactionPayload(foodCategory, "30.00", "Direita"));

        List<JsonNode> outcomes = race(() -> syncOnce(left), () -> syncOnce(right));

        List<String> statuses = outcomes.stream()
                .map(node -> node.get("status").stringValue()).toList();
        assertThat(statuses).containsExactlyInAnyOrder("APPLIED", "CONFLICT");

        JsonNode loser = outcomes.get(statuses.indexOf("CONFLICT"));
        assertThat(loser.get("conflict").get("conflictType").stringValue())
                .isEqualTo("VERSION_MISMATCH");

        // Exactly one of the two values won; neither was merged into the other.
        String winner = outcomes.get(statuses.indexOf("APPLIED"))
                .get("result").get("description").stringValue();
        mockMvc.perform(get("/api/transactions/{id}", id).cookie(user.session()))
                .andExpect(jsonPath("$.description").value(winner))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void anUpdateRacingADeleteEndsInOneDocumentedOutcome() throws Exception {
        long id = createTransactionOnline(user, foodCategory, "10.00", "Original");
        String edit = update(UUID.randomUUID(), "TRANSACTION", id, 0,
                transactionPayload(foodCategory, "20.00", "Editada"));
        String removal = deleteMutation(UUID.randomUUID(), "TRANSACTION", id, 0);

        List<JsonNode> outcomes = race(() -> syncOnce(edit), () -> syncOnce(removal));

        List<String> statuses = outcomes.stream()
                .map(node -> node.get("status").stringValue()).toList();
        // Whoever loses learns why: either the row moved on or it is gone.
        assertThat(statuses).contains("APPLIED");
        assertThat(statuses).containsAnyOf("CONFLICT");
    }

    @Test
    void twoSimultaneousBudgetsForTheSameMonthAndCategoryCannotBothExist() throws Exception {
        String payload = """
                {"month":"2026-07","categoryId":%d,"limitAmount":800.00}
                """.formatted(foodCategory);
        String first = create(UUID.randomUUID(), "BUDGET", UUID.randomUUID(), payload);
        String second = create(UUID.randomUUID(), "BUDGET", UUID.randomUUID(), payload);

        race(() -> syncOnce(first), () -> syncOnce(second));

        mockMvc.perform(get("/api/budgets").cookie(user.session()).param("month", "2026-07"))
                .andExpect(jsonPath("$.budgets.length()").value(1));
    }

    @Test
    void submittingTheWholeBatchTwiceAtOnceDuplicatesNothing() throws Exception {
        String batch = String.join(",",
                create(UUID.randomUUID(), "TRANSACTION", UUID.randomUUID(),
                        transactionPayload(foodCategory, "10.00", "Uma")),
                create(UUID.randomUUID(), "TRANSACTION", UUID.randomUUID(),
                        transactionPayload(foodCategory, "20.00", "Duas")),
                create(UUID.randomUUID(), "TRANSACTION", UUID.randomUUID(),
                        transactionPayload(foodCategory, "30.00", "Três")));

        race(() -> syncBatch(batch), () -> syncBatch(batch));

        mockMvc.perform(get("/api/transactions").cookie(user.session()).param("month", "2026-07"))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void aParentAppliedByOneRequestIsResolvableByAnotherImmediatelyAfterwards()
            throws Exception {
        UUID itemId = UUID.randomUUID();
        syncOnce(create(UUID.randomUUID(), "WISHLIST_ITEM", itemId, """
                {"name":"Notebook","priority":"HIGH"}
                """));

        JsonNode child = syncOnce(create(UUID.randomUUID(), "PURCHASE_OPTION", UUID.randomUUID(),
                """
                {"item":{"clientResourceId":"%s"},"merchant":"Loja","kind":"CASH",
                 "basePrice":100.00}
                """.formatted(itemId)));

        assertThat(child.get("status").stringValue()).isEqualTo("APPLIED");
    }

    /** Posts one envelope and returns its single result. */
    private JsonNode syncOnce(String envelope) throws Exception {
        return syncBatch(envelope).get(0);
    }

    /** Posts a raw batch body and returns the results array. */
    private JsonNode syncBatch(String envelopes) throws Exception {
        String body = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post(SYNC_PATH)
                                .cookie(user.session()).with(csrf())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{\"mutations\":[%s]}".formatted(envelopes)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("results");
    }

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<T> left = executor.submit(() -> {
                ready.countDown();
                start.await();
                return first.call();
            });
            Future<T> right = executor.submit(() -> {
                ready.countDown();
                start.await();
                return second.call();
            });
            ready.await();
            start.countDown();
            return List.of(left.get(), right.get());
        }
    }
}
