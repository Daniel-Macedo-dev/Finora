package com.finora.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/**
 * Direct-ID attack scenarios: user B knows the numeric ids of user A's
 * resources and tries to read, modify, delete or reference them. Every
 * attempt must behave as "not found" (never 403, to avoid enumeration)
 * and must leave A's data untouched.
 */
class OwnershipAttackTest extends AbstractIntegrationTest {

    private TestUser alice;
    private TestUser bruno;
    private Long aliceExpenseCategoryId;
    private long aliceAccountId;
    private long aliceTransactionId;
    private long aliceGoalId;
    private long aliceWishlistItemId;
    private long aliceOptionId;

    @BeforeEach
    void setUp() throws Exception {
        alice = registerUser("Alice");
        bruno = registerUser("Bruno");
        aliceExpenseCategoryId = categoryId(alice, "Alimentação", CategoryType.EXPENSE);

        aliceAccountId = idFrom(mockMvc.perform(post("/api/accounts")
                        .cookie(alice.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Conta da Alice", "type": "CHECKING", "openingBalance": 5000.00}
                                """))
                .andExpect(status().isCreated()));

        aliceTransactionId = idFrom(mockMvc.perform(post("/api/transactions")
                        .cookie(alice.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 120.00, "description": "Compra da Alice",
                                 "date": "2026-07-05", "categoryId": %d, "accountId": %d}
                                """.formatted(aliceExpenseCategoryId, aliceAccountId)))
                .andExpect(status().isCreated()));

        aliceGoalId = idFrom(mockMvc.perform(post("/api/goals")
                        .cookie(alice.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Meta da Alice", "targetAmount": 1000.00, "currentAmount": 100.00}
                                """))
                .andExpect(status().isCreated()));

        aliceWishlistItemId = idFrom(mockMvc.perform(post("/api/wishlist")
                        .cookie(alice.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Desejo da Alice", "priority": "HIGH"}
                                """))
                .andExpect(status().isCreated()));

        aliceOptionId = idFrom(mockMvc.perform(post("/api/wishlist/{id}/options", aliceWishlistItemId)
                        .cookie(alice.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Loja", "kind": "CASH", "basePrice": 500.00}
                                """))
                .andExpect(status().isCreated()));
    }

    private long idFrom(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("id").asLong();
    }

    @Test
    void directReadsOfForeignResourcesBehaveAsNotFound() throws Exception {
        mockMvc.perform(get("/api/accounts/{id}", aliceAccountId).cookie(bruno.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/transactions/{id}", aliceTransactionId).cookie(bruno.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/goals/{id}", aliceGoalId).cookie(bruno.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/wishlist/{id}", aliceWishlistItemId).cookie(bruno.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/categories/{id}", aliceExpenseCategoryId).cookie(bruno.session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listingsNeverIncludeForeignRows() throws Exception {
        mockMvc.perform(get("/api/transactions").cookie(bruno.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/accounts").cookie(bruno.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/wishlist").cookie(bruno.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void foreignCategoryCannotBeInjectedIntoTransaction() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .cookie(bruno.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 50.00, "description": "Ataque",
                                 "date": "2026-07-06", "categoryId": %d}
                                """.formatted(aliceExpenseCategoryId)))
                .andExpect(status().isNotFound());

        // Nothing was created for Bruno.
        mockMvc.perform(get("/api/transactions").cookie(bruno.session()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void foreignAccountCannotBeInjectedIntoTransaction() throws Exception {
        Long brunoCategory = categoryId(bruno, "Alimentação", CategoryType.EXPENSE);
        mockMvc.perform(post("/api/transactions")
                        .cookie(bruno.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 50.00, "description": "Ataque",
                                 "date": "2026-07-06", "categoryId": %d, "accountId": %d}
                                """.formatted(brunoCategory, aliceAccountId)))
                .andExpect(status().isNotFound());

        // Alice's account balance is untouched (5000 - 120 own expense).
        mockMvc.perform(get("/api/accounts/{id}", aliceAccountId).cookie(alice.session()))
                .andExpect(jsonPath("$.currentBalance").value(4880.00));
    }

    @Test
    void foreignCategoryCannotBeInjectedIntoBudgetOrCommitmentOrWishlist() throws Exception {
        mockMvc.perform(post("/api/budgets")
                        .cookie(bruno.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"month": "2026-07", "categoryId": %d, "limitAmount": 100.00}
                                """.formatted(aliceExpenseCategoryId)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/commitments")
                        .cookie(bruno.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Ataque", "amount": 10.00, "categoryId": %d,
                                 "cadence": "MONTHLY", "dueDay": 1, "startDate": "2026-01-01"}
                                """.formatted(aliceExpenseCategoryId)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/wishlist")
                        .cookie(bruno.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Ataque", "priority": "LOW", "categoryId": %d}
                                """.formatted(aliceExpenseCategoryId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void foreignWishlistItemAndOptionsAreUnreachable() throws Exception {
        // Add option to Alice's item.
        mockMvc.perform(post("/api/wishlist/{id}/options", aliceWishlistItemId)
                        .cookie(bruno.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Ataque", "kind": "CASH", "basePrice": 1.00}
                                """))
                .andExpect(status().isNotFound());

        // Edit and delete Alice's option.
        mockMvc.perform(put("/api/wishlist/{id}/options/{optionId}", aliceWishlistItemId, aliceOptionId)
                        .cookie(bruno.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Hackeada", "kind": "CASH", "basePrice": 1.00}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/wishlist/{id}/options/{optionId}", aliceWishlistItemId, aliceOptionId)
                        .cookie(bruno.session()).with(csrf()))
                .andExpect(status().isNotFound());

        // Run analysis on Alice's item.
        mockMvc.perform(get("/api/wishlist/{id}/analysis", aliceWishlistItemId)
                        .cookie(bruno.session()))
                .andExpect(status().isNotFound());

        // Alice still sees her intact option.
        mockMvc.perform(get("/api/wishlist/{id}", aliceWishlistItemId).cookie(alice.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options.length()").value(1))
                .andExpect(jsonPath("$.options[0].merchant").value("Loja"));
    }

    @Test
    void foreignGoalCannotReceiveContributions() throws Exception {
        mockMvc.perform(post("/api/goals/{id}/contributions", aliceGoalId)
                        .cookie(bruno.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 999.00}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/goals/{id}", aliceGoalId).cookie(alice.session()))
                .andExpect(jsonPath("$.currentAmount").value(100.00));
    }

    @Test
    void foreignResourcesCannotBeUpdatedOrDeleted() throws Exception {
        mockMvc.perform(put("/api/transactions/{id}", aliceTransactionId)
                        .cookie(bruno.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 1.00, "description": "Alterada",
                                 "date": "2026-07-06", "categoryId": %d}
                                """.formatted(categoryId(bruno, "Alimentação", CategoryType.EXPENSE))))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/transactions/{id}", aliceTransactionId)
                        .cookie(bruno.session()).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/accounts/{id}", aliceAccountId)
                        .cookie(bruno.session()).with(csrf()))
                .andExpect(status().isNotFound());

        // Alice's transaction is intact.
        mockMvc.perform(get("/api/transactions/{id}", aliceTransactionId).cookie(alice.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(120.00));
    }

    @Test
    void userIdQueryParameterCannotSwitchOwnership() throws Exception {
        // A userId query parameter must be ignored: ownership comes from the session.
        mockMvc.perform(get("/api/transactions")
                        .cookie(bruno.session())
                        .param("userId", String.valueOf(alice.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void categoryDeactivationIsIsolatedBetweenUsers() throws Exception {
        // Bruno deactivates his own "Lazer" category.
        Long brunoLeisure = categoryId(bruno, "Lazer", CategoryType.EXPENSE);
        mockMvc.perform(put("/api/categories/{id}", brunoLeisure)
                        .cookie(bruno.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Lazer", "type": "EXPENSE", "active": false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // Alice's "Lazer" stays active.
        Long aliceLeisure = categoryId(alice, "Lazer", CategoryType.EXPENSE);
        assertThat(aliceLeisure).isNotEqualTo(brunoLeisure);
        mockMvc.perform(get("/api/categories/{id}", aliceLeisure).cookie(alice.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }
}
