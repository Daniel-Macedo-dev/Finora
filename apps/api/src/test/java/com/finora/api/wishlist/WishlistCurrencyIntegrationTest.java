package com.finora.api.wishlist;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A wishlist item is priced in one currency, and everything hanging off it --
 * options, the card that would finance them, the account that would pay --
 * has to agree, because Finora cannot convert between them.
 */
class WishlistCurrencyIntegrationTest extends AbstractIntegrationTest {

    @Test
    void itemWithoutCurrencyUsesBaseCurrency() throws Exception {
        TestUser user = registerUser();

        createItem(user, "Notebook", null)
                .andExpect(jsonPath("$.currency").value("BRL"));
    }

    @Test
    void itemKeepsAnExplicitForeignCurrency() throws Exception {
        TestUser user = registerUser();

        createItem(user, "Notebook", "USD")
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void itemCurrencyCannotBeChanged() throws Exception {
        TestUser user = registerUser();
        long item = idOf(createItem(user, "Notebook", "USD").andReturn());

        mockMvc.perform(put("/api/wishlist/{id}", item)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody("Notebook", "BRL", categoryFor(user))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_IMMUTABLE"));
    }

    @Test
    void optionsAreReadInTheItemCurrency() throws Exception {
        TestUser user = registerUser();
        long item = idOf(createItem(user, "Notebook", "USD").andReturn());

        addCashOption(user, item, "1200.00").andExpect(status().isCreated());

        mockMvc.perform(get("/api/wishlist/{id}", item).cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void anInstallmentOptionCannotUseACardInAnotherCurrency() throws Exception {
        TestUser user = registerUser();
        long item = idOf(createItem(user, "Notebook", "USD").andReturn());
        long brlCard = createCard(user, "Cartão BRL", "BRL");

        mockMvc.perform(post("/api/wishlist/{id}/options", item)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Loja", "kind": "INSTALLMENT", "basePrice": 1200.00,
                                 "installmentCount": 12, "installmentAmount": 100.00,
                                 "creditCardId": %d}
                                """.formatted(brlCard)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WISHLIST_CURRENCY_MISMATCH"));
    }

    @Test
    void anInstallmentOptionAcceptsACardOfTheSameCurrency() throws Exception {
        TestUser user = registerUser();
        long item = idOf(createItem(user, "Notebook", "USD").andReturn());
        long usdCard = createCard(user, "Cartão USD", "USD");

        mockMvc.perform(post("/api/wishlist/{id}/options", item)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Loja", "kind": "INSTALLMENT", "basePrice": 1200.00,
                                 "installmentCount": 12, "installmentAmount": 100.00,
                                 "creditCardId": %d}
                                """.formatted(usdCard)))
                .andExpect(status().isCreated());
    }

    @Test
    void cashPurchaseCannotBePaidFromAnAccountInAnotherCurrency() throws Exception {
        TestUser user = registerUser();
        long item = idOf(createItem(user, "Notebook", "USD").andReturn());
        long option = idOf(addCashOption(user, item, "1200.00").andReturn());
        long brlAccount = createAccount(user, "Conta BRL", "BRL");

        mockMvc.perform(post("/api/wishlist/{id}/purchase", item)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId": %d, "accountId": %d, "purchasedOn": "2026-07-01"}
                                """.formatted(option, brlAccount)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WISHLIST_CURRENCY_MISMATCH"));
    }

    @Test
    void cashPurchaseWithoutAnAccountKeepsTheItemCurrency() throws Exception {
        TestUser user = registerUser();
        long item = idOf(createItem(user, "Notebook", "EUR").andReturn());
        long option = idOf(addCashOption(user, item, "1200.00").andReturn());

        MvcResult result = mockMvc.perform(post("/api/wishlist/{id}/purchase", item)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId": %d, "purchasedOn": "2026-07-01"}
                                """.formatted(option)))
                .andExpect(status().isCreated())
                .andReturn();

        long transactionId = objectMapper
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("transactionId").asLong();
        mockMvc.perform(get("/api/transactions/{id}", transactionId).cookie(user.session()))
                .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void cashPurchaseIsAcceptedFromAnAccountOfTheSameCurrency() throws Exception {
        TestUser user = registerUser();
        long item = idOf(createItem(user, "Notebook", "USD").andReturn());
        long option = idOf(addCashOption(user, item, "1200.00").andReturn());
        long usdAccount = createAccount(user, "Conta USD", "USD");

        mockMvc.perform(post("/api/wishlist/{id}/purchase", item)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId": %d, "accountId": %d, "purchasedOn": "2026-07-01"}
                                """.formatted(option, usdAccount)))
                .andExpect(status().isCreated());
    }

    @Test
    void anotherUsersCardStaysInvisibleToAnOption() throws Exception {
        TestUser owner = registerUser("Dono");
        long foreignCard = createCard(owner, "Cartão do dono", "USD");

        TestUser user = registerUser("Outro");
        long item = idOf(createItem(user, "Notebook", "USD").andReturn());

        mockMvc.perform(post("/api/wishlist/{id}/options", item)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Loja", "kind": "INSTALLMENT", "basePrice": 1200.00,
                                 "installmentCount": 12, "installmentAmount": 100.00,
                                 "creditCardId": %d}
                                """.formatted(foreignCard)))
                .andExpect(status().isNotFound());
    }

    // --- helpers ---------------------------------------------------------

    private ResultActions createItem(TestUser user, String name, String currency) throws Exception {
        return mockMvc.perform(post("/api/wishlist")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody(name, currency, categoryFor(user))))
                .andExpect(status().isCreated());
    }

    /** Executing a purchase requires a category, so every fixture item has one. */
    private Long categoryFor(TestUser user) {
        return categoryId(user, "Compras", CategoryType.EXPENSE);
    }

    private static String itemBody(String name, String currency, Long categoryId) {
        String currencyField = currency == null ? "" : "\"currency\": \"%s\", ".formatted(currency);
        return """
                {"name": "%s", %s"priority": "HIGH", "referencePrice": 1500.00,
                 "categoryId": %d}
                """.formatted(name, currencyField, categoryId);
    }

    private ResultActions addCashOption(TestUser user, long itemId, String price) throws Exception {
        return mockMvc.perform(post("/api/wishlist/{id}/options", itemId)
                .cookie(user.session())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"merchant": "Loja", "kind": "CASH", "basePrice": %s}
                        """.formatted(price)));
    }

    private long createCard(TestUser user, String name, String currency) throws Exception {
        return idOf(mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "brand": "VISA", "creditLimit": 20000.00,
                                 "closingDay": 5, "dueDay": 12, "currency": "%s"}
                                """.formatted(name, currency)))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private long createAccount(TestUser user, String name, String currency) throws Exception {
        return idOf(mockMvc.perform(post("/api/accounts")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "type": "CHECKING", "currency": "%s",
                                 "openingBalance": 5000.00}
                                """.formatted(name, currency)))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private long idOf(MvcResult result) throws Exception {
        return objectMapper
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }
}
