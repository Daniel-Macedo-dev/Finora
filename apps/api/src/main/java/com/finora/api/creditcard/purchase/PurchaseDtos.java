package com.finora.api.creditcard.purchase;

import com.finora.api.creditcard.installment.InstallmentStatus;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public final class PurchaseDtos {

    private PurchaseDtos() {
    }

    public record PurchaseRequest(
            @NotBlank @Size(max = 200) String description,
            @Size(max = 150) String merchant,
            @NotNull Long categoryId,
            @NotNull LocalDate purchaseDate,
            @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal totalAmount,
            @NotNull @Min(1) @Max(120) Integer installmentCount,
            String notes) {
    }

    /** Metadata-only update; financial fields have their own stricter rules. */
    public record PurchaseInstallmentResponse(
            Long id,
            int sequenceNumber,
            int totalInstallments,
            BigDecimal amount,
            Long invoiceId,
            YearMonth invoiceMonth,
            LocalDate invoiceDueDate,
            InstallmentStatus status) {
    }

    public record PurchaseCategory(Long id, String name) {
    }

    public record PurchaseResponse(
            Long id,
            Long cardId,
            String description,
            String merchant,
            PurchaseCategory category,
            LocalDate purchaseDate,
            BigDecimal totalAmount,
            int installmentCount,
            PurchaseStatus status,
            Long wishlistItemId,
            String notes,
            List<PurchaseInstallmentResponse> installments) {
    }
}
