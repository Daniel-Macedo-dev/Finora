package com.finora.api.budget;

import com.finora.api.category.CategoryType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

public final class BudgetDtos {

    private BudgetDtos() {
    }

    public enum BudgetStatus {
        HEALTHY,
        WARNING,
        EXCEEDED
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

    public record BudgetResponse(
            Long id,
            YearMonth month,
            BudgetCategory category,
            BigDecimal limitAmount,
            BigDecimal consumedAmount,
            BigDecimal remainingAmount,
            BigDecimal percentUsed,
            BudgetStatus status) {
    }

    public record BudgetSummaryResponse(
            YearMonth month,
            BigDecimal totalLimit,
            BigDecimal totalConsumed,
            BigDecimal totalRemaining,
            BigDecimal percentUsed,
            int exceededCount,
            int warningCount,
            List<BudgetResponse> budgets) {
    }
}
