package com.finora.api.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Central monetary rules for Finora.
 *
 * <p>All monetary values are BRL, stored with 2 decimal places and rounded
 * HALF_UP. Every calculation that produces a monetary result must normalize
 * through this class so rounding behavior is identical everywhere.
 */
public final class MoneyRules {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** Scale used for intermediate ratios/rates before final monetary rounding. */
    public static final int RATE_SCALE = 10;

    private MoneyRules() {
    }

    public static BigDecimal normalize(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
