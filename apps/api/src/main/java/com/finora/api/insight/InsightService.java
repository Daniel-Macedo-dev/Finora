package com.finora.api.insight;

import com.finora.api.budget.BudgetDtos.BudgetResponse;
import com.finora.api.budget.BudgetDtos.BudgetStatus;
import com.finora.api.budget.BudgetService;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.common.money.CurrencyTotals;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.CardLimitService;
import com.finora.api.creditcard.CreditCard;
import com.finora.api.creditcard.CreditCardRepository;
import com.finora.api.creditcard.invoice.InvoiceDtos.InvoiceSummaryResponse;
import com.finora.api.creditcard.invoice.InvoiceService;
import com.finora.api.creditcard.invoice.InvoiceStatus;
import com.finora.api.goal.GoalDtos.GoalResponse;
import com.finora.api.goal.GoalDtos.GoalStatus;
import com.finora.api.goal.GoalService;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.insight.InsightDtos.AggregateCoverage;
import com.finora.api.insight.InsightDtos.Insight;
import com.finora.api.insight.InsightDtos.InsightRule;
import com.finora.api.insight.InsightDtos.InsightSeverity;
import com.finora.api.insight.InsightDtos.InsightsResponse;
import com.finora.api.purchaseanalysis.PurchaseFinancialContext;
import com.finora.api.purchaseanalysis.PurchaseFinancialContextService;
import com.finora.api.settings.AppSettings;
import com.finora.api.settings.SettingsService;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import com.finora.api.wishlist.PurchaseOption;
import com.finora.api.wishlist.PurchaseOptionKind;
import com.finora.api.wishlist.WishlistItem;
import com.finora.api.wishlist.WishlistItemRepository;
import com.finora.api.wishlist.WishlistStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic insight rules over real data.
 *
 * <p>Every rule only fires when the data needed to justify it exists — no
 * insight is fabricated from missing data, and none is fabricated from
 * incomparable data either. Two kinds of rule live here and they fail
 * differently:
 *
 * <ul>
 *   <li><strong>Resource-native</strong> — an overdue invoice, a card running
 *       out of limit. Every operand belongs to one card and shares its currency,
 *       so the conclusion is true regardless of what else the ledger holds. These
 *       are never withheld, and they are stated in the card's own currency.</li>
 *   <li><strong>Base-currency aggregate</strong> — expense growth, category
 *       dominance, commitment share, installment burden, goal pace, wishlist
 *       affordability. Each compares or divides amounts drawn from across the
 *       ledger, so it is only meaningful when those amounts are all already in
 *       the base currency. Without an exchange rate, the honest output is
 *       silence plus a note saying so.</li>
 * </ul>
 *
 * <p>That note is {@link AggregateCoverage}, and it is deliberately narrow: it
 * reports a rule only when the rule had relevant input and a currency mismatch
 * stopped it. A user with no previous month, no history or no goals is not
 * having a currency problem, and saying otherwise would make an ordinary empty
 * account look broken.
 */
@Service
public class InsightService {

    /** Expense growth vs previous month that triggers a warning (20%). */
    static final BigDecimal EXPENSE_INCREASE_THRESHOLD = new BigDecimal("1.20");
    /** Share of total expenses that makes a single category dominant (40%). */
    static final BigDecimal DOMINANT_CATEGORY_SHARE = new BigDecimal("0.40");
    /** Share of average income taken by recurring commitments that deserves attention (30%). */
    static final BigDecimal COMMITMENT_SHARE_THRESHOLD = new BigDecimal("0.30");
    /** Card utilization that deserves attention (80%). */
    static final BigDecimal CARD_UTILIZATION_THRESHOLD = new BigDecimal("80.0");
    /** Days ahead in which an unpaid invoice counts as "due soon". */
    static final int INVOICE_DUE_SOON_DAYS = 7;
    /** Share of average income taken by next month's card installments (30%). */
    static final BigDecimal CARD_BURDEN_THRESHOLD = new BigDecimal("0.30");

    private final TransactionRepository transactions;
    private final BudgetService budgets;
    private final GoalService goals;
    private final WishlistItemRepository wishlist;
    private final PurchaseFinancialContextService contextService;
    private final SettingsService settings;
    private final CreditCardRepository cards;
    private final CardLimitService cardLimits;
    private final InvoiceService invoices;
    private final CurrentUserProvider currentUser;

