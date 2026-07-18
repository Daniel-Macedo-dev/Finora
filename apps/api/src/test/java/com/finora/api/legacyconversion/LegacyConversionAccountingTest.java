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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Single-count accounting proofs beyond the dashboard (already covered by
 * {@link LegacyConversionApiIntegrationTest}): budgets shift by exactly the
 * preview's monthly deltas across conversion and reversal, and the forecast
 * counts the moved expense as invoice cash exactly once.
 */
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class LegacyConversionAccountingTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private TestUser user;
    private Long categoryId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("Contadora");
        categoryId = categoryId(user, "Compras", CategoryType.EXPENSE);
    }

    private long createCard(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).get("id").asLong();
    }

    private long createAccount(String openingBalance) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Conta Principal", "type": "CHECKING",
                                 "openingBalance": %s}
                                """.formatted(openingBalance)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).get("id").asLong();
    }

    /** A pre-card-era CREDIT expense, forged exactly like migration V7 did. */
    private long createLegacyTransaction(String amount, String date, Long accountId)
            throws Exception {
        String accountField = accountId != null ? ", \"accountId\": %d".formatted(accountId) : "";
        MvcResult result = mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": %s,
                                 "description": "Compra antiga no crédito",
                                 "date": "%s", "categoryId": %d%s}
                                """.formatted(amount, date, categoryId, accountField)))
                .andExpect(status().isCreated())
                .andReturn();
        long id = json(result).get("id").asLong();
        jdbc.update("UPDATE transactions SET payment_method = 'CREDIT', legacy_credit = TRUE "
                + "WHERE id = ?", id);
        entityManager.clear();
        return id;
    }

    private void createBudget(String month, String limit) throws Exception {
        mockMvc.perform(post("/api/budgets")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"month": "%s", "categoryId": %d, "limitAmount": %s}
                                """.formatted(month, categoryId, limit)))
                .andExpect(status().isCreated());
    }

    private void assertBudgetConsumed(String month, double expected) throws Exception {
        mockMvc.perform(get("/api/budgets").cookie(user.session()).param("month", month))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgets[0].consumedAmount").value(expected));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void budgetsShiftTheExpenseAcrossConversionAndReversalWithoutDoubling() throws Exception {
        long cardId = createCard("""
                {"name": "Cartão Roxo", "brand": "VISA", "creditLimit": 10000,
                 "closingDay": 10, "dueDay": 17}
                """);
        long sourceId = createLegacyTransaction("300.00", "2025-11-20", null);
        createBudget("2025-11", "800.00");
        createBudget("2025-12", "800.00");
        createBudget("2026-01", "800.00");

        // Before: the legacy row consumes the November budget only.
        assertBudgetConsumed("2025-11", 300.00);
        assertBudgetConsumed("2025-12", 0.00);

        // Two installments: 150 in December, 150 in January — never both the
        // source and the installments.
        MvcResult converted = mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId": %d, "cardId": %d,
                                 "effectivePurchaseDate": "2025-11-20",
                                 "installmentCount": 2, "firstInvoiceMonth": "2025-12"}
                                """.formatted(sourceId, cardId)))
                .andExpect(status().isCreated())
                .andReturn();
        long conversionId = json(converted).get("id").asLong();

        assertBudgetConsumed("2025-11", 0.00);
        assertBudgetConsumed("2025-12", 150.00);
        assertBudgetConsumed("2026-01", 150.00);

        // Reversal restores the source month exactly once.
        mockMvc.perform(post("/api/legacy-conversions/%d/reverse".formatted(conversionId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk());
        assertBudgetConsumed("2025-11", 300.00);
        assertBudgetConsumed("2025-12", 0.00);
        assertBudgetConsumed("2026-01", 0.00);
    }

    @Test
    void forecastCountsTheMovedExpenseAsInvoiceCashExactlyOnce() throws Exception {
        long accountId = createAccount("5000");
        long cardId = createCard("""
                {"name": "Cartão Previsto", "brand": "VISA", "creditLimit": 10000,
                 "closingDay": 10, "dueDay": 17, "defaultPaymentAccountId": %d}
                """.formatted(accountId));

        // A recent account-linked legacy expense: the cash already moved.
        String recentDate = LocalDate.now().minusDays(3)
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
        long sourceId = createLegacyTransaction("300.00", recentDate, accountId);

        MvcResult before = mockMvc.perform(get("/api/forecast?days=60")
                        .cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance").value(4700.00))
                .andExpect(jsonPath("$.projectedInvoiceOutflows").value(0.00))
                .andReturn();
        double closingBefore = json(before).get("closingBalance").asDouble();

        // Convert with the deterministic first invoice computed by the preview.
        MvcResult preview = mockMvc.perform(post("/api/legacy-conversions/preview")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId": %d, "cardId": %d,
                                 "effectivePurchaseDate": "%s", "installmentCount": 1}
                                """.formatted(sourceId, cardId, recentDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.convertible").value(true))
                .andReturn();
        String firstInvoiceMonth = json(preview).get("firstInvoiceMonth").asText();
        mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId": %d, "cardId": %d,
                                 "effectivePurchaseDate": "%s",
                                 "installmentCount": 1, "firstInvoiceMonth": "%s"}
                                """.formatted(sourceId, cardId, recentDate, firstInvoiceMonth)))
                .andExpect(status().isCreated());

        // After: the source's cash effect is gone (+300 opening) and the same
        // 300 appears exactly once as a future invoice outflow — the closing
        // balance ends where it started, never double-charged.
        MvcResult after = mockMvc.perform(get("/api/forecast?days=60").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance").value(5000.00))
                .andExpect(jsonPath("$.projectedInvoiceOutflows").value(300.00))
                .andExpect(jsonPath(
                        "$.events[?(@.source == 'CARD_INVOICE' && @.amount == -300.0)]")
                        .exists())
                .andReturn();
        double closingAfter = json(after).get("closingBalance").asDouble();
        assertThat(closingAfter).isEqualTo(closingBefore);
    }
}
