package com.finora.api.statementimport;

import com.finora.api.transaction.TransactionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class CategoryRuleDtos {

    private CategoryRuleDtos() {
    }

    public record CategoryRuleRequest(
            @NotNull(message = "Informe o tipo da transação.")
            TransactionType transactionType,

            Long accountId,

            @NotNull(message = "Informe o campo de comparação.")
            CategoryRuleField matchField,

            @NotNull(message = "Informe a operação de comparação.")
            CategoryRuleOperation operation,

            @NotBlank(message = "Informe o texto da regra.")
            @Size(max = 200, message = "O texto da regra pode ter no máximo 200 caracteres.")
            String pattern,

            @NotNull(message = "Informe a categoria.")
            Long categoryId,

            @Min(value = 0, message = "A prioridade deve estar entre 0 e 1000.")
            @Max(value = 1000, message = "A prioridade deve estar entre 0 e 1000.")
            int priority,

            boolean active) {
    }

    public record CategoryRuleResponse(
            Long id,
            boolean active,
            TransactionType transactionType,
            Long accountId,
            String accountName,
            CategoryRuleField matchField,
            CategoryRuleOperation operation,
            String pattern,
            Long categoryId,
            String categoryName,
            int priority,
            long matchCount,
            Instant lastUsedAt) {
    }
}
