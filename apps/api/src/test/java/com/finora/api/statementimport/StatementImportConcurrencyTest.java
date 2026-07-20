package com.finora.api.statementimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Real PostgreSQL races over the import lifecycle: every HTTP request
 * commits its own transaction against the Testcontainers database, so the
 * pessimistic item claim, the last-line duplicate recheck and the partial
 * unique indexes ({@code uq_import_items_external_imported},
 * {@code uq_transactions_import_item}) are exercised for real.
 *
 * <p>Shared invariant after every storm: one included import item produced
 * at most one Finora transaction, whatever the interleaving.
 */
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class StatementImportConcurrencyTest extends AbstractIntegrationTest {

    /** Expense 25.90 (FIT-RACE-1) and income 5200.00 (FIT-RACE-2). */
    private static final String OFX = """
            OFXHEADER:100
            DATA:OFXSGML

            <OFX>
            <BANKMSGSRSV1><STMTTRNRS><STMTRS>
            <BANKTRANLIST>
            <STMTTRN>
            <TRNTYPE>DEBIT
            <DTPOSTED>20260605
            <TRNAMT>-25.90
            <FITID>FIT-RACE-1
            <NAME>Padaria Sao Joao
            </STMTTRN>
            <STMTTRN>
            <TRNTYPE>CREDIT
            <DTPOSTED>20260606
            <TRNAMT>5200.00
            <FITID>FIT-RACE-2
            <NAME>Salario de junho
            </STMTTRN>
            </BANKTRANLIST>
            </STMTRS></STMTTRNRS></BANKMSGSRSV1>
            </OFX>
            """;

    @Autowired
    private JdbcTemplate jdbc;

    private TestUser user;
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("Corredora");
        MvcResult account = mockMvc.perform(post("/api/accounts")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Conta Corrente", "type": "CHECKING",
                                 "openingBalance": 1000}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        accountId = json(account).get("id").asLong();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode upload() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/statement-imports")
                        .file(new MockMultipartFile("file", "extrato.ofx",
                                "application/octet-stream", OFX.getBytes(StandardCharsets.UTF_8)))
                        .param("accountId", String.valueOf(accountId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result);
    }

    private void selectCategories(long batchId) throws Exception {
        MvcResult detail = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/statement-imports/" + batchId)
                                .cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode item : json(detail).get("items")) {
            long category = item.get("type").asString().equals("INCOME")
                    ? categoryId(user, "Salário", CategoryType.INCOME)
                    : categoryId(user, "Alimentação", CategoryType.EXPENSE);
            mockMvc.perform(patch("/api/statement-imports/%d/items/%d"
                            .formatted(batchId, item.get("id").asLong()))
                            .cookie(user.session()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"selectedCategoryId\": %d}".formatted(category)))
                    .andExpect(status().isOk());
        }
    }

    private JsonNode confirm(long batchId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/statement-imports/%d/confirm"
                        .formatted(batchId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        return json(result);
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

    private long transactionsInAccount() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM transactions WHERE account_id = ?", Long.class, accountId);
        return count != null ? count : -1;
    }

    /** One imported item per strong identity, one transaction per item. */
    private void assertSingleMaterialization() {
        for (String fitid : new String[] {"FIT-RACE-1", "FIT-RACE-2"}) {
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM statement_import_items
                    WHERE account_id = ? AND external_id = ? AND status = 'IMPORTED'
                    """, Long.class, accountId, fitid))
                    .as("imported items for %s", fitid)
                    .isEqualTo(1);
        }
        Long orphans = jdbc.queryForObject("""
                SELECT count(*) FROM transactions t
                WHERE t.account_id = ? AND t.statement_import_item_id IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM statement_import_items i
                      WHERE i.id = t.statement_import_item_id AND i.status = 'IMPORTED')
                """, Long.class, accountId);
        assertThat(orphans).isZero();
    }

    private static int count(List<JsonNode> responses, String code) {
        int total = 0;
        for (JsonNode response : responses) {
            for (JsonNode result : response.get("results")) {
                if (result.get("result").asString().equals(code)) {
                    total++;
                }
            }
        }
        return total;
    }

    @Test
    void simultaneousConfirmationsOfOneBatchMaterializeEachItemOnce() throws Exception {
        long batchId = upload().get("id").asLong();
        selectCategories(batchId);

        Callable<JsonNode> attempt = () -> confirm(batchId);
        List<JsonNode> responses = race(List.of(attempt, attempt, attempt, attempt));

        // Each item was imported by exactly one racer; every other racer saw
        // the committed result (ALREADY_IMPORTED with the transaction id) —
        // never a duplicate transaction, never an unexplained failure.
        assertThat(count(responses, "SUCCESS")).isEqualTo(2);
        assertThat(count(responses, "FAILED")).isZero();
        for (JsonNode response : responses) {
            for (JsonNode result : response.get("results")) {
                assertThat(result.get("transactionId").isNumber()).isTrue();
            }
        }
        assertThat(transactionsInAccount()).isEqualTo(2);
        assertSingleMaterialization();
    }

    @Test
    void twoBatchesOfTheSameFileRacingConfirmationImportEachRowOnce() throws Exception {
        // Both batches preview UNIQUE rows (nothing imported yet); the partial
        // unique index is the only thing standing between them.
        long first = upload().get("id").asLong();
        long second = upload().get("id").asLong();
        selectCategories(first);
        selectCategories(second);

        List<JsonNode> responses = race(List.of(() -> confirm(first), () -> confirm(second)));

        // Exactly one SUCCESS per statement row across both racers; the loser
        // resolved to a structured non-imported result.
        assertThat(count(responses, "SUCCESS")).isEqualTo(2);
        assertThat(transactionsInAccount()).isEqualTo(2);
        assertSingleMaterialization();

        // A later retry of both batches resolves every leftover honestly and
        // still creates nothing.
        confirm(first);
        confirm(second);
        assertThat(transactionsInAccount()).isEqualTo(2);
        assertSingleMaterialization();
    }

    @Test
    void simultaneousUploadsOfTheSameFileStayConsistent() throws Exception {
        Callable<JsonNode> attempt = this::upload;
        List<JsonNode> responses = race(List.of(attempt, attempt));

        // Both uploads land as independent preview batches with all rows and
        // no transaction: parsing never materializes anything.
        assertThat(responses).hasSize(2);
        for (JsonNode response : responses) {
            assertThat(response.get("status").asString()).isEqualTo("PREVIEW_READY");
            assertThat(response.get("items")).hasSize(2);
        }
        assertThat(responses.get(0).get("id").asLong())
                .isNotEqualTo(responses.get(1).get("id").asLong());
        assertThat(transactionsInAccount()).isZero();
    }

    @Test
    void partialFailureRetryAndReimportAfterUndoImportOnlyEligibleRows() throws Exception {
        JsonNode batch = upload();
        long batchId = batch.get("id").asLong();
        long expenseItemId = -1;
        long incomeItemId = -1;
        for (JsonNode item : batch.get("items")) {
            if (item.get("type").asString().equals("EXPENSE")) {
                expenseItemId = item.get("id").asLong();
            } else {
                incomeItemId = item.get("id").asLong();
            }
        }
        // Only the income row gets a category: the expense row fails.
        mockMvc.perform(patch("/api/statement-imports/%d/items/%d"
                        .formatted(batchId, incomeItemId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedCategoryId\": %d}".formatted(
                                categoryId(user, "Salário", CategoryType.INCOME))))
                .andExpect(status().isOk());
        JsonNode firstRun = confirm(batchId);
        assertThat(count(List.of(firstRun), "SUCCESS")).isEqualTo(1);
        assertThat(count(List.of(firstRun), "FAILED")).isEqualTo(1);
        assertThat(firstRun.get("batchStatus").asString()).isEqualTo("PARTIALLY_COMPLETED");
        assertThat(transactionsInAccount()).isEqualTo(1);

        // Retry after fixing the failure imports only the eligible remaining
        // row — the already-imported income is not even targeted again.
        mockMvc.perform(patch("/api/statement-imports/%d/items/%d"
                        .formatted(batchId, expenseItemId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedCategoryId\": %d}".formatted(
                                categoryId(user, "Alimentação", CategoryType.EXPENSE))))
                .andExpect(status().isOk());
        JsonNode retry = confirm(batchId);
        assertThat(retry.get("results")).hasSize(1);
        assertThat(retry.get("results").get(0).get("result").asString()).isEqualTo("SUCCESS");
        assertThat(retry.get("batchStatus").asString()).isEqualTo("COMPLETED");
        assertThat(transactionsInAccount()).isEqualTo(2);
        assertSingleMaterialization();

        // Undo releases the strong identity: the documented policy is that a
        // fresh upload may deliberately import that row again — exactly once.
        mockMvc.perform(post("/api/statement-imports/%d/items/%d/undo"
                        .formatted(batchId, expenseItemId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk());
        assertThat(transactionsInAccount()).isEqualTo(1);

        JsonNode reupload = upload();
        long reuploadId = reupload.get("id").asLong();
        for (JsonNode item : reupload.get("items")) {
            String duplicateStatus = item.get("duplicateStatus").asString();
            if (item.get("type").asString().equals("EXPENSE")) {
                assertThat(duplicateStatus).isNotEqualTo("EXACT_DUPLICATE");
            } else {
                assertThat(duplicateStatus).isEqualTo("EXACT_DUPLICATE");
            }
        }
        selectCategories(reuploadId);
        JsonNode reimport = confirm(reuploadId);
        assertThat(count(List.of(reimport), "SUCCESS")).isEqualTo(1);
        assertThat(count(List.of(reimport), "EXACT_DUPLICATE")).isEqualTo(1);
        assertThat(transactionsInAccount()).isEqualTo(2);
        assertSingleMaterialization();
    }
}
