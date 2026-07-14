package com.finora.api.commitment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Card-target occurrences reuse the whole credit-card domain: cycle
 * calculator, installment allocator, limit lock and invoices. Failures leave
 * no partial artifact and stay retryable.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecurringCardMaterializationTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long expenseCategory;
    private long cardId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        expenseCategory = categoryId(user, "Assinaturas", CategoryType.EXPENSE);
        MvcResult card = mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Cartão Recorrente", "brand": "VISA",
                                 "creditLimit": 500.00, "closingDay": 10, "dueDay": 17}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        cardId = objectMapper.readTree(
                        card.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    private long cardCommitment(String amount, int installments) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Streaming anual", "amount": %s, "categoryId": %d,
                                 "cadence": "MONTHLY", "dueDay": 5, "startDate": "2026-01-01",
                                 "targetKind": "CREDIT_CARD_PURCHASE", "creditCardId": %d,
                                 "installmentCount": %d}
                                """.formatted(amount, expenseCategory, cardId, installments)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    @Test
    void cardOccurrenceCreatesRealPurchaseOnTheCorrectInvoice() throws Exception {
        long id = cardCommitment("120.00", 2);

        MvcResult result = mockMvc.perform(
                        post("/api/commitments/%d/occurrences/2026-03-05/materialize".formatted(id))
                                .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATERIALIZED"))
                .andExpect(jsonPath("$.cardPurchaseId").isNumber())
                .andReturn();
        long purchaseId = objectMapper.readTree(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("cardPurchaseId").asLong();

        // A real purchase with the exact cent-split schedule on March/April.
        mockMvc.perform(get("/api/credit-cards/%d/purchases/%d".formatted(cardId, purchaseId))
                        .cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitmentId").value(id))
                .andExpect(jsonPath("$.installments.length()").value(2))
                .andExpect(jsonPath("$.installments[0].invoiceMonth").value("2026-03"))
                .andExpect(jsonPath("$.installments[0].amount").value(60.00))
                .andExpect(jsonPath("$.installments[1].invoiceMonth").value("2026-04"));

        // Limit consumed like any purchase.
        mockMvc.perform(get("/api/credit-cards/" + cardId).cookie(user.session()))
                .andExpect(jsonPath("$.limit.usedLimit").value(120.00));

        // Idempotent.
        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-03-05/materialize".formatted(id))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OCCURRENCE_ALREADY_MATERIALIZED"));
    }

    @Test
    void insufficientLimitFailsRetryablyWithoutPartialArtifacts() throws Exception {
        long id = cardCommitment("600.00", 1); // above the R$ 500,00 limit

        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-03-05/materialize".formatted(id))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_CARD_LIMIT"));

        // The failure is recorded and visible; nothing was charged.
        mockMvc.perform(get("/api/commitments/%d/occurrences?from=2026-03-01&to=2026-03-31"
                        .formatted(id)).cookie(user.session()))
                .andExpect(jsonPath("$.occurrences[0].status").value("FAILED"))
                .andExpect(jsonPath("$.occurrences[0].failureCode").value("INSUFFICIENT_CARD_LIMIT"));
        mockMvc.perform(get("/api/credit-cards/" + cardId).cookie(user.session()))
                .andExpect(jsonPath("$.limit.usedLimit").value(0.00));
        mockMvc.perform(get("/api/credit-cards/%d/purchases".formatted(cardId))
                        .cookie(user.session()))
                .andExpect(jsonPath("$.content.length()").value(0));

        // After raising the limit, retry succeeds through the same identity.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/credit-cards/" + cardId)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Cartão Recorrente", "brand": "VISA",
                                 "creditLimit": 1000.00, "closingDay": 10, "dueDay": 17}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-03-05/retry".formatted(id))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATERIALIZED"));
    }

    @Test
    void reversalCancelsThePurchaseThroughTheCardDomain() throws Exception {
        long id = cardCommitment("90.00", 1);
        MvcResult result = mockMvc.perform(
                        post("/api/commitments/%d/occurrences/2026-03-05/materialize".formatted(id))
                                .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        long purchaseId = objectMapper.readTree(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("cardPurchaseId").asLong();

        // Direct cancellation through the card API is redirected to the occurrence.
        mockMvc.perform(post("/api/credit-cards/%d/purchases/%d/cancel".formatted(cardId, purchaseId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PURCHASE_FROM_RECURRING"));

        mockMvc.perform(post("/api/commitments/%d/occurrences/2026-03-05/reverse".formatted(id))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"))
                .andExpect(jsonPath("$.cardPurchaseId").value(purchaseId));

        // The purchase remains in history as CANCELLED and the limit is free.
        mockMvc.perform(get("/api/credit-cards/%d/purchases/%d".formatted(cardId, purchaseId))
                        .cookie(user.session()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/credit-cards/" + cardId).cookie(user.session()))
                .andExpect(jsonPath("$.limit.usedLimit").value(0.00));
    }
}
