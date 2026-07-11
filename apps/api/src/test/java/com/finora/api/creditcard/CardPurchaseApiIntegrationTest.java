package com.finora.api.creditcard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Purchase → installment → invoice mechanics. Purchases use far-future fixed
 * dates so derived invoice status never flips as the real calendar advances.
 */
class CardPurchaseApiIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long categoryId;
    private long cardId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        categoryId = categoryId(user, "Compras", CategoryType.EXPENSE);
        cardId = createCard("Cartão Teste", "5000.00", 10, 17);
    }

    private long createCard(String name, String limit, int closingDay, int dueDay) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "brand": "VISA", "creditLimit": %s,
                                 "closingDay": %d, "dueDay": %d}
                                """.formatted(name, limit, closingDay, dueDay)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    private JsonNode createPurchase(long card, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/credit-cards/%d/purchases".formatted(card))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void oneTimePurchaseCreatesSingleInstallmentOnCorrectInvoice() throws Exception {
        JsonNode purchase = createPurchase(cardId, """
                {"description": "Fone de ouvido", "merchant": "TechShop",
                 "categoryId": %d, "purchaseDate": "2031-03-05",
                 "totalAmount": 350.00, "installmentCount": 1}
                """.formatted(categoryId));

        assertThat(purchase.get("installments")).hasSize(1);
        JsonNode installment = purchase.get("installments").get(0);
        // closing 10 ≥ Mar 5 → March invoice, due Mar 17.
        assertThat(installment.get("invoiceMonth").asString()).isEqualTo("2031-03");
        assertThat(installment.get("invoiceDueDate").asString()).isEqualTo("2031-03-17");
        assertThat(installment.get("amount").decimalValue()).isEqualByComparingTo("350.00");

        mockMvc.perform(get("/api/credit-cards/" + cardId).cookie(user.session()))
                .andExpect(jsonPath("$.limit.usedLimit").value(350.00))
                .andExpect(jsonPath("$.limit.availableLimit").value(4650.00))
                .andExpect(jsonPath("$.limit.utilizationPercent").value(7.0));
    }

    @Test
    void purchaseAfterClosingDayRollsToNextInvoice() throws Exception {
        JsonNode purchase = createPurchase(cardId, """
                {"description": "Jantar", "categoryId": %d, "purchaseDate": "2031-03-11",
                 "totalAmount": 100.00, "installmentCount": 1}
                """.formatted(categoryId));
        assertThat(purchase.get("installments").get(0).get("invoiceMonth").asString())
                .isEqualTo("2031-04");
    }

    @Test
    void installmentPurchaseDistributesCentsExactlyAcrossConsecutiveMonths() throws Exception {
        JsonNode purchase = createPurchase(cardId, """
                {"description": "Notebook", "categoryId": %d, "purchaseDate": "2031-03-05",
                 "totalAmount": 1000.00, "installmentCount": 3}
                """.formatted(categoryId));

        JsonNode installments = purchase.get("installments");
        assertThat(installments).hasSize(3);
        assertThat(installments.get(0).get("amount").decimalValue()).isEqualByComparingTo("333.33");
        assertThat(installments.get(1).get("amount").decimalValue()).isEqualByComparingTo("333.33");
        assertThat(installments.get(2).get("amount").decimalValue()).isEqualByComparingTo("333.34");
        assertThat(installments.get(0).get("invoiceMonth").asString()).isEqualTo("2031-03");
        assertThat(installments.get(1).get("invoiceMonth").asString()).isEqualTo("2031-04");
        assertThat(installments.get(2).get("invoiceMonth").asString()).isEqualTo("2031-05");

        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode i : installments) {
            sum = sum.add(i.get("amount").decimalValue());
        }
        assertThat(sum).isEqualByComparingTo("1000.00");

        // The whole schedule consumes limit immediately.
        mockMvc.perform(get("/api/credit-cards/" + cardId).cookie(user.session()))
                .andExpect(jsonPath("$.limit.usedLimit").value(1000.00));

        // Three future invoices exist, one per month.
        mockMvc.perform(get("/api/credit-cards/%d/invoices".formatted(cardId))
                        .cookie(user.session()))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].referenceMonth").value("2031-03"))
                .andExpect(jsonPath("$[0].invoiceTotal").value(333.33))
                .andExpect(jsonPath("$[2].referenceMonth").value("2031-05"))
                .andExpect(jsonPath("$[2].invoiceTotal").value(333.34))
                .andExpect(jsonPath("$[0].status").value("UPCOMING"));
    }

    @Test
    void purchasesShareTheSameMonthlyInvoice() throws Exception {
        createPurchase(cardId, """
                {"description": "Compra 1", "categoryId": %d, "purchaseDate": "2031-03-01",
                 "totalAmount": 100.00, "installmentCount": 1}
                """.formatted(categoryId));
        createPurchase(cardId, """
                {"description": "Compra 2", "categoryId": %d, "purchaseDate": "2031-03-09",
                 "totalAmount": 50.00, "installmentCount": 1}
                """.formatted(categoryId));

        mockMvc.perform(get("/api/credit-cards/%d/invoices".formatted(cardId))
                        .cookie(user.session()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].invoiceTotal").value(150.00))
                .andExpect(jsonPath("$[0].installmentCount").value(2));
    }

    @Test
    void rejectsPurchaseBeyondAvailableLimit() throws Exception {
        createPurchase(cardId, """
                {"description": "Grande", "categoryId": %d, "purchaseDate": "2031-03-05",
                 "totalAmount": 4900.00, "installmentCount": 10}
                """.formatted(categoryId));
        mockMvc.perform(post("/api/credit-cards/%d/purchases".formatted(cardId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Estouro", "categoryId": %d,
                                 "purchaseDate": "2031-03-06",
                                 "totalAmount": 200.00, "installmentCount": 1}
                                """.formatted(categoryId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_CARD_LIMIT"));
    }

    @Test
    void rejectsPurchaseOnArchivedCard() throws Exception {
        mockMvc.perform(post("/api/credit-cards/%d/archive".formatted(cardId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/credit-cards/%d/purchases".formatted(cardId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Bloqueada", "categoryId": %d,
                                 "purchaseDate": "2031-03-05",
                                 "totalAmount": 10.00, "installmentCount": 1}
                                """.formatted(categoryId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_ARCHIVED"));
    }

    @Test
    void rejectsIncomeCategoryAndForeignCategory() throws Exception {
        Long incomeCategory = categoryId(user, "Salário", CategoryType.INCOME);
        mockMvc.perform(post("/api/credit-cards/%d/purchases".formatted(cardId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Errada", "categoryId": %d,
                                 "purchaseDate": "2031-03-05",
                                 "totalAmount": 10.00, "installmentCount": 1}
                                """.formatted(incomeCategory)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_EXPENSE"));

        TestUser other = registerUser("Outra");
        Long foreignCategory = categoryId(other, "Compras", CategoryType.EXPENSE);
        mockMvc.perform(post("/api/credit-cards/%d/purchases".formatted(cardId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Alheia", "categoryId": %d,
                                 "purchaseDate": "2031-03-05",
                                 "totalAmount": 10.00, "installmentCount": 1}
                                """.formatted(foreignCategory)))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancellationReleasesLimitAndExcludesInstallments() throws Exception {
        JsonNode purchase = createPurchase(cardId, """
                {"description": "Arrependida", "categoryId": %d, "purchaseDate": "2031-03-05",
                 "totalAmount": 900.00, "installmentCount": 3}
                """.formatted(categoryId));
        long purchaseId = purchase.get("id").asLong();

        mockMvc.perform(post("/api/credit-cards/%d/purchases/%d/cancel"
                        .formatted(cardId, purchaseId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.installments[0].status").value("CANCELLED"));

        mockMvc.perform(get("/api/credit-cards/" + cardId).cookie(user.session()))
                .andExpect(jsonPath("$.limit.usedLimit").value(0.00));
        mockMvc.perform(get("/api/credit-cards/%d/invoices".formatted(cardId))
                        .cookie(user.session()))
                .andExpect(jsonPath("$[0].invoiceTotal").value(0.00))
                .andExpect(jsonPath("$[0].installmentCount").value(0));

        // Cancelling again is rejected explicitly.
        mockMvc.perform(post("/api/credit-cards/%d/purchases/%d/cancel"
                        .formatted(cardId, purchaseId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PURCHASE_NOT_ACTIVE"));
    }

    @Test
    void metadataEditKeepsScheduleFinancialEditRegeneratesIt() throws Exception {
        JsonNode purchase = createPurchase(cardId, """
                {"description": "原 Compra", "categoryId": %d, "purchaseDate": "2031-03-05",
                 "totalAmount": 300.00, "installmentCount": 3}
                """.formatted(categoryId));
        long purchaseId = purchase.get("id").asLong();

        // Metadata-only edit: same schedule.
        mockMvc.perform(put("/api/credit-cards/%d/purchases/%d".formatted(cardId, purchaseId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Compra Renomeada", "merchant": "Loja Nova",
                                 "categoryId": %d, "purchaseDate": "2031-03-05",
                                 "totalAmount": 300.00, "installmentCount": 3}
                                """.formatted(categoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Compra Renomeada"))
                .andExpect(jsonPath("$.installments.length()").value(3));

        // Financial edit regenerates the schedule (future, unpaid invoices).
        mockMvc.perform(put("/api/credit-cards/%d/purchases/%d".formatted(cardId, purchaseId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Compra Renomeada", "categoryId": %d,
                                 "purchaseDate": "2031-03-05",
                                 "totalAmount": 500.00, "installmentCount": 2}
                                """.formatted(categoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installments.length()").value(2))
                .andExpect(jsonPath("$.installments[0].amount").value(250.00));

        mockMvc.perform(get("/api/credit-cards/" + cardId).cookie(user.session()))
                .andExpect(jsonPath("$.limit.usedLimit").value(500.00));
    }

    @Test
    void anotherUserCannotAccessPurchases() throws Exception {
        JsonNode purchase = createPurchase(cardId, """
                {"description": "Privada", "categoryId": %d, "purchaseDate": "2031-03-05",
                 "totalAmount": 100.00, "installmentCount": 1}
                """.formatted(categoryId));
        long purchaseId = purchase.get("id").asLong();
        TestUser intruder = registerUser("Intruso");

        mockMvc.perform(get("/api/credit-cards/%d/purchases".formatted(cardId))
                        .cookie(intruder.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/credit-cards/%d/purchases/%d".formatted(cardId, purchaseId))
                        .cookie(intruder.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/credit-cards/%d/purchases/%d/cancel"
                        .formatted(cardId, purchaseId))
                        .cookie(intruder.session()).with(csrf()))
                .andExpect(status().isNotFound());
    }
}
