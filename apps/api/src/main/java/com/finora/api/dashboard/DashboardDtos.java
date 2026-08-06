package com.finora.api.dashboard;

import com.finora.api.commitment.CommitmentDtos.UpcomingCommitment;
import com.finora.api.common.money.CurrencyTotals;
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

    /**
     * One category's expenses in one currency.
     *
     * <p>Rows are per category <em>and</em> currency, because a category can
     * hold spending in more than one and adding those would be a fabricated
     * figure. {@code percentOfTotal} is the share of that same currency's
     * monthly expenses — a ratio between two operands that really are
     * comparable — never a share of some mixed denominator.
     */
    public record CategoryShare(
            Long categoryId,
            String categoryName,
            BigDecimal amount,
            /** Authoritative currency of {@code amount}. */
            String currency,
            /** Share of the month's expenses in this currency, 1 decimal. */
            BigDecimal percentOfTotal) {
    }

    /**
     * Budget rollup for the dashboard card.
     *
     * <p>Budgets are denominated in the user's base currency, so the totals stay
     * scalar. What can be missing is <em>coverage</em>: when foreign spending
     * landed in a budgeted category, the consumed figure is a floor rather than
     * an answer, and {@code incompleteCount} says how many budgets are in that
     * state. {@code percentUsed} is then null instead of an understatement.
     */
    public record BudgetOverview(
            BigDecimal totalLimit,
            BigDecimal totalConsumed,
            /** Null when any budget's consumption is incomplete. */
            BigDecimal percentUsed,
            int budgetCount,
            int warningCount,
            int exceededCount,
            int incompleteCount) {
    }

    public record MonthTrendPoint(
            YearMonth month,
            BigDecimal income,
            BigDecimal expense) {
    }

    /**
     * One homogeneous trend series.
     *
     * <p>A chart axis can only carry one denomination, so the trend is split
     * into a series per currency rather than a single line whose points mean
     * different things. A base-currency-only user gets exactly one series.
     */
    public record MonthTrendSeries(
            String currency,
            List<MonthTrendPoint> points) {
    }

    public record CardInvoiceBrief(
            Long cardId,
            String cardName,
            Long invoiceId,
            YearMonth referenceMonth,
            LocalDate dueDate,
            InvoiceStatus status,
            BigDecimal outstandingAmount,
            /** The card's currency, which is what the invoice bills in. */
            String currency) {
    }

    public record RecentCardPurchase(
            Long id,
            Long cardId,
            String cardName,
            String description,
            LocalDate purchaseDate,
            BigDecimal totalAmount,
            /** The card's currency. */
            String currency,
            int installmentCount) {
    }

    /**
     * Card debt lives here, deliberately apart from account balances: cash and
     * card obligations are different things and are never netted together.
     *
     * <p>Each total is grouped by the billing card's currency. A USD limit and a
     * BRL limit are not one number, and presenting them as one would misstate
     * how much credit is actually available in either.
     */
    public record CardsOverview(
            int cardCount,
            CurrencyTotals outstanding,
            CurrencyTotals availableLimit,
            /** Card expense recognized in the month (installments + adjustments). */
            CurrencyTotals monthCardExpense,
            int overdueCount,
            CardInvoiceBrief nextDueInvoice,
            List<RecentCardPurchase> recentPurchases) {
    }

    /** One upcoming projected cash event surfaced on the dashboard. */
    public record FutureCashEvent(
            LocalDate date,
            String description,
            BigDecimal amount,
            String currency) {
    }

    /**
     * Compact future-cash view backed by the forecast service (30 days).
     * The dashboard never computes projections on its own.
     *
     * <p>{@code projectedBalance30d} and {@code firstNegativeDate} describe one
     * running balance, which only exists if everything feeding it settles in one
     * currency. When it does not, {@code available} is false and both are null:
     * a projected balance that silently added dollars to reais would be the most
     * actionable wrong number on the page.
     */
    public record FutureCashOverview(
            boolean available,
            BigDecimal projectedBalance30d,
            /** Currency of the projection; null when unavailable. */
            String currency,
            FutureCashEvent nextRecurringEvent,
            FutureCashEvent nextInvoiceObligation,
            LocalDate firstNegativeDate,
            long failedOccurrences) {
    }

    /**
     * The month at a glance.
     *
     * <p>Every monetary rollup is a {@link CurrencyTotals}: it carries the
     * per-currency breakdown always, a native total when the set is
     * homogeneous, and a base-denominated total only when nothing is left to
     * convert. A base-currency-only user therefore still sees exactly one figure
     * everywhere, in {@code baseTotal}.
     *
     * <p>Derived ratios are the opposite: they are null unless every operand is
     * base-complete, because a percentage computed over part of the data reads
     * as a statement about all of it.
     */
    public record DashboardResponse(
            YearMonth month,
            String baseCurrency,
            /** Current balances of active accounts, grouped by currency. */
            CurrencyTotals accountBalances,
            CurrencyTotals income,
            CurrencyTotals expense,
            /** income − expense, per currency. */
            CurrencyTotals monthResult,
            /** Percentage of income kept; null when income or expense is incomplete in base currency. */
            BigDecimal savingsRate,
            CurrencyTotals previousMonthExpense,
            /** Percent variation vs previous month; null when either period is incomplete. */
            BigDecimal expenseVariationPercent,
            BudgetOverview budgets,
            /** Top expense categories, per currency present. */
            List<CategoryShare> topCategories,
            /** Last 6 months (oldest first), one homogeneous series per currency. */
            List<MonthTrendSeries> trend,
            List<UpcomingCommitment> upcomingCommitments,
            CurrencyTotals upcomingCommitmentsTotal,
            List<GoalResponse> goals,
            List<TransactionResponse> recentTransactions,
            /** Null when the user has no credit cards. */
            CardsOverview cards,
            FutureCashOverview futureCash) {
    }
}
