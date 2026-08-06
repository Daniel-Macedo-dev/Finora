package com.finora.api.purchaseanalysis;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.account.AccountType;
import com.finora.api.category.CategoryType;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * A purchase recommendation that compared incompatible currencies would be the
 * single most actionable wrong output the product could produce.
 *
 * <p>So eligibility is decided first, and when it fails the response carries no
 * BUY, no WAIT, no assumptions and no option analyses — only the factual reason
 * why. The item, its options and its price history stay reachable through their
 * own endpoints throughout.
 */
class PurchaseAnalysisAvailabilityTest extends AbstractIntegrationTest {

    private static final LocalDate REFERENCE = LocalDate.of(2026, 7, 15);

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    private TestUser user;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
    }

    private long createItem(TestUser owner, String name, String targetPrice, String currency)
            throws Exception {
        String money = currency == null ? "" : ", \"currency\": \"%s\"".formatted(currency);
        String response = mockMvc.perform(post("/api/wishlist")
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "targetPrice": %s, "priority": "MEDIUM"%s}
                                """.formatted(name, targetPrice, money)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response).get("id").asLong();
    }

    private void addCashOption(TestUser owner, long itemId, String merchant, String price)
            throws Exception {
        mockMvc.perform(post("/api/wishlist/{id}/options", itemId)
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "%s", "kind": "CASH", "basePrice": %s,
                                 "shipping": 0, "fees": 0}
                                """.formatted(merchant, price)))
                .andExpect(status().isCreated());
    }

    private void account(TestUser owner, String name, String balance, CurrencyCode currency,
            int order) {
        accounts.save(new Account(owner.id(), name, AccountType.CHECKING,
                new BigDecimal(balance), order, currency));
    }

    private void income(TestUser owner, String amount, YearMonth month, CurrencyCode currency) {
        var category = categoryRepository
                .findByUserIdAndNameIgnoreCaseAndType(owner.id(), "Salário", CategoryType.INCOME)
                .orElseThrow();
        Transaction transaction = new Transaction(owner.id(), TransactionType.INCOME,
                new BigDecimal(amount), "Receita", month.atDay(5), category);
        transaction.setCurrency(currency);
        transactions.save(transaction);
    }

    private org.springframework.test.web.servlet.ResultActions analyze(TestUser owner, long itemId)
            throws Exception {
        return mockMvc.perform(get("/api/wishlist/{id}/analysis", itemId)
                .cookie(owner.session())
                .param("referenceDate", REFERENCE.toString()));
    }

    // ── Available: existing behaviour preserved ──────────────────────────────

    @Test
    void aCompleteBaseCurrencyAnalysisRemainsAvailable() throws Exception {
        account(user, "Conta", "10000.00", CurrencyCode.BRL, 0);
        long item = createItem(user, "Notebook", "5000.00", null);
        addCashOption(user, item, "Loja A", "4800.00");

        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.baseCurrency").value("BRL"))
                .andExpect(jsonPath("$.itemCurrency").value("BRL"))
                .andExpect(jsonPath("$.assumptions").exists())
                .andExpect(jsonPath("$.assumptions.availableCash").value(10000.00))
                .andExpect(jsonPath("$.options.length()").value(1))
                .andExpect(jsonPath("$.recommendation.type").value("BUY_CASH"))
                .andExpect(jsonPath("$.unavailableReasons.length()").value(0))
                .andExpect(jsonPath("$.missingCurrencies.length()").value(0));
    }

    @Test
    void aUserWithNoHistoryStillGetsTheCashOnlyAnalysis() throws Exception {
        // No transactions at all: averages are absent, which the engine has
        // always handled by analysing cash and warning about it.
        account(user, "Conta", "10000.00", CurrencyCode.BRL, 0);
        long item = createItem(user, "Notebook", "5000.00", null);
        addCashOption(user, item, "Loja A", "4800.00");

        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.assumptions.historyMonthsUsed").value(0))
                .andExpect(jsonPath("$.assumptions.avgMonthlyIncome").doesNotExist())
                .andExpect(jsonPath("$.recommendation.warnings.length()")
                        .value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void anUnaffordableBaseCurrencyItemStillWaits() throws Exception {
        account(user, "Conta", "100.00", CurrencyCode.BRL, 0);
        long item = createItem(user, "Notebook", "5000.00", null);
        addCashOption(user, item, "Loja A", "4800.00");

        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.recommendation.type").value("WAIT"));
    }

    // ── Foreign item ─────────────────────────────────────────────────────────

    @Test
    void aForeignItemIsUnavailableAndOffersNoConclusion() throws Exception {
        account(user, "Conta", "100000.00", CurrencyCode.BRL, 0);
        long item = createItem(user, "Camera", "1200.00", "USD");
        addCashOption(user, item, "Store A", "1100.00");

        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("EXCHANGE_RATE_REQUIRED"))
                .andExpect(jsonPath("$.baseCurrency").value("BRL"))
                .andExpect(jsonPath("$.itemCurrency").value("USD"))
                .andExpect(jsonPath("$.missingCurrencies[0]").value("USD"))
                .andExpect(jsonPath("$.unavailableReasons[0].code")
                        .value("ITEM_CURRENCY_DIFFERS_FROM_BASE"))
                // No BUY, no WAIT, no numbers that would have needed a rate.
                .andExpect(jsonPath("$.recommendation").doesNotExist())
                .andExpect(jsonPath("$.assumptions").doesNotExist())
                .andExpect(jsonPath("$.options.length()").value(0));
    }

    @Test
    void aForeignItemRemainsReadableThroughItsOwnEndpoints() throws Exception {
        long item = createItem(user, "Camera", "1200.00", "USD");
        addCashOption(user, item, "Store A", "1100.00");

        // The analysis is unavailable, but nothing about the item is hidden.
        analyze(user, item)
                .andExpect(jsonPath("$.availability").value("EXCHANGE_RATE_REQUIRED"));
        mockMvc.perform(get("/api/wishlist/{id}", item).cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.options.length()").value(1));
        mockMvc.perform(get("/api/wishlist/{id}/price-history-summary", item)
                        .cookie(user.session()))
                .andExpect(status().isOk());
    }

    // ── Base item, incomplete context ────────────────────────────────────────

    @Test
    void aForeignAccountBalanceMakesTheAnalysisUnavailable() throws Exception {
        account(user, "Conta", "10000.00", CurrencyCode.BRL, 0);
        account(user, "Checking USD", "1200.00", CurrencyCode.USD, 1);
        long item = createItem(user, "Notebook", "5000.00", null);
        addCashOption(user, item, "Loja A", "4800.00");

        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("EXCHANGE_RATE_REQUIRED"))
                .andExpect(jsonPath("$.itemCurrency").value("BRL"))
                .andExpect(jsonPath("$.unavailableReasons[?(@.code == 'AVAILABLE_CASH_INCOMPLETE')]")
                        .exists())
                .andExpect(jsonPath("$.recommendation").doesNotExist())
                .andExpect(jsonPath("$.assumptions").doesNotExist());
    }

    @Test
    void aZeroForeignAccountDoesNotMakeItUnavailable() throws Exception {
        account(user, "Conta", "10000.00", CurrencyCode.BRL, 0);
        account(user, "Checking USD", "0.00", CurrencyCode.USD, 1);
        long item = createItem(user, "Notebook", "5000.00", null);
        addCashOption(user, item, "Loja A", "4800.00");

        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("AVAILABLE"));
    }

    @Test
    void foreignIncomeHistoryMakesTheAnalysisUnavailable() throws Exception {
        account(user, "Conta", "10000.00", CurrencyCode.BRL, 0);
        income(user, "3000.00", YearMonth.of(2026, 6), CurrencyCode.BRL);
        income(user, "500.00", YearMonth.of(2026, 6), CurrencyCode.USD);
        long item = createItem(user, "Notebook", "5000.00", null);
        addCashOption(user, item, "Loja A", "4800.00");

        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("EXCHANGE_RATE_REQUIRED"))
                .andExpect(jsonPath("$.unavailableReasons[?(@.code == 'INCOME_HISTORY_INCOMPLETE')]")
                        .exists())
                .andExpect(jsonPath("$.recommendation").doesNotExist());
    }

    @Test
    void foreignOnlyHistoryIsUnavailableRatherThanTreatedAsNoHistory() throws Exception {
        // The dangerous case: a USD-only history must not be read as "no
        // history" and silently fall through to the cash-only analysis.
        account(user, "Conta", "10000.00", CurrencyCode.BRL, 0);
        income(user, "3000.00", YearMonth.of(2026, 6), CurrencyCode.USD);
        long item = createItem(user, "Notebook", "5000.00", null);
        addCashOption(user, item, "Loja A", "4800.00");

        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("EXCHANGE_RATE_REQUIRED"))
                .andExpect(jsonPath("$.unavailableReasons[?(@.code == 'INCOME_HISTORY_INCOMPLETE')]")
                        .exists());
    }

    @Test
    void missingCurrenciesFollowCatalogueOrderAcrossDimensions() throws Exception {
        account(user, "Conta", "10000.00", CurrencyCode.BRL, 0);
        account(user, "Checking JPY", "5000", CurrencyCode.JPY, 1);
        income(user, "500.00", YearMonth.of(2026, 6), CurrencyCode.EUR);
        long item = createItem(user, "Camera", "1200.00", "USD");

        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("EXCHANGE_RATE_REQUIRED"))
                .andExpect(jsonPath("$.missingCurrencies").value(
                        org.hamcrest.Matchers.contains("USD", "EUR", "JPY")));
    }

    @Test
    void everyBlockingReasonIsReportedNotJustTheFirst() throws Exception {
        account(user, "Conta", "10000.00", CurrencyCode.BRL, 0);
        account(user, "Checking USD", "500.00", CurrencyCode.USD, 1);
        long item = createItem(user, "Camera", "1200.00", "USD");

        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailableReasons[?(@.code == 'ITEM_CURRENCY_DIFFERS_FROM_BASE')]")
                        .exists())
                .andExpect(jsonPath("$.unavailableReasons[?(@.code == 'AVAILABLE_CASH_INCOMPLETE')]")
                        .exists());
    }

    // ── Isolation ────────────────────────────────────────────────────────────

    @Test
    void anotherUsersItemRemainsNotFound() throws Exception {
        TestUser other = registerUser();
        long theirItem = createItem(other, "Deles", "100.00", "USD");

        analyze(user, theirItem).andExpect(status().isNotFound());
    }

    @Test
    void anotherUsersForeignMoneyNeverMakesMyAnalysisUnavailable() throws Exception {
        account(user, "Conta", "10000.00", CurrencyCode.BRL, 0);
        long item = createItem(user, "Notebook", "5000.00", null);
        addCashOption(user, item, "Loja A", "4800.00");

        TestUser other = registerUser();
        account(other, "Checking USD", "99999.00", CurrencyCode.USD, 0);
        income(other, "9999.00", YearMonth.of(2026, 6), CurrencyCode.USD);

        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.missingCurrencies.length()").value(0));
    }
}
