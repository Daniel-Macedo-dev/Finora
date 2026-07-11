package com.finora.api.creditcard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class CreditCardApiIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
    }

    private long createCard(String name, String limit, int closingDay, int dueDay) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "brand": "VISA",
                                  "creditLimit": %s,
                                  "closingDay": %d,
                                  "dueDay": %d
                                }
                                """.formatted(name, limit, closingDay, dueDay)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    @Test
    void createsCardWithFullLimitAvailableAndCurrentCycle() throws Exception {
        mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Nubank",
                                  "issuer": "Nu Pagamentos",
                                  "brand": "MASTERCARD",
                                  "lastFourDigits": "4242",
                                  "creditLimit": 5000.00,
                                  "closingDay": 10,
                                  "dueDay": 17
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Nubank"))
                .andExpect(jsonPath("$.lastFourDigits").value("4242"))
                .andExpect(jsonPath("$.limit.creditLimit").value(5000.00))
                .andExpect(jsonPath("$.limit.usedLimit").value(0.00))
                .andExpect(jsonPath("$.limit.availableLimit").value(5000.00))
                .andExpect(jsonPath("$.limit.utilizationPercent").value(0.0))
                .andExpect(jsonPath("$.currentCycle.referenceMonth").isString())
                .andExpect(jsonPath("$.currentCycle.invoiceId").isEmpty())
                .andExpect(jsonPath("$.nextDueInvoice").isEmpty())
                .andExpect(jsonPath("$.archived").value(false));
    }

    @Test
    void rejectsDuplicateNameCaseInsensitive() throws Exception {
        createCard("Cartão Principal", "1000.00", 10, 17);
        mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "cartão principal", "brand": "VISA",
                                 "creditLimit": 500.00, "closingDay": 1, "dueDay": 10}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_NAME_TAKEN"));
    }

    @Test
    void rejectsInvalidDaysAndDigits() throws Exception {
        mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Inválido", "brand": "VISA", "lastFourDigits": "12a4",
                                 "creditLimit": -10, "closingDay": 0, "dueDay": 32}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void rejectsArchivedAccountAsDefaultPaymentAccount() throws Exception {
        MvcResult account = mockMvc.perform(post("/api/accounts")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Conta Velha", "type": "CHECKING", "openingBalance": 0}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long accountId = objectMapper.readTree(
                account.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("id").asLong();
        mockMvc.perform(put("/api/accounts/" + accountId)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Conta Velha", "type": "CHECKING",
                                 "openingBalance": 0, "archived": true}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/credit-cards")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Cartão X", "brand": "VISA", "creditLimit": 100,
                                 "closingDay": 1, "dueDay": 10,
                                 "defaultPaymentAccountId": %d}
                                """.formatted(accountId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ARCHIVED"));
    }

    @Test
    void archivesCardWithoutBalanceAndUnarchives() throws Exception {
        long cardId = createCard("Cartão Livre", "1000.00", 10, 17);
        mockMvc.perform(post("/api/credit-cards/%d/archive".formatted(cardId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
        mockMvc.perform(post("/api/credit-cards/%d/unarchive".formatted(cardId))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false));
    }

    @Test
    void deletesOnlyCardsWithoutHistory() throws Exception {
        long cardId = createCard("Cartão Temporário", "1000.00", 10, 17);
        mockMvc.perform(delete("/api/credit-cards/" + cardId)
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/credit-cards/" + cardId).cookie(user.session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherUserCannotSeeTouchOrInferTheCard() throws Exception {
        long cardId = createCard("Cartão de A", "3000.00", 10, 17);
        TestUser intruder = registerUser("Intruso");

        mockMvc.perform(get("/api/credit-cards").cookie(intruder.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/credit-cards/" + cardId).cookie(intruder.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/credit-cards/" + cardId)
                        .cookie(intruder.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Roubado", "brand": "VISA", "creditLimit": 1,
                                 "closingDay": 1, "dueDay": 2}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/credit-cards/%d/archive".formatted(cardId))
                        .cookie(intruder.session()).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/credit-cards/" + cardId)
                        .cookie(intruder.session()).with(csrf()))
                .andExpect(status().isNotFound());
    }
}