    public InsightService(TransactionRepository transactions,
                          BudgetService budgets,
                          GoalService goals,
                          WishlistItemRepository wishlist,
                          PurchaseFinancialContextService contextService,
                          SettingsService settings,
                          CreditCardRepository cards,
                          CardLimitService cardLimits,
                          InvoiceService invoices,
                          CurrentUserProvider currentUser) {
        this.transactions = transactions;
        this.budgets = budgets;
        this.goals = goals;
        this.wishlist = wishlist;
        this.contextService = contextService;
        this.settings = settings;
        this.cards = cards;
        this.cardLimits = cardLimits;
        this.invoices = invoices;
        this.currentUser = currentUser;
    }

    /**
     * Accumulates what an exchange rate would have unlocked.
     *
     * <p>A currency is recorded only alongside the rule it actually blocked, so
     * a user who merely owns a dollar card — while every aggregate rule ran
     * fine on reais — is told nothing was withheld, because nothing was.
     */
    private static final class Coverage {
        private final EnumSet<CurrencyCode> missing = EnumSet.noneOf(CurrencyCode.class);
        private final EnumSet<InsightRule> unavailable = EnumSet.noneOf(InsightRule.class);

        void withhold(InsightRule rule, List<String> blockingCurrencies) {
            unavailable.add(rule);
            blockingCurrencies.forEach(code -> missing.add(CurrencyCode.parse(code)));
        }

        void withhold(InsightRule rule, CurrencyCode blocking) {
            unavailable.add(rule);
            missing.add(blocking);
        }

        /** Both lists follow catalogue and declaration order, so runs match. */
        AggregateCoverage toResponse() {
            if (unavailable.isEmpty()) {
                return AggregateCoverage.nothingWithheld();
            }
            List<String> currencies = new ArrayList<>(missing.size());
            missing.forEach(currency -> currencies.add(currency.name()));
            List<String> rules = new ArrayList<>(unavailable.size());
            unavailable.forEach(rule -> rules.add(rule.name()));
            return new AggregateCoverage(false, List.copyOf(currencies), List.copyOf(rules));
        }
    }

    /** Every rule below reads exclusively the authenticated user's data. */
    @Transactional(readOnly = true)
    public InsightsResponse generate(YearMonth month, LocalDate today) {
        Long userId = currentUser.currentUserId();
        List<Insight> insights = new ArrayList<>();
        Coverage coverage = new Coverage();
        AppSettings config = settings.forUser(userId);
        PurchaseFinancialContext context = contextService.build(userId, today);
        CurrencyCode base = CurrencyCode.parse(context.baseCurrency());

        // One grouped query spans both months; the two totals are split out of
        // its rows rather than fetched separately.
        MonthlyExpenses expenses = monthlyExpenses(userId, month, base);

        expenseIncrease(insights, coverage, expenses, base);
        dominantCategory(insights, coverage, userId, month, expenses, base);
        budgetAlerts(insights, coverage, month, base);
        commitmentShare(insights, coverage, context, base);
        cardInvoiceAlerts(insights, userId, today);
        cardUtilization(insights, userId);
        cardInstallmentBurden(insights, coverage, context, base);
        goalPace(insights, coverage, userId, context, base);
        affordableWishlist(insights, coverage, userId, context, config, base);

        return new InsightsResponse(month, base.name(), List.copyOf(insights),
                coverage.toResponse());
    }

    // ── Monthly expenses, grouped ────────────────────────────────────────────

    /**
     * The two months this response reasons about, each grouped by currency.
     *
     * <p>{@code of} rather than {@code ofSnapshots}: these are flows, and a
     * currency whose month happens to net to zero still had events a future rate
     * would convert at different moments.
     */
    private record MonthlyExpenses(CurrencyTotals current, CurrencyTotals previous) {

        /** Whether the period had any expense at all, in any currency. */
        private static boolean any(CurrencyTotals totals) {
            return totals.byCurrency().stream().anyMatch(entry -> entry.amount().signum() > 0);
        }

        boolean anyCurrent() {
            return any(current);
        }

        boolean anyPrevious() {
            return any(previous);
        }
    }

