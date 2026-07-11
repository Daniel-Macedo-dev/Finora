package com.finora.api.purchaseanalysis;

import com.finora.api.wishlist.PurchaseOptionKind;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

public final class PurchaseAnalysisDtos {

    private PurchaseAnalysisDtos() {
    }

    public enum RecommendationType {
        BUY_CASH,
        BUY_INSTALLMENT,
        WAIT,
        NO_OPTIONS
    }

    /** Machine-readable finding about a single option. */
    public record OptionIssue(String code, String message, boolean blocking) {
    }

    /** Card projection for an INSTALLMENT option linked to one of the user's cards. */
    public record CardAnalysis(
            Long cardId,
            String cardName,
            BigDecimal availableLimit,
            BigDecimal availableLimitAfter,
            /** Card utilization after the purchase, percentage with 1 decimal. */
            BigDecimal utilizationAfterPercent,
            /** Invoice month the first installment would land on. */
            YearMonth firstInvoiceMonth,
            boolean limitSufficient) {
    }

    public record OptionAnalysis(
            Long optionId,
            String merchant,
            PurchaseOptionKind kind,
            BigDecimal nominalCost,
            /** Nominal cost discounted at the monthly opportunity rate. */
            BigDecimal presentValue,
            /** Cash leaving the pocket at purchase time (full price when CASH, upfront extras when INSTALLMENT). */
            BigDecimal upfrontCost,
            /** Recurring monthly load while installments last; null for CASH. */
            BigDecimal monthlyBurden,
            Integer installmentCount,
            /** Available cash after paying the upfront cost. */
            BigDecimal cashAfterPurchase,
            /** Null when the option is not linked to a credit card. */
            CardAnalysis card,
            boolean safe,
            List<OptionIssue> issues) {
    }

    /** The assumptions the engine used, surfaced so the UI can explain itself. */
    public record AnalysisAssumptions(
            BigDecimal availableCash,
            BigDecimal minimumCashBuffer,
            BigDecimal monthlyOpportunityRate,
            BigDecimal maxInstallmentCommitmentRatio,
            BigDecimal avgMonthlyIncome,
            BigDecimal avgMonthlyExpense,
            BigDecimal avgMonthlySurplus,
            BigDecimal monthlyCommitments,
            /** Unpaid card obligations across all invoices, present and future. */
            BigDecimal cardOutstandingTotal,
            /** Card installments already scheduled for next month's invoices. */
            BigDecimal nextMonthCardInstallments,
            int historyMonthsUsed) {
    }

    public record Recommendation(
            RecommendationType type,
            Long recommendedOptionId,
            List<String> reasonCodes,
            String explanation,
            List<String> warnings,
            /** Extra cash needed before the cheapest option becomes safe; only for WAIT. */
            BigDecimal requiredAdditionalCash,
            /** Months of average surplus needed to accumulate that cash; null without surplus history. */
            Integer estimatedMonthsToAfford) {
    }

    public record AnalysisResponse(
            Long itemId,
            String itemName,
            AnalysisAssumptions assumptions,
            List<OptionAnalysis> options,
            Recommendation recommendation) {
    }
}
