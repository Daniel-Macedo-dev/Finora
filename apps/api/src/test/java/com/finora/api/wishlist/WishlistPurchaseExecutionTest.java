package com.finora.api.wishlist;

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

/** Wishlist execution: one selected option becomes one real financial event. */
class WishlistPurchaseExecutionTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long categoryId;
    private long accountId;
    private long cardId;
    private long itemId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        categoryId = categoryId(user, "Compras", CategoryType.EXPENSE);
        accountId = readId(mockMvc.perform(post("/api/accounts")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Conta Wishlist", "type": "CHECKING", "openingBalance": 4000}
                                """))
                .andExpect(status().isCreated())
                .andReturn());
        cardId = readId(mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Cartão Wishlist", "brand": "VISA", "creditLimit": 3000,
                                 "closingDay": 10, "dueDay": 17}
                                """))
                .andExpect(status().isCreated())
                .andReturn());
        itemId = readId(mockMvc.perform(post("/api/wishlist")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Cadeira ergonômica", "priority": "HIGH", "categoryId": %d}
                                """.formatted(categoryId)))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private long readId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    private long addCashOption(String price) throws Exception {
        return readId(mockMvc.perform(post("/api/wishlist/%d/options".formatted(itemId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Loja À Vista", "kind": "CASH", "basePrice": %s}
                                """.formatted(price)))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private long addInstallmentOption(String total, int count, String each, Long cardIdOrNull)
            throws Exception {
        String cardField = cardIdOrNull != null ? ", \"creditCardId\": %d".formatted(cardIdOrNull) : "";
        return readId(mockMvc.perform(post("/api/wishlist/%d/options".formatted(itemId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Loja Parcelada", "kind": "INSTALLMENT",
                                 "basePrice": %s, "installmentCount": %d, "installmentAmount": %s%s}
                                """.formatted(total, count, each, cardField)))
                .andExpect(status().isCreated())
                .andReturn());
    }

    @Test
    void cashExecutionCreatesLinkedExpenseAndMarksItemPurchased() throws Exception {
        long optionId = addCashOption("1200.00");
        mockMvc.perform(post("/api/wishlist/%d/purchase".formatted(itemId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId": %d, "accountId": %d, "purchasedOn": "2031-03-05"}
                                """.formatted(optionId, accountId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PURCHASED"))
                .andExpect(jsonPath("$.transactionId").isNumber())
                .andExpect(jsonPath("$.cardPurchaseId").isEmpty());

        mockMvc.perform(get("/api/wishlist/" + itemId).cookie(user.session()))
                .andExpect(jsonPath("$.status").value("PURCHASED"));
        mockMvc.perform(get("/api/accounts/" + accountId).cookie(user.session()))
                .andExpect(jsonPath("$.currentBalance").value(2800.00));
    }

    @Test
    void installmentExecutionCreatesCardPurchaseWithExactSchedule() throws Exception {
        long optionId = addInstallmentOption("1200.00", 12, "100.00", cardId);
        mockMvc.perform(post("/api/wishlist/%d/purchase".formatted(itemId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId": %d, "purchasedOn": "2031-03-05"}
                                """.formatted(optionId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cardPurchaseId").isNumber())
                .andExpect(jsonPath("$.transactionId").isEmpty());

        mockMvc.perform(get("/api/credit-cards/" + cardId).cookie(user.session()))
                .andExpect(jsonPath("$.limit.usedLimit").value(1200.00));
        mockMvc.perform(get("/api/credit-cards/%d/purchases".formatted(cardId))
                        .cookie(user.session()))
                .andExpect(jsonPath("$.content[0].installmentCount").value(12))
                .andExpect(jsonPath("$.content[0].wishlistItemId").value((int) itemId))
                .andExpect(jsonPath("$.content[0].totalAmount").value(1200.00));
        // Bank account untouched: only invoice payments move cash.
        mockMvc.perform(get("/api/accounts/" + accountId).cookie(user.session()))
                .andExpect(jsonPath("$.currentBalance").value(4000.00));
    }

    @Test
    void retryCannotExecuteTwice() throws Exception {
        long optionId = addCashOption("500.00");
        mockMvc.perform(post("/api/wishlist/%d/purchase".formatted(itemId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId": %d, "accountId": %d}
                                """.formatted(optionId, accountId)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/wishlist/%d/purchase".formatted(itemId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId": %d, "accountId": %d}
                                """.formatted(optionId, accountId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WISHLIST_ALREADY_PURCHASED"));
        mockMvc.perform(get("/api/accounts/" + accountId).cookie(user.session()))
                .andExpect(jsonPath("$.currentBalance").value(3500.00));
    }

    @Test
    void installmentExecutionRequiresCardAndSufficientLimit() throws Exception {
        long noCardOption = addInstallmentOption("600.00", 6, "100.00", null);
        mockMvc.perform(post("/api/wishlist/%d/purchase".formatted(itemId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId": %d}
                                """.formatted(noCardOption)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WISHLIST_CARD_REQUIRED"));

        long bigOption = addInstallmentOption("9000.00", 10, "900.00", cardId);
        mockMvc.perform(post("/api/wishlist/%d/purchase".formatted(itemId))
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId": %d}
                                """.formatted(bigOption)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_CARD_LIMIT"));
    }

    @Test
    void anotherUserCannotExecuteOrReferenceForeignResources() throws Exception {
        long optionId = addCashOption("500.00");
        TestUser intruder = registerUser("Intruso");
        mockMvc.perform(post("/api/wishlist/%d/purchase".formatted(itemId))
                        .cookie(intruder.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId": %d, "accountId": %d}
                                """.formatted(optionId, accountId)))
                .andExpect(status().isNotFound());

        // Option linked to a foreign card is rejected at creation time.
        TestUser other = registerUser("Outro Dono");
        long foreignItem = readId(mockMvc.perform(post("/api/wishlist")
                        .cookie(other.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Item alheio", "priority": "LOW"}
                                """))
                .andExpect(status().isCreated())
                .andReturn());
        mockMvc.perform(post("/api/wishlist/%d/options".formatted(foreignItem))
                        .cookie(other.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Loja", "kind": "INSTALLMENT", "basePrice": 100.00,
                                 "installmentCount": 2, "installmentAmount": 50.00,
                                 "creditCardId": %d}
                                """.formatted(cardId)))
                .andExpect(status().isNotFound());
    }
}