    private MonthlyExpenses monthlyExpenses(Long userId, YearMonth month, CurrencyCode base) {
        YearMonth previous = month.minusMonths(1);
        List<CurrencyTotals.Entry> currentEntries = new ArrayList<>();
        List<CurrencyTotals.Entry> previousEntries = new ArrayList<>();
        for (Object[] row : transactions.sumGroupedByMonthTypeAndCurrency(
                userId, previous.atDay(1), month.atEndOfMonth())) {
            if (row[2] != TransactionType.EXPENSE || row[3] == null || row[4] == null) {
                continue;
            }
            YearMonth rowMonth = YearMonth.of(
                    ((Number) row[0]).intValue(), ((Number) row[1]).intValue());
            CurrencyTotals.Entry entry =
                    new CurrencyTotals.Entry((BigDecimal) row[4], (CurrencyCode) row[3]);
            if (rowMonth.equals(month)) {
                currentEntries.add(entry);
            } else if (rowMonth.equals(previous)) {
                previousEntries.add(entry);
            }
        }
        return new MonthlyExpenses(
                CurrencyTotals.of(currentEntries, base),
                CurrencyTotals.of(previousEntries, base));
    }

    // ── Aggregate rules ──────────────────────────────────────────────────────

    /**
     * Growth is a ratio, so both months must be denominated the same way.
     *
     * <p>A month of reais over a month of dollars is not an approximate
     * percentage — it is a number with no meaning, and it would be shown to the
     * user as a headline.
     */
    private void expenseIncrease(List<Insight> insights, Coverage coverage,
                                 MonthlyExpenses expenses, CurrencyCode base) {
        if (!expenses.anyCurrent() || !expenses.anyPrevious()) {
            // Nothing to compare. An ordinary absence, not a rate problem.
            return;
        }
        if (!expenses.current().baseComplete() || !expenses.previous().baseComplete()) {
            coverage.withhold(InsightRule.EXPENSE_INCREASE, blocking(expenses));
            return;
        }
        BigDecimal current = expenses.current().baseTotal();
        BigDecimal earlier = expenses.previous().baseTotal();
        if (earlier.signum() <= 0 || current.signum() <= 0) {
            return;
        }
        BigDecimal ratio = current.divide(earlier, MoneyRules.RATE_SCALE, RoundingMode.HALF_UP);
        if (ratio.compareTo(EXPENSE_INCREASE_THRESHOLD) >= 0) {
            BigDecimal percent = ratio.subtract(BigDecimal.ONE)
                    .multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP);
            insights.add(new Insight(
                    "EXPENSE_INCREASE",
                    InsightSeverity.WARNING,
                    "Gastos subiram em relação ao mês anterior",
                    "As despesas do mês estão %s%% acima do mês anterior.".formatted(percent),
                    MoneyRules.normalize(current.subtract(earlier), base),
                    base.name()));
        }
    }

    private static List<String> blocking(MonthlyExpenses expenses) {
        List<String> blocking = new ArrayList<>(expenses.current().unconvertedCurrencies());
        blocking.addAll(expenses.previous().unconvertedCurrencies());
        return blocking;
    }

    /**
     * "This category is 40% of your spending" is a claim about the whole month.
     *
     * <p>With foreign expenses present it cannot be made: the denominator is
     * unknowable without a rate, and a base-only denominator would inflate every
     * base category's share.
     */
    private void dominantCategory(List<Insight> insights, Coverage coverage, Long userId,
                                  YearMonth month, MonthlyExpenses expenses, CurrencyCode base) {
        if (!expenses.anyCurrent()) {
            return;
        }
        if (!expenses.current().baseComplete()) {
            coverage.withhold(InsightRule.CATEGORY_DOMINANT,
                    expenses.current().unconvertedCurrencies());
            return;
        }
        BigDecimal total = expenses.current().baseTotal();
        if (total.signum() <= 0) {
            return;
        }
        String topName = null;
        BigDecimal topAmount = null;
        for (Object[] row : transactions.sumExpensesGroupedByCategoryAndCurrency(
                userId, month.atDay(1), month.atEndOfMonth())) {
            if (row[2] != base || row[3] == null) {
                continue;
            }
            BigDecimal amount = (BigDecimal) row[3];
            if (topAmount == null || amount.compareTo(topAmount) > 0) {
                topAmount = amount;
                topName = (String) row[1];
            }
        }
        if (topAmount == null) {
            return;
        }
        BigDecimal share = topAmount.divide(total, MoneyRules.RATE_SCALE, RoundingMode.HALF_UP);
        if (share.compareTo(DOMINANT_CATEGORY_SHARE) >= 0) {
            insights.add(new Insight(
                    "CATEGORY_DOMINANT",
                    InsightSeverity.INFO,
                    "Uma categoria concentra os gastos",
                    "%s representa %s%% das despesas do mês.".formatted(
                            topName,
                            share.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP)),
                    MoneyRules.normalize(topAmount, base),
                    base.name()));
        }
    }

    /**
     * Budget alerts are per budget and already base-denominated.
     *
     * <p>{@code INCOMPLETE} is its own status precisely so a budget with foreign
     * spending in its category can never be called exceeded or healthy from the
     * part that happens to be in reais. Those budgets are reported through
     * coverage instead of through a conclusion.
     */
    private void budgetAlerts(List<Insight> insights, Coverage coverage, YearMonth month,
                              CurrencyCode base) {
        List<BudgetResponse> monthBudgets = budgets.summary(month).budgets();
        monthBudgets.stream()
                .filter(budget -> budget.status() == BudgetStatus.EXCEEDED)
                .forEach(budget -> insights.add(new Insight(
                        "BUDGET_EXCEEDED",
                        InsightSeverity.CRITICAL,
                        "Orçamento estourado: " + budget.category().name(),
                        "O orçamento de %s foi ultrapassado: %s gastos de um limite de %s.".formatted(
                                budget.category().name(),
                                money(budget.consumedAmount(), base),
                                money(budget.limitAmount(), base)),
                        MoneyRules.normalize(
                                budget.consumedAmount().subtract(budget.limitAmount()), base),
                        base.name())));
        monthBudgets.stream()
                .filter(budget -> budget.status() == BudgetStatus.WARNING)
                .forEach(budget -> insights.add(new Insight(
                        "BUDGET_NEAR_LIMIT",
                        InsightSeverity.WARNING,
                        "Orçamento perto do limite: " + budget.category().name(),
                        "%s já consumiu %s%% do limite mensal.".formatted(
                                budget.category().name(),
                                budget.percentUsed().setScale(0, RoundingMode.HALF_UP)),
                        MoneyRules.normalize(budget.remainingAmount(), base),
                        base.name())));
        monthBudgets.stream()
                .filter(budget -> budget.status() == BudgetStatus.INCOMPLETE)
                .forEach(budget -> coverage.withhold(InsightRule.BUDGET_STATUS,
                        budget.consumedTotals().unconvertedCurrencies()));
    }

    /** Commitments over income: a ratio, so both sides must be base-complete. */
    private void commitmentShare(List<Insight> insights, Coverage coverage,
                                 PurchaseFinancialContext context, CurrencyCode base) {
        if (!context.averageIncome().anyHistory() || !hasValue(context.monthlyCommitments())) {
            return;
        }
        if (!context.averageIncome().baseComplete() || !context.monthlyCommitments().baseComplete()) {
            coverage.withhold(InsightRule.COMMITMENT_SHARE_HIGH,
                    both(context.averageIncome().unconvertedCurrencies(),
                            context.monthlyCommitments().unconvertedCurrencies()));
            return;
        }
        BigDecimal income = context.averageIncome().baseAverage();
        BigDecimal commitments = context.monthlyCommitments().baseTotal();
        if (income == null || income.signum() <= 0 || commitments.signum() <= 0) {
            return;
        }
        BigDecimal share = commitments.divide(income, MoneyRules.RATE_SCALE, RoundingMode.HALF_UP);
        if (share.compareTo(COMMITMENT_SHARE_THRESHOLD) >= 0) {
            insights.add(new Insight(
                    "COMMITMENT_SHARE_HIGH",
                    InsightSeverity.WARNING,
                    "Compromissos recorrentes pesam na renda",
                    "Os compromissos recorrentes do próximo mês somam %s, cerca de %s%% da renda média observada."
                            .formatted(
                                    money(commitments, base),
                                    share.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP)),
                    commitments,
                    base.name()));
        }
    }

    /** Next month's card installments already claiming a big slice of the income. */
    private void cardInstallmentBurden(List<Insight> insights, Coverage coverage,
                                       PurchaseFinancialContext context, CurrencyCode base) {
        if (!context.averageIncome().anyHistory()
                || !hasValue(context.nextMonthCardInstallments())) {
            return;
        }
        if (!context.averageIncome().baseComplete()
                || !context.nextMonthCardInstallments().baseComplete()) {
            coverage.withhold(InsightRule.CARD_INSTALLMENT_BURDEN_HIGH,
                    both(context.averageIncome().unconvertedCurrencies(),
                            context.nextMonthCardInstallments().unconvertedCurrencies()));
            return;
        }
        BigDecimal income = context.averageIncome().baseAverage();
        BigDecimal installments = context.nextMonthCardInstallments().baseTotal();
        if (income == null || income.signum() <= 0 || installments.signum() <= 0) {
            return;
        }
        BigDecimal share = installments.divide(income, MoneyRules.RATE_SCALE, RoundingMode.HALF_UP);
        if (share.compareTo(CARD_BURDEN_THRESHOLD) >= 0) {
            insights.add(new Insight(
                    "CARD_INSTALLMENT_BURDEN_HIGH",
                    InsightSeverity.WARNING,
                    "Parcelas de cartão pesam no próximo mês",
                    ("As parcelas de cartão já programadas para o próximo mês somam %s, cerca de %s%% "
                            + "da renda média observada.").formatted(
                            money(installments, base),
                            share.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP)),
                    installments,
                    base.name()));
        }
    }

    /**
     * A goal's pace is measured against the surplus that would fund it.
     *
     * <p>Both sides therefore have to be the same money. A dollar goal keeps
     * existing and displaying normally in its own screen; what it does not get
     * is a verdict derived from a real-denominated surplus.
     */
    private void goalPace(List<Insight> insights, Coverage coverage, Long userId,
                          PurchaseFinancialContext context, CurrencyCode base) {
        List<GoalResponse> candidates = goals.listForUser(userId).stream()
                .filter(goal -> goal.status() == GoalStatus.IN_PROGRESS)
                .filter(goal -> goal.suggestedMonthlyContribution() != null)
                .toList();
        if (candidates.isEmpty()) {
            return;
        }
        boolean surplusUsable = context.averageSurplus().anyHistory()
                && context.averageSurplus().baseComplete()
                && context.averageSurplus().baseAverage() != null;
        if (!surplusUsable) {
            if (context.averageSurplus().anyHistory()) {
                coverage.withhold(InsightRule.GOAL_OFF_PACE,
                        context.averageSurplus().unconvertedCurrencies());
            }
            // No history at all is an ordinary absence, not a rate problem.
            return;
        }
        BigDecimal surplus = context.averageSurplus().baseAverage();
        for (GoalResponse goal : candidates) {
            CurrencyCode goalCurrency = CurrencyCode.parse(goal.currency());
            if (goalCurrency != base) {
                coverage.withhold(InsightRule.GOAL_OFF_PACE, goalCurrency);
                continue;
            }
            if (goal.suggestedMonthlyContribution().compareTo(surplus) > 0) {
                insights.add(new Insight(
                        "GOAL_OFF_PACE",
                        InsightSeverity.WARNING,
                        "Meta fora do ritmo: " + goal.name(),
                        ("Para alcançar %s na data desejada seriam necessários %s por mês, acima da sobra média "
                                + "mensal de %s.").formatted(
                                goal.name(),
                                money(goal.suggestedMonthlyContribution(), base),
                                money(surplus, base)),
                        MoneyRules.normalize(goal.suggestedMonthlyContribution(), base),
                        base.name()));
            }
        }
    }

    /**
     * "You can afford this" is the most actionable thing the dashboard says.
     *
     * <p>So the buffer is subtracted only once the cash it is subtracted from is
     * proven to be one currency, and only a base-denominated item is ever
     * measured against it. A foreign item is not cheap and not expensive here —
     * it is simply not comparable, and saying nothing is the correct output.
     */
    private void affordableWishlist(List<Insight> insights, Coverage coverage, Long userId,
                                    PurchaseFinancialContext context, AppSettings config,
                                    CurrencyCode base) {
        List<WishlistItem> candidates = wishlist.findAllByUserIdAndStatusIn(
                userId,
                List.of(WishlistStatus.PLANNING, WishlistStatus.MONITORING, WishlistStatus.READY_TO_BUY))
                .stream()
                .filter(item -> item.getOptions().stream()
                        .anyMatch(option -> option.getKind() == PurchaseOptionKind.CASH))
                .toList();
        if (candidates.isEmpty()) {
            return;
        }
        if (!context.availableCash().baseComplete()) {
            coverage.withhold(InsightRule.WISHLIST_AFFORDABLE,
                    context.availableCash().unconvertedCurrencies());
            return;
        }
        BigDecimal spendable = MoneyRules.normalize(
                context.availableCash().baseTotal().subtract(config.getMinimumCashBuffer()), base);
        for (WishlistItem item : candidates) {
            if (item.getCurrency() != base) {
                coverage.withhold(InsightRule.WISHLIST_AFFORDABLE, item.getCurrency());
                continue;
            }
            if (spendable.signum() <= 0) {
                continue;
            }
            item.getOptions().stream()
                    .filter(option -> option.getKind() == PurchaseOptionKind.CASH)
                    .map(PurchaseOption::nominalCost)
                    .min(Comparator.naturalOrder())
                    .filter(cheapest -> cheapest.compareTo(spendable) <= 0)
                    .ifPresent(cheapest -> insights.add(new Insight(
                            "WISHLIST_AFFORDABLE",
                            InsightSeverity.POSITIVE,
                            "Compra viável: " + item.getName(),
                            ("%s pode ser comprado à vista por %s mantendo a reserva mínima de caixa. "
                                    + "Veja a análise completa na lista de desejos.").formatted(
                                    item.getName(), money(cheapest, base)),
                            MoneyRules.normalize(cheapest, base),
                            base.name())));
        }
    }

    // ── Resource-native rules ────────────────────────────────────────────────

    /**
     * Overdue invoices are critical; invoices due within the next days warn.
     *
     * <p>Native to one card: the outstanding amount, the invoice and the card
     * share a denomination, so this stays true and stays visible however many
     * currencies the rest of the ledger holds.
     */
    private void cardInvoiceAlerts(List<Insight> insights, Long userId, LocalDate today) {
        for (CreditCard card : cards.findAllByUserIdOrderByArchivedAscNameAsc(userId)) {
            CurrencyCode currency = card.getCurrency();
            for (InvoiceSummaryResponse invoice : invoices.listForCard(card.getId(), today)) {
                if (invoice.outstandingAmount().signum() <= 0) {
                    continue;
                }
                if (invoice.status() == InvoiceStatus.OVERDUE) {
                    insights.add(new Insight(
                            "INVOICE_OVERDUE",
                            InsightSeverity.CRITICAL,
                            "Fatura vencida: " + card.getName(),
                            "A fatura de %s venceu em %s com %s em aberto.".formatted(
                                    card.getName(),
                                    formatDate(invoice.dueDate()),
                                    money(invoice.outstandingAmount(), currency)),
                            MoneyRules.normalize(invoice.outstandingAmount(), currency),
                            currency.name()));
                } else if (!invoice.dueDate().isBefore(today)
                        && !invoice.dueDate().isAfter(today.plusDays(INVOICE_DUE_SOON_DAYS))) {
                    boolean partial = invoice.amountPaid().signum() > 0;
                    insights.add(new Insight(
                            "INVOICE_DUE_SOON",
                            InsightSeverity.WARNING,
                            "Fatura vence em breve: " + card.getName(),
                            (partial
                                    ? "A fatura de %s vence em %s e ainda tem %s em aberto após pagamento parcial."
                                    : "A fatura de %s vence em %s com %s em aberto.").formatted(
                                    card.getName(),
                                    formatDate(invoice.dueDate()),
                                    money(invoice.outstandingAmount(), currency)),
                            MoneyRules.normalize(invoice.outstandingAmount(), currency),
                            currency.name()));
                }
            }
        }
    }

    /**
     * High utilization means little limit left for the month's remaining
     * spending. Limit, used and available all belong to the same card, and the
     * percentage between two of them is dimensionless — so this is native too.
     */
    private void cardUtilization(List<Insight> insights, Long userId) {
        for (CreditCard card : cards.findAllByUserIdOrderByArchivedAscNameAsc(userId)) {
            if (card.isArchived()) {
                continue;
            }
            CurrencyCode currency = card.getCurrency();
            var limit = cardLimits.limitOf(card);
            if (limit.utilizationPercent().compareTo(CARD_UTILIZATION_THRESHOLD) >= 0) {
                insights.add(new Insight(
                        "CARD_UTILIZATION_HIGH",
                        InsightSeverity.WARNING,
                        "Limite quase comprometido: " + card.getName(),
                        "O cartão %s está com %s%% do limite em uso; restam %s disponíveis.".formatted(
                                card.getName(),
                                limit.utilizationPercent().setScale(0, RoundingMode.HALF_UP),
                                money(limit.availableLimit(), currency)),
                        MoneyRules.normalize(limit.availableLimit(), currency),
                        currency.name()));
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Whether the grouping holds anything positive, in any currency. */
    private static boolean hasValue(CurrencyTotals totals) {
        return totals.byCurrency().stream().anyMatch(entry -> entry.amount().signum() > 0);
    }

    private static List<String> both(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static String formatDate(LocalDate date) {
        return date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    /** Formats in the currency the amount is actually denominated in. */
    private static String money(BigDecimal value, CurrencyCode currency) {
        return MoneyRules.format(value, currency);
    }
}
