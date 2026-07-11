package com.finora.api.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class TransactionApiIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long expenseCategoryId;
    private Long incomeCategoryId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        expenseCategoryId = categoryId(user, "Alimentação", CategoryType.EXPENSE);
        incomeCategoryId = categoryId(user, "Salário", CategoryType.INCOME);
    }

    @Test
    void createsExpenseTransaction() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "EXPENSE",
                                  "amount": 89.90,
                                  "description": "Supermercado",
                                  "date": "2026-07-05",
                                  "categoryId": %d,
                                  "paymentMethod": "PIX"
                                }
                                """.formatted(expenseCategoryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.amount").value(89.90))
                .andExpect(jsonPath("$.category.name").value("Alimentação"))
                .andExpect(jsonPath("$.paymentMethod").value("PIX"));
    }

    @Test
    void rejectsUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    }

    @Test
    void rejectsZeroAmount() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "EXPENSE",
                                  "amount": 0,
                                  "description": "Inválida",
                                  "date": "2026-07-05",
                                  "categoryId": %d
                                }
                                """.formatted(expenseCategoryId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
    }

    @Test
    void rejectsCategoryTypeMismatch() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "INCOME",
                                  "amount": 1000,
                                  "description": "Pagamento",
                                  "date": "2026-07-05",
                                  "categoryId": %d
                                }
                                """.formatted(expenseCategoryId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CATEGORY_TYPE_MISMATCH"));
    }

    @Test
    void filtersByMonthAndType() throws Exception {
        createTransaction("EXPENSE", "50.00", "Junho", "2026-06-15", expenseCategoryId);
        createTransaction("EXPENSE", "70.00", "Julho", "2026-07-15", expenseCategoryId);
        createTransaction("INCOME", "3000.00", "Salário julho", "2026-07-01", incomeCategoryId);

        mockMvc.perform(get("/api/transactions")
                        .cookie(user.session())
                        .param("month", "2026-07")
                        .param("type", "EXPENSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].description").value("Julho"));
    }

    @Test
    void searchesByDescription() throws Exception {
        createTransaction("EXPENSE", "120.00", "Farmácia do bairro", "2026-07-10", expenseCategoryId);
        createTransaction("EXPENSE", "60.00", "Padaria", "2026-07-11", expenseCategoryId);

        mockMvc.perform(get("/api/transactions")
                        .cookie(user.session())
                        .param("search", "farmácia"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].description").value("Farmácia do bairro"));
    }

    @Test
    void preventsDeletingCategoryWithTransactions() throws Exception {
        createTransaction("EXPENSE", "10.00", "Café", "2026-07-08", expenseCategoryId);

        mockMvc.perform(delete("/api/categories/{id}", expenseCategoryId)
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CATEGORY_HAS_TRANSACTIONS"));

        assertThat(categoryRepository.findById(expenseCategoryId)).isPresent();
    }

    @Test
    void returnsNotFoundForMissingTransaction() throws Exception {
        mockMvc.perform(get("/api/transactions/{id}", 999_999).cookie(user.session()))
                .andExpect(status().isNotFound());
    }

    private void createTransaction(String type, String amount, String description,
                                   String date, Long categoryId) throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "%s",
                                  "amount": %s,
                                  "description": "%s",
                                  "date": "%s",
                                  "categoryId": %d
                                }
                                """.formatted(type, amount, description, date, categoryId)))
                .andExpect(status().isCreated());
    }
}
