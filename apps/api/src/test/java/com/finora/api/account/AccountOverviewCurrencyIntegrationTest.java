package com.finora.api.account;

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

/**
 * The account overview may never present one currency's money as another's.
 *
 * <p>The read model answers two separate questions — is this set homogeneous
 * (so a native total exists), and is it entirely in the user's base currency
 * (so a base total exists) — and a mixed portfolio answers no to both.
 */
class AccountOverviewCurrencyIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
    }

    private long createAccount(TestUser owner, String body) throws Exception {
        String response = mockMvc.perform(post("/api/accounts")
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response).get("id").asLong();
    }

    @Test
    void aBrlOnlyUserStillGetsOneConsolidatedTotal() throws Exception {
        createAccount(user, """
                {"name": "Conta Corrente", "type": "CHECKING", "openingBalance": 5000.00}
                """);
        createAccount(user, """
                {"name": "Carteira", "type": "CASH", "openingBalance": 3000.00}
                """);

        mockMvc.perform(get("/api/accounts/overview").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.length()").value(2))
                .andExpect(jsonPath("$.totals.baseCurrency").value("BRL"))
                .andExpect(jsonPath("$.totals.baseComplete").value(true))
                .andExpect(jsonPath("$.totals.baseTotal").value(8000.00))
                .andExpect(jsonPath("$.totals.homogeneous").value(true))
                .andExpect(jsonPath("$.totals.homogeneousCurrency").value("BRL"))
                .andExpect(jsonPath("$.totals.unconvertedCurrencies.length()").value(0));
    }

    @Test
    void aHomogeneousForeignUserGetsANativeTotalButNoBaseTotal() throws Exception {
        createAccount(user, """
                {"name": "Checking", "type": "CHECKING", "openingBalance": 1200.00,
                 "currency": "USD"}
                """);
        createAccount(user, """
                {"name": "Savings", "type": "SAVINGS", "openingBalance": 800.00,
                 "currency": "USD"}
                """);

        mockMvc.perform(get("/api/accounts/overview").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.homogeneous").value(true))
                .andExpect(jsonPath("$.totals.homogeneousCurrency").value("USD"))
                .andExpect(jsonPath("$.totals.nativeTotal").value(2000.00))
                // A real USD figure, and emphatically not a BRL conclusion.
                .andExpect(jsonPath("$.totals.baseComplete").value(false))
                .andExpect(jsonPath("$.totals.baseTotal").doesNotExist())
                .andExpect(jsonPath("$.totals.unconvertedCurrencies[0]").value("USD"));
    }

    @Test
    void aMixedUserGetsGroupedTotalsAndNoConsolidatedScalar() throws Exception {
        createAccount(user, """
                {"name": "Conta Corrente", "type": "CHECKING", "openingBalance": 8000.00}
                """);
        createAccount(user, """
                {"name": "Checking USD", "type": "CHECKING", "openingBalance": 1200.00,
                 "currency": "USD"}
                """);

        mockMvc.perform(get("/api/accounts/overview").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.byCurrency.length()").value(2))
                .andExpect(jsonPath("$.totals.byCurrency[0].currency").value("BRL"))
                .andExpect(jsonPath("$.totals.byCurrency[0].amount").value(8000.00))
                .andExpect(jsonPath("$.totals.byCurrency[1].currency").value("USD"))
                .andExpect(jsonPath("$.totals.byCurrency[1].amount").value(1200.00))
                .andExpect(jsonPath("$.totals.homogeneous").value(false))
                .andExpect(jsonPath("$.totals.nativeTotal").doesNotExist())
                .andExpect(jsonPath("$.totals.baseComplete").value(false))
                .andExpect(jsonPath("$.totals.baseTotal").doesNotExist());
    }

    @Test
    void anEmptyForeignAccountDoesNotBlockTheBaseTotal() throws Exception {
        createAccount(user, """
                {"name": "Conta Corrente", "type": "CHECKING", "openingBalance": 8000.00}
                """);
        // Zero converts to zero under any rate, so this genuinely leaves
        // nothing to convert.
        createAccount(user, """
                {"name": "Checking USD", "type": "CHECKING", "openingBalance": 0,
                 "currency": "USD"}
                """);

        mockMvc.perform(get("/api/accounts/overview").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.length()").value(2))
                .andExpect(jsonPath("$.totals.baseComplete").value(true))
                .andExpect(jsonPath("$.totals.baseTotal").value(8000.00))
                .andExpect(jsonPath("$.totals.unconvertedCurrencies.length()").value(0));
    }

    @Test
    void archivedBalancesAreReportedSeparatelyFromAvailableCash() throws Exception {
        long archived = createAccount(user, """
                {"name": "Antiga", "type": "CHECKING", "openingBalance": 500.00}
                """);
        createAccount(user, """
                {"name": "Atual", "type": "CHECKING", "openingBalance": 2000.00}
                """);
        mockMvc.perform(put("/api/accounts/{id}", archived)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Antiga", "type": "CHECKING", "openingBalance": 500.00,
                                 "archived": true}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/accounts/overview").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.length()").value(2))
                .andExpect(jsonPath("$.totals.baseTotal").value(2000.00))
                .andExpect(jsonPath("$.archivedTotals.baseTotal").value(500.00));
    }

    @Test
    void balancesReflectMovementsOfTheOwningAccountOnly() throws Exception {
        long brl = createAccount(user, """
                {"name": "Conta Corrente", "type": "CHECKING", "openingBalance": 1000.00}
                """);
        createAccount(user, """
                {"name": "Checking USD", "type": "CHECKING", "openingBalance": 100.00,
                 "currency": "USD"}
                """);
        Long incomeCategory = categoryId(user, "Salário", com.finora.api.category.CategoryType.INCOME);
        mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "INCOME", "amount": 250.00, "description": "Salário",
                                 "date": "2026-07-01", "categoryId": %d, "accountId": %d}
                                """.formatted(incomeCategory, brl)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/accounts/overview").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.byCurrency[0].amount").value(1250.00))
                .andExpect(jsonPath("$.totals.byCurrency[1].amount").value(100.00));
    }

    @Test
    void anotherUsersAccountsNeverAppearInTheOverview() throws Exception {
        createAccount(user, """
                {"name": "Minha", "type": "CHECKING", "openingBalance": 1000.00}
                """);
        TestUser other = registerUser();
        createAccount(other, """
                {"name": "Dele", "type": "CHECKING", "openingBalance": 999999.00,
                 "currency": "USD"}
                """);

        mockMvc.perform(get("/api/accounts/overview").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.length()").value(1))
                .andExpect(jsonPath("$.totals.baseComplete").value(true))
                .andExpect(jsonPath("$.totals.baseTotal").value(1000.00))
                .andExpect(jsonPath("$.totals.unconvertedCurrencies.length()").value(0));
    }
}
