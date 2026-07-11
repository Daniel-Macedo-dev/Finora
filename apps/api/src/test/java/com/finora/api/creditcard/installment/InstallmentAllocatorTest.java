package com.finora.api.creditcard.installment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class InstallmentAllocatorTest {

    @Test
    void singleInstallmentKeepsTotal() {
        assertThat(InstallmentAllocator.allocate(new BigDecimal("1234.56"), 1))
                .containsExactly(new BigDecimal("1234.56"));
    }

    @Test
    void evenSplitHasEqualParts() {
        assertThat(InstallmentAllocator.allocate(new BigDecimal("100.00"), 2))
                .containsExactly(new BigDecimal("50.00"), new BigDecimal("50.00"));
    }

    @Test
    void remainderGoesToFinalInstallments() {
        assertThat(InstallmentAllocator.allocate(new BigDecimal("100.00"), 3))
                .containsExactly(
                        new BigDecimal("33.33"),
                        new BigDecimal("33.33"),
                        new BigDecimal("33.34"));
    }

    @Test
    void twoCentRemainderRaisesLastTwo() {
        // 100.01 / 3 = 33.33 base with remainder 2 → last two get one extra cent.
        assertThat(InstallmentAllocator.allocate(new BigDecimal("100.01"), 3))
                .containsExactly(
                        new BigDecimal("33.33"),
                        new BigDecimal("33.34"),
                        new BigDecimal("33.34"));
    }

    @Test
    void twelveInstallmentsOfTwelveHundredAreExactHundreds() {
        List<BigDecimal> parts = InstallmentAllocator.allocate(new BigDecimal("1200.00"), 12);
        assertThat(parts).hasSize(12).allSatisfy(part ->
                assertThat(part).isEqualByComparingTo(new BigDecimal("100.00")));
    }

    @Test
    void sumAlwaysEqualsTotalExactly() {
        for (String total : new String[] {"0.01", "0.99", "1.00", "9.99", "100.00",
                "999.99", "1234.56", "999999999999.99"}) {
            for (int count : new int[] {1, 2, 3, 5, 7, 11, 12, 24, 48, 60}) {
                BigDecimal value = new BigDecimal(total);
                if (value.movePointRight(2).longValueExact() < count) {
                    continue; // cannot produce positive installments
                }
                List<BigDecimal> parts = InstallmentAllocator.allocate(value, count);
                assertThat(parts).hasSize(count);
                assertThat(parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                        .as("%s in %d parts", total, count)
                        .isEqualByComparingTo(value);
                // Deterministic: parts differ by at most one cent, larger ones last.
                assertThat(parts.getFirst().subtract(parts.getLast()).abs())
                        .isLessThanOrEqualTo(new BigDecimal("0.01"));
            }
        }
    }

    @Test
    void oneCentSplitsOnlyIntoOneInstallment() {
        assertThat(InstallmentAllocator.allocate(new BigDecimal("0.01"), 1))
                .containsExactly(new BigDecimal("0.01"));
        assertThatThrownBy(() -> InstallmentAllocator.allocate(new BigDecimal("0.01"), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoCentsInTwoInstallmentsIsOneCentEach() {
        assertThat(InstallmentAllocator.allocate(new BigDecimal("0.02"), 2))
                .containsExactly(new BigDecimal("0.01"), new BigDecimal("0.01"));
    }

    @Test
    void nonPositiveInputsAreRejected() {
        assertThatThrownBy(() -> InstallmentAllocator.allocate(BigDecimal.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InstallmentAllocator.allocate(new BigDecimal("10.00"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void subCentPrecisionIsNormalizedBeforeSplitting() {
        // Inputs are normalized HALF_UP to cents, matching MoneyRules.
        assertThat(InstallmentAllocator.allocate(new BigDecimal("10.005"), 2))
                .containsExactly(new BigDecimal("5.00"), new BigDecimal("5.01"));
    }
}
