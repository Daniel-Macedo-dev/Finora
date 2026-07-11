package com.finora.api.dashboard;

import com.finora.api.commitment.CommitmentDtos.UpcomingCommitment;
import com.finora.api.creditcard.invoice.InvoiceStatus;
import com.finora.api.goal.GoalDtos.GoalResponse;
import com.finora.api.transaction.TransactionDtos.TransactionResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
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

    public record CardInvoiceBrief(
            Long cardId,
            String cardName,
            Long invoiceId,
            YearMonth referenceMonth,
            LocalDate dueDate,
            InvoiceStatus status,
            BigDecimal outstandingAmount) {
    }

    public record RecentCardPurchase(
            Long id,
            Long cardId,
            String cardName,
            String description,
            LocalDate purchaseDate,
            BigDecimal totalAmount,
            int installmentCount) {
    }

    /**
     * Card debt lives here, deliberately apart from {@code totalBalance}: cash
     * and card obligations are different things and are never netted together.
     */
    public record CardsOverview(
            int cardCount,
            BigDecimal totalOutstanding,
            BigDecimal totalAvailableLimit,
            /** Card expense recognized in the month (installments + adjustments). */
            BigDecimal monthCardExpense,
            int overdueCount,
            CardInvoiceBrief nextDueInvoice,
            List<RecentCardPurchase> recentPurchases) {
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
            List<TransactionResponse> recentTransactions,
            /** Null when the user has no credit cards. */
            CardsOverview cards) {
    }
}
