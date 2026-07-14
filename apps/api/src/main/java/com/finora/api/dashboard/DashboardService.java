package com.finora.api.dashboard;

import com.finora.api.account.AccountBalanceService;
import com.finora.api.account.AccountRepository;
import com.finora.api.budget.BudgetDtos.BudgetSummaryResponse;
import com.finora.api.budget.BudgetService;
import com.finora.api.commitment.CommitmentService;
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
import com.finora.api.commitment.occurrence.CommitmentOccurrenceRepository;
import com.finora.api.commitment.occurrence.OccurrenceStatus;
import com.finora.api.dashboard.DashboardDtos.DashboardResponse;
import com.finora.api.dashboard.DashboardDtos.FutureCashEvent;
import com.finora.api.dashboard.DashboardDtos.FutureCashOverview;
import com.finora.api.dashboard.DashboardDtos.MonthTrendPoint;
import com.finora.api.dashboard.DashboardDtos.RecentCardPurchase;
import com.finora.api.forecast.ForecastDtos;
import com.finora.api.forecast.ForecastService;
import com.finora.api.goal.GoalService;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.transaction.TransactionDtos.TransactionResponse;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    static final int TREND_MONTHS = 6;
    static final int TOP_CATEGORIES = 5;

    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final AccountBalanceService balances;
    private final BudgetService budgets;
    private final CommitmentService commitments;
    private final GoalService goals;
    private final CreditCardRepository cards;
    private final CardInstallmentRepository installments;
    private final InvoiceAdjustmentRepository adjustments;
    private final CardPurchaseRepository purchases;
    private final CardLimitService limits;
    private final InvoiceService invoices;
    private final ForecastService forecast;
    private final CommitmentOccurrenceRepository occurrences;
    private final CurrentUserProvider currentUser;

    public DashboardService(TransactionRepository transactions,
                            AccountRepository accounts,
                            AccountBalanceService balances,
                            BudgetService budgets,
                            CommitmentService commitments,
                            GoalService goals,
                            CreditCardRepository cards,
                            CardInstallmentRepository installments,
                            InvoiceAdjustmentRepository adjustments,
                            CardPurchaseRepository purchases,
                            CardLimitService limits,
                            InvoiceService invoices,
                            ForecastService forecast,
                            CommitmentOccurrenceRepository occurrences,
                            CurrentUserProvider currentUser) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.balances = balances;
        this.budgets = budgets;
        this.commitments = commitments;
        this.goals = goals;
        this.cards = cards;
        this.installments = installments;
        this.adjustments = adjustments;
        this.purchases = purchases;
        this.limits = limits;
        this.invoices = invoices;
        this.forecast = forecast;
        this.occurrences = occurrences;
        this.currentUser = currentUser;
    }

    /** Every section below aggregates exclusively the authenticated user's data. */
    @Transactional(readOnly = true)
    public DashboardResponse build(YearMonth month, LocalDate today) {
        Long userId = currentUser.currentUserId();
        BigDecimal income = sum(userId, TransactionType.INCOME, month);
        // Monthly expense = regular expenses + card expense recognized in the
        // month (active installments + net adjustments of that invoice month).
        // Invoice payments never enter here — counting them would double the
        // card spending the installments already recognized.
        BigDecimal expense = sum(userId, TransactionType.EXPENSE, month)
                .add(cardExpense(userId, month));
        BigDecimal previousExpense = sum(userId, TransactionType.EXPENSE, month.minusMonths(1))
                .add(cardExpense(userId, month.minusMonths(1)));

        BigDecimal savingsRate = null;
        if (income.signum() > 0) {
            savingsRate = income.subtract(expense)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(income, 1, RoundingMode.HALF_UP);
        }

        BigDecimal variation = null;
        if (previousExpense.signum() > 0) {
            variation = expense.subtract(previousExpense)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(previousExpense, 1, RoundingMode.HALF_UP);
        }

        BudgetSummaryResponse budgetSummary = budgets.summary(month);
        var upcoming = commitments.upcomingForUser(userId, today, 1);

        return new DashboardResponse(
                month,
                totalBalance(userId),
                MoneyRules.normalize(income),
                MoneyRules.normalize(expense),
                MoneyRules.normalize(income.subtract(expense)),
                savingsRate,
                MoneyRules.normalize(previousExpense),
                variation,
                new BudgetOverview(
                        budgetSummary.totalLimit(),
                        budgetSummary.totalConsumed(),
                        budgetSummary.percentUsed(),
                        budgetSummary.budgets().size(),
                        budgetSummary.warningCount(),
                        budgetSummary.exceededCount()),
                topCategories(userId, month, expense),
                trend(userId, month),
                upcoming.items(),
                upcoming.totalAmount(),
                goals.listForUser(userId).stream()
                        .filter(goal -> goal.status() != com.finora.api.goal.GoalDtos.GoalStatus.ARCHIVED)
                        .toList(),
                transactions.findTop10ByUserIdOrderByOccurredOnDescIdDesc(userId).stream()
                        .map(TransactionResponse::from)
                        .toList(),
                cardsOverview(userId, month, today),
                futureCash(userId));
    }

    /** Compact 30-day future-cash view; the forecast service is the single source. */
    private FutureCashOverview futureCash(Long userId) {
        var result = forecast.forecastForUser(userId, 30, null);
        FutureCashEvent nextRecurring = result.events().stream()
                .filter(e -> e.source() == ForecastDtos.ForecastSource.RECURRING_ACCOUNT_OCCURRENCE
                        || e.source() == ForecastDtos.ForecastSource.PROJECTED_RECURRING_CARD_PURCHASE)
                .findFirst()
                .map(e -> new FutureCashEvent(e.date(), e.description(), e.amount()))
                .orElse(null);
        FutureCashEvent nextInvoice = result.events().stream()
                .filter(e -> e.source() == ForecastDtos.ForecastSource.CARD_INVOICE)
                .findFirst()
                .map(e -> new FutureCashEvent(e.date(), e.description(), e.amount()))
                .orElse(null);
        return new FutureCashOverview(
                result.closingBalance(),
                nextRecurring,
                nextInvoice,
                result.firstNegativeDate(),
                occurrences.countByUserIdAndStatus(userId, OccurrenceStatus.FAILED));
    }

    /** Card expense recognized in a month: active installments + net adjustments. */
    private BigDecimal cardExpense(Long userId, YearMonth month) {
        return installments.sumActiveByMonth(userId, month.atDay(1))
                .add(adjustments.sumActiveNetByMonth(userId, month.atDay(1)));
    }

    private CardsOverview cardsOverview(Long userId, YearMonth month, LocalDate today) {
        List<CreditCard> userCards = cards.findAllByUserIdOrderByArchivedAscNameAsc(userId);
        if (userCards.isEmpty()) {
            return null;
        }
        BigDecimal totalAvailable = BigDecimal.ZERO;
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        int overdueCount = 0;
        CardInvoiceBrief nextDue = null;
        for (CreditCard card : userCards) {
            if (!card.isArchived()) {
                totalAvailable = totalAvailable.add(limits.limitOf(card).availableLimit());
            }
            for (InvoiceSummaryResponse invoice : invoices.listForCard(card.getId(), today)) {
                if (invoice.outstandingAmount().signum() <= 0) {
                    continue;
                }
                totalOutstanding = totalOutstanding.add(invoice.outstandingAmount());
                if (invoice.status() == InvoiceStatus.OVERDUE) {
                    overdueCount++;
                }
                if (nextDue == null || invoice.dueDate().isBefore(nextDue.dueDate())) {
                    nextDue = new CardInvoiceBrief(
                            card.getId(), card.getName(), invoice.id(),
                            invoice.referenceMonth(), invoice.dueDate(),
                            invoice.status(), invoice.outstandingAmount());
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
                        MoneyRules.normalize(p.getTotalAmount()),
                        p.getInstallmentCount()))
                .toList();
        return new CardsOverview(
                userCards.size(),
                MoneyRules.normalize(totalOutstanding),
                MoneyRules.normalize(totalAvailable),
                MoneyRules.normalize(cardExpense(userId, month)),
                overdueCount,
                nextDue,
                recent);
    }

    private BigDecimal totalBalance(Long userId) {
        return MoneyRules.normalize(accounts.findAllByUserIdOrderByDisplayOrderAscNameAsc(userId)
                .stream()
                .filter(account -> !account.isArchived())
                .map(balances::currentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private BigDecimal sum(Long userId, TransactionType type, YearMonth month) {
        return transactions.sumAmountByTypeAndPeriod(userId, type, month.atDay(1), month.atEndOfMonth());
    }

    /**
     * Top expense categories of the month, merging regular expenses with card
     * installments and categorized card adjustments — each amount counted
     * exactly once, in its recognition month.
     */
    private List<CategoryShare> topCategories(Long userId, YearMonth month, BigDecimal totalExpense) {
        if (totalExpense.signum() <= 0) {
            return List.of();
        }
        record CategoryTotal(Long id, String name, BigDecimal amount) {
        }
        Map<Long, CategoryTotal> byCategory = new LinkedHashMap<>();
        List<List<Object[]>> sources = List.of(
                transactions.sumExpensesGroupedByCategory(userId, month.atDay(1), month.atEndOfMonth()),
                installments.sumActiveGroupedByCategory(userId, month.atDay(1)),
                adjustments.sumActiveNetGroupedByCategory(userId, month.atDay(1)));
        for (List<Object[]> source : sources) {
            for (Object[] row : source) {
                Long id = (Long) row[0];
                BigDecimal amount = (BigDecimal) row[2];
                byCategory.merge(
                        id,
                        new CategoryTotal(id, (String) row[1], amount),
                        (a, b) -> new CategoryTotal(id, a.name(), a.amount().add(b.amount())));
            }
        }
        return byCategory.values().stream()
                .filter(total -> total.amount().signum() > 0)
                .sorted(Comparator.comparing(CategoryTotal::amount).reversed())
                .limit(TOP_CATEGORIES)
                .map(total -> new CategoryShare(
                        total.id(),
                        total.name(),
                        MoneyRules.normalize(total.amount()),
                        total.amount().multiply(BigDecimal.valueOf(100))
                                .divide(totalExpense, 1, RoundingMode.HALF_UP)))
                .toList();
    }

    private List<MonthTrendPoint> trend(Long userId, YearMonth month) {
        List<MonthTrendPoint> points = new ArrayList<>();
        for (int i = TREND_MONTHS - 1; i >= 0; i--) {
            YearMonth m = month.minusMonths(i);
            points.add(new MonthTrendPoint(
                    m,
                    MoneyRules.normalize(sum(userId, TransactionType.INCOME, m)),
                    MoneyRules.normalize(sum(userId, TransactionType.EXPENSE, m)
                            .add(cardExpense(userId, m)))));
        }
        return points;
    }
}
