package com.finora.api.common.money;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A monetary total that refuses to lie when more than one currency is involved.
 *
 * <p>Finora has no exchange-rate ledger yet, so amounts in different currencies
 * genuinely cannot be combined. Adding them anyway, or quietly dropping the
 * foreign ones, would both produce a number the user would reasonably act on.
 *
 * <p>Two different questions are answered separately, because conflating them
 * is exactly how a foreign number gets presented as a base-currency conclusion:
 *
 * <ul>
 *   <li><strong>Homogeneity</strong> — do all contributing amounts share one
 *       currency? If so {@code nativeTotal} is a real, addable figure in
 *       {@code homogeneousCurrency}. A user whose entire ledger is in USD gets
 *       a valid USD total.
 *   <li><strong>Base completeness</strong> — is every contributing amount
 *       already denominated in the user's base currency? Only then does
 *       {@code baseTotal} exist, and only then may a base-denominated analysis
 *       (savings rate, budget consumption, affordability) draw a conclusion.
 * </ul>
 *
 * <p>A homogeneous USD set is <em>not</em> a complete BRL analysis. Both flags
 * are therefore reported, and callers must pick the one their domain needs.
 *
 * <p>The grouping is bounded by the closed {@link CurrencyCode} catalogue and
 * ordered by catalogue position, so a breakdown can never grow with the size of
 * the ledger and two identical datasets always serialize identically.
 *
 * @param baseCurrency the user's base currency, for callers rendering the total
 * @param byCurrency native totals per currency, ordered by catalogue position;
 *     always available, even when nothing can be consolidated
 * @param homogeneous whether every contributing amount shares one currency
 * @param homogeneousCurrency that shared currency, or null when mixed; an empty
 *     set reports {@code baseCurrency}, because zero is zero everywhere
 * @param nativeTotal the addable total in {@code homogeneousCurrency}, or null
 *     when the set is mixed
 * @param baseComplete whether every contributing amount was already in
 *     {@code baseCurrency}
 * @param baseTotal the base-denominated total, or null when the set is not
 *     base-complete; never a converted or assumed figure
 * @param unconvertedCurrencies the non-base currencies present, i.e. exactly
 *     what a future FX stage would have to convert
 */
public record CurrencyTotals(
        String baseCurrency,
        List<CurrencyAmount> byCurrency,
        boolean homogeneous,
        String homogeneousCurrency,
        BigDecimal nativeTotal,
        boolean baseComplete,
        BigDecimal baseTotal,
        List<String> unconvertedCurrencies) {

    /** One currency's own total. */
    public record CurrencyAmount(BigDecimal amount, String currency) {
    }

    /** A single amount tagged with the currency it is denominated in. */
    public record Entry(BigDecimal amount, CurrencyCode currency) {
    }

    /**
     * Groups flow entries by currency and consolidates only where it is honest.
     *
     * <p>Presence is decided by contribution, not by the net figure. A currency
     * whose entries happen to cancel out (+100 and -100 USD) still counts as
     * present: those are two events on two dates, and a future FX ledger would
     * convert them at two different rates, so their base-currency sum is not
     * zero and cannot be assumed away. Use {@link #ofSnapshots} for point-in-time
     * balances, where a zero really is nothing to convert.
     *
     * <p>An empty set is both homogeneous and base-complete with a zero total:
     * zero is zero in any currency.
     */
    public static CurrencyTotals of(List<Entry> entries, CurrencyCode baseCurrency) {
        return build(entries, baseCurrency, false);
    }

    /**
     * Same grouping, for point-in-time snapshots rather than flows.
     *
     * <p>A currency whose snapshot total is exactly zero is dropped before the
     * completeness questions are answered. Converting a zero balance yields zero
     * under any rate, so an empty USD account must not make an otherwise
     * complete BRL analysis unavailable. This is only sound for a single
     * observation per row — never for a stream of dated movements, which is why
     * {@link #of} exists separately.
     */
    public static CurrencyTotals ofSnapshots(List<Entry> entries, CurrencyCode baseCurrency) {
        return build(entries, baseCurrency, true);
    }

    /** Convenience for callers that already hold one homogeneous currency. */
    public static CurrencyTotals single(BigDecimal amount, CurrencyCode currency,
            CurrencyCode baseCurrency) {
        return of(List.of(new Entry(amount, currency)), baseCurrency);
    }

    /** The deterministic zero: homogeneous, base-complete, nothing to convert. */
    public static CurrencyTotals empty(CurrencyCode baseCurrency) {
        return of(List.of(), baseCurrency);
    }

    private static CurrencyTotals build(List<Entry> entries, CurrencyCode baseCurrency,
            boolean dropZeroGroups) {
        Objects.requireNonNull(baseCurrency, "baseCurrency");
        // An EnumMap is bounded by the closed catalogue and iterates in ordinal
        // order, which is where the deterministic currency ordering comes from.
        Map<CurrencyCode, BigDecimal> sums = new EnumMap<>(CurrencyCode.class);
        for (Entry entry : entries) {
            if (entry == null || entry.amount() == null || entry.currency() == null) {
                continue;
            }
            sums.merge(entry.currency(), entry.amount(), BigDecimal::add);
        }
        if (dropZeroGroups) {
            sums.values().removeIf(amount -> amount.signum() == 0);
        }

        List<CurrencyAmount> byCurrency = sums.entrySet().stream()
                .map(entry -> new CurrencyAmount(
                        MoneyRules.normalize(entry.getValue(), entry.getKey()),
                        entry.getKey().name()))
                .toList();

        List<String> unconverted = sums.keySet().stream()
                .filter(currency -> currency != baseCurrency)
                .map(Enum::name)
                .toList();

        boolean baseComplete = unconverted.isEmpty();
        BigDecimal baseTotal = baseComplete
                ? MoneyRules.normalize(
                        sums.getOrDefault(baseCurrency, BigDecimal.ZERO), baseCurrency)
                : null;

        boolean homogeneous = sums.size() <= 1;
        CurrencyCode nativeCurrency = homogeneous
                ? sums.keySet().stream().findFirst().orElse(baseCurrency)
                : null;
        BigDecimal nativeTotal = homogeneous
                ? MoneyRules.normalize(
                        sums.getOrDefault(nativeCurrency, BigDecimal.ZERO), nativeCurrency)
                : null;

        return new CurrencyTotals(
                baseCurrency.name(),
                byCurrency,
                homogeneous,
                nativeCurrency == null ? null : nativeCurrency.name(),
                nativeTotal,
                baseComplete,
                baseTotal,
                unconverted);
    }
}
