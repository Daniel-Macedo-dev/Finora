package com.finora.api.purchaseanalysis;

import com.finora.api.wishlist.PurchaseOptionKind;
import java.math.BigDecimal;
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
