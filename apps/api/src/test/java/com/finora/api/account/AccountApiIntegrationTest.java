package com.finora.api.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryRepository;
import com.finora.api.category.CategoryType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class AccountApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsAccountAndComputesCurrentBalance() throws Exception {
        String body = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Conta Corrente", "type": "CHECKING", "openingBalance": 1000.00}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentBalance").value(1000.00))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long accountId = objectMapper.readTree(body).get("id").asLong();

        Long incomeCategoryId = categories
                .findByNameIgnoreCaseAndType("Salário", CategoryType.INCOME).orElseThrow().getId();
        Long expenseCategoryId = categories
                .findByNameIgnoreCaseAndType("Moradia", CategoryType.EXPENSE).orElseThrow().getId();

        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "INCOME", "amount": 500.00, "description": "Salário",
                                 "date": "2026-07-01", "categoryId": %d, "accountId": %d}
                                """.formatted(incomeCategoryId, accountId)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 200.00, "description": "Aluguel",
                                 "date": "2026-07-05", "categoryId": %d, "accountId": %d}
                                """.formatted(expenseCategoryId, accountId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/accounts/{id}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value(1300.00));
    }

    @Test
    void rejectsDuplicateAccountName() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Carteira", "type": "CASH", "openingBalance": 0}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "carteira", "type": "CASH", "openingBalance": 0}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NAME_TAKEN"));
    }

    @Test
    void preventsDeletingAccountWithTransactions() throws Exception {
        String body = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Poupança", "type": "SAVINGS", "openingBalance": 100}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode created = objectMapper.readTree(body);
        long accountId = created.get("id").asLong();

        Long categoryId = categories
                .findByNameIgnoreCaseAndType("Lazer", CategoryType.EXPENSE).orElseThrow().getId();
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": 30.00, "description": "Cinema",
                                 "date": "2026-07-02", "categoryId": %d, "accountId": %d}
                                """.formatted(categoryId, accountId)))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/accounts/{id}", accountId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_HAS_TRANSACTIONS"));
    }
}
