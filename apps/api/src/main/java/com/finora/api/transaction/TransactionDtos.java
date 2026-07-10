package com.finora.api.transaction;

import com.finora.api.account.AccountType;
import com.finora.api.category.CategoryType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class TransactionDtos {

    private TransactionDtos() {
    }

    public record TransactionRequest(
            @NotNull(message = "Informe o tipo da transação.")
            TransactionType type,

            @NotNull(message = "Informe o valor.")
            @Positive(message = "O valor deve ser maior que zero.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal amount,

            @NotBlank(message = "Informe a descrição.")
            @Size(max = 200, message = "A descrição pode ter no máximo 200 caracteres.")
            String description,

            @NotNull(message = "Informe a data.")
            LocalDate date,

            @NotNull(message = "Informe a categoria.")
            Long categoryId,

            Long accountId,

            PaymentMethod paymentMethod,

            @Size(max = 2000, message = "As observações podem ter no máximo 2000 caracteres.")
            String notes) {
    }

    public record CategorySummary(Long id, String name, CategoryType type) {
    }

    public record AccountSummary(Long id, String name, AccountType type) {
    }

    public record TransactionResponse(
            Long id,
            TransactionType type,
            BigDecimal amount,
            String description,
            LocalDate date,
            CategorySummary category,
            AccountSummary account,
            PaymentMethod paymentMethod,
            String notes) {

        public static TransactionResponse from(Transaction t) {
            return new TransactionResponse(
                    t.getId(),
                    t.getType(),
                    t.getAmount(),
                    t.getDescription(),
                    t.getOccurredOn(),
                    new CategorySummary(
                            t.getCategory().getId(),
                            t.getCategory().getName(),
                            t.getCategory().getType()),
                    t.getAccount() != null
                            ? new AccountSummary(
                                    t.getAccount().getId(),
                                    t.getAccount().getName(),
                                    t.getAccount().getType())
                            : null,
                    t.getPaymentMethod(),
                    t.getNotes());
        }
    }
}
