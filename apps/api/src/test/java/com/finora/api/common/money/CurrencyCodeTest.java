package com.finora.api.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.api.common.error.BusinessRuleException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The currency catalogue is closed: only the eight supported codes exist. */
class CurrencyCodeTest {

    @ParameterizedTest
    @ValueSource(strings = {"BRL", "USD", "EUR", "GBP", "CAD", "AUD", "CHF", "JPY"})
    void parsesEverySupportedCode(String code) {
        assertThat(CurrencyCode.parse(code).name()).isEqualTo(code);
    }

    @Test
    void catalogueContainsExactlyTheSupportedCodes() {
        assertThat(CurrencyCode.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        "BRL", "USD", "EUR", "GBP", "CAD", "AUD", "CHF", "JPY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"BTC", "XAU", "ARS", "brlx", "", "   ", "R$"})
    void rejectsCodesOutsideTheCatalogue(String code) {
        assertThatThrownBy(() -> CurrencyCode.parse(code))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(error ->
                        assertThat(((BusinessRuleException) error).getCode())
                                .isEqualTo("CURRENCY_UNSUPPORTED"));
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> CurrencyCode.parse(null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void parseOrNullTreatsAbsenceAsUnspecifiedRatherThanInvalid() {
        assertThat(CurrencyCode.parseOrNull(null)).isNull();
        assertThat(CurrencyCode.parseOrNull("  ")).isNull();
        assertThat(CurrencyCode.parseOrNull("usd")).isEqualTo(CurrencyCode.USD);
    }

    @Test
    void jpyIsTheOnlyZeroDecimalCurrency() {
        assertThat(CurrencyCode.JPY.getFractionDigits()).isZero();
        for (CurrencyCode currency : CurrencyCode.values()) {
            if (currency != CurrencyCode.JPY) {
                assertThat(currency.getFractionDigits()).isEqualTo(2);
            }
        }
    }

    @Test
    void dollarSymbolsStayDistinguishable() {
        assertThat(CurrencyCode.USD.getSymbol()).isNotEqualTo(CurrencyCode.CAD.getSymbol());
        assertThat(CurrencyCode.CAD.getSymbol()).isNotEqualTo(CurrencyCode.AUD.getSymbol());
        assertThat(CurrencyCode.USD.getSymbol()).isNotEqualTo("$");
    }

    @Test
    void twoDecimalCurrenciesAcceptCents() {
        for (CurrencyCode currency : CurrencyCode.values()) {
            if (currency == CurrencyCode.JPY) {
                continue;
            }
            BigDecimal amount = new BigDecimal("10.55");
            MoneyRules.validateScale(amount, currency);
            assertThat(MoneyRules.normalize(amount, currency))
                    .isEqualByComparingTo(new BigDecimal("10.55"));
        }
    }

    @Test
    void jpyAcceptsWholeAmounts() {
        BigDecimal amount = new BigDecimal("1200");
        MoneyRules.validateScale(amount, CurrencyCode.JPY);
        assertThat(MoneyRules.normalize(amount, CurrencyCode.JPY))
                .isEqualByComparingTo(new BigDecimal("1200"));
    }

    @Test
    void jpyRejectsNonZeroFractions() {
        assertThatThrownBy(
                        () -> MoneyRules.validateScale(new BigDecimal("100.50"), CurrencyCode.JPY))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(error ->
                        assertThat(((BusinessRuleException) error).getCode())
                                .isEqualTo("CURRENCY_FRACTION_INVALID"));
    }

    @Test
    void jpyAcceptsTrailingZeroFractionsBecauseTheValueIsStillWhole() {
        MoneyRules.validateScale(new BigDecimal("100.00"), CurrencyCode.JPY);
    }

    @Test
    void twoDecimalCurrenciesRejectSubCentPrecision() {
        assertThatThrownBy(
                        () -> MoneyRules.validateScale(new BigDecimal("10.005"), CurrencyCode.USD))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(error ->
                        assertThat(((BusinessRuleException) error).getCode())
                                .isEqualTo("CURRENCY_FRACTION_INVALID"));
    }

    @Test
    void formatsEachCurrencyWithItsOwnFractionDigits() {
        assertThat(MoneyRules.format(new BigDecimal("1234.50"), CurrencyCode.BRL))
                .contains("1.234,50");
        assertThat(MoneyRules.format(new BigDecimal("1234"), CurrencyCode.JPY))
                .contains("1.234")
                .doesNotContain("1.234,00");
    }

    @Test
    void storageScaleStaysAtTwoSoExistingColumnsRemainValid() {
        assertThat(MoneyRules.normalize(new BigDecimal("1200"), CurrencyCode.JPY).scale())
                .isEqualTo(MoneyRules.SCALE);
    }
}
