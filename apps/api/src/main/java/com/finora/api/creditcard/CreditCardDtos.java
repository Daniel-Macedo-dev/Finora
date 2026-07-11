package com.finora.api.creditcard;

import com.finora.api.creditcard.invoice.InvoiceDtos.InvoiceSummaryResponse;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

public final class CreditCardDtos {

    private CreditCardDtos() {
    }

    public record CreditCardRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 100) String issuer,
            @NotNull CreditCardBrand brand,
            @Pattern(regexp = "\\d{4}", message = "Informe exatamente os 4 últimos dígitos.")
            String lastFourDigits,
            @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal creditLimit,
            @NotNull @Min(1) @Max(31) Integer closingDay,
            @NotNull @Min(1) @Max(31) Integer dueDay,
            Long defaultPaymentAccountId) {
    }

    public record CardLimitResponse(
            BigDecimal creditLimit,
            BigDecimal usedLimit,
            BigDecimal availableLimit,
            BigDecimal utilizationPercent) {
    }

    /** The cycle a purchase made today would enter; {@code invoiceId} is null while no charge exists. */
    public record CurrentCycleResponse(
            Long invoiceId,
            YearMonth referenceMonth,
            LocalDate closingDate,
            LocalDate dueDate) {
    }

    public record CreditCardResponse(
            Long id,
            String name,
            String issuer,
            CreditCardBrand brand,
            String lastFourDigits,
            Integer closingDay,
            Integer dueDay,
            Long defaultPaymentAccountId,
            String defaultPaymentAccountName,
            boolean archived,
            CardLimitResponse limit,
            CurrentCycleResponse currentCycle,
            InvoiceSummaryResponse nextDueInvoice) {
    }
}
