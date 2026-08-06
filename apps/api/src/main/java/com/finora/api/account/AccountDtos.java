package com.finora.api.account;

import com.finora.api.common.money.CurrencyTotals;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

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

    /**
     * The account list plus the only totals that can honestly be stated.
     *
     * <p>Balances in different currencies are not addable, so there is no
     * consolidated scalar here at all: {@code totals} reports each currency on
     * its own and says whether a native or a base-denominated total exists.
     * A user with only BRL accounts still gets exactly one figure, unchanged.
     *
     * @param accounts every account, archived ones included, each with its own
     *     native currency and balances
     * @param totals grouped current balances of the <em>active</em> accounts
     *     only, matching what the dashboard treats as available cash
     * @param archivedTotals the same grouping for archived accounts, so the
     *     money is still visible without being folded into available cash
     */
    public record AccountsOverviewResponse(
            List<AccountResponse> accounts,
            CurrencyTotals totals,
            CurrencyTotals archivedTotals) {
    }
}
