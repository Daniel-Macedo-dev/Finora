package com.finora.api.insight;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.time.YearMonth;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Insights that would need an exchange rate are withheld, not approximated.
 *
 * <p>The distinction this suite exists to protect is between two silences. A
 * rule that had nothing to say — no previous month, no history, no goal — is the
 * ordinary case and must never be reported as a currency problem. A rule that
 * had real input and could not evaluate it because the operands were in
 * different denominations is reported, once, in bounded coverage metadata.
 *
 * <p>Resource-native rules sit on the other side of that line entirely: an
 * overdue dollar invoice is true in dollars whatever else the ledger holds, and
 * no amount of mixed currency elsewhere may hide it or relabel it as reais.
 */
class InsightCurrencyCoverageTest extends AbstractIntegrationTest {

    /**
     * The month the insights are generated for.
     *
     * <p>Relative to today rather than fixed: the endpoint takes a month but
     * builds its financial context as of the real date, so a hard-coded month
     * would drift out of the context's own history window and stop testing the
     * scenario it names.
     */
    private static final YearMonth MONTH = YearMonth.now();
    private static final YearMonth PREVIOUS = MONTH.minusMonths(1);

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    private TestUser user;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private ResultActions insights(TestUser owner) throws Exception {
        return mockMvc.perform(get("/api/insights")
                .cookie(owner.session())
                .param("month", MONTH.toString()));
    }

    private void account(TestUser owner, String name, String balance, CurrencyCode currency) {
        accounts.save(new Account(owner.id(), name, AccountType.CHECKING,
                new BigDecimal(balance), 0, currency));
    }

    private void expense(TestUser owner, String amount, YearMonth month, CurrencyCode currency,
            String categoryName) {
        var category = categoryRepository
                .findByUserIdAndNameIgnoreCaseAndType(owner.id(), categoryName, CategoryType.EXPENSE)
                .orElseThrow();
        Transaction transaction = new Transaction(owner.id(), TransactionType.EXPENSE,
                new BigDecimal(amount), "Despesa", month.atDay(10), category);
        transaction.setCurrency(currency);
        transactions.save(transaction);
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

    /** Income across the whole three-month context window, so history exists. */
    private void incomeHistory(TestUser owner, String amount, CurrencyCode currency) {
        for (int back = 1; back <= 3; back++) {
            income(owner, amount, MONTH.minusMonths(back), currency);
        }
    }

    private void commitment(TestUser owner, String amount, CurrencyCode currency) throws Exception {
        long category = categoryId(owner, "Moradia", CategoryType.EXPENSE);
        mockMvc.perform(post("/api/commitments")
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Aluguel", "amount": %s, "categoryId": %d,
                                 "cadence": "MONTHLY", "dueDay": 10, "startDate": "2025-01-10",
                                 "currency": "%s"}
                                """.formatted(amount, category, currency.name())))
                .andExpect(status().isCreated());
    }

    private long card(TestUser owner, String name, String limit, CurrencyCode currency)
            throws Exception {
        String response = mockMvc.perform(post("/api/credit-cards")
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "brand": "VISA", "creditLimit": %s,
                                 "closingDay": 10, "dueDay": 17, "currency": "%s"}
                                """.formatted(name, limit, currency.name())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response).get("id").asLong();
    }

