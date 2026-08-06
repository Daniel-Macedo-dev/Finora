package com.finora.api.dashboard;

import com.finora.api.account.AccountService;
import com.finora.api.budget.BudgetDtos.BudgetSummaryResponse;
import com.finora.api.budget.BudgetService;
import com.finora.api.commitment.CommitmentService;
import com.finora.api.commitment.occurrence.CommitmentOccurrenceRepository;
import com.finora.api.commitment.occurrence.OccurrenceStatus;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.common.money.CurrencyTotals;
import com.finora.api.common.money.CurrencyTotals.Entry;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.CardLimitService;
import com.finora.api.creditcard.CreditCard;
import com.finora.api.creditcard.CreditCardRepository;
import com.finora.api.creditcard.adjustment.InvoiceAdjustmentRepository;
import com.finora.api.creditcard.installment.CardInstallmentRepository;
import com.finora.api.creditcard.invoice.InvoiceDtos.InvoiceSummaryResponse;
import com.finora.api.creditcard.invoice.InvoiceService;
import com.finora.api.creditcard.invoice.InvoiceStatus;
import com.finora.api.creditcard.purchase.CardPurchaseRepository;
import com.finora.api.creditcard.purchase.PurchaseStatus;
import com.finora.api.dashboard.DashboardDtos.BudgetOverview;
import com.finora.api.dashboard.DashboardDtos.CardInvoiceBrief;
import com.finora.api.dashboard.DashboardDtos.CardsOverview;
import com.finora.api.dashboard.DashboardDtos.CategoryShare;
import com.finora.api.dashboard.DashboardDtos.DashboardResponse;
import com.finora.api.dashboard.DashboardDtos.FutureCashEvent;
import com.finora.api.dashboard.DashboardDtos.FutureCashOverview;
import com.finora.api.dashboard.DashboardDtos.MonthTrendPoint;
import com.finora.api.dashboard.DashboardDtos.MonthTrendSeries;
import com.finora.api.dashboard.DashboardDtos.RecentCardPurchase;
import com.finora.api.forecast.ForecastDtos;
import com.finora.api.forecast.ForecastService;
import com.finora.api.goal.GoalService;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.settings.SettingsService;
import com.finora.api.transaction.TransactionDtos.TransactionResponse;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The month at a glance, assembled without ever adding two currencies.
 *
 * <p>Every rollup here is grouped in the database by the currency the amount is
 * denominated in, and the grouping survives all the way into the response as a
 * {@link CurrencyTotals}. A base-currency-only user therefore sees exactly the
 * same single figures as before; a user with foreign money sees each currency on
 * its own, and the derived ratios that would have needed a conversion are
 * reported as unavailable rather than computed over a subset.
 */
@Service
public class DashboardService {

    static final int TREND_MONTHS = 6;
    static final int TOP_CATEGORIES = 5;

    private final TransactionRepository transactions;
    private final AccountService accountService;
    private final BudgetService budgets;
    private final CommitmentService commitments;
    private final com.finora.api.commitment.CommitmentRepository commitmentRepository;
    private final GoalService goals;
    private final CreditCardRepository cards;
    private final CardInstallmentRepository installments;
    private final InvoiceAdjustmentRepository adjustments;
    private final CardPurchaseRepository purchases;
    private final CardLimitService limits;
    private final InvoiceService invoices;
    private final ForecastService forecast;
    private final CommitmentOccurrenceRepository occurrences;
    private final SettingsService settings;
    private final CurrentUserProvider currentUser;

    public DashboardService(TransactionRepository transactions,
                            AccountService accountService,
                            BudgetService budgets,
                            CommitmentService commitments,
                            com.finora.api.commitment.CommitmentRepository commitmentRepository,
                            GoalService goals,
                            CreditCardRepository cards,
                            CardInstallmentRepository installments,
                            InvoiceAdjustmentRepository adjustments,
                            CardPurchaseRepository purchases,
                            CardLimitService limits,
                            InvoiceService invoices,
                            ForecastService forecast,
                            CommitmentOccurrenceRepository occurrences,
                            SettingsService settings,
                            CurrentUserProvider currentUser) {
        this.transactions = transactions;
        this.accountService = accountService;
        this.budgets = budgets;
        this.commitments = commitments;
        this.commitmentRepository = commitmentRepository;
        this.goals = goals;
        this.cards = cards;
        this.installments = installments;
        this.adjustments = adjustments;
        this.purchases = purchases;
        this.limits = limits;
        this.invoices = invoices;
        this.forecast = forecast;
        this.occurrences = occurrences;
        this.settings = settings;
        this.currentUser = currentUser;
    }

