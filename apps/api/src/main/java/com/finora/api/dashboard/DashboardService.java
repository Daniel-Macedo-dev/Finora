package com.finora.api.dashboard;

import com.finora.api.account.AccountBalanceService;
import com.finora.api.account.AccountRepository;
import com.finora.api.budget.BudgetDtos.BudgetSummaryResponse;
import com.finora.api.budget.BudgetService;
import com.finora.api.commitment.CommitmentService;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.dashboard.DashboardDtos.BudgetOverview;
import com.finora.api.dashboard.DashboardDtos.CategoryShare;
import com.finora.api.dashboard.DashboardDtos.DashboardResponse;
import com.finora.api.dashboard.DashboardDtos.MonthTrendPoint;
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
import java.util.List;
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
    private final CurrentUserProvider currentUser;

    public DashboardService(TransactionRepository transactions,
                            AccountRepository accounts,
                            AccountBalanceService balances,
                            BudgetService budgets,
                            CommitmentService commitments,
                            GoalService goals,
                            CurrentUserProvider currentUser) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.balances = balances;
        this.budgets = budgets;
        this.commitments = commitments;
        this.goals = goals;
        this.currentUser = currentUser;
    }

    /** Every section below aggregates exclusively the authenticated user's data. */
    @Transactional(readOnly = true)
    public DashboardResponse build(YearMonth month, LocalDate today) {
        Long userId = currentUser.currentUserId();
        BigDecimal income = sum(userId, TransactionType.INCOME, month);
        BigDecimal expense = sum(userId, TransactionType.EXPENSE, month);
        BigDecimal previousExpense = sum(userId, TransactionType.EXPENSE, month.minusMonths(1));

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
                        .toList());
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

    private List<CategoryShare> topCategories(Long userId, YearMonth month, BigDecimal totalExpense) {
        if (totalExpense.signum() <= 0) {
            return List.of();
        }
        return transactions.sumExpensesGroupedByCategory(userId, month.atDay(1), month.atEndOfMonth()).stream()
                .limit(TOP_CATEGORIES)
                .map(row -> {
                    BigDecimal amount = (BigDecimal) row[2];
                    return new CategoryShare(
                            (Long) row[0],
                            (String) row[1],
                            MoneyRules.normalize(amount),
                            amount.multiply(BigDecimal.valueOf(100))
                                    .divide(totalExpense, 1, RoundingMode.HALF_UP));
                })
                .toList();
    }

    private List<MonthTrendPoint> trend(Long userId, YearMonth month) {
        List<MonthTrendPoint> points = new ArrayList<>();
        for (int i = TREND_MONTHS - 1; i >= 0; i--) {
            YearMonth m = month.minusMonths(i);
            points.add(new MonthTrendPoint(
                    m,
                    MoneyRules.normalize(sum(userId, TransactionType.INCOME, m)),
                    MoneyRules.normalize(sum(userId, TransactionType.EXPENSE, m))));
        }
        return points;
    }
}
