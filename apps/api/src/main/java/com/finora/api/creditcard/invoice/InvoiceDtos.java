package com.finora.api.creditcard.invoice;

import com.finora.api.creditcard.adjustment.AdjustmentKind;
import com.finora.api.creditcard.adjustment.AdjustmentStatus;
import com.finora.api.creditcard.installment.InstallmentStatus;
import com.finora.api.creditcard.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public final class InvoiceDtos {

    private InvoiceDtos() {
    }

    /**
     * Invoice with derived financial totals. {@code purchaseTotal} sums the
     * active installments; {@code adjustmentsNet} sums debits minus credits;
     * {@code invoiceTotal} is their sum; {@code outstandingAmount} subtracts
     * completed payments.
     */
    public record InvoiceSummaryResponse(
            Long id,
            Long cardId,
            YearMonth referenceMonth,
            LocalDate closingDate,
            LocalDate dueDate,
            InvoiceStatus status,
            BigDecimal purchaseTotal,
            BigDecimal adjustmentsNet,
            BigDecimal invoiceTotal,
            BigDecimal amountPaid,
            BigDecimal outstandingAmount,
            int installmentCount) {
    }

    public record InvoiceInstallmentLine(
            Long id,
            Long purchaseId,
            String description,
            String merchant,
            String categoryName,
            LocalDate purchaseDate,
            int sequenceNumber,
            int totalInstallments,
            BigDecimal amount,
            InstallmentStatus status) {
    }

    public record InvoiceAdjustmentLine(
            Long id,
            AdjustmentKind kind,
            String description,
            String categoryName,
            BigDecimal amount,
            AdjustmentStatus status,
            Instant reversedAt) {
    }

    public record InvoicePaymentLine(
            Long id,
            Long accountId,
            String accountName,
            BigDecimal amount,
            LocalDate paidOn,
            PaymentStatus status,
            String notes,
            Instant reversedAt) {
    }

    public record InvoiceDetailResponse(
            InvoiceSummaryResponse invoice,
            List<InvoiceInstallmentLine> installments,
            List<InvoiceAdjustmentLine> adjustments,
            List<InvoicePaymentLine> payments) {
    }
}
