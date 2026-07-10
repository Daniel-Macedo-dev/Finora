package com.finora.api.insight;

import com.finora.api.budget.BudgetDtos.BudgetResponse;
import com.finora.api.budget.BudgetDtos.BudgetStatus;
import com.finora.api.budget.BudgetService;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.goal.GoalDtos.GoalResponse;
import com.finora.api.goal.GoalDtos.GoalStatus;
import com.finora.api.goal.GoalService;
import com.finora.api.insight.InsightDtos.Insight;
import com.finora.api.insight.InsightDtos.InsightSeverity;
import com.finora.api.insight.InsightDtos.InsightsResponse;
import com.finora.api.purchaseanalysis.FinancialContext;
import com.finora.api.purchaseanalysis.FinancialContextService;
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
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic insight rules over real data. Every rule only fires when the
 * data needed to justify it exists — no insight is fabricated from missing data.
 */
@Service
public class InsightService {

    /** Expense growth vs previous month that triggers a warning (20%). */
    static final BigDecimal EXPENSE_INCREASE_THRESHOLD = new BigDecimal("1.20");
    /** Share of total expenses that makes a single category dominant (40%). */
    static final BigDecimal DOMINANT_CATEGORY_SHARE = new BigDecimal("0.40");
    /** Share of average income taken by recurring commitments that deserves attention (30%). */
    static final BigDecimal COMMITMENT_SHARE_THRESHOLD = new BigDecimal("0.30");

    private final TransactionRepository transactions;
    private final BudgetService budgets;
    private final GoalService goals;
    private final WishlistItemRepository wishlist;
    private final FinancialContextService contextService;
    private final SettingsService settings;

    public InsightService(TransactionRepository transactions,
                          BudgetService budgets,
                          GoalService goals,
                          WishlistItemRepository wishlist,
                          FinancialContextService contextService,
                          SettingsService settings) {
        this.transactions = transactions;
        this.budgets = budgets;
        this.goals = goals;
        this.wishlist = wishlist;
        this.contextService = contextService;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public InsightsResponse generate(YearMonth month, LocalDate today) {
        List<Insight> insights = new ArrayList<>();
        AppSettings config = settings.current();
        FinancialContext context = contextService.build(today);

        BigDecimal expense = sum(TransactionType.EXPENSE, month);
        BigDecimal previousExpense = sum(TransactionType.EXPENSE, month.minusMonths(1));

        expenseIncrease(insights, expense, previousExpense);
        dominantCategory(insights, month, expense);
        budgetAlerts(insights, month);
        commitmentShare(insights, context);
        goalPace(insights, context);
        affordableWishlist(insights, context, config);

        return new InsightsResponse(month, List.copyOf(insights));
    }

    private void expenseIncrease(List<Insight> insights, BigDecimal expense, BigDecimal previousExpense) {
        if (previousExpense.signum() <= 0 || expense.signum() <= 0) {
            return;
        }
        BigDecimal ratio = expense.divide(previousExpense, MoneyRules.RATE_SCALE, RoundingMode.HALF_UP);
        if (ratio.compareTo(EXPENSE_INCREASE_THRESHOLD) >= 0) {
            BigDecimal percent = ratio.subtract(BigDecimal.ONE)
                    .multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP);
            insights.add(new Insight(
                    "EXPENSE_INCREASE",
                    InsightSeverity.WARNING,
                    "Gastos subiram em relação ao mês anterior",
                    "As despesas do mês estão %s%% acima do mês anterior.".formatted(percent),
                    MoneyRules.normalize(expense.subtract(previousExpense))));
        }
    }

