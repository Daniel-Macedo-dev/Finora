package com.finora.api.legacyconversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Real PostgreSQL races over the conversion lifecycle, in the style of
 * {@link com.finora.api.creditcard.PurchaseLimitConcurrencyTest}: each HTTP
 * request commits its own transaction against the Testcontainers database, so
 * the pessimistic source-row claim, the locked idempotency lookup and the
 * partial unique indexes are exercised for real — not mocked.
 *
 * <p>Shared invariants asserted after every race: at most one ACTIVE
 * conversion per source, the source is financially active exactly when no
 * ACTIVE conversion exists, and every ACTIVE generated purchase belongs to an
 * ACTIVE conversion (no orphans).
 */
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class LegacyConversionConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private TestUser user;
    private long cardId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("Concorrente");
        cardId = createCard("Cartão Concorrente");
    }

    private long createCard(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "brand": "VISA", "creditLimit": 10000,
                                 "closingDay": 10, "dueDay": 17}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).get("id").asLong();
    }

    /** A pre-card-era CREDIT expense, forged exactly like migration V7 did. */
    private long createLegacyTransaction(String amount, String date) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": %s,
                                 "description": "Compra antiga no crédito",
                                 "date": "%s", "categoryId": %d}
                                """.formatted(amount, date,
                                categoryId(user, "Compras", CategoryType.EXPENSE))))
                .andExpect(status().isCreated())
                .andReturn();
        long id = json(result).get("id").asLong();
        jdbc.update("UPDATE transactions SET payment_method = 'CREDIT', legacy_credit = TRUE "
                + "WHERE id = ?", id);
        entityManager.clear();
        return id;
    }

    private String convertBody(long sourceId, int installments) {
        return """
                {"transactionId": %d, "cardId": %d, "effectivePurchaseDate": "2025-11-20",
                 "installmentCount": %d, "firstInvoiceMonth": "2025-12"}
                """.formatted(sourceId, cardId, installments);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<T>> gated = tasks.stream()
                .map(task -> (Callable<T>) () -> {
                    start.await();
                    return task.call();
                })
                .toList();
        ExecutorService executor = Executors.newFixedThreadPool(gated.size());
        try {
            List<Future<T>> futures = gated.stream().map(executor::submit).toList();
            start.countDown();
            List<T> results = new ArrayList<>(futures.size());
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }

    /** Invariants that must hold after any interleaving of lifecycle races. */
    private void assertConsistentFinalState(long sourceId) {
        Long activeConversions = jdbc.queryForObject("""
                SELECT count(*) FROM legacy_credit_conversions
                WHERE source_transaction_id = ? AND status = 'ACTIVE'
                """, Long.class, sourceId);
        assertThat(activeConversions).isLessThanOrEqualTo(1);

        Boolean financiallyActive = jdbc.queryForObject(
                "SELECT financially_active FROM transactions WHERE id = ?",
                Boolean.class, sourceId);
        assertThat(financiallyActive).isEqualTo(activeConversions == 0);

        // No orphans: every non-cancelled generated purchase for this source
        // belongs to the single ACTIVE conversion.
        Long activePurchases = jdbc.queryForObject("""
                SELECT count(*) FROM credit_card_purchases
                WHERE legacy_transaction_id = ? AND status = 'ACTIVE'
                """, Long.class, sourceId);
        assertThat(activePurchases).isEqualTo(activeConversions);
        Long orphanActivePurchases = jdbc.queryForObject("""
                SELECT count(*) FROM credit_card_purchases p
                WHERE p.legacy_transaction_id = ? AND p.status = 'ACTIVE'
                  AND NOT EXISTS (
                      SELECT 1 FROM legacy_credit_conversions c
                      WHERE c.card_purchase_id = p.id AND c.status = 'ACTIVE')
                """, Long.class, sourceId);
        assertThat(orphanActivePurchases).isZero();

        // No orphan installments either: cancelled purchases only carry
        // cancelled installments.
        Long danglingInstallments = jdbc.queryForObject("""
                SELECT count(*) FROM credit_card_installments i
                JOIN credit_card_purchases p ON p.id = i.purchase_id
                WHERE p.legacy_transaction_id = ?
                  AND p.status = 'CANCELLED' AND i.status = 'ACTIVE'
                """, Long.class, sourceId);
        assertThat(danglingInstallments).isZero();
    }

    @Test
    void simultaneousConversionsCreateExactlyOneConversionAndPurchase() throws Exception {
        long sourceId = createLegacyTransaction("300.00", "2025-11-20");

        Callable<JsonNode> attempt = () -> json(mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody(sourceId, 1)))
                .andExpect(status().isCreated())
                .andReturn());
        List<JsonNode> responses = race(List.of(attempt, attempt, attempt, attempt));

        // The locked idempotency check makes every racer see the same record.
        List<Long> conversionIds = responses.stream().map(r -> r.get("id").asLong()).toList();
        List<Long> purchaseIds = responses.stream()
                .map(r -> r.get("cardPurchaseId").asLong()).toList();
        assertThat(conversionIds).containsOnly(conversionIds.getFirst());
        assertThat(purchaseIds).containsOnly(purchaseIds.getFirst());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM legacy_credit_conversions WHERE source_transaction_id = ?",
                Long.class, sourceId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM credit_card_purchases WHERE legacy_transaction_id = ?",
                Long.class, sourceId)).isEqualTo(1);
        assertConsistentFinalState(sourceId);

        // The expense counts exactly once after the storm.
        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2025-12"))
                .andExpect(jsonPath("$.expense").value(300.00));
        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2025-11"))
                .andExpect(jsonPath("$.expense").value(0.00));
    }

    @Test
    void simultaneousReversalsRestoreTheSourceExactlyOnce() throws Exception {
        long sourceId = createLegacyTransaction("300.00", "2025-11-20");
        MvcResult converted = mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody(sourceId, 1)))
                .andExpect(status().isCreated())
                .andReturn();
        long conversionId = json(converted).get("id").asLong();

        Callable<Integer> attempt = () -> mockMvc
                .perform(post("/api/legacy-conversions/%d/reverse".formatted(conversionId))
                        .cookie(user.session()).with(csrf()))
                .andReturn().getResponse().getStatus();
        List<Integer> statuses = race(List.of(attempt, attempt));

        // Exactly one reversal wins; the loser is rejected under the lock.
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM legacy_credit_conversions WHERE id = ?",
                String.class, conversionId)).isEqualTo("REVERSED");
        assertConsistentFinalState(sourceId);

        // Restored exactly once: the source is the November expense again.
        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2025-11"))
                .andExpect(jsonPath("$.expense").value(300.00));
        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2025-12"))
                .andExpect(jsonPath("$.expense").value(0.00));
    }

    @Test
    void conversionRacingReversalEndsInOneConsistentState() throws Exception {
        long sourceId = createLegacyTransaction("300.00", "2025-11-20");
        long conversionId = json(mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody(sourceId, 1)))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asLong();

        // One racer reverses, the other re-converts. Both serialize on the
        // source row, so either order is legal — but only one end state is.
        Callable<Integer> reverse = () -> mockMvc
                .perform(post("/api/legacy-conversions/%d/reverse".formatted(conversionId))
                        .cookie(user.session()).with(csrf()))
                .andReturn().getResponse().getStatus();
        Callable<Integer> reconvert = () -> mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody(sourceId, 2)))
                .andReturn().getResponse().getStatus();
        List<Integer> statuses = race(List.of(reverse, reconvert));

        // The reversal always finds an ACTIVE conversion to flip (the original
        // or the idempotent return); the conversion either returns the still
        // active record or creates a fresh one after the reversal.
        assertThat(statuses.get(0)).isEqualTo(200);
        assertThat(statuses.get(1)).isEqualTo(201);
        assertConsistentFinalState(sourceId);

        // Whatever the interleaving, the expense is recognized exactly once —
        // in November if the reversal won, or split across the two invoice
        // months (December and January) if the re-conversion won.
        double total = 0;
        for (String month : new String[] {"2025-11", "2025-12", "2026-01"}) {
            MvcResult dashboard = mockMvc.perform(get("/api/dashboard")
                            .cookie(user.session()).param("month", month))
                    .andReturn();
            total += json(dashboard).get("expense").asDouble();
        }
        assertThat(total).isEqualTo(300.00);
    }

    @Test
    void concurrentIdenticalBatchesNeverDuplicateConversions() throws Exception {
        long first = createLegacyTransaction("100.00", "2025-10-05");
        long second = createLegacyTransaction("200.00", "2025-10-05");
        String batch = """
                {"items": [
                    {"transactionId": %d, "cardId": %d, "effectivePurchaseDate": "2025-10-05",
                     "installmentCount": 1, "firstInvoiceMonth": "2025-10"},
                    {"transactionId": %d, "cardId": %d, "effectivePurchaseDate": "2025-10-05",
                     "installmentCount": 2, "firstInvoiceMonth": "2025-10"}
                ]}
                """.formatted(first, cardId, second, cardId);

        Callable<JsonNode> attempt = () -> json(mockMvc
                .perform(post("/api/legacy-conversions/batch")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch))
                .andExpect(status().isOk())
                .andReturn());
        List<JsonNode> responses = race(List.of(attempt, attempt));

        // Every item resolves for both racers — as SUCCESS or ALREADY_CONVERTED,
        // never FAILED — while the engine guarantees a single physical result.
        for (JsonNode response : responses) {
            assertThat(response.get("failed").asInt()).isZero();
            assertThat(response.get("succeeded").asInt()
                    + response.get("alreadyConverted").asInt()).isEqualTo(2);
        }
        for (long sourceId : new long[] {first, second}) {
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM legacy_credit_conversions
                    WHERE source_transaction_id = ?
                    """, Long.class, sourceId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM credit_card_purchases
                    WHERE legacy_transaction_id = ?
                    """, Long.class, sourceId)).isEqualTo(1);
            assertConsistentFinalState(sourceId);
        }

        // A later retry reads honestly: nothing converts twice.
        mockMvc.perform(post("/api/legacy-conversions/batch")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch))
                .andExpect(jsonPath("$.succeeded").value(0))
                .andExpect(jsonPath("$.alreadyConverted").value(2));
    }
}
