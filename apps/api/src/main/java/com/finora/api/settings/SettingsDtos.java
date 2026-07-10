package com.finora.api.settings;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public final class SettingsDtos {

    private SettingsDtos() {
    }

    public record SettingsRequest(
            @NotNull(message = "Informe a reserva mínima de caixa.")
            @DecimalMin(value = "0", message = "A reserva mínima não pode ser negativa.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal minimumCashBuffer,

            @NotNull(message = "Informe o comprometimento máximo da renda.")
            @DecimalMin(value = "0", message = "O comprometimento deve estar entre 0 e 1.")
            @DecimalMax(value = "1", message = "O comprometimento deve estar entre 0 e 1.")
            @Digits(integer = 1, fraction = 4, message = "Use no máximo 4 casas decimais.")
            BigDecimal maxInstallmentCommitmentRatio,

            @NotNull(message = "Informe a taxa de oportunidade mensal.")
            @DecimalMin(value = "0", message = "A taxa deve estar entre 0 e 0,2.")
            @DecimalMax(value = "0.2", message = "A taxa deve estar entre 0 e 0,2.")
            @Digits(integer = 1, fraction = 6, message = "Use no máximo 6 casas decimais.")
            BigDecimal monthlyOpportunityRate,

            @NotNull(message = "Informe o alerta de orçamento.")
            @DecimalMin(value = "0", message = "O alerta deve estar entre 0 e 1.")
            @DecimalMax(value = "1", message = "O alerta deve estar entre 0 e 1.")
            @Digits(integer = 1, fraction = 4, message = "Use no máximo 4 casas decimais.")
            BigDecimal budgetWarningThreshold) {
    }

    public record SettingsResponse(
            BigDecimal minimumCashBuffer,
            BigDecimal maxInstallmentCommitmentRatio,
            BigDecimal monthlyOpportunityRate,
            BigDecimal budgetWarningThreshold) {

        public static SettingsResponse from(AppSettings settings) {
            return new SettingsResponse(
                    settings.getMinimumCashBuffer(),
                    settings.getMaxInstallmentCommitmentRatio(),
                    settings.getMonthlyOpportunityRate(),
                    settings.getBudgetWarningThreshold());
        }
    }
}
