package com.finora.api.purchaseanalysis;

import com.finora.api.common.money.CurrencyCode;
import com.finora.api.common.money.CurrencyTotals;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The purchase engine's view of a user's finances, denominated honestly.
 *
 * <p>This is the currency-aware successor to {@link FinancialContext}, which
 * still exists for {@code InsightService} while that migration is a separate
 * task. Nothing new should consume the legacy record.
 *
 * <p>Every dimension carries its own base-currency coverage, because they fail
 * independently: a user can hold all their cash in reais while one month of
 * their history is in dollars. A single global "complete" flag would either
 * suppress an analysis that is genuinely available, or — worse — let one
 * complete dimension vouch for an incomplete one.
 *
 * <p>Nothing here is converted, and nothing is invented. An average with no
 * history is absent, not zero; an average that exists only in a foreign
 * currency is present natively and explicitly incomplete in the base currency.
 * Those two are not the same thing, and the distinction is the whole point of
 * {@link HistoricalAverage#anyHistory()}.
 *
 * @param missingCurrencies every non-base currency contributing anywhere above,
 *     deduplicated and in catalogue order — exactly what a future exchange-rate
 *     stage would have to convert
 */
public record PurchaseFinancialContext(
        String baseCurrency,
        CurrencyTotals availableCash,
        HistoricalAverage averageIncome,
        HistoricalAverage averageExpenses,
        HistoricalAverage averageSurplus,
        CurrencyTotals monthlyCommitments,
        CurrencyTotals cardOutstanding,
        CurrencyTotals nextMonthCardInstallments,
        List<String> missingCurrencies) {

    /**
     * One currency's monthly average and the denominator that produced it.
     *
     * <p>The denominator is per currency on purpose. A user with three months of
     * reais and one month of dollars has a BRL average over three months and a
     * USD average over one. Sharing a divisor would understate the dollars by
     * two thirds and triple the reais.
     */
    public record CurrencyAverage(String currency, BigDecimal average, int monthsUsed) {
    }

    /**
     * Monthly averages across every currency that had activity in the window.
     *
     * @param anyHistory whether the window held any activity at all. False means
     *     a genuinely new user, for whom a cash-only analysis is still valid.
     *     True with {@code baseComplete} false means the history exists but is
     *     not in the base currency — which is emphatically not the same as
     *     having none, and must never be treated as a safe absence.
     * @param baseAverage the base-currency average, or null when the base data
     *     is incomplete or absent — never a zero standing in for either
     */
    public record HistoricalAverage(
            List<CurrencyAverage> byCurrency,
            boolean anyHistory,
            boolean baseComplete,
            BigDecimal baseAverage,
            int baseMonthsUsed,
            List<String> unconvertedCurrencies) {

        /** A window with no activity at all: absent averages, not a fake zero. */
        public static HistoricalAverage noHistory() {
            return new HistoricalAverage(List.of(), false, true, null, 0, List.of());
        }

        /** The native average in one currency, or null when it had no activity. */
        public BigDecimal forCurrency(CurrencyCode currency) {
            return byCurrency.stream()
                    .filter(entry -> entry.currency().equals(currency.name()))
                    .map(CurrencyAverage::average)
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Whether the historical side is safe to reason about in the base currency.
     *
     * <p>True in exactly two situations: there is no history at all — which the
     * engine already handles by falling back to a cash-only analysis with
     * explicit warnings — or the history is entirely base-denominated.
     */
    public boolean historyUsableInBase() {
        return (!averageIncome.anyHistory() && !averageExpenses.anyHistory())
                || (averageIncome.baseComplete() && averageExpenses.baseComplete());
    }

    /**
     * Non-base currencies from the given dimensions, deduplicated and ordered.
     *
     * <p>Bounded by the closed catalogue, so it can never grow with the size of
     * the ledger, and an {@link EnumSet} iterates in ordinal order — which is
     * the catalogue order — so two identical datasets always report identically.
     */
    static List<String> collectMissing(CurrencyCode base, List<List<String>> perDimension) {
        EnumSet<CurrencyCode> missing = EnumSet.noneOf(CurrencyCode.class);
        for (List<String> dimension : perDimension) {
            for (String code : dimension) {
                CurrencyCode parsed = CurrencyCode.parse(code);
                if (parsed != base) {
                    missing.add(parsed);
                }
            }
        }
        List<String> ordered = new ArrayList<>(missing.size());
        missing.forEach(currency -> ordered.add(currency.name()));
        return List.copyOf(ordered);
    }
}