    private void dominantCategory(List<Insight> insights, YearMonth month, BigDecimal totalExpense) {
        if (totalExpense.signum() <= 0) {
            return;
        }
        List<Object[]> byCategory = transactions.sumExpensesGroupedByCategory(
                month.atDay(1), month.atEndOfMonth());
        if (byCategory.isEmpty()) {
            return;
        }
        Object[] top = byCategory.getFirst();
        BigDecimal amount = (BigDecimal) top[2];
        BigDecimal share = amount.divide(totalExpense, MoneyRules.RATE_SCALE, RoundingMode.HALF_UP);
        if (share.compareTo(DOMINANT_CATEGORY_SHARE) >= 0) {
            insights.add(new Insight(
                    "CATEGORY_DOMINANT",
                    InsightSeverity.INFO,
                    "Uma categoria concentra os gastos",
                    "%s representa %s%% das despesas do mês.".formatted(
                            top[1],
                            share.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP)),
                    MoneyRules.normalize(amount)));
        }
    }

    private void budgetAlerts(List<Insight> insights, YearMonth month) {
        List<BudgetResponse> monthBudgets = budgets.summary(month).budgets();
        monthBudgets.stream()
                .filter(budget -> budget.status() == BudgetStatus.EXCEEDED)
                .forEach(budget -> insights.add(new Insight(
                        "BUDGET_EXCEEDED",
                        InsightSeverity.CRITICAL,
                        "Orçamento estourado: " + budget.category().name(),
                        "O orçamento de %s foi ultrapassado: %s gastos de um limite de %s.".formatted(
                                budget.category().name(),
                                brl(budget.consumedAmount()),
                                brl(budget.limitAmount())),
                        budget.consumedAmount().subtract(budget.limitAmount()))));
        monthBudgets.stream()
                .filter(budget -> budget.status() == BudgetStatus.WARNING)
                .forEach(budget -> insights.add(new Insight(
                        "BUDGET_NEAR_LIMIT",
                        InsightSeverity.WARNING,
                        "Orçamento perto do limite: " + budget.category().name(),
                        "%s já consumiu %s%% do limite mensal.".formatted(
                                budget.category().name(),
                                budget.percentUsed().setScale(0, RoundingMode.HALF_UP)),
                        budget.remainingAmount())));
    }

    private void commitmentShare(List<Insight> insights, FinancialContext context) {
        if (context.avgMonthlyIncome() == null || context.avgMonthlyIncome().signum() <= 0
                || context.monthlyCommitments().signum() <= 0) {
            return;
        }
        BigDecimal share = context.monthlyCommitments()
                .divide(context.avgMonthlyIncome(), MoneyRules.RATE_SCALE, RoundingMode.HALF_UP);
        if (share.compareTo(COMMITMENT_SHARE_THRESHOLD) >= 0) {
            insights.add(new Insight(
                    "COMMITMENT_SHARE_HIGH",
                    InsightSeverity.WARNING,
                    "Compromissos recorrentes pesam na renda",
                    "Os compromissos recorrentes do próximo mês somam %s, cerca de %s%% da renda média observada."
                            .formatted(
                                    brl(context.monthlyCommitments()),
                                    share.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP)),
                    context.monthlyCommitments()));
        }
    }

    private void goalPace(List<Insight> insights, FinancialContext context) {
        if (context.avgMonthlySurplus() == null) {
            return;
        }
        for (GoalResponse goal : goals.list()) {
            if (goal.status() != GoalStatus.IN_PROGRESS || goal.suggestedMonthlyContribution() == null) {
                continue;
            }
            if (goal.suggestedMonthlyContribution().compareTo(context.avgMonthlySurplus()) > 0) {
                insights.add(new Insight(
                        "GOAL_OFF_PACE",
                        InsightSeverity.WARNING,
                        "Meta fora do ritmo: " + goal.name(),
                        ("Para alcançar %s na data desejada seriam necessários %s por mês, acima da sobra média "
                                + "mensal de %s.").formatted(
                                goal.name(),
                                brl(goal.suggestedMonthlyContribution()),
                                brl(context.avgMonthlySurplus())),
                        goal.suggestedMonthlyContribution()));
            }
        }
    }

    private void affordableWishlist(List<Insight> insights, FinancialContext context, AppSettings config) {
        List<WishlistItem> candidates = wishlist.findAllByStatusIn(
                List.of(WishlistStatus.PLANNING, WishlistStatus.MONITORING, WishlistStatus.READY_TO_BUY));
        BigDecimal spendable = context.availableCash().subtract(config.getMinimumCashBuffer());
        if (spendable.signum() <= 0) {
            return;
        }
        for (WishlistItem item : candidates) {
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
                                    item.getName(), brl(cheapest)),
                            MoneyRules.normalize(cheapest))));
        }
    }

    private BigDecimal sum(TransactionType type, YearMonth month) {
        return transactions.sumAmountByTypeAndPeriod(type, month.atDay(1), month.atEndOfMonth());
    }

    private static String brl(BigDecimal value) {
        return "R$ " + MoneyRules.normalize(value).toPlainString().replace('.', ',');
    }
}