    /** Every section below aggregates exclusively the authenticated user's data. */
    @Transactional(readOnly = true)
    public DashboardResponse build(YearMonth month, LocalDate today) {
        Long userId = currentUser.currentUserId();
        CurrencyCode base = settings.forUser(userId).getBaseCurrency();

        // One pass over the trend window answers this month, the previous month
        // and every point of the chart, so nothing is scanned repeatedly.
        MonthlyFlows flows = monthlyFlows(userId, month);

        CurrencyTotals income = flows.income(month, base);
        CurrencyTotals expense = flows.expense(month, base);
        CurrencyTotals previousExpense = flows.expense(month.minusMonths(1), base);
        CurrencyTotals monthResult = CurrencyTotals.of(
                flows.signedResultEntries(month), base);

        // A ratio is only meaningful when both operands are complete in the same
        // currency. Computed over the base-currency subset alone it would read
        // as a statement about the whole month.
        BigDecimal savingsRate = null;
        if (income.baseComplete() && expense.baseComplete()
                && income.baseTotal().signum() > 0) {
            savingsRate = income.baseTotal().subtract(expense.baseTotal())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(income.baseTotal(), 1, RoundingMode.HALF_UP);
        }

        BigDecimal variation = null;
        if (expense.baseComplete() && previousExpense.baseComplete()
                && previousExpense.baseTotal().signum() > 0) {
            variation = expense.baseTotal().subtract(previousExpense.baseTotal())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(previousExpense.baseTotal(), 1, RoundingMode.HALF_UP);
        }

        BudgetSummaryResponse budgetSummary = budgets.summary(month);
        var upcoming = commitments.upcomingForUser(userId, today, 1);
        CurrencyTotals accountBalances = accountService.overview().totals();
        List<CreditCard> userCards = cards.findAllByUserIdOrderByArchivedAscNameAsc(userId);
        boolean singleCurrency = everythingSettlesInBase(userId, base, userCards, accountBalances);

        return new DashboardResponse(
                month,
                base.name(),
                accountBalances,
                income,
                expense,
                monthResult,
                savingsRate,
                previousExpense,
                variation,
                new BudgetOverview(
                        budgetSummary.totalLimit(),
                        budgetSummary.totalConsumed(),
                        budgetSummary.percentUsed(),
                        budgetSummary.budgets().size(),
                        budgetSummary.warningCount(),
                        budgetSummary.exceededCount(),
                        budgetSummary.incompleteCount()),
                topCategories(userId, month, flows.expenseByCurrency(month)),
                trend(flows, month, base),

                upcoming.items(),
                upcoming.totals(),
                goals.listForUser(userId).stream()
                        .filter(goal -> goal.status() != com.finora.api.goal.GoalDtos.GoalStatus.ARCHIVED)
                        .toList(),
                transactions.findTop10ByUserIdOrderByOccurredOnDescIdDesc(userId).stream()
                        .map(TransactionResponse::from)
                        .toList(),
                cardsOverview(userId, userCards, today, base, flows.cardExpense(month, base)),
                futureCash(userId, base, singleCurrency));
    }

    // ── Monthly flows ───────────────────────────────────────────────────────

