package com.finora.api.legacyconversion;

import com.finora.api.creditcard.invoice.InvoiceStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Deterministic conversion preview: everything the user must see before
 * confirming — the exact installment schedule, invoice months and dates, the
 * card-limit effect, the monthly expense redistribution and the cash-flow
 * explanation. A preview never persists anything.
 */
public final class ConversionPreviewDtos {

    private ConversionPreviewDtos() {
    }

    /** Immutable summary of the source transaction (the future audit record). */
    public record PreviewSource(
            Long transactionId,
            String description,
            BigDecimal amount,
            LocalDate date,
            LegacyConversionDtos.CategorySummary category,
            String accountName,
            boolean affectsAccountBalance) {
    }

    public record PreviewCard(
            Long cardId,
            String name,
            int closingDay,
            int dueDay,
            boolean archived) {
    }

    /** One projected installment with the invoice cycle it lands on. */
    public record PreviewInstallment(
            int sequenceNumber,
            int totalInstallments,
            BigDecimal amount,
            YearMonth invoiceMonth,
            LocalDate closingDate,
            LocalDate dueDate,
            boolean invoiceExists,
            InvoiceStatus invoiceStatus,
            BigDecimal invoiceAmountPaid) {
    }

    public record PreviewLimit(
            BigDecimal creditLimit,
            BigDecimal availableBefore,
            BigDecimal availableAfter,
            boolean sufficient) {
    }

    /**
     * Expense recognition moving between months: the source month loses the
     * historical amount and each invoice month gains its installment. Budgets
     * of the source's category shift by exactly these deltas.
     */
    public record MonthlyExpenseShift(YearMonth month, BigDecimal delta) {
    }

    /** A structured, machine-readable message. Blockers prevent conversion. */
    public record PreviewMessage(String code, String message) {
    }

    /** How the conversion changes cash and the forecast, spelled out. */
    public record CashFlowExplanation(
            boolean sourceAffectsAccountBalance,
            boolean removesSourceCashEffect,
            boolean invoicePaymentAccountAssigned,
            String explanation) {
    }

    public record ConversionPreviewResponse(
            PreviewSource source,
            PreviewCard card,
            BigDecimal totalAmount,
            int installmentCount,
            YearMonth firstInvoiceMonth,
            List<PreviewInstallment> installments,
            PreviewLimit limit,
            List<MonthlyExpenseShift> monthlyExpenseShift,
            CashFlowExplanation cashFlow,
            String forecastExplanation,
            List<PreviewMessage> warnings,
            List<PreviewMessage> blockers,
            boolean convertible) {
    }
}
