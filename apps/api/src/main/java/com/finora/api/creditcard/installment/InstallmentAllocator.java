package com.finora.api.creditcard.installment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, cent-exact split of a purchase total into installments.
 *
 * <p>Rule: normalize the total to cents, divide by the installment count and
 * give every installment the base value; the remainder (always fewer cents
 * than installments) is distributed one cent each to the <em>last</em>
 * installments. The sum of the parts equals the total exactly — no cent is
 * ever created or lost, and equal inputs always produce equal outputs.
 *
 * <p>Example: R$ 100,00 in 3 → 33,33 · 33,33 · 33,34.
 */
public final class InstallmentAllocator {

    private InstallmentAllocator() {
    }

    /** Splits {@code total} into {@code count} parts, in sequence order. */
    public static List<BigDecimal> allocate(BigDecimal total, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("installment count must be at least 1");
        }
        long cents = total.setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact();
        if (cents <= 0) {
            throw new IllegalArgumentException("total must be positive");
        }
        if (cents < count) {
            throw new IllegalArgumentException(
                    "total of %d cents cannot be split into %d positive installments"
                            .formatted(cents, count));
        }
        long base = cents / count;
        long remainder = cents % count;
        List<BigDecimal> amounts = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            long value = i > count - remainder ? base + 1 : base;
            amounts.add(BigDecimal.valueOf(value).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY));
        }
        return List.copyOf(amounts);
    }
}
