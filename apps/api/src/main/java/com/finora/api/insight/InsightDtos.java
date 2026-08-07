package com.finora.api.insight;

import java.math.BigDecimal;
import java.util.List;
import java.time.YearMonth;

public final class InsightDtos {

    private InsightDtos() {
    }

    public enum InsightSeverity {
        POSITIVE,
        INFO,
        WARNING,
        CRITICAL
    }

    /**
     * The aggregate rules that can be withheld for want of an exchange rate.
     *
     * <p>Stable and machine-readable: the frontend turns them into prose, so
     * renaming one is an API change. Resource-native rules are deliberately
     * absent — an overdue invoice is true in its own currency no matter what
     * else the ledger holds, so it can never appear here.
     */
    public enum InsightRule {
        EXPENSE_INCREASE,
        CATEGORY_DOMINANT,
        BUDGET_STATUS,
        COMMITMENT_SHARE_HIGH,
        CARD_INSTALLMENT_BURDEN_HIGH,
        GOAL_OFF_PACE,
        WISHLIST_AFFORDABLE
    }

    /**
     * One finding, denominated.
     *
     * <p>An amount without its currency is not a smaller piece of information
     * than an amount with one — it is a different number. A card's remaining
     * limit in dollars presented as reais overstates it by roughly five times,
     * so the two fields travel together or neither does.
     *
     * @param amount the main figure, or null when the finding is not monetary
     * @param currency ISO code of {@code amount}; null exactly when it is
     */
    public record Insight(
            String type,
            InsightSeverity severity,
            String title,
            String message,
            BigDecimal amount,
            String currency) {

        /**
         * @throws IllegalArgumentException when an amount arrives without its
         *     currency, or a currency without an amount to denominate
         */
        public Insight {
            if (amount != null && currency == null) {
                throw new IllegalArgumentException(
                        "A monetary insight must carry the currency of its amount: " + type);
            }
            if (amount == null && currency != null) {
                throw new IllegalArgumentException(
                        "An insight without an amount has nothing to denominate: " + type);
            }
        }
    }

    /**
     * Which aggregate conclusions the month's currencies allowed.
     *
     * <p>This exists to separate two silences that look identical from outside.
     * A rule can be quiet because nothing triggered it — no previous month, no
     * history, no goal — and that is the ordinary, healthy case which must never
     * appear here. Or it can be quiet because evaluating it would have meant
     * dividing dollars by reais, and that is what this reports.
     *
     * @param complete whether every aggregate rule could be evaluated
     * @param missingCurrencies the non-base currencies that actually blocked a
     *     rule above, deduplicated and in catalogue order — not every foreign
     *     currency the user happens to own
     * @param unavailableRules stable rule identifiers, never prose
     */
    public record AggregateCoverage(
            boolean complete,
            List<String> missingCurrencies,
            List<String> unavailableRules) {

        /** Every aggregate rule ran on comparable operands. */
        public static AggregateCoverage nothingWithheld() {
            return new AggregateCoverage(true, List.of(), List.of());
        }
    }

    /**
     * @param baseCurrency the currency every aggregate conclusion is stated in
     * @param aggregateCoverage never null; complete when nothing was withheld
     */
    public record InsightsResponse(
            YearMonth month,
            String baseCurrency,
            List<Insight> insights,
            AggregateCoverage aggregateCoverage) {
    }
}
