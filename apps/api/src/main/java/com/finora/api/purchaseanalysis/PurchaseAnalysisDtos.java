package com.finora.api.purchaseanalysis;

import com.finora.api.wishlist.PurchaseOptionKind;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

public final class PurchaseAnalysisDtos {

    private PurchaseAnalysisDtos() {
    }

    /**
     * Whether a financial recommendation could honestly be produced.
     *
     * <p>{@code EXCHANGE_RATE_REQUIRED} is not an error. The request succeeded,
     * the item is readable, and its options and price history are all still
     * available through their ordinary APIs. What is missing is a rate — and
     * without one, every conclusion the engine would draw (cash after purchase,
     * buffer violation, installment pressure, months to save) would compare
     * amounts that are not comparable.
     *
     * <p>The currency itself is perfectly supported, which is why this
     * deliberately does not reuse {@code CURRENCY_UNSUPPORTED}.
     */
    public enum AnalysisAvailability {
        AVAILABLE,
        EXCHANGE_RATE_REQUIRED
    }

    /**
     * Why a complete analysis could not run.
     *
     * <p>{@code code} is stable and machine-readable; {@code message} is the
     * human sentence. The set is bounded by the number of context dimensions,
     * so it cannot grow with the ledger.
     */
    public record UnavailableReason(String code, String message) {
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

    /**
     * The analysis, or the honest statement that it cannot be produced.
     *
     * <p>When {@code availability} is {@code EXCHANGE_RATE_REQUIRED} the
     * assumptions, option analyses and recommendation are all absent — not
     * emptied, not zeroed, and above all not replaced by WAIT. A WAIT standing
     * in for "unknown" reads as financial advice, which is the specific failure
     * this contract exists to prevent.
     *
     * <p>The two factory methods are the only way to build a response, so the
     * illegal combinations (a recommendation alongside an unavailable status, or
     * an available status with no assumptions) cannot be assembled by accident.
     *
     * @param itemCurrency the currency of the item and all of its options
     * @param missingCurrencies what a future exchange-rate stage would convert
     * @param unavailableReasons empty when {@code availability} is AVAILABLE
     */
    public record AnalysisResponse(
            Long itemId,
            String itemName,
            AnalysisAvailability availability,
            String baseCurrency,
            String itemCurrency,
            List<String> missingCurrencies,
            List<UnavailableReason> unavailableReasons,
            AnalysisAssumptions assumptions,
            List<OptionAnalysis> options,
            Recommendation recommendation) {

        /** The complete analysis. Item and base currency are the same here. */
        public static AnalysisResponse available(Long itemId, String itemName, String baseCurrency,
                AnalysisAssumptions assumptions, List<OptionAnalysis> options,
                Recommendation recommendation) {
            return new AnalysisResponse(itemId, itemName, AnalysisAvailability.AVAILABLE,
                    baseCurrency, baseCurrency, List.of(), List.of(),
                    assumptions, options, recommendation);
        }

        /**
         * No assumptions, no option analyses, no recommendation — and therefore
         * no BUY and no WAIT. The item, its options and its price history stay
         * reachable through their own endpoints.
         */
        public static AnalysisResponse exchangeRateRequired(Long itemId, String itemName,
                String baseCurrency, String itemCurrency, List<String> missingCurrencies,
                List<UnavailableReason> reasons) {
            return new AnalysisResponse(itemId, itemName,
                    AnalysisAvailability.EXCHANGE_RATE_REQUIRED,
                    baseCurrency, itemCurrency, missingCurrencies, reasons,
                    null, List.of(), null);
        }
    }
}
