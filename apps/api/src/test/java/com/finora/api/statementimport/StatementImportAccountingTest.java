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
 * Single-count accounting proofs for imported transactions: they move the
 * account balance, dashboard, budgets and forecast exactly once; excluded,
 * failed and duplicate-skipped items never count; a repeated confirmation
 * changes nothing; and undo removes the financial effect exactly once.
 */
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class StatementImportAccountingTest extends AbstractIntegrationTest {

    /** Expense 25.90 on 2026-06-05 and income 5200.00 on 2026-06-06. */
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
            <FITID>FIT-ACC-1
            <NAME>Padaria Sao Joao
            </STMTTRN>
            <STMTTRN>
            <TRNTYPE>CREDIT
            <DTPOSTED>20260606
            <TRNAMT>5200.00
            <FITID>FIT-ACC-2
            <NAME>Salario de junho
            </STMTTRN>
            </BANKTRANLIST>
            </STMTRS></STMTTRNRS></BANKMSGSRSV1>
            </OFX>
            """;

    private TestUser user;
    private long accountId;
    private long expenseCategoryId;
    private long incomeCategoryId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("Contadora");
        expenseCategoryId = categoryId(user, "Alimentação", CategoryType.EXPENSE);
        incomeCategoryId = categoryId(user, "Salário", CategoryType.INCOME);
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

    private void selectCategories(JsonNode batch) throws Exception {
        for (JsonNode item : batch.get("items")) {
            long category = item.get("type").asString().equals("INCOME")
                    ? incomeCategoryId : expenseCategoryId;
            mockMvc.perform(patch("/api/statement-imports/%d/items/%d"
                            .formatted(batch.get("id").asLong(), item.get("id").asLong()))
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

    private void assertBalance(double expected) throws Exception {
        mockMvc.perform(get("/api/accounts/" + accountId).cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value(expected));
    }

    private void assertDashboard(double income, double expense) throws Exception {
        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2026-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income").value(income))
                .andExpect(jsonPath("$.expense").value(expense));
    }

    private void assertBudgetConsumed(double expected) throws Exception {
        mockMvc.perform(get("/api/budgets").cookie(user.session()).param("month", "2026-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgets[0].consumedAmount").value(expected));
    }

    @Test
    void importCountsOnceAcrossBalanceDashboardBudgetAndForecastAndUndoRemovesItOnce()
            throws Exception {
        mockMvc.perform(post("/api/budgets")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"month": "2026-06", "categoryId": %d, "limitAmount": 500.00}
                                """.formatted(expenseCategoryId)))
                .andExpect(status().isCreated());

        // Preview alone moves nothing.
        JsonNode batch = upload();
        long batchId = batch.get("id").asLong();
        assertBalance(1000.00);
        assertDashboard(0.00, 0.00);
        assertBudgetConsumed(0.00);

        selectCategories(batch);
        confirm(batchId);
        assertBalance(1000.00 - 25.90 + 5200.00);
        assertDashboard(5200.00, 25.90);
        assertBudgetConsumed(25.90);

        // The imported rows are recognized cash: the forecast opening balance
        // starts from the post-import balance.
        mockMvc.perform(get("/api/forecast?days=30").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance").value(6174.10));

        // A repeated confirmation changes no aggregate.
        confirm(batchId);
        assertBalance(6174.10);
        assertDashboard(5200.00, 25.90);
        assertBudgetConsumed(25.90);

        // Undoing the batch removes the effect exactly once; the second undo
        // is a no-op on every aggregate.
        mockMvc.perform(post("/api/statement-imports/%d/undo".formatted(batchId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk());
        assertBalance(1000.00);
        assertDashboard(0.00, 0.00);
        assertBudgetConsumed(0.00);
        mockMvc.perform(post("/api/statement-imports/%d/undo".formatted(batchId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk());
        assertBalance(1000.00);
    }

    @Test
    void excludedFailedAndDuplicateSkippedItemsNeverCount() throws Exception {
        JsonNode batch = upload();
        long batchId = batch.get("id").asLong();
        selectCategories(batch);

        // Exclude the expense row: only the income may count.
        long expenseItemId = -1;
        for (JsonNode item : batch.get("items")) {
            if (item.get("type").asString().equals("EXPENSE")) {
                expenseItemId = item.get("id").asLong();
            }
        }
        mockMvc.perform(patch("/api/statement-imports/%d/items/%d"
                        .formatted(batchId, expenseItemId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"included\": false}"))
                .andExpect(status().isOk());
        confirm(batchId);
        assertBalance(1000.00 + 5200.00);
        assertDashboard(5200.00, 0.00);

        // Reuploading the same file yields an exact duplicate for the imported
        // row; excluding the never-imported row again, confirming the second
        // batch changes nothing financially.
        JsonNode second = upload();
        selectCategories(second);
        for (JsonNode item : second.get("items")) {
            if (item.get("type").asString().equals("EXPENSE")) {
                mockMvc.perform(patch("/api/statement-imports/%d/items/%d"
                                .formatted(second.get("id").asLong(), item.get("id").asLong()))
                                .cookie(user.session()).with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"included\": false}"))
                        .andExpect(status().isOk());
            }
        }
        JsonNode result = confirm(second.get("id").asLong());
        for (JsonNode itemResult : result.get("results")) {
            assertThat(itemResult.get("result").asString()).isNotEqualTo("SUCCESS");
        }
        assertBalance(6200.00);
        assertDashboard(5200.00, 0.00);
    }

    @Test
    void futureDatedImportedRowsStayOutOfRecognizedCashUntilTheirDate() throws Exception {
        // A future-dated statement row (bank-scheduled entry) must not move
        // today's forecast opening balance, only the projection.
        String futureCsv = "10/08/2026;Aluguel agendado;-800,00\n";
        MvcResult uploaded = mockMvc.perform(multipart("/api/statement-imports")
                        .file(new MockMultipartFile("file", "agendado.csv",
                                "application/octet-stream",
                                futureCsv.getBytes(StandardCharsets.UTF_8)))
                        .param("accountId", String.valueOf(accountId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn();
        long batchId = json(uploaded).get("id").asLong();
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
        MvcResult parsed = mockMvc.perform(post("/api/statement-imports/%d/reparse"
                        .formatted(batchId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        selectCategories(json(parsed));
        confirm(batchId);

        // Recognized balance today excludes the future row; the forecast
        // horizon that includes 2026-08-10 projects it exactly once.
        mockMvc.perform(get("/api/forecast?days=60").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance").value(1000.00))
                .andExpect(jsonPath("$.closingBalance").value(200.00));
    }
}
