package com.finora.api.budget;

import com.finora.api.category.CategoryType;
import com.finora.api.common.money.CurrencyTotals;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

public final class BudgetDtos {

    private BudgetDtos() {
    }

    /**
     * How a budget is doing, or the honest admission that it cannot be known.
     *
     * <p>{@code INCOMPLETE} exists because the alternative is worse. When a
     * category holds spending in a currency the budget is not denominated in,
     * the base-currency consumption is a floor, not an answer: treating the
     * foreign spending as zero can leave a genuinely blown budget sitting at
     * HEALTHY, which is precisely the reassurance a person would act on.
     */
    public enum BudgetStatus {
        HEALTHY,
        WARNING,
        EXCEEDED,
        INCOMPLETE
    }

    public record BudgetRequest(
            @NotNull(message = "Informe o mês do orçamento.")
            YearMonth month,

            @NotNull(message = "Informe a categoria.")
            Long categoryId,

            @NotNull(message = "Informe o valor limite.")
            @Positive(message = "O limite deve ser maior que zero.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal limitAmount) {
    }

    public record BudgetCategory(Long id, String name, CategoryType type) {
    }

    /**
     * One budget's month.
     *
     * <p>A budget is always denominated in the owner's base currency — there is
     * deliberately no per-budget currency to edit, because a limit that drifted
     * from the currency of the analysis around it would mean nothing.
     *
     * @param currency the base currency {@code limitAmount} and
     *     {@code consumedAmount} are denominated in
     * @param consumedAmount consumption already known in the base currency; a
     *     floor, not a total, when {@code status} is INCOMPLETE
     * @param consumedTotals every expense that landed in this category and
     *     month, grouped by its own currency and never summed across them
     * @param remainingAmount null when consumption is incomplete
     * @param percentUsed null when consumption is incomplete; an understated
     *     percentage reads as a complete one
     */
    public record BudgetResponse(
            Long id,
            YearMonth month,
            BudgetCategory category,
            BigDecimal limitAmount,
            String currency,
            BigDecimal consumedAmount,
            CurrencyTotals consumedTotals,
            BigDecimal remainingAmount,
            BigDecimal percentUsed,
            BudgetStatus status,
            /** Optimistic version; offline UPDATE/DELETE must send the one they saw. */
            long version) {
    }

    /**
     * The month's budgets rolled up.
     *
     * @param totalConsumed base-currency consumption across every budget
     * @param consumedTotals the same consumption grouped by currency
     * @param totalRemaining null when any budget's consumption is incomplete
     * @param percentUsed null when any budget's consumption is incomplete
     * @param incompleteCount budgets whose category holds foreign spending
     */
    public record BudgetSummaryResponse(
            YearMonth month,
            String baseCurrency,
            BigDecimal totalLimit,
            BigDecimal totalConsumed,
            CurrencyTotals consumedTotals,
            BigDecimal totalRemaining,
            BigDecimal percentUsed,
            int exceededCount,
            int warningCount,
            int incompleteCount,
            List<BudgetResponse> budgets) {
    }
}
