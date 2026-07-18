package com.finora.api.legacyconversion;

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

/**
 * Cross-owner isolation of the whole conversion surface: another user's
 * inventory, sources and conversions behave as absent — 404, never 403 — and
 * an attack attempt leaves the victim's data untouched. Complements the
 * eligibility/foreign-card/batch ownership checks that already live in
 * {@link LegacyConversionApiIntegrationTest}.
 *
 * <p>The engine commits in its own transaction (REQUIRES_NEW), so the test
 * transaction is suppressed and per-user isolation comes from registration.
 */
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class LegacyConversionOwnershipTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private TestUser owner;
    private TestUser attacker;
    private long ownerCardId;
    private long ownerSourceId;

    @BeforeEach
    void setUp() throws Exception {
        owner = registerUser("Titular");
        attacker = registerUser("Invasor");
        ownerCardId = createCard(owner, "Cartão do Titular");
        ownerSourceId = createLegacyTransaction(owner, "300.00", "2025-11-20");
    }

    private long createCard(TestUser user, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "brand": "VISA", "creditLimit": 10000,
                                 "closingDay": 10, "dueDay": 17}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    /** A pre-card-era CREDIT expense, forged exactly like migration V7 did. */
    private long createLegacyTransaction(TestUser user, String amount, String date)
            throws Exception {
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
        long id = objectMapper.readTree(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
        jdbc.update("UPDATE transactions SET payment_method = 'CREDIT', legacy_credit = TRUE "
                + "WHERE id = ?", id);
        entityManager.clear();
        return id;
    }

    private long convert(TestUser user, long sourceId, long cardId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId": %d, "cardId": %d,
                                 "effectivePurchaseDate": "2025-11-20",
                                 "installmentCount": 1, "firstInvoiceMonth": "2025-12"}
                                """.formatted(sourceId, cardId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    @Test
    void inventoryNeverLeaksAnotherUsersSources() throws Exception {
        // The attacker's inventory is empty even though the owner has data —
        // both bare and with filters that match the owner's rows exactly.
        mockMvc.perform(get("/api/legacy-conversions").cookie(attacker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.eligibleCount").value(0))
                .andExpect(jsonPath("$.summary.convertedCount").value(0))
                .andExpect(jsonPath("$.summary.reversedCount").value(0))
                .andExpect(jsonPath("$.summary.pendingAmount").value(0.00))
                .andExpect(jsonPath("$.page.totalElements").value(0));
        mockMvc.perform(get("/api/legacy-conversions").cookie(attacker.session())
                        .param("month", "2025-11").param("minAmount", "100"))
                .andExpect(jsonPath("$.page.totalElements").value(0));

        // The owner still sees exactly their own source.
        mockMvc.perform(get("/api/legacy-conversions").cookie(owner.session()))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.content[0].transactionId").value(ownerSourceId));
    }

    @Test
    void previewAndConversionOfForeignSourcesBehaveAsAbsent() throws Exception {
        long attackerCardId = createCard(attacker, "Cartão do Invasor");

        mockMvc.perform(post("/api/legacy-conversions/preview")
                        .cookie(attacker.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId": %d, "cardId": %d,
                                 "effectivePurchaseDate": "2025-11-20", "installmentCount": 1}
                                """.formatted(ownerSourceId, attackerCardId)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/legacy-conversions")
                        .cookie(attacker.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId": %d, "cardId": %d,
                                 "effectivePurchaseDate": "2025-11-20",
                                 "installmentCount": 1, "firstInvoiceMonth": "2025-12"}
                                """.formatted(ownerSourceId, attackerCardId)))
                .andExpect(status().isNotFound());

        // The attack attempt left the owner's source untouched and convertible.
        mockMvc.perform(get("/api/transactions/" + ownerSourceId).cookie(owner.session()))
                .andExpect(jsonPath("$.financiallyActive").value(true))
                .andExpect(jsonPath("$.legacyConversionStatus").isEmpty());
    }

    @Test
    void conversionDetailAndReversalOfForeignConversionsBehaveAsAbsent() throws Exception {
        long conversionId = convert(owner, ownerSourceId, ownerCardId);

        mockMvc.perform(get("/api/legacy-conversions/" + conversionId)
                        .cookie(attacker.session()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/legacy-conversions/%d/reverse".formatted(conversionId))
                        .cookie(attacker.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "ataque"}
                                """))
                .andExpect(status().isNotFound());

        // The owner's conversion is still active and reversible by the owner.
        mockMvc.perform(get("/api/legacy-conversions/" + conversionId).cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.reversible").value(true));
    }
}
