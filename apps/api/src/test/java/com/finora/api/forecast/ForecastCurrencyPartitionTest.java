package com.finora.api.forecast;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A running balance only means something in one denomination.
 *
 * <p>The forecast therefore keeps one independent balance per currency. The
 * failure this prevents is a single projected balance that quietly added
 * dollars into reais — the most actionable wrong number the product could put
 * in front of somebody deciding whether they can afford something.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ForecastCurrencyPartitionTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long expenseCategory;
    private Long incomeCategory;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        expenseCategory = categoryId(user, "Assinaturas", CategoryType.EXPENSE);
        incomeCategory = categoryId(user, "Salário", CategoryType.INCOME);
    }

    private long createAccount(String name, String openingBalance, String currency)
            throws Exception {
        String money = currency == null ? "" : ", \"currency\": \"%s\"".formatted(currency);
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "type": "CHECKING", "openingBalance": %s%s}
                                """.formatted(name, openingBalance, money)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    private void createTransaction(String type, String amount, String description,
            LocalDate date, Long accountId, String currency) throws Exception {
        Long category = "INCOME".equals(type) ? incomeCategory : expenseCategory;
        String account = accountId == null ? "" : ", \"accountId\": %d".formatted(accountId);
        String money = currency == null ? "" : ", \"currency\": \"%s\"".formatted(currency);
        mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "%s", "amount": %s, "description": "%s",
                                 "date": "%s", "categoryId": %d%s%s}
                                """.formatted(type, amount, description, date, category,
                                account, money)))
                .andExpect(status().isCreated());
    }

    // ── Homogeneous datasets keep the familiar scalar contract ───────────────

    @Test
    void aBaseCurrencyOnlyForecastKeepsEveryScalarItAlwaysHad() throws Exception {
        long account = createAccount("Conta", "1000.00", null);
        createTransaction("EXPENSE", "300.00", "Assinatura", today.plusDays(5), account, null);

        mockMvc.perform(get("/api/forecast").cookie(user.session()).param("days", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.baseCurrency").value("BRL"))
                .andExpect(jsonPath("$.openingBalance").value(1000.00))
                .andExpect(jsonPath("$.closingBalance").value(700.00))
                .andExpect(jsonPath("$.byCurrency.length()").value(1))
                .andExpect(jsonPath("$.byCurrency[0].currency").value("BRL"))
                .andExpect(jsonPath("$.byCurrency[0].closingBalance").value(700.00))
                .andExpect(jsonPath("$.months.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void aHomogeneousForeignForecastIsAlsoScalarButNamesItsCurrency() throws Exception {
        long usd = createAccount("Checking USD", "2000.00", "USD");
        createTransaction("EXPENSE", "500.00", "Subscription", today.plusDays(3), usd, "USD");

        mockMvc.perform(get("/api/forecast").cookie(user.session()).param("days", "60"))
                .andExpect(status().isOk())
                // A real, addable USD figure — and it says so.
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.baseCurrency").value("BRL"))
                .andExpect(jsonPath("$.closingBalance").value(1500.00))
                .andExpect(jsonPath("$.byCurrency.length()").value(1))
                .andExpect(jsonPath("$.byCurrency[0].currency").value("USD"));
    }

    // ── Mixed datasets are partitioned, never consolidated ───────────────────

    @Test
    void mixedAccountsProduceIndependentSummariesAndNoMixedScalar() throws Exception {
        long brl = createAccount("Conta", "1000.00", null);
        long usd = createAccount("Checking USD", "2000.00", "USD");
        createTransaction("EXPENSE", "300.00", "Assinatura", today.plusDays(5), brl, null);
        createTransaction("EXPENSE", "500.00", "Subscription", today.plusDays(6), usd, "USD");

        mockMvc.perform(get("/api/forecast").cookie(user.session()).param("days", "60"))
                .andExpect(status().isOk())
                // Every scalar that would have mixed denominations is withheld.
                .andExpect(jsonPath("$.currency").doesNotExist())
                .andExpect(jsonPath("$.openingBalance").doesNotExist())
                .andExpect(jsonPath("$.closingBalance").doesNotExist())
                .andExpect(jsonPath("$.lowestBalance").doesNotExist())
                .andExpect(jsonPath("$.firstNegativeDate").doesNotExist())
                .andExpect(jsonPath("$.projectedIncome").doesNotExist())
                .andExpect(jsonPath("$.projectedAccountExpenses").doesNotExist())
                // Two real balances instead, in catalogue order.
                .andExpect(jsonPath("$.byCurrency.length()").value(2))
                .andExpect(jsonPath("$.byCurrency[0].currency").value("BRL"))
                .andExpect(jsonPath("$.byCurrency[0].openingBalance").value(1000.00))
                .andExpect(jsonPath("$.byCurrency[0].closingBalance").value(700.00))
                .andExpect(jsonPath("$.byCurrency[1].currency").value("USD"))
                .andExpect(jsonPath("$.byCurrency[1].openingBalance").value(2000.00))
                .andExpect(jsonPath("$.byCurrency[1].closingBalance").value(1500.00));
    }

    @Test
    void everyEventCarriesItsAuthoritativeCurrency() throws Exception {
        long brl = createAccount("Conta", "1000.00", null);
        long usd = createAccount("Checking USD", "2000.00", "USD");
        createTransaction("EXPENSE", "300.00", "Assinatura", today.plusDays(5), brl, null);
        createTransaction("EXPENSE", "500.00", "Subscription", today.plusDays(6), usd, "USD");

        mockMvc.perform(get("/api/forecast").cookie(user.session()).param("days", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[?(@.description == 'Assinatura')].currency")
                        .value("BRL"))
                .andExpect(jsonPath("$.events[?(@.description == 'Subscription')].currency")
                        .value("USD"))
                // The balance shown after an event is that currency's balance.
                .andExpect(jsonPath("$.events[?(@.description == 'Assinatura')].balanceAfter")
                        .value(700.00))
                .andExpect(jsonPath("$.events[?(@.description == 'Subscription')].balanceAfter")
                        .value(1500.00));
    }

    @Test
    void aNegativeBalanceInOneCurrencyNeverMarksAnother() throws Exception {
        long brl = createAccount("Conta", "5000.00", null);
        long usd = createAccount("Checking USD", "100.00", "USD");
        createTransaction("EXPENSE", "900.00", "Subscription", today.plusDays(4), usd, "USD");
        createTransaction("EXPENSE", "100.00", "Assinatura", today.plusDays(5), brl, null);

        mockMvc.perform(get("/api/forecast").cookie(user.session()).param("days", "60"))
                .andExpect(status().isOk())
                // USD goes negative; BRL is comfortably positive and must not
                // inherit the warning.
                .andExpect(jsonPath("$.byCurrency[0].currency").value("BRL"))
                .andExpect(jsonPath("$.byCurrency[0].firstNegativeDate").doesNotExist())
                .andExpect(jsonPath("$.byCurrency[0].closingBalance").value(4900.00))
                .andExpect(jsonPath("$.byCurrency[1].currency").value("USD"))
                .andExpect(jsonPath("$.byCurrency[1].firstNegativeDate")
                        .value(today.plusDays(4).toString()))
                .andExpect(jsonPath("$.byCurrency[1].lowestBalance").value(-800.00))
                .andExpect(jsonPath("$.firstNegativeDate").doesNotExist());
    }

    // ── Account filter stays native and scalar ───────────────────────────────

    @Test
    void anAccountFilteredForeignForecastIsNativeAndComplete() throws Exception {
        long brl = createAccount("Conta", "1000.00", null);
        long usd = createAccount("Checking USD", "2000.00", "USD");
        createTransaction("EXPENSE", "300.00", "Assinatura", today.plusDays(5), brl, null);
        createTransaction("EXPENSE", "500.00", "Subscription", today.plusDays(6), usd, "USD");

        mockMvc.perform(get("/api/forecast").cookie(user.session())
                        .param("days", "60").param("accountId", String.valueOf(usd)))
                .andExpect(status().isOk())
                // Filtered to one account, so it is homogeneous by construction
                // and every scalar survives.
                .andExpect(jsonPath("$.accountId").value(usd))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.openingBalance").value(2000.00))
                .andExpect(jsonPath("$.closingBalance").value(1500.00))
                .andExpect(jsonPath("$.byCurrency.length()").value(1))
                // The BRL account's events cannot enter this series.
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].currency").value("USD"));
    }

    @Test
    void anAccountFilteredBaseCurrencyForecastIsUnchanged() throws Exception {
        long brl = createAccount("Conta", "1000.00", null);
        createAccount("Checking USD", "2000.00", "USD");
        createTransaction("EXPENSE", "300.00", "Assinatura", today.plusDays(5), brl, null);

        mockMvc.perform(get("/api/forecast").cookie(user.session())
                        .param("days", "60").param("accountId", String.valueOf(brl)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.openingBalance").value(1000.00))
                .andExpect(jsonPath("$.closingBalance").value(700.00));
    }

    // ── Unassigned events ────────────────────────────────────────────────────

    @Test
    void aForeignAccountlessEventStaysVisibleAndMovesNoBalance() throws Exception {
        long brl = createAccount("Conta", "1000.00", null);
        createTransaction("EXPENSE", "300.00", "Assinatura", today.plusDays(5), brl, null);
        // No account settles this: nothing can be debited for it.
        createTransaction("EXPENSE", "50.00", "App", today.plusDays(6), null, "USD");

        mockMvc.perform(get("/api/forecast").cookie(user.session()).param("days", "60"))
                .andExpect(status().isOk())
                // Disclosed, in its own currency, and marked unassigned.
                .andExpect(jsonPath("$.events[?(@.description == 'App')].currency").value("USD"))
                .andExpect(jsonPath("$.events[?(@.description == 'App')].unassigned").value(true))
                // Ordered by date, so the unassigned USD event is the second
                // one; it carries no balance because nothing settles it.
                .andExpect(jsonPath("$.events[1].description").value("App"))
                .andExpect(jsonPath("$.events[1].balanceAfter").doesNotExist())
                // It is grouped under USD without ever moving a balance there.
                .andExpect(jsonPath("$.byCurrency[1].currency").value("USD"))
                .andExpect(jsonPath("$.byCurrency[1].unassignedOutflows").value(50.00))
                .andExpect(jsonPath("$.byCurrency[1].closingBalance").value(0.00))
                .andExpect(jsonPath("$.byCurrency[1].assignedEventCount").value(0))
                // And the BRL balance is untouched by it.
                .andExpect(jsonPath("$.byCurrency[0].closingBalance").value(700.00));
    }

    @Test
    void anUnassignedEventIsExcludedFromAnAccountFilteredForecast() throws Exception {
        long brl = createAccount("Conta", "1000.00", null);
        createTransaction("EXPENSE", "50.00", "App", today.plusDays(6), null, "USD");

        mockMvc.perform(get("/api/forecast").cookie(user.session())
                        .param("days", "60").param("accountId", String.valueOf(brl)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(0))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.closingBalance").value(1000.00));
    }

    // ── Isolation ────────────────────────────────────────────────────────────

    @Test
    void anotherUsersForeignForecastNeverLeaksIn() throws Exception {
        long brl = createAccount("Conta", "1000.00", null);
        createTransaction("EXPENSE", "300.00", "Assinatura", today.plusDays(5), brl, null);

        TestUser other = registerUser();
        mockMvc.perform(post("/api/accounts")
                        .cookie(other.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Dele", "type": "CHECKING",
                                 "openingBalance": 999999.00, "currency": "USD"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/forecast").cookie(user.session()).param("days", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byCurrency.length()").value(1))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.closingBalance").value(700.00));
    }
}
