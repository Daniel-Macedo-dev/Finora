package com.finora.api.commitment;

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

/**
 * Goals hold one currency; commitments take theirs from the destination they
 * will actually settle in.
 */
class GoalAndCommitmentCurrencyTest extends AbstractIntegrationTest {

    // --- goals -----------------------------------------------------------

    @Test
    void goalWithoutCurrencyUsesBaseCurrency() throws Exception {
        TestUser user = registerUser();

        createGoal(user, "Viagem", null, "10000.00")
                .andExpect(jsonPath("$.currency").value("BRL"));
    }

    @Test
    void goalKeepsAnExplicitForeignCurrency() throws Exception {
        TestUser user = registerUser();

        createGoal(user, "Intercâmbio", "USD", "10000.00")
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void contributionStaysInTheGoalCurrency() throws Exception {
        TestUser user = registerUser();
        long goal = idOf(createGoal(user, "Intercâmbio", "USD", "10000.00").andReturn());

        mockMvc.perform(post("/api/goals/{id}/contributions", goal)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 250.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.currentAmount").value(250.00));
    }

    @Test
    void goalCurrencyCannotBeChanged() throws Exception {
        TestUser user = registerUser();
        long goal = idOf(createGoal(user, "Intercâmbio", "USD", "10000.00").andReturn());

        mockMvc.perform(put("/api/goals/{id}", goal)
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goalBody("Intercâmbio", "BRL", "10000.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_IMMUTABLE"));
    }

    @Test
    void jpyGoalRejectsFractionalTarget() throws Exception {
        TestUser user = registerUser();

        mockMvc.perform(post("/api/goals")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goalBody("Meta JPY", "JPY", "10000.50")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_FRACTION_INVALID"));
    }

    // --- commitments -----------------------------------------------------

    @Test
    void projectionOnlyCommitmentMayNameAnySupportedCurrency() throws Exception {
        TestUser user = registerUser();

        createCommitment(user, "Assinatura", "EUR", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void projectionOnlyCommitmentWithoutCurrencyUsesBaseCurrency() throws Exception {
        TestUser user = registerUser();

        createCommitment(user, "Assinatura", null, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("BRL"));
    }

    @Test
    void accountTargetImposesItsOwnCurrency() throws Exception {
        TestUser user = registerUser();
        long usd = createAccount(user, "Conta USD", "USD");

        // No currency stated: it is derived from the destination, not the base.
        createCommitment(user, "Aluguel", null, usd)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void commitmentContradictingItsAccountTargetIsRejected() throws Exception {
        TestUser user = registerUser();
        long usd = createAccount(user, "Conta USD", "USD");

        createCommitment(user, "Aluguel", "BRL", usd)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COMMITMENT_CURRENCY_MISMATCH"));
    }

    @Test
    void upcomingWindowNeverAddsDifferentCurrencies() throws Exception {
        TestUser user = registerUser();
        createCommitment(user, "Assinatura BRL", "BRL", null).andExpect(status().isCreated());
        createCommitment(user, "Assinatura EUR", "EUR", null).andExpect(status().isCreated());

        mockMvc.perform(get("/api/commitments/upcoming")
                        .cookie(user.session())
                        .param("from", "2026-07-01")
                        .param("months", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.complete").value(false))
                .andExpect(jsonPath("$.totals.total").doesNotExist())
                .andExpect(jsonPath("$.totals.unconvertedCurrencies[0]").value("EUR"))
                .andExpect(jsonPath("$.totals.byCurrency.length()").value(2));
    }

    @Test
    void anotherUsersAccountCannotBecomeACommitmentTarget() throws Exception {
        TestUser owner = registerUser("Dono");
        long foreign = createAccount(owner, "Conta do dono", "USD");

        TestUser user = registerUser("Outro");
        createCommitment(user, "Aluguel", null, foreign)
                .andExpect(status().isNotFound());
    }

    // --- helpers ---------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions createGoal(
            TestUser user, String name, String currency, String target) throws Exception {
        return mockMvc.perform(post("/api/goals")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goalBody(name, currency, target)))
                .andExpect(status().isCreated());
    }

    private static String goalBody(String name, String currency, String target) {
        String currencyField = currency == null ? "" : "\"currency\": \"%s\", ".formatted(currency);
        return """
                {"name": "%s", %s"targetAmount": %s}
                """.formatted(name, currencyField, target);
    }

    private org.springframework.test.web.servlet.ResultActions createCommitment(
            TestUser user, String description, String currency, Long accountId) throws Exception {
        String currencyField = currency == null ? "" : "\"currency\": \"%s\", ".formatted(currency);
        String targetFields = accountId == null
                ? "\"targetKind\": \"PROJECTION_ONLY\", "
                : "\"targetKind\": \"ACCOUNT_TRANSACTION\", \"accountId\": %d, ".formatted(accountId);
        return mockMvc.perform(post("/api/commitments")
                .cookie(user.session())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"description": "%s", "amount": 39.90, "categoryId": %d,
                         "cadence": "MONTHLY", "dueDay": 10, "startDate": "2026-07-01",
                         %s%s"paymentMethod": "PIX"}
                        """.formatted(description,
                        categoryId(user, "Assinaturas", CategoryType.EXPENSE),
                        currencyField, targetFields)));
    }

    private long createAccount(TestUser user, String name, String currency) throws Exception {
        return idOf(mockMvc.perform(post("/api/accounts")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "type": "CHECKING", "currency": "%s",
                                 "openingBalance": 1000.00}
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
