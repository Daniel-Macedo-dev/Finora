package com.finora.api.settings;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Base currency exists per user, defaults to BRL, and stops being changeable
 * once there is a ledger it would reinterpret.
 */
class BaseCurrencyApiIntegrationTest extends AbstractIntegrationTest {

    @Test
    void newUserDefaultsToBrlAndMayStillChangeIt() throws Exception {
        TestUser user = registerUser();

        mockMvc.perform(get("/api/settings").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value("BRL"))
                .andExpect(jsonPath("$.baseCurrencyChangeable").value(true));
    }

    @Test
    void emptyUserCanChangeBaseCurrency() throws Exception {
        TestUser user = registerUser();

        mockMvc.perform(put("/api/settings")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingsBody("USD", "0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value("USD"));

        mockMvc.perform(get("/api/settings").cookie(user.session()))
                .andExpect(jsonPath("$.baseCurrency").value("USD"));
    }

    @Test
    void userWithFinancialDataCannotChangeBaseCurrency() throws Exception {
        TestUser user = registerUser();
        createAccount(user);

        mockMvc.perform(get("/api/settings").cookie(user.session()))
                .andExpect(jsonPath("$.baseCurrencyChangeable").value(false));

        mockMvc.perform(put("/api/settings")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingsBody("USD", "0")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BASE_CURRENCY_CHANGE_BLOCKED"));

        // The ledger was not reinterpreted.
        mockMvc.perform(get("/api/settings").cookie(user.session()))
                .andExpect(jsonPath("$.baseCurrency").value("BRL"));
    }

    @Test
    void aNonZeroCashBufferAloneBlocksTheChange() throws Exception {
        TestUser user = registerUser();
        mockMvc.perform(put("/api/settings")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingsBody(null, "500.00")))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/settings")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingsBody("EUR", "500.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BASE_CURRENCY_CHANGE_BLOCKED"));
    }

    @Test
    void omittingBaseCurrencyKeepsTheCurrentOneInsteadOfResettingToBrl() throws Exception {
        TestUser user = registerUser();
        mockMvc.perform(put("/api/settings")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingsBody("JPY", "0")))
                .andExpect(jsonPath("$.baseCurrency").value("JPY"));

        // An old client that does not know about baseCurrency at all.
        mockMvc.perform(put("/api/settings")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingsBody(null, "0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value("JPY"));
    }

    @Test
    void resendingTheCurrentBaseCurrencyIsNotTreatedAsAChange() throws Exception {
        TestUser user = registerUser();
        createAccount(user);

        // Data exists, but BRL -> BRL changes nothing, so it must not be blocked.
        mockMvc.perform(put("/api/settings")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingsBody("BRL", "0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value("BRL"));
    }

    @Test
    void unsupportedBaseCurrencyIsRejected() throws Exception {
        TestUser user = registerUser();

        mockMvc.perform(put("/api/settings")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingsBody("BTC", "0")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_UNSUPPORTED"));
    }

    @Test
    void anotherUsersDataDoesNotBlockThisUser() throws Exception {
        TestUser other = registerUser("Outro");
        createAccount(other);

        TestUser user = registerUser();
        mockMvc.perform(put("/api/settings")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingsBody("USD", "0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value("USD"));
    }

    private void createAccount(TestUser user) throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .cookie(user.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Conta", "type": "CHECKING", "openingBalance": 0}
                                """))
                .andExpect(status().isCreated());
    }

    private static String settingsBody(String baseCurrency, String buffer) {
        String currencyField = baseCurrency == null
                ? ""
                : "\"baseCurrency\": \"%s\", ".formatted(baseCurrency);
        return """
                {%s"minimumCashBuffer": %s, "maxInstallmentCommitmentRatio": 0.3,
                 "monthlyOpportunityRate": 0, "budgetWarningThreshold": 0.8}
                """.formatted(currencyField, buffer);
    }
}
