package com.finora.api.common.money;

import com.finora.api.common.error.BusinessRuleException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * Central monetary rules for Finora.
 *
 * <p>Monetary values are stored with 2 decimal places and rounded HALF_UP.
 * Every calculation that produces a monetary result must normalize through this
 * class so rounding behavior is identical everywhere.
 *
 * <p>Each amount belongs to exactly one {@link CurrencyCode}. Storage scale
 * stays at 2 for every currency so the existing {@code NUMERIC(14,2)} columns
 * remain valid, but the <em>currency</em> decides how many fraction digits are
 * meaningful: a JPY amount is rounded and validated as a whole number and is
 * presented without decimals. Amounts in different currencies are never added;
 * that is a caller-level invariant this class deliberately cannot express.
 */
public final class MoneyRules {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** Scale used for intermediate ratios/rates before final monetary rounding. */
    public static final int RATE_SCALE = 10;

    private static final Locale PT_BR = Locale.of("pt", "BR");

    private MoneyRules() {
    }

    public static BigDecimal normalize(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }

    /**
     * Rounds a value to the fraction digits its currency actually has, then
     * restores the storage scale so it fits the shared numeric columns.
     *
     * <p>For a two-decimal currency this is identical to {@link #normalize};
     * for JPY it collapses any fractional part first, so the stored value is
     * always a whole number of yen.
     */
    public static BigDecimal normalize(BigDecimal value, CurrencyCode currency) {
        return value.setScale(currency.getFractionDigits(), ROUNDING).setScale(SCALE, ROUNDING);
    }

    /**
     * Rejects an amount carrying more precision than its currency allows,
     * rather than silently rounding away money the user typed.
     *
     * @throws BusinessRuleException with code {@code CURRENCY_FRACTION_INVALID}
     */
    public static void validateScale(BigDecimal value, CurrencyCode currency) {
        if (value == null) {
            return;
        }
        int digits = currency.getFractionDigits();
        if (value.stripTrailingZeros().scale() > digits) {
            throw new BusinessRuleException(
                    "CURRENCY_FRACTION_INVALID",
                    digits == 0
                            ? "%s não aceita centavos: informe um valor inteiro."
                                    .formatted(currency.name())
                            : "%s aceita no máximo %d casas decimais."
                                    .formatted(currency.name(), digits));
        }
    }

    /** Formats a value in its own currency for human-readable API messages. */
    public static String format(BigDecimal value, CurrencyCode currency) {
        NumberFormat format = NumberFormat.getCurrencyInstance(PT_BR);
        format.setCurrency(Currency.getInstance(currency.name()));
        format.setMinimumFractionDigits(currency.getFractionDigits());
        format.setMaximumFractionDigits(currency.getFractionDigits());
        return format.format(normalize(value, currency));
    }

    /**
     * Formats a value as BRL for human-readable API messages (pt-BR grouping).
     *
     * @deprecated genuinely BRL-specific callers only; prefer
     *     {@link #format(BigDecimal, CurrencyCode)} so the amount carries its
     *     own currency.
     */
    @Deprecated
    public static String formatBrl(BigDecimal value) {
        return format(value, CurrencyCode.BRL);
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