    /**
     * Income and expense of the trend window, per month and per currency.
     *
     * <p>Card expense is folded into the expense side because that is when a
     * card charge is recognized: active installments plus net adjustments of the
     * invoice month. Invoice payments deliberately never enter — counting them
     * would recognize the same card spending twice.
     */
    private record MonthlyFlows(
            Map<YearMonth, Map<CurrencyCode, BigDecimal>> income,
            Map<YearMonth, Map<CurrencyCode, BigDecimal>> regularExpense,
            Map<YearMonth, Map<CurrencyCode, BigDecimal>> cardExpense) {

        CurrencyTotals income(YearMonth month, CurrencyCode base) {
            return CurrencyTotals.of(entriesOf(income, month), base);
        }

        CurrencyTotals expense(YearMonth month, CurrencyCode base) {
            return CurrencyTotals.of(expenseEntries(month), base);
        }

        CurrencyTotals cardExpense(YearMonth month, CurrencyCode base) {
            return CurrencyTotals.of(entriesOf(cardExpense, month), base);
        }

        /** Regular plus card expense, still tagged with each amount's currency. */
        List<Entry> expenseEntries(YearMonth month) {
            List<Entry> entries = new ArrayList<>(entriesOf(regularExpense, month));
            entries.addAll(entriesOf(cardExpense, month));
            return entries;
        }

        Map<CurrencyCode, BigDecimal> expenseByCurrency(YearMonth month) {
            EnumMap<CurrencyCode, BigDecimal> merged = new EnumMap<>(CurrencyCode.class);
            for (Entry entry : expenseEntries(month)) {
                merged.merge(entry.currency(), entry.amount(), BigDecimal::add);
            }
            return merged;
        }

        /** Income minus expense, kept signed and separated by currency. */
        List<Entry> signedResultEntries(YearMonth month) {
            List<Entry> entries = new ArrayList<>(entriesOf(income, month));
            for (Entry entry : expenseEntries(month)) {
                entries.add(new Entry(entry.amount().negate(), entry.currency()));
            }
            return entries;
        }

        /** Every currency that moved anywhere in the window. */
        java.util.Set<CurrencyCode> currenciesInWindow() {
            java.util.EnumSet<CurrencyCode> present = java.util.EnumSet.noneOf(CurrencyCode.class);
            for (var source : List.of(income, regularExpense, cardExpense)) {
                source.values().forEach(byCurrency -> present.addAll(byCurrency.keySet()));
            }
            return present;
        }

        private static List<Entry> entriesOf(
                Map<YearMonth, Map<CurrencyCode, BigDecimal>> source, YearMonth month) {
            return source.getOrDefault(month, Map.of()).entrySet().stream()
                    .map(entry -> new Entry(entry.getValue(), entry.getKey()))
                    .toList();
        }
    }

    private MonthlyFlows monthlyFlows(Long userId, YearMonth month) {
        // The window starts one month before the chart so the previous-month
        // comparison is covered by the same scan.
        YearMonth from = month.minusMonths(TREND_MONTHS);
        Map<YearMonth, Map<CurrencyCode, BigDecimal>> income = new LinkedHashMap<>();
        Map<YearMonth, Map<CurrencyCode, BigDecimal>> regularExpense = new LinkedHashMap<>();
        Map<YearMonth, Map<CurrencyCode, BigDecimal>> cardExpense = new LinkedHashMap<>();

        for (Object[] row : transactions.sumGroupedByMonthTypeAndCurrency(
                userId, from.atDay(1), month.atEndOfMonth())) {
            YearMonth key = YearMonth.of(((Number) row[0]).intValue(), ((Number) row[1]).intValue());
            Map<YearMonth, Map<CurrencyCode, BigDecimal>> target =
                    row[2] == TransactionType.INCOME ? income : regularExpense;
            add(target, key, (CurrencyCode) row[3], (BigDecimal) row[4]);
        }
        for (Object[] row : installments.sumActiveGroupedByMonthAndCurrency(
                userId, from.atDay(1), month.atDay(1))) {
            add(cardExpense, YearMonth.from((LocalDate) row[0]),
                    (CurrencyCode) row[1], (BigDecimal) row[2]);
        }
        for (Object[] row : adjustments.sumActiveNetGroupedByMonthAndCurrency(
                userId, from.atDay(1), month.atDay(1))) {
            add(cardExpense, YearMonth.from((LocalDate) row[0]),
                    (CurrencyCode) row[1], (BigDecimal) row[2]);
        }
        return new MonthlyFlows(income, regularExpense, cardExpense);
    }

    private static void add(Map<YearMonth, Map<CurrencyCode, BigDecimal>> target,
            YearMonth month, CurrencyCode currency, BigDecimal amount) {
        if (currency == null || amount == null) {
            return;
        }
        target.computeIfAbsent(month, key -> new EnumMap<>(CurrencyCode.class))
                .merge(currency, amount, BigDecimal::add);
    }

    // ── Sections ────────────────────────────────────────────────────────────

