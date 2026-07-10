package com.finora.api.goal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class GoalDtos {

    private GoalDtos() {
    }

    public enum GoalStatus {
        IN_PROGRESS,
        COMPLETED,
        ARCHIVED
    }

    public record GoalRequest(
            @NotBlank(message = "Informe o nome da meta.")
            @Size(max = 100, message = "O nome pode ter no máximo 100 caracteres.")
            String name,

            @NotNull(message = "Informe o valor alvo.")
            @Positive(message = "O valor alvo deve ser maior que zero.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal targetAmount,

            @PositiveOrZero(message = "O valor atual não pode ser negativo.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal currentAmount,

            LocalDate targetDate,

            Boolean archived) {
    }

    public record ContributionRequest(
            @NotNull(message = "Informe o valor do aporte.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal amount) {
    }

    public record GoalResponse(
            Long id,
            String name,
            BigDecimal targetAmount,
            BigDecimal currentAmount,
            BigDecimal remainingAmount,
            BigDecimal percentAchieved,
            LocalDate targetDate,
            GoalStatus status,
            /** Suggested contribution per month to hit the target date; null without a future target date. */
            BigDecimal suggestedMonthlyContribution) {
    }
}
