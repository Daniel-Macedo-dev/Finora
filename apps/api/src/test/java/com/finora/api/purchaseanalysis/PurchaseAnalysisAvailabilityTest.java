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
import com.finora.api.commitment.Commitment;
import com.finora.api.commitment.CommitmentCadence;
import com.finora.api.commitment.CommitmentRepository;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

    /**
     * A month the endpoint's history window genuinely covers.
     *
     * <p>{@code GET /analysis} analyses as of today and takes no reference date,
     * so the window is always the three complete months before the run. A fixed
     * calendar month drifts out of it and the scenario silently stops being the
     * one the test names — passing for the wrong reason first, then failing on a
     * date rather than on a change.
     */
    private static final YearMonth IN_WINDOW = YearMonth.now().minusMonths(1);

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private CommitmentRepository commitments;

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

    /** An active monthly expense commitment, settled in the given currency. */
    private void commitment(TestUser owner, String amount, CurrencyCode currency) {
        var category = categoryRepository
                .findByUserIdAndNameIgnoreCaseAndType(owner.id(), "Moradia", CategoryType.EXPENSE)
                .orElseThrow();
        Commitment commitment = new Commitment(owner.id(), "Aluguel", new BigDecimal(amount),
                category, CommitmentCadence.MONTHLY, 10, IN_WINDOW.atDay(10));
        commitment.setCurrency(currency);
        commitments.save(commitment);
    }

    /** A credit card of the given currency, created through the normal API. */
    private long card(TestUser owner, String name, CurrencyCode currency) throws Exception {
        String response = mockMvc.perform(post("/api/credit-cards")
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "brand": "VISA", "creditLimit": 20000,
                                 "closingDay": 10, "dueDay": 17, "currency": "%s"}
                                """.formatted(name, currency.name())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response).get("id").asLong();
    }

    /**
     * A three-installment purchase on that card.
     *
     * <p>Three rather than one on purpose: whichever cycle the first installment
     * lands on, one of the three necessarily falls on next month's invoice, so
     * the next-month burden exists without the test having to reason about the
     * closing day relative to whatever today happens to be.
     */
    private void cardPurchase(TestUser owner, long cardId, String total) throws Exception {
        var category = categoryRepository
                .findByUserIdAndNameIgnoreCaseAndType(owner.id(), "Compras", CategoryType.EXPENSE)
                .orElseThrow();
        mockMvc.perform(post("/api/credit-cards/{id}/purchases", cardId)
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Compra", "merchant": "Loja", "categoryId": %d,
                                 "purchaseDate": "%s", "totalAmount": %s, "installmentCount": 3}
                                """.formatted(category.getId(), java.time.LocalDate.now(), total)))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions analyze(TestUser owner, long itemId)
            throws Exception {
        return mockMvc.perform(get("/api/wishlist/{id}/analysis", itemId)
                .cookie(owner.session()));
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
        income(user, "3000.00", IN_WINDOW, CurrencyCode.BRL);
        income(user, "500.00", IN_WINDOW, CurrencyCode.USD);
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
        income(user, "3000.00", IN_WINDOW, CurrencyCode.USD);
        long item = createItem(user, "Notebook", "5000.00", null);
        addCashOption(user, item, "Loja A", "4800.00");

        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("EXCHANGE_RATE_REQUIRED"))
                .andExpect(jsonPath("$.unavailableReasons[?(@.code == 'INCOME_HISTORY_INCOMPLETE')]")
                        .exists());
    }

    @Test
    void aForeignRecurringCommitmentMakesTheAnalysisUnavailable() throws Exception {
        account(user, "Conta", "10000.00", CurrencyCode.BRL, 0);
        commitment(user, "1200.00", CurrencyCode.BRL);
        commitment(user, "90.00", CurrencyCode.USD);
        long item = createItem(user, "Notebook", "5000.00", null);
        addCashOption(user, item, "Loja A", "4800.00");

        // The installment-pressure rule divides by commitments, so an
        // incomplete commitment total would distort a real conclusion.
        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("EXCHANGE_RATE_REQUIRED"))
                .andExpect(jsonPath("$.unavailableReasons[?(@.code == 'COMMITMENTS_INCOMPLETE')]")
                        .exists())
                .andExpect(jsonPath("$.missingCurrencies").value(
                        org.hamcrest.Matchers.contains("USD")))
                .andExpect(jsonPath("$.recommendation").doesNotExist())
                .andExpect(jsonPath("$.assumptions").doesNotExist());
    }

    @Test
    void baseCurrencyCommitmentsAloneKeepTheAnalysisAvailable() throws Exception {
        account(user, "Conta", "10000.00", CurrencyCode.BRL, 0);
        commitment(user, "1200.00", CurrencyCode.BRL);
        long item = createItem(user, "Notebook", "5000.00", null);
        addCashOption(user, item, "Loja A", "4800.00");

        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.assumptions.monthlyCommitments").value(1200.00));
    }

    @Test
    void aForeignCardBurdenNextMonthMakesTheAnalysisUnavailable() throws Exception {
        account(user, "Conta", "10000.00", CurrencyCode.BRL, 0);
        long foreignCard = card(user, "Cartão USD", CurrencyCode.USD);
        cardPurchase(user, foreignCard, "900.00");
        long item = createItem(user, "Notebook", "5000.00", null);
        addCashOption(user, item, "Loja A", "4800.00");

        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("EXCHANGE_RATE_REQUIRED"))
                .andExpect(
                        jsonPath("$.unavailableReasons[?(@.code == 'CARD_INSTALLMENTS_INCOMPLETE')]")
                                .exists())
                .andExpect(jsonPath("$.recommendation").doesNotExist());
    }

    @Test
    void aBaseCurrencyCardBurdenKeepsTheAnalysisAvailable() throws Exception {
        account(user, "Conta", "10000.00", CurrencyCode.BRL, 0);
        long baseCard = card(user, "Cartão BRL", CurrencyCode.BRL);
        cardPurchase(user, baseCard, "900.00");
        long item = createItem(user, "Notebook", "5000.00", null);
        addCashOption(user, item, "Loja A", "4800.00");

        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("AVAILABLE"))
                // Card obligations are informational; they never block, and the
                // grouped total is the card's own currency, not a mixed sum.
                .andExpect(jsonPath("$.assumptions.nextMonthCardInstallments").exists());
    }

    @Test
    void missingCurrenciesFollowCatalogueOrderAcrossDimensions() throws Exception {
        account(user, "Conta", "10000.00", CurrencyCode.BRL, 0);
        account(user, "Checking JPY", "5000", CurrencyCode.JPY, 1);
        income(user, "500.00", IN_WINDOW, CurrencyCode.EUR);
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

    // ── A base currency that is not BRL ──────────────────────────────────────

    /**
     * Only possible while the ledger is empty, which is exactly when the guard
     * allows it — so these two tests set it before creating anything.
     */
    private void setBaseCurrency(TestUser owner, CurrencyCode currency) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/settings")
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseCurrency": "%s", "minimumCashBuffer": 0,
                                 "maxInstallmentCommitmentRatio": 0.3,
                                 "monthlyOpportunityRate": 0, "budgetWarningThreshold": 0.8}
                                """.formatted(currency.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value(currency.name()));
    }

    @Test
    void anAvailableAnalysisIsExplainedInItsOwnBaseCurrency() throws Exception {
        setBaseCurrency(user, CurrencyCode.USD);
        account(user, "Checking", "10000.00", CurrencyCode.USD, 0);
        long item = createItem(user, "Notebook", "5000.00", "USD");
        addCashOption(user, item, "Store A", "4800.00");

        // The whole analysis is denominated in dollars. Printing those figures
        // with a reais symbol would misstate the amount, not merely look wrong.
        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.baseCurrency").value("USD"))
                .andExpect(jsonPath("$.recommendation.type").value("BUY_CASH"))
                .andExpect(jsonPath("$.recommendation.explanation")
                        .value(org.hamcrest.Matchers.containsString("US$")))
                .andExpect(jsonPath("$.recommendation.explanation")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("R$ "))));
    }

    @Test
    void aZeroDecimalBaseCurrencyIsNeverGivenCents() throws Exception {
        setBaseCurrency(user, CurrencyCode.JPY);
        account(user, "Checking", "100", CurrencyCode.JPY, 0);
        long item = createItem(user, "Notebook", "500000", "JPY");
        addCashOption(user, item, "Store A", "480000");

        // Not affordable, so the WAIT explanation states the gap — in yen, which
        // has no cents at all. An invented ",00" would describe money that does
        // not exist.
        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.recommendation.type").value("WAIT"))
                .andExpect(jsonPath("$.recommendation.explanation")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(",00"))));
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
        income(other, "9999.00", IN_WINDOW, CurrencyCode.USD);

        analyze(user, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.missingCurrencies.length()").value(0));
    }
}
