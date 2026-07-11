package com.finora.api.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

class AccountApiIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
    }

    private long createAccount(String body) throws Exception {
        String response = mockMvc.perform(post("/api/accounts")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response).get("id").asLong();
    }

    @Test
    void createsAccountAndComputesCurrentBalance() throws Exception {
        long accountId = createAccount("""
                {"name": "Conta Corrente", "type": "CHECKING", "openingBalance": 1000.00}
                """);

        Long incomeCategoryId = categoryId(user, "Salário", CategoryType.INCOME);
        Long expenseCategoryId = categoryId(user, "Moradia", CategoryType.EXPENSE);

        mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "INCOME", "amount": 500.00, "description": "Salário",
                                 "date": "2026-07-01", "categoryId": %d, "accountId": %d}
                                """.formatted(incomeCategoryId, accountId)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 200.00, "description": "Aluguel",
                                 "date": "2026-07-05", "categoryId": %d, "accountId": %d}
                                """.formatted(expenseCategoryId, accountId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/accounts/{id}", accountId).cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value(1300.00));
    }

    @Test
    void rejectsDuplicateAccountNameForSameUser() throws Exception {
        createAccount("""
                {"name": "Carteira", "type": "CASH", "openingBalance": 0}
                """);
        mockMvc.perform(post("/api/accounts")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "carteira", "type": "CASH", "openingBalance": 0}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NAME_TAKEN"));
    }

    @Test
    void allowsSameAccountNameForDifferentUsers() throws Exception {
        createAccount("""
                {"name": "Nubank", "type": "CHECKING", "openingBalance": 0}
                """);

        TestUser other = registerUser("Outra Pessoa");
        mockMvc.perform(post("/api/accounts")
                        .cookie(other.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Nubank", "type": "CHECKING", "openingBalance": 0}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void preventsDeletingAccountWithTransactions() throws Exception {
        long accountId = createAccount("""
                {"name": "Poupança", "type": "SAVINGS", "openingBalance": 100}
                """);
        Long categoryIdValue = categoryId(user, "Lazer", CategoryType.EXPENSE);
        mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 30.00, "description": "Cinema",
                                 "date": "2026-07-02", "categoryId": %d, "accountId": %d}
                                """.formatted(categoryIdValue, accountId)))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/accounts/{id}", accountId)
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_HAS_TRANSACTIONS"));
    }
}