    /**
     * Compact 30-day future-cash view; the forecast service is the single source.
     *
     * <p>A projected balance is one running number. It exists only if everything
     * feeding it settles in one currency — otherwise it is withheld, because a
     * balance that had quietly added dollars to reais would be the single most
     * actionable wrong figure on the page.
     */
    private FutureCashOverview futureCash(Long userId, CurrencyCode base, boolean available) {
        var result = forecast.forecastForUser(userId, 30, null);
        long failed = occurrences.countByUserIdAndStatus(userId, OccurrenceStatus.FAILED);

        FutureCashEvent nextRecurring = result.events().stream()
                .filter(e -> e.source() == ForecastDtos.ForecastSource.RECURRING_ACCOUNT_OCCURRENCE
                        || e.source() == ForecastDtos.ForecastSource.PROJECTED_RECURRING_CARD_PURCHASE)
                .findFirst()
                .map(e -> new FutureCashEvent(e.date(), e.description(), e.amount(), base.name()))
                .orElse(null);
        FutureCashEvent nextInvoice = result.events().stream()
                .filter(e -> e.source() == ForecastDtos.ForecastSource.CARD_INVOICE)
                .findFirst()
                .map(e -> new FutureCashEvent(e.date(), e.description(), e.amount(), base.name()))
                .orElse(null);

        return new FutureCashOverview(
                available,
                available ? result.closingBalance() : null,
                available ? base.name() : null,
                available ? nextRecurring : null,
                available ? nextInvoice : null,
                available ? result.firstNegativeDate() : null,
                failed);
    }

    /**
     * Whether every monetary root the user owns settles in the base currency.
     *
     * <p>The forecast still folds every account, card, commitment and future
     * transaction into one running balance. That number is correct exactly when
     * there is nothing foreign anywhere — so rather than presenting a mixed
     * projection, the section is withheld until the forecast itself reports per
     * currency. Accounts and cards are already loaded; the two counts below
     * cover what neither would disclose: an accountless foreign transaction and
     * a projection-only foreign commitment.
     */
    private boolean everythingSettlesInBase(Long userId, CurrencyCode base,
            List<CreditCard> userCards, CurrencyTotals accountBalances) {
        if (!accountBalances.baseComplete()) {
            return false;
        }
        if (userCards.stream().anyMatch(card -> card.getCurrency() != base)) {
            return false;
        }
        return transactions.countByUserIdAndCurrencyNot(userId, base) == 0
                && commitmentRepository.countByUserIdAndCurrencyNot(userId, base) == 0;
    }

    private CardsOverview cardsOverview(Long userId, List<CreditCard> userCards, LocalDate today,
            CurrencyCode base, CurrencyTotals monthCardExpense) {
        if (userCards.isEmpty()) {
            return null;
        }
        List<Entry> available = new ArrayList<>();
        List<Entry> outstanding = new ArrayList<>();
        int overdueCount = 0;
        CardInvoiceBrief nextDue = null;
        for (CreditCard card : userCards) {
            CurrencyCode cardCurrency = card.getCurrency();
            if (!card.isArchived()) {
                available.add(new Entry(limits.limitOf(card).availableLimit(), cardCurrency));
            }
            for (InvoiceSummaryResponse invoice : invoices.listForCard(card.getId(), today)) {
                if (invoice.outstandingAmount().signum() <= 0) {
                    continue;
                }
                outstanding.add(new Entry(invoice.outstandingAmount(), cardCurrency));
                if (invoice.status() == InvoiceStatus.OVERDUE) {
                    overdueCount++;
                }
                if (nextDue == null || invoice.dueDate().isBefore(nextDue.dueDate())) {
                    nextDue = new CardInvoiceBrief(
                            card.getId(), card.getName(), invoice.id(),
                            invoice.referenceMonth(), invoice.dueDate(),
                            invoice.status(), invoice.outstandingAmount(),
                            cardCurrency.name());
                }
            }
        }
        List<RecentCardPurchase> recent = purchases
                .findTop5ByUserIdAndStatusOrderByPurchaseDateDescIdDesc(userId, PurchaseStatus.ACTIVE)
                .stream()
                .map(p -> new RecentCardPurchase(
                        p.getId(),
                        p.getCard().getId(),
                        p.getCard().getName(),
                        p.getDescription(),
                        p.getPurchaseDate(),
                        MoneyRules.normalize(p.getTotalAmount(), p.getCard().getCurrency()),
                        p.getCard().getCurrency().name(),
                        p.getInstallmentCount()))
                .toList();
        return new CardsOverview(
                userCards.size(),
                // A limit is a snapshot: a card with nothing outstanding has
                // genuinely nothing to convert.
                CurrencyTotals.ofSnapshots(outstanding, base),
                CurrencyTotals.ofSnapshots(available, base),
                // Recognized card expense comes from the same pass as the trend:
                // active installments plus net adjustments of the invoice month.
                // The invoice payment that settles them is cash movement, not a
                // second expense.
                monthCardExpense,
                overdueCount,
                nextDue,
                recent);
    }

