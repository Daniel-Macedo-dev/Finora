package com.finora.api.common.money;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.api.common.money.CurrencyTotals.Entry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A total must never combine currencies Finora cannot convert. */
class CurrencyTotalsTest {

    @Test
    void aSingleCurrencyMatchingTheBaseConsolidatesNormally() {
        CurrencyTotals totals = CurrencyTotals.of(
                List.of(new Entry(new BigDecimal("100.00"), CurrencyCode.BRL),
                        new Entry(new BigDecimal("50.50"), CurrencyCode.BRL)),
                CurrencyCode.BRL);

        assertThat(totals.complete()).isTrue();
        assertThat(totals.total()).isEqualByComparingTo("150.50");
        assertThat(totals.unconvertedCurrencies()).isEmpty();
        assertThat(totals.byCurrency()).singleElement()
                .satisfies(entry -> assertThat(entry.currency()).isEqualTo("BRL"));
    }

    @Test
    void mixedCurrenciesNeverProduceAConsolidatedNumber() {
        CurrencyTotals totals = CurrencyTotals.of(
                List.of(new Entry(new BigDecimal("8000.00"), CurrencyCode.BRL),
                        new Entry(new BigDecimal("1200.00"), CurrencyCode.USD)),
                CurrencyCode.BRL);

        assertThat(totals.complete()).isFalse();
        assertThat(totals.total())
                .as("a consolidated total would be a fabricated number")
                .isNull();
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
        assertThat(totals.complete()).isFalse();
        assertThat(totals.total()).isNull();
    }

    @Test
    void aNonBaseSingleCurrencyStillRequiresConversion() {
        CurrencyTotals totals = CurrencyTotals.of(
                List.of(new Entry(new BigDecimal("10.00"), CurrencyCode.EUR)),
                CurrencyCode.BRL);

        assertThat(totals.complete()).isFalse();
        assertThat(totals.unconvertedCurrencies()).containsExactly("EUR");
    }

    @Test
    void emptyIsCompleteBecauseZeroIsZeroInEveryCurrency() {
        CurrencyTotals totals = CurrencyTotals.of(List.of(), CurrencyCode.BRL);

        assertThat(totals.complete()).isTrue();
        assertThat(totals.total()).isEqualByComparingTo("0.00");
        assertThat(totals.byCurrency()).isEmpty();
    }

    @Test
    void jpyGroupsAsWholeUnits() {
        CurrencyTotals totals = CurrencyTotals.of(
                List.of(new Entry(new BigDecimal("1200"), CurrencyCode.JPY),
                        new Entry(new BigDecimal("300"), CurrencyCode.JPY)),
                CurrencyCode.JPY);

        assertThat(totals.complete()).isTrue();
        assertThat(totals.total()).isEqualByComparingTo("1500");
    }

    @Test
    void breakdownIsBoundedByTheClosedCatalogue() {
        List<Entry> many = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            for (CurrencyCode currency : CurrencyCode.values()) {
                many.add(new Entry(BigDecimal.ONE, currency));
            }
        }
        CurrencyTotals totals = CurrencyTotals.of(many, CurrencyCode.BRL);

        assertThat(totals.byCurrency()).hasSize(CurrencyCode.values().length);
        assertThat(totals.unconvertedCurrencies()).hasSize(CurrencyCode.values().length - 1);
    }
}
