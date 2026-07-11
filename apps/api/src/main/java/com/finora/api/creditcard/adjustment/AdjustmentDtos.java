package com.finora.api.creditcard.adjustment;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public final class AdjustmentDtos {

    private AdjustmentDtos() {
    }

    public record AdjustmentRequest(
            @NotNull AdjustmentKind kind,
            @NotBlank @Size(max = 200) String description,
            @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
            Long categoryId) {
    }

    public record AdjustmentResponse(
            Long id,
            Long invoiceId,
            AdjustmentKind kind,
            String description,
            Long categoryId,
            String categoryName,
            BigDecimal amount,
            AdjustmentStatus status,
            Instant reversedAt) {
    }
}