    /**
     * Top expense categories of the month, per currency.
     *
     * <p>Regular expenses, card installments and categorized card adjustments
     * are merged — each amount counted exactly once, in its recognition month —
     * but only within the same currency. The share is measured against that
     * currency's own monthly expenses, which is a ratio between two comparable
     * operands rather than a slice of a mixed denominator.
     */
    private List<CategoryShare> topCategories(Long userId, YearMonth month,
            Map<CurrencyCode, BigDecimal> expenseByCurrency) {
        record Key(Long categoryId, CurrencyCode currency) {
        }
        record CategoryTotal(Long id, String name, CurrencyCode currency, BigDecimal amount) {
        }
        Map<Key, CategoryTotal> byKey = new LinkedHashMap<>();
        List<List<Object[]>> sources = List.of(
                transactions.sumExpensesGroupedByCategoryAndCurrency(
                        userId, month.atDay(1), month.atEndOfMonth()),
                installments.sumActiveGroupedByCategoryAndCurrency(userId, month.atDay(1)),
                adjustments.sumActiveNetGroupedByCategoryAndCurrency(userId, month.atDay(1)));
        for (List<Object[]> source : sources) {
            for (Object[] row : source) {
                Long id = (Long) row[0];
                if (id == null) {
                    continue;
                }
                CurrencyCode currency = (CurrencyCode) row[2];
                BigDecimal amount = (BigDecimal) row[3];
                byKey.merge(
                        new Key(id, currency),
                        new CategoryTotal(id, (String) row[1], currency, amount),
                        (a, b) -> new CategoryTotal(id, a.name(), currency,
                                a.amount().add(b.amount())));
            }
        }

        // Bounded output: at most TOP_CATEGORIES per currency, and the catalogue
        // is closed, so this cannot grow with the ledger.
        Map<CurrencyCode, List<CategoryTotal>> perCurrency = new EnumMap<>(CurrencyCode.class);
        for (CategoryTotal total : byKey.values()) {
            if (total.amount().signum() > 0) {
                perCurrency.computeIfAbsent(total.currency(), key -> new ArrayList<>()).add(total);
            }
        }
        List<CategoryShare> shares = new ArrayList<>();
        for (var entry : perCurrency.entrySet()) {
            CurrencyCode currency = entry.getKey();
            BigDecimal denominator = expenseByCurrency.getOrDefault(currency, BigDecimal.ZERO);
            entry.getValue().stream()
                    .sorted(Comparator.comparing(CategoryTotal::amount).reversed())
                    .limit(TOP_CATEGORIES)
                    .forEach(total -> shares.add(new CategoryShare(
                            total.id(),
                            total.name(),
                            MoneyRules.normalize(total.amount(), currency),
                            currency.name(),
                            denominator.signum() > 0
                                    ? total.amount().multiply(BigDecimal.valueOf(100))
                                            .divide(denominator, 1, RoundingMode.HALF_UP)
                                    : null)));
        }
        return List.copyOf(shares);
    }

    /**
     * One homogeneous series per currency that moved in the window.
     *
     * <p>Every series covers the same six months, gaps filled with that
     * currency's own zero, so two series stay comparable point by point without
     * their values ever being added. A user with no movement at all still gets
     * one base-currency series, so the chart has a denomination to label itself
     * with instead of an unlabelled axis.
     */
    private List<MonthTrendSeries> trend(MonthlyFlows flows, YearMonth month, CurrencyCode base) {
        var currencies = new java.util.ArrayList<>(flows.currenciesInWindow());
        if (currencies.isEmpty()) {
            currencies.add(base);
        }
        List<MonthTrendSeries> series = new ArrayList<>();
        for (CurrencyCode currency : currencies) {
            List<MonthTrendPoint> points = new ArrayList<>();
            for (int i = TREND_MONTHS - 1; i >= 0; i--) {
                YearMonth m = month.minusMonths(i);
                Map<CurrencyCode, BigDecimal> income = flows.income().getOrDefault(m, Map.of());
                Map<CurrencyCode, BigDecimal> expense = flows.expenseByCurrency(m);
                points.add(new MonthTrendPoint(
                        m,
                        MoneyRules.normalize(
                                income.getOrDefault(currency, BigDecimal.ZERO), currency),
                        MoneyRules.normalize(
                                expense.getOrDefault(currency, BigDecimal.ZERO), currency)));
            }
            series.add(new MonthTrendSeries(currency.name(), List.copyOf(points)));
        }
        return List.copyOf(series);
    }
}
