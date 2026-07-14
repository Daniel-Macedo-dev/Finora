package com.finora.api.commitment;

import com.finora.api.category.CategoryType;
import com.finora.api.transaction.PaymentMethod;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class CommitmentDtos {

    private CommitmentDtos() {
    }

    public record CommitmentRequest(
            @NotBlank(message = "Informe a descrição.")
            @Size(max = 200, message = "A descrição pode ter no máximo 200 caracteres.")
            String description,

            @NotNull(message = "Informe o valor.")
            @Positive(message = "O valor deve ser maior que zero.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal amount,

            @NotNull(message = "Informe a categoria.")
            Long categoryId,

            @NotNull(message = "Informe a recorrência.")
            CommitmentCadence cadence,

            @Min(value = 1, message = "O dia de vencimento deve estar entre 1 e 31.")
            @Max(value = 31, message = "O dia de vencimento deve estar entre 1 e 31.")
            Integer dueDay,

            @NotNull(message = "Informe a data de início.")
            LocalDate startDate,

            LocalDate endDate,

            Boolean active,

            PaymentMethod paymentMethod,

            /** Defaults to MANUAL when omitted (backward compatible). */
            ExecutionMode executionMode,

            /** Defaults to PROJECTION_ONLY when omitted (backward compatible). */
            RecurrenceTarget targetKind,

            /** Required for ACCOUNT_TRANSACTION targets. */
            Long accountId,

            /** Required for CREDIT_CARD_PURCHASE targets. */
            Long creditCardId,

            @Min(value = 1, message = "O número de parcelas deve estar entre 1 e 120.")
            @Max(value = 120, message = "O número de parcelas deve estar entre 1 e 120.")
            Integer installmentCount) {
    }

    public record CommitmentCategory(Long id, String name, CategoryType type) {
    }

    public record CommitmentResponse(
            Long id,
            String description,
            BigDecimal amount,
            CommitmentCategory category,
            CommitmentCadence cadence,
            Integer dueDay,
            LocalDate startDate,
            LocalDate endDate,
            boolean active,
            PaymentMethod paymentMethod,
            LocalDate nextDueDate,
            ExecutionMode executionMode,
            RecurrenceTarget targetKind,
            Long accountId,
            String accountName,
            Long creditCardId,
            String creditCardName,
            int installmentCount,
            /** True for pre-automation rows kept projection-only for safety. */
            boolean legacyProjectionOnly,
            long failedOccurrences) {
    }

    public record UpcomingCommitment(
            Long commitmentId,
            String description,
            BigDecimal amount,
            CommitmentCategory category,
            LocalDate dueDate,
            PaymentMethod paymentMethod) {
    }

    public record UpcomingResponse(
            LocalDate from,
            LocalDate to,
            BigDecimal totalAmount,
            List<UpcomingCommitment> items) {
    }
}
