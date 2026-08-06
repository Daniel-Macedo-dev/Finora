package com.finora.api.common.money;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.api.common.money.CurrencyTotals.Entry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A total must never combine currencies Finora cannot convert, and must never
 * present a homogeneous foreign total as a complete base-currency analysis.
 */
class CurrencyTotalsTest {

    // ── Base completeness ───────────────────────────────────────────────────

    @Test
    void aSingleCurrencyMatchingTheBaseIsBothHomogeneousAndBaseComplete() {
        CurrencyTotals totals = CurrencyTotals.of(
                List.of(new Entry(new BigDecimal("100.00"), CurrencyCode.BRL),
                        new Entry(new BigDecimal("50.50"), CurrencyCode.BRL)),
                CurrencyCode.BRL);

        assertThat(totals.baseComplete()).isTrue();
        assertThat(totals.baseTotal()).isEqualByComparingTo("150.50");
        assertThat(totals.homogeneous()).isTrue();
        assertThat(totals.homogeneousCurrency()).isEqualTo("BRL");
        assertThat(totals.nativeTotal()).isEqualByComparingTo("150.50");
        assertThat(totals.unconvertedCurrencies()).isEmpty();
        assertThat(totals.byCurrency()).singleElement()
                .satisfies(entry -> assertThat(entry.currency()).isEqualTo("BRL"));
    }

    @Test
    void aHomogeneousForeignSetHasANativeTotalButNoBaseTotal() {
        CurrencyTotals totals = CurrencyTotals.of(
                List.of(new Entry(new BigDecimal("1200.00"), CurrencyCode.USD),
                        new Entry(new BigDecimal("300.00"), CurrencyCode.USD)),
                CurrencyCode.BRL);

        // The USD figure is real and addable...
        assertThat(totals.homogeneous()).isTrue();
        assertThat(totals.homogeneousCurrency()).isEqualTo("USD");
        assertThat(totals.nativeTotal()).isEqualByComparingTo("1500.00");
        // ...but it is emphatically not an answer denominated in the user's BRL.
        assertThat(totals.baseComplete()).isFalse();
        assertThat(totals.baseTotal()).isNull();
        assertThat(totals.unconvertedCurrencies()).containsExactly("USD");
    }

    @Test
    void mixedCurrenciesProduceNeitherANativeNorABaseTotal() {
        CurrencyTotals totals = CurrencyTotals.of(
                List.of(new Entry(new BigDecimal("8000.00"), CurrencyCode.BRL),
                        new Entry(new BigDecimal("1200.00"), CurrencyCode.USD)),
                CurrencyCode.BRL);

        assertThat(totals.homogeneous()).isFalse();
        assertThat(totals.homogeneousCurrency()).isNull();
        assertThat(totals.nativeTotal())
                .as("adding BRL to USD would be a fabricated number")
                .isNull();
        assertThat(totals.baseComplete()).isFalse();
        assertThat(totals.baseTotal()).isNull();
        assertThat(totals.unconvertedCurrencies()).containsExactly("USD");
        assertThat(totals.byCurrency())
                .extracting(CurrencyTotals.CurrencyAmount::currency)
                .containsExactly("BRL", "USD");
    }