    /** A purchase old enough that its first invoice is already overdue. */
    private void overduePurchase(TestUser owner, long cardId, String total) throws Exception {
        long category = categoryId(owner, "Compras", CategoryType.EXPENSE);
        mockMvc.perform(post("/api/credit-cards/{id}/purchases", cardId)
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Compra", "merchant": "Loja", "categoryId": %d,
                                 "purchaseDate": "%s", "totalAmount": %s, "installmentCount": 1}
                                """.formatted(category, MONTH.minusMonths(3).atDay(1), total)))
                .andExpect(status().isCreated());
    }

    /** A three-installment purchase, so one lands on next month's invoice. */
    private void spreadPurchase(TestUser owner, long cardId, String total) throws Exception {
        long category = categoryId(owner, "Compras", CategoryType.EXPENSE);
        mockMvc.perform(post("/api/credit-cards/{id}/purchases", cardId)
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Parcelada", "merchant": "Loja", "categoryId": %d,
                                 "purchaseDate": "%s", "totalAmount": %s, "installmentCount": 3}
                                """.formatted(category, java.time.LocalDate.now(), total)))
                .andExpect(status().isCreated());
    }

    private void goal(TestUser owner, String name, String target, String currency)
            throws Exception {
        String money = currency == null ? "" : ", \"currency\": \"%s\"".formatted(currency);
        mockMvc.perform(post("/api/goals")
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "targetAmount": %s, "currentAmount": 0,
                                 "targetDate": "%s"%s}
                                """.formatted(name, target, MONTH.plusMonths(1).atDay(28), money)))
                .andExpect(status().isCreated());
    }

    private void wishlistItem(TestUser owner, String name, String price, String currency)
            throws Exception {
        String money = currency == null ? "" : ", \"currency\": \"%s\"".formatted(currency);
        String response = mockMvc.perform(post("/api/wishlist")
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "priority": "MEDIUM", "status": "MONITORING"%s}
                                """.formatted(name, money)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long itemId = objectMapper.readTree(response).get("id").asLong();
        mockMvc.perform(post("/api/wishlist/{id}/options", itemId)
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant": "Loja", "kind": "CASH", "basePrice": %s,
                                 "shipping": 0, "fees": 0}
                                """.formatted(price)))
                .andExpect(status().isCreated());
    }

    private void setBaseCurrency(TestUser owner, CurrencyCode currency) throws Exception {
        mockMvc.perform(put("/api/settings")
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseCurrency": "%s", "minimumCashBuffer": 0,
                                 "maxInstallmentCommitmentRatio": 0.3,
                                 "monthlyOpportunityRate": 0, "budgetWarningThreshold": 0.8}
                                """.formatted(currency.name())))
                .andExpect(status().isOk());
    }

    // ── Expense increase ─────────────────────────────────────────────────────

    @Test
    void baseCurrencyExpenseGrowthStillFires() throws Exception {
        expense(user, "1000.00", PREVIOUS, CurrencyCode.BRL, "Alimentação");
        expense(user, "2000.00", MONTH, CurrencyCode.BRL, "Alimentação");

        insights(user)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value("BRL"))
                .andExpect(jsonPath("$.insights[?(@.type == 'EXPENSE_INCREASE')]").exists())
                .andExpect(jsonPath("$.insights[?(@.type == 'EXPENSE_INCREASE')].currency")
                        .value(Matchers.contains("BRL")))
                .andExpect(jsonPath("$.aggregateCoverage.complete").value(true))
                .andExpect(jsonPath("$.aggregateCoverage.unavailableRules.length()").value(0));
    }

    @Test
    void aSmallBaseCurrencyChangeDoesNotFire() throws Exception {
        expense(user, "1000.00", PREVIOUS, CurrencyCode.BRL, "Alimentação");
        expense(user, "1050.00", MONTH, CurrencyCode.BRL, "Alimentação");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'EXPENSE_INCREASE')]").doesNotExist())
                .andExpect(jsonPath("$.aggregateCoverage.complete").value(true));
    }

    @Test
    void noPreviousMonthIsAnAbsenceNotACurrencyProblem() throws Exception {
        expense(user, "2000.00", MONTH, CurrencyCode.BRL, "Alimentação");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'EXPENSE_INCREASE')]").doesNotExist())
                .andExpect(jsonPath("$.aggregateCoverage.complete").value(true))
                .andExpect(jsonPath("$.aggregateCoverage.missingCurrencies.length()").value(0));
    }

    @Test
    void mixedCurrentExpensesSuppressGrowthAndAreReported() throws Exception {
        expense(user, "1000.00", PREVIOUS, CurrencyCode.BRL, "Alimentação");
        expense(user, "2000.00", MONTH, CurrencyCode.BRL, "Alimentação");
        expense(user, "300.00", MONTH, CurrencyCode.USD, "Lazer");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'EXPENSE_INCREASE')]").doesNotExist())
                .andExpect(jsonPath("$.aggregateCoverage.complete").value(false))
                .andExpect(jsonPath("$.aggregateCoverage.unavailableRules")
                        .value(Matchers.hasItem("EXPENSE_INCREASE")))
                .andExpect(jsonPath("$.aggregateCoverage.missingCurrencies")
                        .value(Matchers.contains("USD")));
    }

    @Test
    void mixedPreviousExpensesSuppressGrowth() throws Exception {
        expense(user, "1000.00", PREVIOUS, CurrencyCode.BRL, "Alimentação");
        expense(user, "200.00", PREVIOUS, CurrencyCode.EUR, "Lazer");
        expense(user, "2000.00", MONTH, CurrencyCode.BRL, "Alimentação");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'EXPENSE_INCREASE')]").doesNotExist())
                .andExpect(jsonPath("$.aggregateCoverage.unavailableRules")
                        .value(Matchers.hasItem("EXPENSE_INCREASE")))
                .andExpect(jsonPath("$.aggregateCoverage.missingCurrencies")
                        .value(Matchers.contains("EUR")));
    }

    @Test
    void anotherUsersForeignExpenseNeitherSuppressesNorAppearsInCoverage() throws Exception {
        expense(user, "1000.00", PREVIOUS, CurrencyCode.BRL, "Alimentação");
        expense(user, "2000.00", MONTH, CurrencyCode.BRL, "Alimentação");

        TestUser other = registerUser();
        expense(other, "9000.00", MONTH, CurrencyCode.USD, "Lazer");
        account(other, "Conta USD", "50000.00", CurrencyCode.USD);

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'EXPENSE_INCREASE')]").exists())
                .andExpect(jsonPath("$.aggregateCoverage.complete").value(true))
                .andExpect(jsonPath("$.aggregateCoverage.missingCurrencies.length()").value(0));
    }

    // ── Dominant category ────────────────────────────────────────────────────

    @Test
    void baseCurrencyCategoryDominanceStillFires() throws Exception {
        expense(user, "1000.00", MONTH, CurrencyCode.BRL, "Alimentação");
        expense(user, "200.00", MONTH, CurrencyCode.BRL, "Lazer");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'CATEGORY_DOMINANT')]").exists())
                .andExpect(jsonPath("$.insights[?(@.type == 'CATEGORY_DOMINANT')].currency")
                        .value(Matchers.contains("BRL")))
                .andExpect(jsonPath("$.aggregateCoverage.complete").value(true));
    }

    @Test
    void anEvenlySpreadMonthHasNoDominantCategory() throws Exception {
        expense(user, "300.00", MONTH, CurrencyCode.BRL, "Alimentação");
        expense(user, "300.00", MONTH, CurrencyCode.BRL, "Lazer");
        expense(user, "300.00", MONTH, CurrencyCode.BRL, "Transporte");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'CATEGORY_DOMINANT')]").doesNotExist())
                .andExpect(jsonPath("$.aggregateCoverage.complete").value(true));
    }

    @Test
    void foreignSpendingSuppressesDominanceRatherThanInflatingIt() throws Exception {
        // Without the foreign row, Alimentação would be 100% of the month. The
        // base-only denominator is exactly the trap: it would report dominance
        // over spending it cannot see.
        expense(user, "1000.00", MONTH, CurrencyCode.BRL, "Alimentação");
        expense(user, "5000.00", MONTH, CurrencyCode.USD, "Lazer");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'CATEGORY_DOMINANT')]").doesNotExist())
                .andExpect(jsonPath("$.aggregateCoverage.unavailableRules")
                        .value(Matchers.hasItem("CATEGORY_DOMINANT")))
                .andExpect(jsonPath("$.aggregateCoverage.missingCurrencies")
                        .value(Matchers.contains("USD")));
    }

    // ── Budgets ──────────────────────────────────────────────────────────────

    private void budget(TestUser owner, String category, String limit) throws Exception {
        mockMvc.perform(post("/api/budgets")
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"month": "%s", "categoryId": %d, "limitAmount": %s}
                                """.formatted(MONTH, categoryId(owner, category, CategoryType.EXPENSE),
                                limit)))
                .andExpect(status().isCreated());
    }

    @Test
    void anExceededBaseBudgetStillEmitsItsInsightInBaseCurrency() throws Exception {
        budget(user, "Alimentação", "500.00");
        expense(user, "900.00", MONTH, CurrencyCode.BRL, "Alimentação");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'BUDGET_EXCEEDED')]").exists())
                .andExpect(jsonPath("$.insights[?(@.type == 'BUDGET_EXCEEDED')].currency")
                        .value(Matchers.contains("BRL")));
    }

    @Test
    void anIncompleteBudgetEmitsNoLimitConclusionAndIsReported() throws Exception {
        budget(user, "Alimentação", "500.00");
        expense(user, "900.00", MONTH, CurrencyCode.BRL, "Alimentação");
        expense(user, "100.00", MONTH, CurrencyCode.USD, "Alimentação");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'BUDGET_EXCEEDED')]").doesNotExist())
                .andExpect(jsonPath("$.insights[?(@.type == 'BUDGET_NEAR_LIMIT')]").doesNotExist())
                .andExpect(jsonPath("$.aggregateCoverage.unavailableRules")
                        .value(Matchers.hasItem("BUDGET_STATUS")))
                .andExpect(jsonPath("$.aggregateCoverage.missingCurrencies")
                        .value(Matchers.hasItem("USD")));
    }

    // ── Native invoice and card rules ────────────────────────────────────────

    @Test
    void anOverdueBaseInvoiceStillAlerts() throws Exception {
        long cardId = card(user, "Cartão BRL", "20000", CurrencyCode.BRL);
        overduePurchase(user, cardId, "900.00");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'INVOICE_OVERDUE')]").exists())
                .andExpect(jsonPath("$.insights[?(@.type == 'INVOICE_OVERDUE')].currency")
                        .value(Matchers.contains("BRL")));
    }

    @Test
    void anOverdueForeignInvoiceIsStatedInItsOwnCurrency() throws Exception {
        long cardId = card(user, "Cartão USD", "20000", CurrencyCode.USD);
        overduePurchase(user, cardId, "900.00");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'INVOICE_OVERDUE')].currency")
                        .value(Matchers.contains("USD")))
                .andExpect(jsonPath("$.insights[?(@.type == 'INVOICE_OVERDUE')].message")
                        .value(Matchers.contains(Matchers.containsString("US$"))))
                .andExpect(jsonPath("$.insights[?(@.type == 'INVOICE_OVERDUE')].message")
                        .value(Matchers.not(Matchers.contains(Matchers.containsString("R$ ")))));
    }

    @Test
    void aYenInvoiceIsNeverGivenCents() throws Exception {
        long cardId = card(user, "Cartão JPY", "2000000", CurrencyCode.JPY);
        overduePurchase(user, cardId, "90000");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'INVOICE_OVERDUE')].currency")
                        .value(Matchers.contains("JPY")))
                .andExpect(jsonPath("$.insights[?(@.type == 'INVOICE_OVERDUE')].message")
                        .value(Matchers.not(Matchers.contains(Matchers.containsString(",00")))));
    }

    @Test
    void aNativeInvoiceAlertSurvivesAnUnrelatedMixedLedger() throws Exception {
        long cardId = card(user, "Cartão USD", "20000", CurrencyCode.USD);
        overduePurchase(user, cardId, "900.00");
        // Enough mixed spending to withhold every aggregate rule that applies.
        expense(user, "1000.00", PREVIOUS, CurrencyCode.BRL, "Alimentação");
        expense(user, "2000.00", MONTH, CurrencyCode.BRL, "Alimentação");
        expense(user, "500.00", MONTH, CurrencyCode.EUR, "Lazer");

        insights(user)
                .andExpect(jsonPath("$.aggregateCoverage.complete").value(false))
                // Withholding an aggregate conclusion never hides a native one.
                .andExpect(jsonPath("$.insights[?(@.type == 'INVOICE_OVERDUE')]").exists())
                .andExpect(jsonPath("$.insights[?(@.type == 'INVOICE_OVERDUE')].currency")
                        .value(Matchers.contains("USD")))
                .andExpect(jsonPath("$.aggregateCoverage.unavailableRules")
                        .value(Matchers.not(Matchers.hasItem("INVOICE_OVERDUE"))));
    }

    @Test
    void aForeignCardReportsItsRemainingLimitInItsOwnCurrency() throws Exception {
        long cardId = card(user, "Cartão USD", "1000", CurrencyCode.USD);
        spreadPurchase(user, cardId, "900.00");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'CARD_UTILIZATION_HIGH')]").exists())
                .andExpect(jsonPath("$.insights[?(@.type == 'CARD_UTILIZATION_HIGH')].currency")
                        .value(Matchers.contains("USD")))
                .andExpect(jsonPath("$.insights[?(@.type == 'CARD_UTILIZATION_HIGH')].message")
                        .value(Matchers.contains(Matchers.containsString("US$"))));
    }

    // ── Commitment share ─────────────────────────────────────────────────────

    @Test
    void completeBaseIncomeAndCommitmentsStillProduceTheShareInsight() throws Exception {
        incomeHistory(user, "1000.00", CurrencyCode.BRL);
        commitment(user, "500.00", CurrencyCode.BRL);

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'COMMITMENT_SHARE_HIGH')]").exists())
                .andExpect(jsonPath("$.insights[?(@.type == 'COMMITMENT_SHARE_HIGH')].currency")
                        .value(Matchers.contains("BRL")))
                .andExpect(jsonPath("$.aggregateCoverage.complete").value(true));
    }

    @Test
    void aForeignCommitmentSuppressesTheShareRatio() throws Exception {
        incomeHistory(user, "1000.00", CurrencyCode.BRL);
        commitment(user, "500.00", CurrencyCode.BRL);
        commitment(user, "100.00", CurrencyCode.USD);

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'COMMITMENT_SHARE_HIGH')]").doesNotExist())
                .andExpect(jsonPath("$.aggregateCoverage.unavailableRules")
                        .value(Matchers.hasItem("COMMITMENT_SHARE_HIGH")))
                .andExpect(jsonPath("$.aggregateCoverage.missingCurrencies")
                        .value(Matchers.hasItem("USD")));
    }

    @Test
    void foreignIncomeSuppressesTheShareRatio() throws Exception {
        incomeHistory(user, "1000.00", CurrencyCode.BRL);
        income(user, "400.00", PREVIOUS, CurrencyCode.USD);
        commitment(user, "500.00", CurrencyCode.BRL);

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'COMMITMENT_SHARE_HIGH')]").doesNotExist())
                .andExpect(jsonPath("$.aggregateCoverage.unavailableRules")
                        .value(Matchers.hasItem("COMMITMENT_SHARE_HIGH")));
    }

    @Test
    void commitmentsWithoutAnyIncomeHistoryAreAnAbsenceNotACurrencyFailure() throws Exception {
        commitment(user, "500.00", CurrencyCode.BRL);

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'COMMITMENT_SHARE_HIGH')]").doesNotExist())
                .andExpect(jsonPath("$.aggregateCoverage.complete").value(true))
                .andExpect(jsonPath("$.aggregateCoverage.unavailableRules.length()").value(0));
    }

    // ── Card installment burden ──────────────────────────────────────────────

    @Test
    void aForeignNextMonthCardBurdenSuppressesTheRatio() throws Exception {
        incomeHistory(user, "1000.00", CurrencyCode.BRL);
        long cardId = card(user, "Cartão USD", "20000", CurrencyCode.USD);
        spreadPurchase(user, cardId, "900.00");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'CARD_INSTALLMENT_BURDEN_HIGH')]")
                        .doesNotExist())
                .andExpect(jsonPath("$.aggregateCoverage.unavailableRules")
                        .value(Matchers.hasItem("CARD_INSTALLMENT_BURDEN_HIGH")));
    }

    @Test
    void aCardBurdenWithoutIncomeHistoryIsAnAbsenceNotACurrencyFailure() throws Exception {
        long cardId = card(user, "Cartão BRL", "20000", CurrencyCode.BRL);
        spreadPurchase(user, cardId, "900.00");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'CARD_INSTALLMENT_BURDEN_HIGH')]")
                        .doesNotExist())
                .andExpect(jsonPath("$.aggregateCoverage.unavailableRules")
                        .value(Matchers.not(Matchers.hasItem("CARD_INSTALLMENT_BURDEN_HIGH"))));
    }

    // ── Goals ────────────────────────────────────────────────────────────────

    @Test
    void aBaseCurrencyGoalIsStillCompared() throws Exception {
        incomeHistory(user, "1000.00", CurrencyCode.BRL);
        goal(user, "Viagem", "90000.00", null);

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'GOAL_OFF_PACE')]").exists())
                .andExpect(jsonPath("$.insights[?(@.type == 'GOAL_OFF_PACE')].currency")
                        .value(Matchers.contains("BRL")));
    }

    @Test
    void aForeignGoalIsNeverComparedWithBaseSurplus() throws Exception {
        incomeHistory(user, "1000.00", CurrencyCode.BRL);
        goal(user, "Viagem", "90000.00", "USD");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'GOAL_OFF_PACE')]").doesNotExist())
                .andExpect(jsonPath("$.aggregateCoverage.unavailableRules")
                        .value(Matchers.hasItem("GOAL_OFF_PACE")))
                .andExpect(jsonPath("$.aggregateCoverage.missingCurrencies")
                        .value(Matchers.hasItem("USD")));
    }

    @Test
    void aGoalWithoutSurplusHistoryIsAnAbsenceNotACurrencyFailure() throws Exception {
        goal(user, "Viagem", "90000.00", null);

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'GOAL_OFF_PACE')]").doesNotExist())
                .andExpect(jsonPath("$.aggregateCoverage.complete").value(true));
    }

    // ── Wishlist affordability ───────────────────────────────────────────────

    @Test
    void aBaseItemWithCompleteBaseCashIsStillCalledAffordable() throws Exception {
        account(user, "Conta", "10000.00", CurrencyCode.BRL);
        wishlistItem(user, "Notebook", "1000.00", null);

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'WISHLIST_AFFORDABLE')]").exists())
                .andExpect(jsonPath("$.insights[?(@.type == 'WISHLIST_AFFORDABLE')].currency")
                        .value(Matchers.contains("BRL")));
    }

    @Test
    void aForeignItemIsNeverCalledAffordableFromBaseCash() throws Exception {
        account(user, "Conta", "10000.00", CurrencyCode.BRL);
        wishlistItem(user, "Câmera", "100.00", "USD");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'WISHLIST_AFFORDABLE')]").doesNotExist())
                .andExpect(jsonPath("$.aggregateCoverage.unavailableRules")
                        .value(Matchers.hasItem("WISHLIST_AFFORDABLE")))
                .andExpect(jsonPath("$.aggregateCoverage.missingCurrencies")
                        .value(Matchers.hasItem("USD")));
    }

    @Test
    void mixedCashSuppressesAffordabilityBeforeTheBufferIsEverSubtracted() throws Exception {
        account(user, "Conta", "10000.00", CurrencyCode.BRL);
        account(user, "Conta USD", "2000.00", CurrencyCode.USD);
        wishlistItem(user, "Notebook", "1000.00", null);

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'WISHLIST_AFFORDABLE')]").doesNotExist())
                .andExpect(jsonPath("$.aggregateCoverage.unavailableRules")
                        .value(Matchers.hasItem("WISHLIST_AFFORDABLE")))
                .andExpect(jsonPath("$.aggregateCoverage.missingCurrencies")
                        .value(Matchers.hasItem("USD")));
    }

    @Test
    void anotherUsersForeignItemNeverAffectsMyCoverage() throws Exception {
        account(user, "Conta", "10000.00", CurrencyCode.BRL);
        wishlistItem(user, "Notebook", "1000.00", null);

        TestUser other = registerUser();
        account(other, "Conta USD", "50000.00", CurrencyCode.USD);
        wishlistItem(other, "Câmera deles", "100.00", "USD");

        insights(user)
                .andExpect(jsonPath("$.insights[?(@.type == 'WISHLIST_AFFORDABLE')]").exists())
                .andExpect(jsonPath("$.aggregateCoverage.complete").value(true))
                .andExpect(jsonPath("$.aggregateCoverage.missingCurrencies.length()").value(0));
    }

    // ── Response contract ────────────────────────────────────────────────────

    @Test
    void anEmptyAccountReportsNoCurrencyProblemAtAll() throws Exception {
        insights(user)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insights.length()").value(0))
                .andExpect(jsonPath("$.baseCurrency").value("BRL"))
                .andExpect(jsonPath("$.aggregateCoverage.complete").value(true))
                .andExpect(jsonPath("$.aggregateCoverage.missingCurrencies.length()").value(0))
                .andExpect(jsonPath("$.aggregateCoverage.unavailableRules.length()").value(0));
    }

    @Test
    void missingCurrenciesFollowCatalogueOrderAcrossRules() throws Exception {
        // JPY is last in the catalogue and USD first among the non-base codes,
        // whatever order the rules happened to discover them in.
        account(user, "Conta", "10000.00", CurrencyCode.BRL);
        account(user, "Conta JPY", "50000", CurrencyCode.JPY);
        wishlistItem(user, "Notebook", "1000.00", null);
        expense(user, "1000.00", PREVIOUS, CurrencyCode.BRL, "Alimentação");
        expense(user, "2000.00", MONTH, CurrencyCode.BRL, "Alimentação");
        expense(user, "300.00", MONTH, CurrencyCode.USD, "Lazer");

        insights(user)
                .andExpect(jsonPath("$.aggregateCoverage.missingCurrencies")
                        .value(Matchers.contains("USD", "JPY")));
    }

    @Test
    void aNonBaseCurrencyUserGetsEveryAggregateStatedInThatCurrency() throws Exception {
        setBaseCurrency(user, CurrencyCode.USD);
        expense(user, "1000.00", PREVIOUS, CurrencyCode.USD, "Alimentação");
        expense(user, "2000.00", MONTH, CurrencyCode.USD, "Alimentação");

        insights(user)
                .andExpect(jsonPath("$.baseCurrency").value("USD"))
                .andExpect(jsonPath("$.insights[?(@.type == 'EXPENSE_INCREASE')].currency")
                        .value(Matchers.contains("USD")))
                .andExpect(jsonPath("$.aggregateCoverage.complete").value(true));
    }
}
