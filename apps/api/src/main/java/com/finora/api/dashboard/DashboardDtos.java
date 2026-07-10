package com.finora.api.dashboard;

import com.finora.api.commitment.CommitmentDtos.UpcomingCommitment;
import com.finora.api.goal.GoalDtos.GoalResponse;
import com.finora.api.transaction.TransactionDtos.TransactionResponse;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    public record CategoryShare(
            Long categoryId,
            String categoryName,
            BigDecimal amount,
            /** Share of the month's expenses, percentage with 1 decimal. */
            BigDecimal percentOfTotal) {
    }

    public record BudgetOverview(
            BigDecimal totalLimit,
            BigDecimal totalConsumed,
            BigDecimal percentUsed,
            int budgetCount,
            int warningCount,
            int exceededCount) {
    }

    public record MonthTrendPoint(
            YearMonth month,
            BigDecimal income,
            BigDecimal expense) {
    }

    public record DashboardResponse(
            YearMonth month,
            BigDecimal totalBalance,
            BigDecimal income,
            BigDecimal expense,
            /** income - expense for the month. */
            BigDecimal monthResult,
            /** Percentage of income kept (1 decimal); null when there is no income. */
            BigDecimal savingsRate,
            BigDecimal previousMonthExpense,
            /** Percent variation vs previous month (1 decimal); null when previous month had no expenses. */
            BigDecimal expenseVariationPercent,
            BudgetOverview budgets,
            List<CategoryShare> topCategories,
            /** Last 6 months (oldest first) of income/expense, for the trend chart. */
            List<MonthTrendPoint> trend,
            List<UpcomingCommitment> upcomingCommitments,
            BigDecimal upcomingCommitmentsTotal,
            List<GoalResponse> goals,
            List<TransactionResponse> recentTransactions) {
    }
}
