package com.finora.api.legacyconversion;

import com.finora.api.common.web.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Response records for the conversion inventory and eligibility endpoints. */
public final class LegacyConversionDtos {

    private LegacyConversionDtos() {
    }

    public record CategorySummary(Long id, String name) {
    }

    /** One legacy-credit transaction in the inventory, with its conversion state. */
    public record ConversionInventoryItem(
            Long transactionId,
            String description,
            BigDecimal amount,
            LocalDate date,
            CategorySummary category,
            String accountName,
            ConversionInventoryState state,
            String stateReasonCode,
            String stateMessage,
            Long conversionId,
            Long generatedCardPurchaseId,
            Long cardId) {
    }

    /**
     * Inventory headline numbers. {@code eligibleCount} counts every source
     * convertible right now — including reversed ones, which are also counted
     * in {@code reversedCount}; {@code pendingAmount} sums the same set.
     */
    public record ConversionInventorySummary(
            long eligibleCount,
            long convertedCount,
            long reversedCount,
            BigDecimal pendingAmount) {
    }

    public record ConversionInventoryResponse(
            ConversionInventorySummary summary,
            PageResponse<ConversionInventoryItem> page) {
    }

    /** Eligibility verdict for one transaction. */
    public record EligibilityResponse(
            Long transactionId,
            EligibilityStatus status,
            boolean convertible,
            String reasonCode,
            String message) {
    }

    /** Full conversion record with its current reversal eligibility. */
    public record ConversionResponse(
            Long id,
            Long sourceTransactionId,
            String sourceDescription,
            BigDecimal amount,
            LocalDate originalTransactionDate,
            Long cardPurchaseId,
            Long cardId,
            String cardName,
            LocalDate effectivePurchaseDate,
            int installmentCount,
            java.time.YearMonth firstInvoiceMonth,
            ConversionStatus status,
            java.time.Instant convertedAt,
            java.time.Instant reversedAt,
            String reversalReason,
            boolean reversible,
            String reversalBlockedCode,
            String reversalBlockedMessage) {
    }

    /** Outcome of one batch item; items are always processed independently. */
    public enum BatchItemStatus {
        SUCCESS,
        ALREADY_CONVERTED,
        FAILED,
        SKIPPED
    }

    /** One result per input item, in input order. */
    public record BatchItemResult(
            Long transactionId,
            BatchItemStatus status,
            Long conversionId,
            Long generatedCardPurchaseId,
            String errorCode,
            String message) {
    }

    public record BatchConversionResponse(
            int total,
            int succeeded,
            int alreadyConverted,
            int failed,
            int skipped,
            java.util.List<BatchItemResult> results) {
    }
}
