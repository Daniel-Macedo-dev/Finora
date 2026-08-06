package com.finora.api.common.money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A monetary total that refuses to lie when more than one currency is involved.
 *
 * <p>Finora has no exchange-rate ledger yet, so amounts in different currencies
 * genuinely cannot be combined. Adding them anyway, or quietly dropping the
 * foreign ones, would both produce a number the user would reasonably act on.
 * This type makes the honest answer expressible: totals are always available
 * grouped by their own currency, and the single consolidated figure exists only
 * when there is nothing left to convert.
 *
 * <p>The grouping is bounded by the closed {@link CurrencyCode} catalogue, so a
 * breakdown can never grow with the size of the ledger.
 *
 * @param baseCurrency the user's base currency, for callers rendering the total
 * @param complete whether every contributing amount was already in
 *     {@code baseCurrency}; when false, {@code total} is deliberately null
 * @param total the consolidated amount, or null when consolidation is
 *     impossible without exchange rates
 * @param byCurrency native totals per currency, ordered by catalogue position
 * @param unconvertedCurrencies the non-base currencies present, i.e. exactly
 *     what a future FX stage would have to convert
 */
public record CurrencyTotals(
        String baseCurrency,
        boolean complete,
        BigDecimal total,
        List<CurrencyAmount> byCurrency,
        List<String> unconvertedCurrencies) {

    /** One currency's own total. */
    public record CurrencyAmount(BigDecimal amount, String currency) {
    }

    /** A single amount tagged with the currency it is denominated in. */
    public record Entry(BigDecimal amount, CurrencyCode currency) {
    }

    /**
     * Groups entries by currency and consolidates only when it is honest to.
     *
     * <p>An empty set of entries is complete: zero is zero in any currency.
     */
    public static CurrencyTotals of(List<Entry> entries, CurrencyCode baseCurrency) {
        Map<CurrencyCode, BigDecimal> sums = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry == null || entry.amount() == null || entry.currency() == null) {
                continue;
            }
            sums.merge(entry.currency(), entry.amount(), BigDecimal::add);
        }

        List<CurrencyAmount> byCurrency = sums.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().ordinal()))
                .map(e -> new CurrencyAmount(
                        MoneyRules.normalize(e.getValue(), e.getKey()), e.getKey().name()))
                .toList();

        List<String> unconverted = new ArrayList<>(sums.keySet().stream()
                .filter(currency -> currency != baseCurrency)
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(Enum::name)
                .toList());

        boolean complete = unconverted.isEmpty();
        BigDecimal total = complete
                ? MoneyRules.normalize(
                        sums.getOrDefault(baseCurrency, BigDecimal.ZERO), baseCurrency)
                : null;

        return new CurrencyTotals(
                baseCurrency.name(), complete, total, byCurrency, List.copyOf(unconverted));
    }

    /** Convenience for callers that already hold one homogeneous currency. */
    public static CurrencyTotals single(BigDecimal amount, CurrencyCode currency,
            CurrencyCode baseCurrency) {
        return of(List.of(new Entry(amount, currency)), baseCurrency);
    }
}
