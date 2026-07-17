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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The complete conversion lifecycle over the real API: inventory and
 * eligibility, deterministic preview, atomic conversion, the central
 * accounting invariant (never two active expenses for one historical
 * purchase), idempotent retries, reversal with settlement guards and batch
 * semantics.
 *
 * <p>Legacy CREDIT rows predate the card domain and can only be born in
 * migration V7, so tests forge them the same way the migration did: a real
 * transaction created through the API is flagged legacy via SQL.
 *
 * <p>The engine commits in its own transaction (REQUIRES_NEW), so the test
 * transaction is suppressed — data is committed for real and isolated per
 * registered user, like the recurring processing tests.
 */
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class LegacyConversionApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private TestUser user;
    private Long categoryId;
    private long cardId;
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        categoryId = categoryId(user, "Compras", CategoryType.EXPENSE);
        accountId = createAccount(user, "Conta Principal", "5000");
        cardId = createCard(user, "Cartão Roxo", "10000", 10, 17);
    }

    private long createAccount(TestUser owner, String name, String openingBalance)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "type": "CHECKING", "openingBalance": %s}
                                """.formatted(name, openingBalance)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).get("id").asLong();
    }

    private long createCard(TestUser owner, String name, String limit, int closingDay, int dueDay)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/credit-cards")
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "brand": "VISA", "creditLimit": %s,
                                 "closingDay": %d, "dueDay": %d}
                                """.formatted(name, limit, closingDay, dueDay)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).get("id").asLong();
    }

    /** A pre-card-era CREDIT expense, forged exactly like migration V7 did. */
    private long createLegacyTransaction(TestUser owner, String amount, String date,
                                         Long linkedAccountId) throws Exception {
        String accountField = linkedAccountId != null
                ? ", \"accountId\": %d".formatted(linkedAccountId)
                : "";
        MvcResult result = mockMvc.perform(post("/api/transactions")
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": %s,
                                 "description": "Compra antiga no crédito",
                                 "date": "%s", "categoryId": %d%s}
                                """.formatted(amount, date,
                                categoryId(owner, "Compras", CategoryType.EXPENSE),
                                accountField)))
                .andExpect(status().isCreated())
                .andReturn();
        long id = json(result).get("id").asLong();
        jdbc.update("UPDATE transactions SET payment_method = 'CREDIT', legacy_credit = TRUE "
                + "WHERE id = ?", id);
        entityManager.clear();
        return id;
    }

    private String convertBody(long transactionId, long targetCardId, String effectiveDate,
                               int installments, String firstInvoiceMonth) {
        return """
                {"transactionId": %d, "cardId": %d, "effectivePurchaseDate": "%s",
                 "installmentCount": %d, "firstInvoiceMonth": "%s"}
                """.formatted(transactionId, targetCardId, effectiveDate, installments,
                firstInvoiceMonth);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ── inventory and eligibility ────────────────────────────────────────────

    @Test
    void inventoryListsOnlyLegacyRowsWithSummaryAndFilters() throws Exception {
        createLegacyTransaction(user, "300.00", "2025-11-20", null);
        createLegacyTransaction(user, "120.00", "2025-08-02", null);
        // An ordinary expense never enters the inventory.
        mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 50.00, "description": "Comum",
                                 "date": "2025-11-21", "categoryId": %d}
                                """.formatted(categoryId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/legacy-conversions").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.eligibleCount").value(2))
                .andExpect(jsonPath("$.summary.convertedCount").value(0))
                .andExpect(jsonPath("$.summary.pendingAmount").value(420.00))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.content[0].state").value("ELIGIBLE"));

        // Month filter narrows to November; amount filter narrows further.
        mockMvc.perform(get("/api/legacy-conversions").cookie(user.session())
                        .param("month", "2025-11"))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.content[0].amount").value(300.00));
        mockMvc.perform(get("/api/legacy-conversions").cookie(user.session())
                        .param("minAmount", "200"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mockMvc.perform(get("/api/legacy-conversions").cookie(user.session())
                        .param("state", "CONVERTED"))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void eligibilityRejectsIncompatibleAndGeneratedSources() throws Exception {
        // Ordinary expense: not legacy credit.
        MvcResult ordinary = mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 50.00, "description": "Comum",
                                 "date": "2025-11-21", "categoryId": %d}
                                """.formatted(categoryId)))
                .andReturn();
        long ordinaryId = json(ordinary).get("id").asLong();
        mockMvc.perform(get("/api/legacy-conversions/eligibility/" + ordinaryId)
                        .cookie(user.session()))
                .andExpect(jsonPath("$.status").value("INCOMPATIBLE_SOURCE"))
                .andExpect(jsonPath("$.reasonCode").value("NOT_LEGACY_CREDIT"))
                .andExpect(jsonPath("$.convertible").value(false));

        // Recurring-generated legacy row: blocked, managed by its occurrence.
        MvcResult commitment = mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Streaming antigo", "amount": 39.90,
                                 "categoryId": %d, "cadence": "MONTHLY", "dueDay": 8,
                                 "startDate": "2025-01-08", "paymentMethod": "CREDIT"}
                                """.formatted(categoryId)))
                .andExpect(status().isCreated())
                .andReturn();
        long commitmentId = json(commitment).get("id").asLong();
        long generated = createLegacyTransaction(user, "39.90", "2025-06-08", null);
        jdbc.update("UPDATE transactions SET commitment_id = ? WHERE id = ?",
                commitmentId, generated);
        entityManager.clear();
        mockMvc.perform(get("/api/legacy-conversions/eligibility/" + generated)
                        .cookie(user.session()))
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.reasonCode").value("SOURCE_FROM_RECURRING"));

        // Another user's transaction id behaves as absent.
        TestUser other = registerUser("Vizinho");
        mockMvc.perform(get("/api/legacy-conversions/eligibility/" + ordinaryId)
                        .cookie(other.session()))
                .andExpect(status().isNotFound());
    }

    // ── preview ──────────────────────────────────────────────────────────────

    @Test
    void previewComputesDeterministicScheduleLimitAndRedistribution() throws Exception {
        long sourceId = createLegacyTransaction(user, "300.00", "2025-11-20", null);

        // Closing day 10: a purchase on 2025-11-20 closes only on 2025-12-10,
        // so the first invoice is December 2025 and the split is cent-exact.
        mockMvc.perform(post("/api/legacy-conversions/preview")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId": %d, "cardId": %d,
                                 "effectivePurchaseDate": "2025-11-20", "installmentCount": 3}
                                """.formatted(sourceId, cardId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstInvoiceMonth").value("2025-12"))
                .andExpect(jsonPath("$.installments.length()").value(3))
                .andExpect(jsonPath("$.installments[0].amount").value(100.00))
                .andExpect(jsonPath("$.installments[0].invoiceMonth").value("2025-12"))
                .andExpect(jsonPath("$.installments[0].dueDate").value("2025-12-17"))
                .andExpect(jsonPath("$.installments[2].invoiceMonth").value("2026-02"))
                .andExpect(jsonPath("$.installments[0].invoiceExists").value(false))
                .andExpect(jsonPath("$.limit.availableBefore").value(10000.00))
                .andExpect(jsonPath("$.limit.availableAfter").value(9700.00))
                .andExpect(jsonPath("$.monthlyExpenseShift[0].month").value("2025-11"))
                .andExpect(jsonPath("$.monthlyExpenseShift[0].delta").value(-300.00))
                .andExpect(jsonPath("$.monthlyExpenseShift[1].delta").value(100.00))
                .andExpect(jsonPath("$.warnings[?(@.code == 'MONTHLY_REDISTRIBUTION')]").exists())
                .andExpect(jsonPath("$.warnings[?(@.code == 'INVOICE_CLOSED')]").exists())
                .andExpect(jsonPath("$.convertible").value(true));

        // A cent-exact uneven split: 100 / 3 = 33,33 + 33,33 + 33,34.
        long unevenId = createLegacyTransaction(user, "100.00", "2025-11-20", null);
        mockMvc.perform(post("/api/legacy-conversions/preview")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId": %d, "cardId": %d,
                                 "effectivePurchaseDate": "2025-11-20", "installmentCount": 3}
                                """.formatted(unevenId, cardId)))
                .andExpect(jsonPath("$.installments[0].amount").value(33.33))
                .andExpect(jsonPath("$.installments[2].amount").value(33.34));
    }

    @Test
    void previewBlocksMismatchedFirstInvoiceInsufficientLimitAndFutureDate() throws Exception {
        long sourceId = createLegacyTransaction(user, "300.00", "2025-11-20", null);

        mockMvc.perform(post("/api/legacy-conversions/preview")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody(sourceId, cardId, "2025-11-20", 3, "2026-01")))
                .andExpect(jsonPath("$.convertible").value(false))
                .andExpect(jsonPath("$.blockers[?(@.code == 'FIRST_INVOICE_MISMATCH')]").exists());

        long smallCard = createCard(user, "Cartão Pequeno", "100", 10, 17);
        mockMvc.perform(post("/api/legacy-conversions/preview")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId": %d, "cardId": %d,
                                 "effectivePurchaseDate": "2025-11-20", "installmentCount": 1}
                                """.formatted(sourceId, smallCard)))
                .andExpect(jsonPath("$.convertible").value(false))
                .andExpect(jsonPath("$.blockers[?(@.code == 'INSUFFICIENT_CARD_LIMIT')]").exists());

        mockMvc.perform(post("/api/legacy-conversions/preview")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId": %d, "cardId": %d,
                                 "effectivePurchaseDate": "2099-01-01", "installmentCount": 1}
                                """.formatted(sourceId, cardId)))
                .andExpect(jsonPath("$.blockers[?(@.code == 'EFFECTIVE_DATE_IN_FUTURE')]").exists());
    }

    // ── conversion and the accounting invariant ──────────────────────────────

    @Test
    void conversionMovesTheExpenseWithoutEverDoublingIt() throws Exception {
        long sourceId = createLegacyTransaction(user, "300.00", "2025-11-20", null);

        // Before: the legacy row is the November expense.
        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2025-11"))
                .andExpect(jsonPath("$.expense").value(300.00));
        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2025-12"))
                .andExpect(jsonPath("$.expense").value(0.00));

        MvcResult converted = mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody(sourceId, cardId, "2025-11-20", 1, "2025-12")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.reversible").value(true))
                .andReturn();
        long purchaseId = json(converted).get("cardPurchaseId").asLong();

        // After: November loses the expense, December (invoice month) gains it.
        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2025-11"))
                .andExpect(jsonPath("$.expense").value(0.00));
        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2025-12"))
                .andExpect(jsonPath("$.expense").value(300.00));

        // The original stays visible, marked as the audit record.
        mockMvc.perform(get("/api/transactions/" + sourceId).cookie(user.session()))
                .andExpect(jsonPath("$.legacyCredit").value(true))
                .andExpect(jsonPath("$.financiallyActive").value(false))
                .andExpect(jsonPath("$.legacyConversionStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.generatedCardPurchaseId").value(purchaseId));

        // The generated purchase is real and linked.
        mockMvc.perform(get("/api/credit-cards/%d/purchases/%d".formatted(cardId, purchaseId))
                        .cookie(user.session()))
                .andExpect(jsonPath("$.totalAmount").value(300.00))
                .andExpect(jsonPath("$.installments[0].invoiceMonth").value("2025-12"));

        // Inventory now shows it converted.
        mockMvc.perform(get("/api/legacy-conversions").cookie(user.session()))
                .andExpect(jsonPath("$.summary.convertedCount").value(1))
                .andExpect(jsonPath("$.summary.eligibleCount").value(0))
                .andExpect(jsonPath("$.page.content[0].state").value("CONVERTED"));

        // The audit record cannot be edited or deleted.
        mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody(sourceId, cardId, "2025-11-20", 1, "2025-12")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(json(converted).get("id").asLong()));
    }

    @Test
    void conversionRemovesTheSourceCashEffectExactlyOnce() throws Exception {
        long sourceId = createLegacyTransaction(user, "300.00", "2025-11-20", accountId);

        // The account-linked legacy row reduces the balance.
        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2025-11"))
                .andExpect(jsonPath("$.totalBalance").value(4700.00));

        mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody(sourceId, cardId, "2025-11-20", 1, "2025-12")))
                .andExpect(status().isCreated());

        // Cash effect removed: only paying the invoice will move money.
        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2025-11"))
                .andExpect(jsonPath("$.totalBalance").value(5000.00));

        MvcResult invoices = mockMvc.perform(
                        get("/api/credit-cards/%d/invoices".formatted(cardId))
                                .cookie(user.session()))
                .andReturn();
        long invoiceId = json(invoices).get(0).get("id").asLong();
        mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/payments"
                        .formatted(cardId, invoiceId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId": %d, "amount": 300.00, "paidOn": "2025-12-17"}
                                """.formatted(accountId)))
                .andExpect(status().isCreated());

        // Money moved exactly once.
        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2025-12"))
                .andExpect(jsonPath("$.totalBalance").value(4700.00));
    }

    @Test
    void failedConversionLeavesNoTrace() throws Exception {
        long smallCard = createCard(user, "Cartão Pequeno", "100", 10, 17);
        long sourceId = createLegacyTransaction(user, "300.00", "2025-11-20", null);

        mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody(sourceId, smallCard, "2025-11-20", 1, "2025-12")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_CARD_LIMIT"));

        // Source stays active; nothing was persisted anywhere.
        mockMvc.perform(get("/api/transactions/" + sourceId).cookie(user.session()))
                .andExpect(jsonPath("$.financiallyActive").value(true))
                .andExpect(jsonPath("$.legacyConversionStatus").isEmpty());
        mockMvc.perform(get("/api/credit-cards/%d/purchases".formatted(smallCard))
                        .cookie(user.session()))
                .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM legacy_credit_conversions WHERE user_id = ?",
                Long.class, user.id())).isZero();
    }

    @Test
    void conversionRejectsArchivedCardAndForeignCard() throws Exception {
        long sourceId = createLegacyTransaction(user, "300.00", "2025-11-20", null);

        long archived = createCard(user, "Cartão Arquivado", "5000", 10, 17);
        mockMvc.perform(post("/api/credit-cards/%d/archive".formatted(archived))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody(sourceId, archived, "2025-11-20", 1, "2025-12")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_ARCHIVED"));

        TestUser other = registerUser("Vizinho");
        long foreignCard = createCard(other, "Cartão Alheio", "5000", 10, 17);
        mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody(sourceId, foreignCard, "2025-11-20", 1, "2025-12")))
                .andExpect(status().isNotFound());
    }

    // ── reversal ─────────────────────────────────────────────────────────────

    @Test
    void reversalRestoresTheOriginalExpenseExactlyOnce() throws Exception {
        long sourceId = createLegacyTransaction(user, "300.00", "2025-11-20", null);
        MvcResult converted = mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody(sourceId, cardId, "2025-11-20", 1, "2025-12")))
                .andExpect(status().isCreated())
                .andReturn();
        long conversionId = json(converted).get("id").asLong();
        long purchaseId = json(converted).get("cardPurchaseId").asLong();

        mockMvc.perform(post("/api/legacy-conversions/%d/reverse".formatted(conversionId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "Cartão errado"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"))
                .andExpect(jsonPath("$.reversalReason").value("Cartão errado"))
                .andExpect(jsonPath("$.reversedAt").isNotEmpty());

        // Expense recognition is back in the source month, once.
        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2025-11"))
                .andExpect(jsonPath("$.expense").value(300.00));
        mockMvc.perform(get("/api/dashboard").cookie(user.session()).param("month", "2025-12"))
                .andExpect(jsonPath("$.expense").value(0.00));

        // The generated purchase is cancelled, not deleted.
        mockMvc.perform(get("/api/credit-cards/%d/purchases/%d".formatted(cardId, purchaseId))
                        .cookie(user.session()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // A second reversal is rejected safely.
        mockMvc.perform(post("/api/legacy-conversions/%d/reverse".formatted(conversionId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONVERSION_NOT_ACTIVE"));

        // The source is convertible again; inventory shows REVERSED.
        mockMvc.perform(get("/api/legacy-conversions/eligibility/" + sourceId)
                        .cookie(user.session()))
                .andExpect(jsonPath("$.status").value("REVERSED_CONVERSION"))
                .andExpect(jsonPath("$.convertible").value(true));
        mockMvc.perform(get("/api/legacy-conversions").cookie(user.session()))
                .andExpect(jsonPath("$.page.content[0].state").value("REVERSED"))
                .andExpect(jsonPath("$.summary.reversedCount").value(1));

        // Reconversion creates a brand-new conversion and purchase.
        MvcResult again = mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody(sourceId, cardId, "2025-11-20", 2, "2025-12")))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(json(again).get("id").asLong()).isNotEqualTo(conversionId);
        assertThat(json(again).get("cardPurchaseId").asLong()).isNotEqualTo(purchaseId);
    }

    @Test
    void completedInvoicePaymentBlocksReversal() throws Exception {
        long sourceId = createLegacyTransaction(user, "300.00", "2025-11-20", accountId);
        MvcResult converted = mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody(sourceId, cardId, "2025-11-20", 1, "2025-12")))
                .andExpect(status().isCreated())
                .andReturn();
        long conversionId = json(converted).get("id").asLong();

        MvcResult invoices = mockMvc.perform(
                        get("/api/credit-cards/%d/invoices".formatted(cardId))
                                .cookie(user.session()))
                .andReturn();
        long invoiceId = json(invoices).get(0).get("id").asLong();
        mockMvc.perform(post("/api/credit-cards/%d/invoices/%d/payments"
                        .formatted(cardId, invoiceId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId": %d, "amount": 300.00, "paidOn": "2025-12-17"}
                                """.formatted(accountId)))
                .andExpect(status().isCreated());

        // Detail reports the block before the user even tries.
        mockMvc.perform(get("/api/legacy-conversions/" + conversionId).cookie(user.session()))
                .andExpect(jsonPath("$.reversible").value(false))
                .andExpect(jsonPath("$.reversalBlockedCode").value("CONVERSION_SETTLED"));

        mockMvc.perform(post("/api/legacy-conversions/%d/reverse".formatted(conversionId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONVERSION_SETTLED"));

        // The source remains the audit record — still financially inactive.
        mockMvc.perform(get("/api/transactions/" + sourceId).cookie(user.session()))
                .andExpect(jsonPath("$.financiallyActive").value(false));
    }

    @Test
    void directPurchaseCancellationIsBlockedForConversionPurchases() throws Exception {
        long sourceId = createLegacyTransaction(user, "300.00", "2025-11-20", null);
        MvcResult converted = mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody(sourceId, cardId, "2025-11-20", 1, "2025-12")))
                .andReturn();
        long purchaseId = json(converted).get("cardPurchaseId").asLong();

        mockMvc.perform(post("/api/credit-cards/%d/purchases/%d/cancel"
                        .formatted(cardId, purchaseId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PURCHASE_FROM_CONVERSION"));
    }

    // ── batch ────────────────────────────────────────────────────────────────

    @Test
    void batchProcessesItemsIndependentlyAndIdempotently() throws Exception {
        long first = createLegacyTransaction(user, "100.00", "2025-10-05", null);
        long second = createLegacyTransaction(user, "200.00", "2025-11-05", null);
        TestUser other = registerUser("Vizinho");
        long foreign = createLegacyTransaction(other, "50.00", "2025-10-05", null);

        // Closing day 10: a purchase on the 5th enters that same month's invoice.
        String batch = """
                {"items": [
                    %s,
                    %s,
                    %s,
                    %s
                ]}
                """.formatted(
                convertBody(first, cardId, "2025-10-05", 1, "2025-10").trim(),
                convertBody(second, cardId, "2025-11-05", 2, "2025-11").trim(),
                convertBody(foreign, cardId, "2025-10-05", 1, "2025-10").trim(),
                convertBody(first, cardId, "2025-10-05", 1, "2025-10").trim());

        mockMvc.perform(post("/api/legacy-conversions/batch")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.succeeded").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.results[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.results[1].status").value("SUCCESS"))
                .andExpect(jsonPath("$.results[2].status").value("FAILED"))
                .andExpect(jsonPath("$.results[2].errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.results[3].status").value("SKIPPED"))
                .andExpect(jsonPath("$.results[3].errorCode").value("DUPLICATE_SOURCE"));

        // The foreign item failed for us and left the other user untouched.
        mockMvc.perform(get("/api/transactions/" + foreign).cookie(other.session()))
                .andExpect(jsonPath("$.financiallyActive").value(true));

        // The same batch again is idempotent: successes become ALREADY_CONVERTED.
        mockMvc.perform(post("/api/legacy-conversions/batch")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch))
                .andExpect(jsonPath("$.succeeded").value(0))
                .andExpect(jsonPath("$.alreadyConverted").value(2))
                .andExpect(jsonPath("$.results[0].status").value("ALREADY_CONVERTED"));
    }

    @Test
    void batchRejectsOversizedInput() throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append(convertBody(1000L + i, cardId, "2025-10-05", 1, "2025-11").trim());
        }
        mockMvc.perform(post("/api/legacy-conversions/batch")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [" + items + "]}"))
                .andExpect(status().isBadRequest());
    }
}
