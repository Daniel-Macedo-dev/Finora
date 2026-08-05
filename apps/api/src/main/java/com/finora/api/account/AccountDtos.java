package com.finora.api.account;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record AccountRequest(
            @NotBlank(message = "Informe o nome da conta.")
            @Size(max = 100, message = "O nome pode ter no máximo 100 caracteres.")
            String name,

            @NotNull(message = "Informe o tipo da conta.")
            AccountType type,

            @NotNull(message = "Informe o saldo inicial.")
            @PositiveOrZero(message = "O saldo inicial não pode ser negativo.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal openingBalance,

            /**
             * ISO code of the currency every movement in this account is
             * denominated in. Optional for backward compatibility: an omitted
             * currency means the authenticated user's base currency, never a
             * guessed foreign one. Ignored on update, because an account's
             * currency is immutable.
             */
            String currency,

            Integer displayOrder,

            Boolean archived) {
    }

    public record AccountResponse(
            Long id,
            String name,
            AccountType type,
            BigDecimal openingBalance,
            BigDecimal currentBalance,
            /** Authoritative currency of both balances above. */
            String currency,
            boolean archived,
            int displayOrder) {
    }
}
