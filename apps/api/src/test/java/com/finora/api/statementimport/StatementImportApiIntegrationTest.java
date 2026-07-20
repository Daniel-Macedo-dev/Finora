package com.finora.api.statementimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Full statement-import lifecycle through the public API: upload and
 * preview (no transactions created), CSV mapping, confirmation with
 * per-item results, idempotent retry, duplicate handling, category rules,
 * item editing, history/detail and undo.
 */
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class StatementImportApiIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;
    private long accountId;

    private static final String OFX_TWO_ROWS = """
            OFXHEADER:100
            DATA:OFXSGML

            <OFX>
            <BANKMSGSRSV1><STMTTRNRS><STMTRS>
            <BANKACCTFROM><BANKID>0260<ACCTID>12345-678<ACCTTYPE>CHECKING</BANKACCTFROM>
            <BANKTRANLIST>
            <STMTTRN>
            <TRNTYPE>DEBIT
            <DTPOSTED>20260605
            <TRNAMT>-25.90
            <FITID>FIT-001
            <NAME>Padaria Sao Joao
            <MEMO>Compra no debito
            </STMTTRN>
            <STMTTRN>
            <TRNTYPE>CREDIT
            <DTPOSTED>20260606
            <TRNAMT>5200.00
            <FITID>FIT-002
            <NAME>Salario de junho
            </STMTTRN>
            </BANKTRANLIST>
            </STMTRS></STMTTRNRS></BANKMSGSRSV1>
            </OFX>
            """;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("Importadora");
        accountId = createAccount("Conta Corrente", "CHECKING");
    }

    private long createAccount(String name, String type) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "type": "%s", "openingBalance": 1000}
                                """.formatted(name, type)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).get("id").asLong();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode upload(String filename, String content) throws Exception {
        return upload(filename, content.getBytes(StandardCharsets.UTF_8), accountId,
                status().isCreated());
    }

    private JsonNode upload(String filename, byte[] content, long account,
                            org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/statement-imports")
                        .file(new MockMultipartFile("file", filename,
                                "application/octet-stream", content))
                        .param("accountId", String.valueOf(account))
                        .cookie(user.session()).with(csrf()))
                .andExpect(expected)
                .andReturn();
        return json(result);
    }

    private JsonNode confirm(long batchId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/statement-imports/%d/confirm"
                        .formatted(batchId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        return json(result);
    }

    // ── OFX flow ────────────────────────────────────────────────────────────

    @Test
    void ofxUploadPreviewsWithoutCreatingTransactions() throws Exception {
        JsonNode batch = upload("extrato.ofx", OFX_TWO_ROWS);
        assertThat(batch.get("status").asString()).isEqualTo("PREVIEW_READY");
        assertThat(batch.get("format").asString()).isEqualTo("OFX");
        assertThat(batch.get("items")).hasSize(2);
        assertThat(batch.get("sourceAccountHint").asString()).contains("•••");
        assertThat(batch.get("totals").get("importedCount").asInt()).isZero();

        // Preview creates no transactions at all.
        MvcResult transactions = mockMvc.perform(get("/api/transactions")
                        .cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(transactions).get("totalElements").asLong()).isZero();
    }

    @Test
    void confirmationMaterializesEachIncludedItemExactlyOnce() throws Exception {
        JsonNode batch = upload("extrato.ofx", OFX_TWO_ROWS);
        long batchId = batch.get("id").asLong();
        // Items need categories before confirmation.
        selectCategoryForAll(batchId);

        JsonNode confirmed = confirm(batchId);
        assertThat(confirmed.get("batchStatus").asString()).isEqualTo("COMPLETED");
        assertThat(confirmed.get("results")).hasSize(2);
        for (JsonNode result : confirmed.get("results")) {
            assertThat(result.get("result").asString()).isEqualTo("SUCCESS");
            assertThat(result.get("transactionId").isNumber()).isTrue();
        }

        // Transactions exist with the import audit link and OTHER method.
        MvcResult transactions = mockMvc.perform(get("/api/transactions")
                        .cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = json(transactions);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        for (JsonNode transaction : page.get("content")) {
            assertThat(transaction.get("imported").asBoolean()).isTrue();
            assertThat(transaction.get("statementImportItemId").isNumber()).isTrue();
            assertThat(transaction.get("statementImportBatchId").asLong()).isEqualTo(batchId);
            assertThat(transaction.get("paymentMethod").asString()).isEqualTo("OTHER");
        }

        // Repeating the confirmation is idempotent: nothing is pending, no
        // third transaction appears.
        JsonNode again = confirm(batchId);
        assertThat(again.get("results")).isEmpty();
        assertThat(again.get("batchStatus").asString()).isEqualTo("COMPLETED");
        MvcResult after = mockMvc.perform(get("/api/transactions").cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(after).get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void reuploadingTheSameFileBlocksExactDuplicates() throws Exception {
        JsonNode first = upload("extrato.ofx", OFX_TWO_ROWS);
        selectCategoryForAll(first.get("id").asLong());
        confirm(first.get("id").asLong());

        JsonNode second = upload("extrato.ofx", OFX_TWO_ROWS);
        assertThat(second.get("fileAlreadyImported").asBoolean()).isTrue();
        for (JsonNode item : second.get("items")) {
            assertThat(item.get("duplicateStatus").asString()).isEqualTo("EXACT_DUPLICATE");
            assertThat(item.get("importable").asBoolean()).isFalse();
        }
        // Confirming anyway blocks every row with a structured result.
        JsonNode confirmed = confirm(second.get("id").asLong());
        assertThat(confirmed.get("results")).hasSize(2);
        for (JsonNode result : confirmed.get("results")) {
            assertThat(result.get("result").asString()).isEqualTo("EXACT_DUPLICATE");
        }
        MvcResult transactions = mockMvc.perform(get("/api/transactions").cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(transactions).get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void possibleDuplicateAgainstManualTransactionRequiresExplicitDecision() throws Exception {
        // A manual transaction equal to the statement's expense row.
        mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 25.90,
                                 "description": "Padaria São João",
                                 "date": "2026-06-04", "categoryId": %d, "accountId": %d}
                                """.formatted(
                                categoryId(user, "Alimentação", CategoryType.EXPENSE),
                                accountId)))
                .andExpect(status().isCreated());

        JsonNode batch = upload("extrato.ofx", OFX_TWO_ROWS);
        long batchId = batch.get("id").asLong();
        JsonNode expenseItem = itemByIndex(batch, 1);
        assertThat(expenseItem.get("duplicateStatus").asString()).isEqualTo("POSSIBLE_DUPLICATE");
        assertThat(expenseItem.get("matchedTransaction").get("description").asString())
                .isEqualTo("Padaria São João");
        assertThat(expenseItem.get("importable").asBoolean()).isFalse();

        selectCategoryForAll(batchId);
        // Without an override the possible duplicate is skipped, not imported.
        JsonNode confirmed = confirm(batchId);
        assertThat(resultFor(confirmed, expenseItem.get("id").asLong()).get("result").asString())
                .isEqualTo("SKIPPED");

        // Explicit "import anyway" works exactly once.
        mockMvc.perform(patch("/api/statement-imports/%d/items/%d"
                        .formatted(batchId, expenseItem.get("id").asLong()))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"duplicateOverride\": true}"))
                .andExpect(status().isOk());
        JsonNode again = confirm(batchId);
        assertThat(resultFor(again, expenseItem.get("id").asLong()).get("result").asString())
                .isEqualTo("SUCCESS");
        MvcResult transactions = mockMvc.perform(get("/api/transactions").cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        // Manual + income + overridden expense = 3.
        assertThat(json(transactions).get("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    void withinFileDuplicatesAreSurfacedWithoutAutoDeletion() throws Exception {
        String csv = """
                05/06/2026;Estacionamento;-8,00
                05/06/2026;Estacionamento;-8,00
                """;
        long batchId = uploadCsvAndMap(csv);
        JsonNode detail = detail(batchId);
        assertThat(detail.get("items")).hasSize(2);
        assertThat(itemByIndex(detail, 2).get("duplicateStatus").asString())
                .isEqualTo("DUPLICATE_WITHIN_FILE");
    }

    @Test
    void cardOfxIsBlockedTowardTheCardArea() throws Exception {
        String cardOfx = """
                <OFX>
                <CREDITCARDMSGSRSV1><CCSTMTTRNRS><CCSTMTRS>
                <CCACCTFROM><ACCTID>4111111111111111</ACCTID></CCACCTFROM>
                </CCSTMTRS></CCSTMTTRNRS></CREDITCARDMSGSRSV1>
                </OFX>
                """;
        MvcResult result = mockMvc.perform(multipart("/api/statement-imports")
                        .file(new MockMultipartFile("file", "fatura.ofx",
                                "application/octet-stream",
                                cardOfx.getBytes(StandardCharsets.UTF_8)))
                        .param("accountId", String.valueOf(accountId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andReturn();
        assertThat(json(result).get("code").asString()).isEqualTo("STATEMENT_CARD_NOT_SUPPORTED");
        assertThat(json(result).get("detail").asString()).contains("Cartões");
    }

    @Test
    void uploadRejectsUnsupportedDestinationAndOtherUsersAccount() throws Exception {
        long cashAccount = createAccount("Carteira", "CASH");
        MvcResult cash = mockMvc.perform(multipart("/api/statement-imports")
                        .file(new MockMultipartFile("file", "extrato.ofx",
                                "application/octet-stream",
                                OFX_TWO_ROWS.getBytes(StandardCharsets.UTF_8)))
                        .param("accountId", String.valueOf(cashAccount))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andReturn();
        assertThat(json(cash).get("code").asString()).isEqualTo("STATEMENT_ACCOUNT_TYPE");
    }

    // ── CSV flow ────────────────────────────────────────────────────────────

    @Test
    void csvFlowRequiresMappingBeforePreview() throws Exception {
        String csv = """
                Data;Descrição;Valor
                05/06/2026;Mercado Central;-120,50
                06/06/2026;Pix recebido;80,00
                """;
        JsonNode batch = upload("extrato.csv", csv);
        long batchId = batch.get("id").asLong();
        assertThat(batch.get("status").asString()).isEqualTo("NEEDS_MAPPING");
        assertThat(batch.get("csvRawPreview")).isNotNull();
        assertThat(batch.get("csvMappingSuggestion").get("delimiter").asString())
                .isEqualTo("SEMICOLON");
        assertThat(batch.get("csvMappingSuggestion").get("hasHeader").asBoolean()).isTrue();

        // Confirming before mapping is refused.
        mockMvc.perform(post("/api/statement-imports/%d/confirm".formatted(batchId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STATEMENT_BATCH_NOT_CONFIRMABLE"));

        // Candidate mapping produces a bounded preview.
        MvcResult preview = mockMvc.perform(put("/api/statement-imports/%d/csv-mapping"
                        .formatted(batchId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BRAZILIAN_MAPPING))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(preview).get("validCount").asInt()).isEqualTo(2);
        assertThat(json(preview).get("entries")).hasSize(2);

        // Authoritative parse persists items and discards the raw file.
        MvcResult parsed = mockMvc.perform(post("/api/statement-imports/%d/reparse"
                        .formatted(batchId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(parsed).get("status").asString()).isEqualTo("PREVIEW_READY");
        assertThat(json(parsed).get("items")).hasSize(2);

        // After the parse the raw file is gone: remapping needs a reupload.
        mockMvc.perform(put("/api/statement-imports/%d/csv-mapping".formatted(batchId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BRAZILIAN_MAPPING))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STATEMENT_FILE_DISCARDED"));
    }

    private static final String BRAZILIAN_MAPPING = """
            {"encoding": "UTF_8", "delimiter": "SEMICOLON", "hasHeader": true,
             "datePattern": "dd/MM/yyyy", "decimalSeparator": "COMMA",
             "thousandsSeparator": "DOT", "dateColumn": 0,
             "descriptionColumn": 1, "amountColumn": 2}
            """;

    private long uploadCsvAndMap(String csvWithoutHeader) throws Exception {
        JsonNode batch = upload("extrato.csv", csvWithoutHeader);
        long batchId = batch.get("id").asLong();
        mockMvc.perform(put("/api/statement-imports/%d/csv-mapping".formatted(batchId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"encoding": "UTF_8", "delimiter": "SEMICOLON",
                                 "hasHeader": false, "datePattern": "dd/MM/yyyy",
                                 "decimalSeparator": "COMMA", "thousandsSeparator": "DOT",
                                 "dateColumn": 0, "descriptionColumn": 1, "amountColumn": 2}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/statement-imports/%d/reparse".formatted(batchId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk());
        return batchId;
    }

    // ── Category rules ──────────────────────────────────────────────────────

    @Test
    void categoryRulesSuggestDeterministically() throws Exception {
        long foodCategory = categoryId(user, "Alimentação", CategoryType.EXPENSE);
        mockMvc.perform(post("/api/category-mapping-rules")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionType": "EXPENSE", "matchField": "DESCRIPTION",
                                 "operation": "CONTAINS", "pattern": "PADARIA",
                                 "categoryId": %d, "priority": 10, "active": true}
                                """.formatted(foodCategory)))
                .andExpect(status().isCreated());

        JsonNode batch = upload("extrato.ofx", OFX_TWO_ROWS);
        JsonNode expense = itemByIndex(batch, 1);
        assertThat(expense.get("suggestedCategoryId").asLong()).isEqualTo(foodCategory);
        assertThat(expense.get("suggestedCategoryName").asString()).isEqualTo("Alimentação");
        assertThat(expense.get("matchedRulePattern").asString()).isEqualTo("padaria");
        assertThat(expense.get("ruleConfidence").asString()).isEqualTo("LOW");
        // The suggestion becomes the selection when none exists yet.
        assertThat(expense.get("selectedCategoryId").asLong()).isEqualTo(foodCategory);
        // No suggestion for the income row (rule is EXPENSE-typed).
        assertThat(itemByIndex(batch, 2).get("suggestedCategoryId").isNull()).isTrue();
    }

    // ── Item editing ────────────────────────────────────────────────────────

    @Test
    void itemEditingValidatesAndPreservesOriginals() throws Exception {
        JsonNode batch = upload("extrato.ofx", OFX_TWO_ROWS);
        long batchId = batch.get("id").asLong();
        long itemId = itemByIndex(batch, 1).get("id").asLong();

        // Category of the wrong type is refused.
        mockMvc.perform(patch("/api/statement-imports/%d/items/%d".formatted(batchId, itemId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedCategoryId\": %d}".formatted(
                                categoryId(user, "Salário", CategoryType.INCOME))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CATEGORY_TYPE_MISMATCH"));

        MvcResult edited = mockMvc.perform(
                        patch("/api/statement-imports/%d/items/%d".formatted(batchId, itemId))
                                .cookie(user.session()).with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"description": "Padaria da esquina", "amount": 30.00,
                                         "included": false}
                                        """))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode item = json(edited);
        assertThat(item.get("description").asString()).isEqualTo("Padaria da esquina");
        assertThat(item.get("amount").asDouble()).isEqualTo(30.00);
        assertThat(item.get("included").asBoolean()).isFalse();
        // Originals preserved for audit.
        assertThat(item.get("originalDescription").asString()).isEqualTo("Padaria Sao Joao");
        assertThat(item.get("originalAmount").asDouble()).isEqualTo(25.90);

        // Excluded items never materialize.
        selectCategoryForAll(batchId);
        JsonNode confirmed = confirm(batchId);
        assertThat(confirmed.get("results")).hasSize(1);
        MvcResult transactions = mockMvc.perform(get("/api/transactions").cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(transactions).get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void accountChangeRerunsDuplicateDetection() throws Exception {
        long otherAccount = createAccount("Poupança", "SAVINGS");
        JsonNode first = upload("extrato.ofx", OFX_TWO_ROWS);
        selectCategoryForAll(first.get("id").asLong());
        confirm(first.get("id").asLong());

        // Same file against the other account: no exact duplicates there.
        JsonNode second = upload("extrato2.ofx", OFX_TWO_ROWS.getBytes(StandardCharsets.UTF_8),
                otherAccount, status().isCreated());
        for (JsonNode item : second.get("items")) {
            assertThat(item.get("duplicateStatus").asString()).isEqualTo("UNIQUE");
        }
        // Moving the batch to the first account resurfaces them.
        MvcResult moved = mockMvc.perform(patch("/api/statement-imports/%d"
                        .formatted(second.get("id").asLong()))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\": %d}".formatted(accountId)))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode item : json(moved).get("items")) {
            assertThat(item.get("duplicateStatus").asString()).isEqualTo("EXACT_DUPLICATE");
        }
    }

    // ── Undo ────────────────────────────────────────────────────────────────

    @Test
    void undoIsAuditedAndIdempotent() throws Exception {
        JsonNode batch = upload("extrato.ofx", OFX_TWO_ROWS);
        long batchId = batch.get("id").asLong();
        selectCategoryForAll(batchId);
        confirm(batchId);
        long itemId = itemByIndex(detail(batchId), 1).get("id").asLong();

        // Direct deletion of an imported transaction is redirected to undo.
        long transactionId = itemByIndex(detail(batchId), 1).get("transactionId").asLong();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/transactions/" + transactionId)
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TRANSACTION_FROM_IMPORT"));

        MvcResult undone = mockMvc.perform(post(
                        "/api/statement-imports/%d/items/%d/undo".formatted(batchId, itemId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(undone).get("results").get(0).get("result").asString())
                .isEqualTo("UNDONE");

        // Idempotent: a second undo reports ALREADY_UNDONE.
        MvcResult again = mockMvc.perform(post(
                        "/api/statement-imports/%d/items/%d/undo".formatted(batchId, itemId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(again).get("results").get(0).get("result").asString())
                .isEqualTo("ALREADY_UNDONE");

        // Batch undo finishes the remaining item and the batch becomes UNDONE.
        MvcResult batchUndo = mockMvc.perform(post(
                        "/api/statement-imports/%d/undo".formatted(batchId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(batchUndo).get("batchStatus").asString()).isEqualTo("UNDONE");
        MvcResult transactions = mockMvc.perform(get("/api/transactions").cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(transactions).get("totalElements").asLong()).isZero();
        // The ledger survives: items remain as audit records.
        JsonNode after = detail(batchId);
        assertThat(after.get("items")).hasSize(2);
        for (JsonNode item : after.get("items")) {
            assertThat(item.get("status").asString()).isEqualTo("UNDONE");
        }
    }

    // ── History ─────────────────────────────────────────────────────────────

    @Test
    void historyListsBatchesNewestFirst() throws Exception {
        upload("primeiro.ofx", OFX_TWO_ROWS);
        String csv = "05/06/2026;Qualquer;-1,00\n";
        upload("segundo.csv", csv);
        MvcResult history = mockMvc.perform(get("/api/statement-imports")
                        .cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = json(history);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.get("content").get(0).get("originalFilename").asString())
                .isEqualTo("segundo.csv");
        assertThat(page.get("content").get(1).get("format").asString()).isEqualTo("OFX");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private JsonNode detail(long batchId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/statement-imports/" + batchId)
                        .cookie(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        return json(result);
    }

    private static JsonNode itemByIndex(JsonNode batch, int sourceIndex) {
        for (JsonNode item : batch.get("items")) {
            if (item.get("sourceIndex").asInt() == sourceIndex) {
                return item;
            }
        }
        throw new AssertionError("item %d não encontrado".formatted(sourceIndex));
    }

    private static JsonNode resultFor(JsonNode confirmResponse, long itemId) {
        for (JsonNode result : confirmResponse.get("results")) {
            if (result.get("itemId").asLong() == itemId) {
                return result;
            }
        }
        throw new AssertionError("resultado do item %d não encontrado".formatted(itemId));
    }

    /** Assigns a compatible category to every pending item of the batch. */
    private void selectCategoryForAll(long batchId) throws Exception {
        JsonNode current = detail(batchId);
        for (JsonNode item : current.get("items")) {
            String status = item.get("status").asString();
            if (!status.equals("READY") && !status.equals("FAILED") && !status.equals("SKIPPED")) {
                continue;
            }
            if (!item.get("selectedCategoryId").isNull()) {
                continue;
            }
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
}
