package com.finora.api.insight;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.api.insight.InsightDtos.Insight;
import com.finora.api.insight.InsightDtos.InsightSeverity;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * An amount and its currency travel together or neither does.
 *
 * <p>The pairing is a type guarantee rather than a convention because the two
 * halves are produced in different places — a native rule takes the card's
 * currency, an aggregate one the base currency — and a figure that lost its
 * denomination on the way out is not a smaller piece of information than one
 * that kept it. It is a different number.
 */
class InsightContractTest {

    private static Insight insight(BigDecimal amount, String currency) {
        return new Insight("EXPENSE_INCREASE", InsightSeverity.WARNING, "Título", "Mensagem",
                amount, currency);
    }

    @Test
    void aMonetaryInsightCannotOmitItsCurrency() {
        assertThatThrownBy(() -> insight(new BigDecimal("1000.00"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void aCurrencyWithoutAnAmountHasNothingToDenominate() {
        assertThatThrownBy(() -> insight(null, "BRL"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNonMonetaryInsightMayCarryNeither() {
        assertThatCode(() -> insight(null, null)).doesNotThrowAnyException();
    }

    @Test
    void aMonetaryInsightCarriesBoth() {
        assertThatCode(() -> insight(new BigDecimal("1000.00"), "USD")).doesNotThrowAnyException();
    }
}
