package com.finora.api.financialcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.account.AccountType;
import com.finora.api.category.Category;
import com.finora.api.category.CategoryType;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The purchase context must never add two currencies, and must never let one
 * currency's activity move another currency's denominator.
 *
 * <p>The distinction that matters most here is between a user with <em>no</em>
 * history — for whom a cash-only analysis is still valid and always was — and a
 * user whose history exists but is denominated in something the base currency
 * cannot be compared with. Collapsing the second into the first would present a
 * foreign ledger as a safe absence.
 */
class FinancialContextServiceTest extends AbstractIntegrationTest {

    private static final LocalDate REFERENCE = LocalDate.of(2026, 7, 15);

    @Autowired
    private FinancialContextService service;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    private TestUser user;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
    }

    private Category incomeCategory(TestUser owner) {
        return categoryRepository
                .findByUserIdAndNameIgnoreCaseAndType(owner.id(), "Salário", CategoryType.INCOME)
                .orElseThrow();
    }

    private Category expenseCategory(TestUser owner) {
        return categoryRepository
                .findByUserIdAndNameIgnoreCaseAndType(owner.id(), "Alimentação", CategoryType.EXPENSE)
                .orElseThrow();
    }

    private void income(TestUser owner, String amount, YearMonth month, CurrencyCode currency) {
        Transaction transaction = new Transaction(owner.id(), TransactionType.INCOME,
                new BigDecimal(amount), "Receita", month.atDay(5), incomeCategory(owner));
        transaction.setCurrency(currency);
        transactions.save(transaction);
    }

    private void expense(TestUser owner, String amount, YearMonth month, CurrencyCode currency) {
        Transaction transaction = new Transaction(owner.id(), TransactionType.EXPENSE,
                new BigDecimal(amount), "Despesa", month.atDay(6), expenseCategory(owner));
        transaction.setCurrency(currency);
        transactions.save(transaction);
    }

    private void account(TestUser owner, String name, String balance, CurrencyCode currency,
            int order) {
        accounts.save(new Account(owner.id(), name, AccountType.CHECKING,
                new BigDecimal(balance), order, currency));
    }

    // ── Available cash ───────────────────────────────────────────────────────

    @Test
    void anEmptyUserHasADeterministicZeroCash() {
        var context = service.build(user.id(), REFERENCE);

        assertThat(context.baseCurrency()).isEqualTo("BRL");
        assertThat(context.availableCash().baseComplete()).isTrue();
        assertThat(context.availableCash().baseTotal()).isEqualByComparingTo("0.00");
        assertThat(context.missingCurrencies()).isEmpty();
    }

    @Test
    void baseCurrencyOnlyCashStaysAsingleFigure() {
        account(user, "Conta", "1000.00", CurrencyCode.BRL, 0);
        account(user, "Poupança", "500.00", CurrencyCode.BRL, 1);

        var context = service.build(user.id(), REFERENCE);

        assertThat(context.availableCash().baseComplete()).isTrue();
        assertThat(context.availableCash().baseTotal()).isEqualByComparingTo("1500.00");
    }

    @Test
    void foreignOnlyCashIsNativeButNotBaseComplete() {
        account(user, "Checking USD", "1200.00", CurrencyCode.USD, 0);

        var context = service.build(user.id(), REFERENCE);

        assertThat(context.availableCash().homogeneous()).isTrue();
        assertThat(context.availableCash().nativeTotal()).isEqualByComparingTo("1200.00");
        assertThat(context.availableCash().baseComplete()).isFalse();
        assertThat(context.availableCash().baseTotal()).isNull();
        assertThat(context.missingCurrencies()).containsExactly("USD");
    }

    @Test
    void mixedCashIsGroupedAndNeverConsolidated() {
        account(user, "Conta", "8000.00", CurrencyCode.BRL, 0);
        account(user, "Checking USD", "1200.00", CurrencyCode.USD, 1);

        var context = service.build(user.id(), REFERENCE);

        assertThat(context.availableCash().byCurrency())
                .extracting(com.finora.api.common.money.CurrencyTotals.CurrencyAmount::currency)
                .containsExactly("BRL", "USD");
        assertThat(context.availableCash().baseTotal()).isNull();
        assertThat(context.availableCash().nativeTotal()).isNull();
    }

    @Test
    void anEmptyForeignAccountDoesNotBlockBaseCompleteness() {
        account(user, "Conta", "8000.00", CurrencyCode.BRL, 0);
        // Zero converts to zero under any rate, so there is nothing to convert.
        account(user, "Checking USD", "0.00", CurrencyCode.USD, 1);

        var context = service.build(user.id(), REFERENCE);

        assertThat(context.availableCash().baseComplete()).isTrue();
        assertThat(context.availableCash().baseTotal()).isEqualByComparingTo("8000.00");
        assertThat(context.missingCurrencies()).isEmpty();
    }

    @Test
    void archivedAccountsDoNotCountTowardsAvailableCash() {
        account(user, "Ativa", "1000.00", CurrencyCode.BRL, 0);
        Account archived = new Account(user.id(), "Arquivada", AccountType.SAVINGS,
                new BigDecimal("500.00"), 1, CurrencyCode.BRL);
        archived.setArchived(true);
        accounts.save(archived);

        var context = service.build(user.id(), REFERENCE);

        assertThat(context.availableCash().baseTotal()).isEqualByComparingTo("1000.00");
    }

    @Test
    void anotherOwnersMoneyNeverEntersTheContext() throws Exception {
        account(user, "Conta A", "100.00", CurrencyCode.BRL, 0);

        TestUser rich = registerUser("Usuária Rica");
        account(rich, "Conta B", "50000.00", CurrencyCode.USD, 0);
        income(rich, "30000.00", YearMonth.of(2026, 6), CurrencyCode.USD);

        var context = service.build(user.id(), REFERENCE);

        assertThat(context.availableCash().baseTotal()).isEqualByComparingTo("100.00");
        assertThat(context.availableCash().baseComplete()).isTrue();
        assertThat(context.missingCurrencies()).isEmpty();
        assertThat(context.averageIncome().anyHistory()).isFalse();
    }

    // ── Historical averages and denominators ─────────────────────────────────

    @Test
    void averagesDivideByThatCurrencyOwnMonthsWithData() {
        income(user, "3000.00", YearMonth.of(2026, 5), CurrencyCode.BRL);

        var context = service.build(user.id(), REFERENCE);

        assertThat(context.averageIncome().anyHistory()).isTrue();
        assertThat(context.averageIncome().baseComplete()).isTrue();
        assertThat(context.averageIncome().baseMonthsUsed()).isEqualTo(1);
        assertThat(context.averageIncome().baseAverage()).isEqualByComparingTo("3000.00");
        assertThat(context.averageSurplus().baseAverage()).isEqualByComparingTo("3000.00");
    }

    @Test
    void denominatorsAreIndependentPerCurrency() {
        // Three months of reais, one month of dollars.
        income(user, "3000.00", YearMonth.of(2026, 4), CurrencyCode.BRL);
        income(user, "3000.00", YearMonth.of(2026, 5), CurrencyCode.BRL);
        income(user, "3000.00", YearMonth.of(2026, 6), CurrencyCode.BRL);
        income(user, "800.00", YearMonth.of(2026, 6), CurrencyCode.USD);

        var context = service.build(user.id(), REFERENCE);

        // BRL divides by 3, USD by 1. Sharing a divisor would understate the
        // dollars by two thirds.
        assertThat(context.averageIncome().byCurrency())
                .anySatisfy(entry -> {
                    assertThat(entry.currency()).isEqualTo("BRL");
                    assertThat(entry.monthsUsed()).isEqualTo(3);
                    assertThat(entry.average()).isEqualByComparingTo("3000.00");
                })
                .anySatisfy(entry -> {
                    assertThat(entry.currency()).isEqualTo("USD");
                    assertThat(entry.monthsUsed()).isEqualTo(1);
                    assertThat(entry.average()).isEqualByComparingTo("800.00");
                });
    }

    @Test
    void currentPartialMonthIsStillExcluded() {
        income(user, "9999.00", YearMonth.from(REFERENCE), CurrencyCode.BRL);

        var context = service.build(user.id(), REFERENCE);

        assertThat(context.averageIncome().anyHistory()).isFalse();
        assertThat(context.averageIncome().baseAverage()).isNull();
    }

    @Test
    void noHistoryIsAbsentRatherThanAFakeZero() {
        var context = service.build(user.id(), REFERENCE);

        assertThat(context.averageIncome().anyHistory()).isFalse();
        assertThat(context.averageIncome().baseAverage()).isNull();
        assertThat(context.averageExpenses().baseAverage()).isNull();
        assertThat(context.averageSurplus().baseAverage()).isNull();
        // No history is still safe to reason about: the engine falls back to a
        // cash-only analysis, exactly as it always did.
        assertThat(context.historyUsableInBase()).isTrue();
    }

    @Test
    void foreignOnlyHistoryIsNotTreatedAsNoHistory() {
        income(user, "1000.00", YearMonth.of(2026, 6), CurrencyCode.USD);

        var context = service.build(user.id(), REFERENCE);

        // The history exists — it is simply not comparable with a BRL base.
        assertThat(context.averageIncome().anyHistory()).isTrue();
        assertThat(context.averageIncome().baseComplete()).isFalse();
        assertThat(context.averageIncome().baseAverage()).isNull();
        assertThat(context.averageIncome().forCurrency(CurrencyCode.USD))
                .isEqualByComparingTo("1000.00");
        assertThat(context.historyUsableInBase()).isFalse();
    }

    @Test
    void mixedIncomeMarksBaseIncomeIncomplete() {
        income(user, "3000.00", YearMonth.of(2026, 6), CurrencyCode.BRL);
        income(user, "500.00", YearMonth.of(2026, 6), CurrencyCode.USD);

        var context = service.build(user.id(), REFERENCE);

        assertThat(context.averageIncome().baseComplete()).isFalse();
        assertThat(context.averageIncome().baseAverage()).isNull();
        assertThat(context.averageIncome().unconvertedCurrencies()).containsExactly("USD");
        assertThat(context.historyUsableInBase()).isFalse();
    }

    @Test
    void mixedExpensesMarkBaseExpensesIncomplete() {
        expense(user, "800.00", YearMonth.of(2026, 6), CurrencyCode.BRL);
        expense(user, "60.00", YearMonth.of(2026, 6), CurrencyCode.EUR);

        var context = service.build(user.id(), REFERENCE);

        assertThat(context.averageExpenses().baseComplete()).isFalse();
        assertThat(context.averageExpenses().baseAverage()).isNull();
        assertThat(context.missingCurrencies()).containsExactly("EUR");
    }

    @Test
    void surplusExistsOnlyBetweenComparableOperands() {
        income(user, "3000.00", YearMonth.of(2026, 6), CurrencyCode.BRL);
        expense(user, "1000.00", YearMonth.of(2026, 6), CurrencyCode.BRL);
        income(user, "900.00", YearMonth.of(2026, 6), CurrencyCode.USD);

        var context = service.build(user.id(), REFERENCE);

        // Native USD surplus is real; the base surplus is withheld because the
        // BRL side is no longer the whole picture.
        assertThat(context.averageSurplus().forCurrency(CurrencyCode.USD))
                .isEqualByComparingTo("900.00");
        assertThat(context.averageSurplus().forCurrency(CurrencyCode.BRL))
                .isEqualByComparingTo("2000.00");
        assertThat(context.averageSurplus().baseComplete()).isFalse();
        assertThat(context.averageSurplus().baseAverage()).isNull();
    }

    @Test
    void missingCurrenciesFollowCatalogueOrder() {
        account(user, "Checking JPY", "5000", CurrencyCode.JPY, 0);
        account(user, "Checking USD", "100.00", CurrencyCode.USD, 1);
        expense(user, "60.00", YearMonth.of(2026, 6), CurrencyCode.EUR);

        var context = service.build(user.id(), REFERENCE);

        assertThat(context.missingCurrencies()).containsExactly("USD", "EUR", "JPY");
    }
}
