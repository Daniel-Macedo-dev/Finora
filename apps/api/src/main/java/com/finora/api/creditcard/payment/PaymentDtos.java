package com.finora.api.creditcard.payment;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record PaymentRequest(
            @NotNull Long accountId,
            @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotNull LocalDate paidOn,
            String notes) {
    }

    public record PaymentResponse(
            Long id,
            Long invoiceId,
            Long accountId,
            String accountName,
            BigDecimal amount,
            LocalDate paidOn,
            PaymentStatus status,
            String notes,
            Instant reversedAt,
            BigDecimal invoiceOutstandingAmount) {
    }
}
