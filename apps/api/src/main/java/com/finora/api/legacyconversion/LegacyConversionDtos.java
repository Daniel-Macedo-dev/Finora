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
}