    @Test
    void foreignAmountsAreNeverSilentlyDropped() {
        CurrencyTotals totals = CurrencyTotals.of(
                List.of(new Entry(new BigDecimal("1200.00"), CurrencyCode.USD)),
                CurrencyCode.BRL);

        // The money exists and is reported; only the conversion is missing.
        assertThat(totals.byCurrency()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.currency()).isEqualTo("USD");
                    assertThat(entry.amount()).isEqualByComparingTo("1200.00");
                });
        assertThat(totals.baseComplete()).isFalse();
        assertThat(totals.baseTotal()).isNull();
    }

    // ── Determinism and bounds ──────────────────────────────────────────────

    @Test
    void emptyIsCompleteBecauseZeroIsZeroInEveryCurrency() {
        CurrencyTotals totals = CurrencyTotals.empty(CurrencyCode.BRL);

        assertThat(totals.baseComplete()).isTrue();
        assertThat(totals.baseTotal()).isEqualByComparingTo("0.00");
        assertThat(totals.homogeneous()).isTrue();
        assertThat(totals.homogeneousCurrency()).isEqualTo("BRL");
        assertThat(totals.nativeTotal()).isEqualByComparingTo("0.00");
        assertThat(totals.byCurrency()).isEmpty();
        assertThat(totals.unconvertedCurrencies()).isEmpty();
    }

    @Test
    void currenciesFollowCatalogueOrderRegardlessOfInsertion() {
        CurrencyTotals totals = CurrencyTotals.of(
                List.of(new Entry(BigDecimal.ONE, CurrencyCode.JPY),
                        new Entry(BigDecimal.ONE, CurrencyCode.USD),
                        new Entry(BigDecimal.ONE, CurrencyCode.BRL),
                        new Entry(BigDecimal.ONE, CurrencyCode.EUR)),
                CurrencyCode.BRL);

        assertThat(totals.byCurrency())
                .extracting(CurrencyTotals.CurrencyAmount::currency)
                .containsExactly("BRL", "USD", "EUR", "JPY");
        assertThat(totals.unconvertedCurrencies()).containsExactly("USD", "EUR", "JPY");
    }

    @Test
    void breakdownIsBoundedByTheClosedCatalogue() {
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            for (CurrencyCode currency : CurrencyCode.values()) {
                many.add(new Entry(BigDecimal.ONE, currency));
            }
        }
        CurrencyTotals totals = CurrencyTotals.of(many, CurrencyCode.BRL);

        assertThat(totals.byCurrency()).hasSize(CurrencyCode.values().length);
        assertThat(totals.unconvertedCurrencies()).hasSize(CurrencyCode.values().length - 1);
        assertThat(totals.homogeneous()).isFalse();
    }

    @Test
    void nullEntriesAndComponentsAreIgnoredRatherThanThrowing() {
        List<Entry> entries = new ArrayList<>();
        entries.add(null);
        entries.add(new Entry(null, CurrencyCode.BRL));
        entries.add(new Entry(BigDecimal.TEN, null));
        entries.add(new Entry(new BigDecimal("5.00"), CurrencyCode.BRL));

        CurrencyTotals totals = CurrencyTotals.of(entries, CurrencyCode.BRL);

        assertThat(totals.baseTotal()).isEqualByComparingTo("5.00");
        assertThat(totals.byCurrency()).hasSize(1);
    }

    // ── Signs and rounding ──────────────────────────────────────────────────

    @Test
    void negativeAndPositiveAmountsStaySigned() {
        CurrencyTotals totals = CurrencyTotals.of(
                List.of(new Entry(new BigDecimal("100.00"), CurrencyCode.BRL),
                        new Entry(new BigDecimal("-250.00"), CurrencyCode.BRL),
                        new Entry(new BigDecimal("-40.00"), CurrencyCode.USD)),
                CurrencyCode.BRL);

        assertThat(totals.byCurrency())
                .extracting(CurrencyTotals.CurrencyAmount::amount)
                .satisfiesExactly(
                        brl -> assertThat(brl).isEqualByComparingTo("-150.00"),
                        usd -> assertThat(usd).isEqualByComparingTo("-40.00"));
    }

    @Test
    void jpyGroupsAsWholeUnits() {
        CurrencyTotals totals = CurrencyTotals.of(
                List.of(new Entry(new BigDecimal("1200"), CurrencyCode.JPY),
                        new Entry(new BigDecimal("300"), CurrencyCode.JPY)),
                CurrencyCode.JPY);

        assertThat(totals.baseComplete()).isTrue();
        assertThat(totals.baseTotal()).isEqualByComparingTo("1500");
        assertThat(totals.nativeTotal()).isEqualByComparingTo("1500");
    }

    // ── Zero handling: flows versus snapshots ───────────────────────────────

    @Test
    void aZeroForeignSnapshotDoesNotBlockABaseAnalysis() {
        // An empty USD account converts to exactly zero BRL under any rate, so
        // its existence must not make the BRL total unavailable.
        CurrencyTotals totals = CurrencyTotals.ofSnapshots(
                List.of(new Entry(new BigDecimal("8000.00"), CurrencyCode.BRL),
                        new Entry(BigDecimal.ZERO, CurrencyCode.USD)),
                CurrencyCode.BRL);

        assertThat(totals.baseComplete()).isTrue();
        assertThat(totals.baseTotal()).isEqualByComparingTo("8000.00");
        assertThat(totals.homogeneous()).isTrue();
        assertThat(totals.homogeneousCurrency()).isEqualTo("BRL");
        assertThat(totals.unconvertedCurrencies()).isEmpty();
        assertThat(totals.byCurrency())
                .extracting(CurrencyTotals.CurrencyAmount::currency)
                .containsExactly("BRL");
    }

    @Test
    void aNonZeroForeignSnapshotStillBlocksTheBaseAnalysis() {
        CurrencyTotals totals = CurrencyTotals.ofSnapshots(
                List.of(new Entry(new BigDecimal("8000.00"), CurrencyCode.BRL),
                        new Entry(new BigDecimal("0.01"), CurrencyCode.USD)),
                CurrencyCode.BRL);

        assertThat(totals.baseComplete()).isFalse();
        assertThat(totals.baseTotal()).isNull();
        assertThat(totals.unconvertedCurrencies()).containsExactly("USD");
    }

    @Test
    void cancellingForeignFlowsStillRequireConversion() {
        // +100 and -100 USD are two dated events. A future FX ledger would
        // convert them at two different rates, so their BRL sum is not zero and
        // the base analysis genuinely remains incomplete.
        CurrencyTotals totals = CurrencyTotals.of(
                List.of(new Entry(new BigDecimal("8000.00"), CurrencyCode.BRL),
                        new Entry(new BigDecimal("100.00"), CurrencyCode.USD),
                        new Entry(new BigDecimal("-100.00"), CurrencyCode.USD)),
                CurrencyCode.BRL);

        assertThat(totals.baseComplete()).isFalse();
        assertThat(totals.baseTotal()).isNull();
        assertThat(totals.unconvertedCurrencies()).containsExactly("USD");
        assertThat(totals.byCurrency())
                .extracting(CurrencyTotals.CurrencyAmount::amount)
                .satisfiesExactly(
                        brl -> assertThat(brl).isEqualByComparingTo("8000.00"),
                        usd -> assertThat(usd).isEqualByComparingTo("0.00"));
    }

    @Test
    void anAllZeroSnapshotSetIsTheDeterministicZero() {
        CurrencyTotals totals = CurrencyTotals.ofSnapshots(
                List.of(new Entry(BigDecimal.ZERO, CurrencyCode.USD),
                        new Entry(BigDecimal.ZERO, CurrencyCode.EUR)),
                CurrencyCode.BRL);

        assertThat(totals.baseComplete()).isTrue();
        assertThat(totals.baseTotal()).isEqualByComparingTo("0.00");
        assertThat(totals.homogeneousCurrency()).isEqualTo("BRL");
        assertThat(totals.byCurrency()).isEmpty();
    }

    @Test
    void singleWrapsOneHomogeneousAmount() {
        CurrencyTotals totals = CurrencyTotals.single(
                new BigDecimal("42.00"), CurrencyCode.EUR, CurrencyCode.EUR);

        assertThat(totals.homogeneous()).isTrue();
        assertThat(totals.baseComplete()).isTrue();
        assertThat(totals.baseTotal()).isEqualByComparingTo("42.00");
    }
}
