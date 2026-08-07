package com.finora.api.financialcontext;

import com.finora.api.account.Account;
import com.finora.api.account.AccountBalanceService;
import com.finora.api.account.AccountRepository;
import com.finora.api.commitment.CommitmentService;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.common.money.CurrencyTotals;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.adjustment.InvoiceAdjustmentRepository;
import com.finora.api.creditcard.installment.CardInstallmentRepository;
import com.finora.api.creditcard.payment.InvoicePaymentRepository;
import com.finora.api.financialcontext.FinancialContext.CurrencyAverage;
import com.finora.api.financialcontext.FinancialContext.HistoricalAverage;
import com.finora.api.settings.SettingsService;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the {@link FinancialContext} the analysis engines reason over.
 *
 * <p>The only implementation. Purchase analysis and insights share it rather
 * than each keeping their own query engine, so a semantic fix — what counts as
 * available cash, how a denominator is chosen, when a dimension is complete in
 * the base currency — lands in one place and both engines get it. A scalar
 * predecessor that summed across denominations was deleted rather than kept as
 * a fallback: a second answer to the same question is how the two drift apart.
 *
 * <p>The history window is unchanged — the {@value #HISTORY_WINDOW_MONTHS}
 * complete months before the reference date, with the current partial month
 * excluded so a mid-month analysis is not skewed — and so is its definition:
 * recorded transactions only, with card expense recognition deliberately left
 * out, exactly as before.
 *
 * <p>Cost is a constant number of round trips regardless of how many accounts,
 * cards, commitments, months or currencies the user has: one account load plus
 * two grouped balance queries, one grouped query over the whole history window,
 * one commitment load, and one grouped query each for card charges, adjustments,
 * payments and next month's installments.
 */
@Service
public class FinancialContextService {

    static final int HISTORY_WINDOW_MONTHS = 3;

    private final AccountRepository accounts;
    private final AccountBalanceService balances;
    private final TransactionRepository transactions;
    private final CommitmentService commitments;
    private final CardInstallmentRepository installments;
    private final InvoiceAdjustmentRepository adjustments;
    private final InvoicePaymentRepository payments;
    private final SettingsService settings;

    public FinancialContextService(AccountRepository accounts,
                                           AccountBalanceService balances,
                                           TransactionRepository transactions,
                                           CommitmentService commitments,
                                           CardInstallmentRepository installments,
                                           InvoiceAdjustmentRepository adjustments,
                                           InvoicePaymentRepository payments,
                                           SettingsService settings) {
        this.accounts = accounts;
        this.balances = balances;
        this.transactions = transactions;
        this.commitments = commitments;
        this.installments = installments;
        this.adjustments = adjustments;
        this.payments = payments;
        this.settings = settings;
    }

    /**
     * Builds the snapshot exclusively from the given owner's data — every query
     * below carries the user predicate, so another user's balances, history,
     * commitments or cards can never influence this context.
     */
    @Transactional(readOnly = true)
    public FinancialContext build(Long userId, LocalDate referenceDate) {
        CurrencyCode base = settings.forUser(userId).getBaseCurrency();
        YearMonth reference = YearMonth.from(referenceDate);

        CurrencyTotals availableCash = availableCash(userId, base);
        History history = history(userId, reference);
        HistoricalAverage income = average(history, base, Flow.INCOME);
        HistoricalAverage expenses = average(history, base, Flow.EXPENSE);
        HistoricalAverage surplus = average(history, base, Flow.SURPLUS);
        CurrencyTotals monthlyCommitments = CurrencyTotals.of(
                commitments.monthlyTotalEntries(userId, reference.plusMonths(1)), base);
        CurrencyTotals cardOutstanding = cardOutstanding(userId, base);
        CurrencyTotals nextMonthInstallments = nextMonthInstallments(userId, reference, base);

        return new FinancialContext(
                base.name(),
                availableCash,
                income,
                expenses,
                surplus,
                monthlyCommitments,
                cardOutstanding,
                nextMonthInstallments,
                FinancialContext.collectMissing(base, List.of(
                        availableCash.unconvertedCurrencies(),
                        income.unconvertedCurrencies(),
                        expenses.unconvertedCurrencies(),
                        monthlyCommitments.unconvertedCurrencies(),
                        cardOutstanding.unconvertedCurrencies(),
                        nextMonthInstallments.unconvertedCurrencies())));
    }

    // ── Available cash ───────────────────────────────────────────────────────

    /**
     * Current balances of active accounts, grouped by each account's currency.
     *
     * <p>Balances come from the batched formula — two grouped queries for the
     * whole list — rather than two per account. A balance is a point-in-time
     * snapshot, so an account sitting at exactly zero genuinely has nothing to
     * convert and does not make an otherwise complete base analysis unavailable.
     */
    private CurrencyTotals availableCash(Long userId, CurrencyCode base) {
        List<Account> active = accounts.findAllByUserIdOrderByDisplayOrderAscNameAsc(userId)
                .stream()
                .filter(account -> !account.isArchived())
                .toList();
        Map<Long, BigDecimal> currentBalances = balances.currentBalances(userId, active);
        List<CurrencyTotals.Entry> entries = active.stream()
                .map(account -> new CurrencyTotals.Entry(
                        MoneyRules.normalize(
                                currentBalances.getOrDefault(account.getId(), BigDecimal.ZERO),
                                account.getCurrency()),
                        account.getCurrency()))
                .toList();
        return CurrencyTotals.ofSnapshots(entries, base);
    }

    // ── Historical averages ──────────────────────────────────────────────────

    private enum Flow { INCOME, EXPENSE, SURPLUS }

    /** The window's income and expenses, per currency and month. */
    private record History(
            Map<CurrencyCode, Map<YearMonth, BigDecimal>> income,
            Map<CurrencyCode, Map<YearMonth, BigDecimal>> expense) {

        /**
         * Months in which a currency actually moved. Income and expenses share
         * one denominator per currency, which is what keeps the two averages
         * comparable enough for their difference to mean anything.
         */
        Map<CurrencyCode, Integer> denominators() {
            Map<CurrencyCode, Integer> months = new EnumMap<>(CurrencyCode.class);
            EnumSet<CurrencyCode> present = EnumSet.noneOf(CurrencyCode.class);
            present.addAll(income.keySet());
            present.addAll(expense.keySet());
            for (CurrencyCode currency : present) {
                Set<YearMonth> active = new HashSet<>();
                addActive(active, income.get(currency));
                addActive(active, expense.get(currency));
                if (!active.isEmpty()) {
                    months.put(currency, active.size());
                }
            }
            return months;
        }

        private static void addActive(Set<YearMonth> target, Map<YearMonth, BigDecimal> byMonth) {
            if (byMonth == null) {
                return;
            }
            byMonth.forEach((month, amount) -> {
                if (amount.signum() != 0) {
                    target.add(month);
                }
            });
        }

        BigDecimal totalOf(Flow flow, CurrencyCode currency) {
            BigDecimal incomeTotal = sum(income.get(currency));
            BigDecimal expenseTotal = sum(expense.get(currency));
            return switch (flow) {
                case INCOME -> incomeTotal;
                case EXPENSE -> expenseTotal;
                case SURPLUS -> incomeTotal.subtract(expenseTotal);
            };
        }

        /** Whether the currency contributed anything at all, in either direction. */
        boolean contributed(CurrencyCode currency) {
            return sum(income.get(currency)).signum() != 0
                    || sum(expense.get(currency)).signum() != 0;
        }

        private static BigDecimal sum(Map<YearMonth, BigDecimal> byMonth) {
            return byMonth == null
                    ? BigDecimal.ZERO
                    : byMonth.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** One grouped query covers the whole window rather than two per month. */
    private History history(Long userId, YearMonth reference) {
        YearMonth from = reference.minusMonths(HISTORY_WINDOW_MONTHS);
        YearMonth to = reference.minusMonths(1);
        Map<CurrencyCode, Map<YearMonth, BigDecimal>> income = new EnumMap<>(CurrencyCode.class);
        Map<CurrencyCode, Map<YearMonth, BigDecimal>> expense = new EnumMap<>(CurrencyCode.class);
        for (Object[] row : transactions.sumGroupedByMonthTypeAndCurrency(
                userId, from.atDay(1), to.atEndOfMonth())) {
            CurrencyCode currency = (CurrencyCode) row[3];
            BigDecimal amount = (BigDecimal) row[4];
            if (currency == null || amount == null) {
                continue;
            }
            YearMonth month = YearMonth.of(
                    ((Number) row[0]).intValue(), ((Number) row[1]).intValue());
            Map<CurrencyCode, Map<YearMonth, BigDecimal>> target =
                    row[2] == TransactionType.INCOME ? income : expense;
            target.computeIfAbsent(currency, key -> new HashMap<>())
                    .merge(month, amount, BigDecimal::add);
        }
        return new History(income, expense);
    }

    /**
     * One flow's averages, each divided by its own currency's month count.
     *
     * <p>A currency whose window total is exactly zero cannot change a base
     * conclusion under any rate, so it is reported natively without marking the
     * base analysis incomplete.
     */
    private static HistoricalAverage average(History history, CurrencyCode base, Flow flow) {
        Map<CurrencyCode, Integer> divisors = history.denominators();
        if (divisors.isEmpty()) {
            return HistoricalAverage.noHistory();
        }

        List<CurrencyAverage> byCurrency = new ArrayList<>();
        List<String> unconverted = new ArrayList<>();
        BigDecimal baseAverage = null;
        int baseMonths = 0;
        for (var entry : divisors.entrySet()) {
            CurrencyCode currency = entry.getKey();
            int divisor = entry.getValue();
            BigDecimal average = history.totalOf(flow, currency)
                    .divide(BigDecimal.valueOf(divisor), MoneyRules.SCALE, MoneyRules.ROUNDING);
            BigDecimal normalized = MoneyRules.normalize(average, currency);
            byCurrency.add(new CurrencyAverage(currency.name(), normalized, divisor));
            if (currency == base) {
                baseAverage = normalized;
                baseMonths = divisor;
            } else if (history.contributed(currency)) {
                unconverted.add(currency.name());
            }
        }
        boolean complete = unconverted.isEmpty();
        return new HistoricalAverage(
                List.copyOf(byCurrency),
                true,
                complete,
                complete ? baseAverage : null,
                complete ? baseMonths : 0,
                List.copyOf(unconverted));
    }

    // ── Card obligations ─────────────────────────────────────────────────────

    /**
     * Unpaid card obligations, netted within each billing currency.
     *
     * <p>Charges and payments meet per currency. Subtracting a payment made in
     * reais from charges billed in dollars would invent a discount that never
     * happened. Reversed payments are already excluded by the query, so the
     * existing anti-double-counting behaviour is untouched.
     */
    private CurrencyTotals cardOutstanding(Long userId, CurrencyCode base) {
        Map<CurrencyCode, BigDecimal> net = new EnumMap<>(CurrencyCode.class);
        accumulate(net, installments.sumActiveGroupedByCurrency(userId), false);
        accumulate(net, adjustments.sumActiveNetGroupedByCurrency(userId), false);
        accumulate(net, payments.sumCompletedGroupedByCurrency(userId), true);
        return CurrencyTotals.ofSnapshots(entriesOf(net), base);
    }

    /** Installments already scheduled on next month's invoices, per currency. */
    private CurrencyTotals nextMonthInstallments(Long userId, YearMonth reference,
            CurrencyCode base) {
        LocalDate month = reference.plusMonths(1).atDay(1);
        List<CurrencyTotals.Entry> entries = new ArrayList<>();
        for (Object[] row : installments.sumActiveGroupedByMonthAndCurrency(userId, month, month)) {
            entries.add(new CurrencyTotals.Entry((BigDecimal) row[2], (CurrencyCode) row[1]));
        }
        return CurrencyTotals.ofSnapshots(entries, base);
    }

    /** Rows are {@code [currency, amount]}. */
    private static void accumulate(Map<CurrencyCode, BigDecimal> target, List<Object[]> rows,
            boolean subtract) {
        for (Object[] row : rows) {
            CurrencyCode currency = (CurrencyCode) row[0];
            BigDecimal amount = (BigDecimal) row[1];
            if (currency == null || amount == null) {
                continue;
            }
            target.merge(currency, subtract ? amount.negate() : amount, BigDecimal::add);
        }
    }

    private static List<CurrencyTotals.Entry> entriesOf(Map<CurrencyCode, BigDecimal> byCurrency) {
        List<CurrencyTotals.Entry> entries = new ArrayList<>(byCurrency.size());
        byCurrency.forEach((currency, amount) ->
                entries.add(new CurrencyTotals.Entry(amount, currency)));
        return entries;
    }
}
