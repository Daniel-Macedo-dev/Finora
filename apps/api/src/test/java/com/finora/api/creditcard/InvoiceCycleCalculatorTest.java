package com.finora.api.creditcard;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.api.creditcard.InvoiceCycleCalculator.InvoiceCycle;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InvoiceCycleCalculatorTest {

    @Nested
    class ClosingBeforeDueSameMonth {

        // closing 10, due 17: statement closes and is due in the same month.

        @Test
        void purchaseBeforeClosingEntersCurrentMonthInvoice() {
            InvoiceCycle cycle = InvoiceCycleCalculator.cycleForPurchase(10, 17, LocalDate.of(2026, 7, 5));
            assertThat(cycle.referenceMonth()).isEqualTo(YearMonth.of(2026, 7));
            assertThat(cycle.closingDate()).isEqualTo(LocalDate.of(2026, 7, 10));
            assertThat(cycle.dueDate()).isEqualTo(LocalDate.of(2026, 7, 17));
        }

        @Test
        void purchaseOnClosingDayStillEntersClosingInvoice() {
            InvoiceCycle cycle = InvoiceCycleCalculator.cycleForPurchase(10, 17, LocalDate.of(2026, 7, 10));
            assertThat(cycle.referenceMonth()).isEqualTo(YearMonth.of(2026, 7));
        }

        @Test
        void purchaseAfterClosingRollsToNextMonth() {
            InvoiceCycle cycle = InvoiceCycleCalculator.cycleForPurchase(10, 17, LocalDate.of(2026, 7, 11));
            assertThat(cycle.referenceMonth()).isEqualTo(YearMonth.of(2026, 8));
            assertThat(cycle.closingDate()).isEqualTo(LocalDate.of(2026, 8, 10));
            assertThat(cycle.dueDate()).isEqualTo(LocalDate.of(2026, 8, 17));
        }
    }

    @Nested
    class ClosingInPreviousMonth {

        // closing 28, due 5: statement closes near month-end, due early next month.

        @Test
        void purchaseBeforePreviousMonthClosingEntersCurrentDueMonth() {
            InvoiceCycle cycle = InvoiceCycleCalculator.cycleForPurchase(28, 5, LocalDate.of(2026, 7, 20));
            assertThat(cycle.referenceMonth()).isEqualTo(YearMonth.of(2026, 8));
            assertThat(cycle.closingDate()).isEqualTo(LocalDate.of(2026, 7, 28));
            assertThat(cycle.dueDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        }

        @Test
        void purchaseAfterClosingRollsToFollowingDueMonth() {
            InvoiceCycle cycle = InvoiceCycleCalculator.cycleForPurchase(28, 5, LocalDate.of(2026, 7, 29));
            assertThat(cycle.referenceMonth()).isEqualTo(YearMonth.of(2026, 9));
            assertThat(cycle.closingDate()).isEqualTo(LocalDate.of(2026, 8, 28));
            assertThat(cycle.dueDate()).isEqualTo(LocalDate.of(2026, 9, 5));
        }

        @Test
        void closingDayEqualToDueDayClosesInPreviousMonth() {
            InvoiceCycle cycle = InvoiceCycleCalculator.cycleFor(15, 15, YearMonth.of(2026, 8));
            assertThat(cycle.closingDate()).isEqualTo(LocalDate.of(2026, 7, 15));
            assertThat(cycle.dueDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        }
    }

    @Nested
    class ShortMonths {

        @Test
        void closingDay31IsCappedInFebruary() {
            // closing 31, due 7 → February invoice... closing 31 > due 7, so the
            // March reference month closes in February, capped to Feb 28.
            InvoiceCycle cycle = InvoiceCycleCalculator.cycleFor(31, 7, YearMonth.of(2026, 3));
            assertThat(cycle.closingDate()).isEqualTo(LocalDate.of(2026, 2, 28));
            assertThat(cycle.dueDate()).isEqualTo(LocalDate.of(2026, 3, 7));
        }

        @Test
        void leapYearFebruaryCapsAt29() {
            InvoiceCycle cycle = InvoiceCycleCalculator.cycleFor(31, 7, YearMonth.of(2028, 3));
            assertThat(cycle.closingDate()).isEqualTo(LocalDate.of(2028, 2, 29));
        }

        @Test
        void closingDay31IsCappedIn30DayMonth() {
            InvoiceCycle cycle = InvoiceCycleCalculator.cycleFor(31, 7, YearMonth.of(2026, 5));
            assertThat(cycle.closingDate()).isEqualTo(LocalDate.of(2026, 4, 30));
        }

        @Test
        void dueDay31IsCappedIn30DayMonth() {
            InvoiceCycle cycle = InvoiceCycleCalculator.cycleFor(20, 31, YearMonth.of(2026, 4));
            assertThat(cycle.dueDate()).isEqualTo(LocalDate.of(2026, 4, 30));
            assertThat(cycle.closingDate()).isEqualTo(LocalDate.of(2026, 4, 20));
        }

        @Test
        void purchaseOnCappedFebruaryClosingDayIsIncluded() {
            InvoiceCycle cycle = InvoiceCycleCalculator.cycleForPurchase(31, 7, LocalDate.of(2026, 2, 28));
            assertThat(cycle.referenceMonth()).isEqualTo(YearMonth.of(2026, 3));
        }
    }

    @Nested
    class YearTransition {

        @Test
        void decemberPurchaseAfterClosingLandsInJanuaryInvoice() {
            InvoiceCycle cycle = InvoiceCycleCalculator.cycleForPurchase(10, 17, LocalDate.of(2026, 12, 15));
            assertThat(cycle.referenceMonth()).isEqualTo(YearMonth.of(2027, 1));
            assertThat(cycle.closingDate()).isEqualTo(LocalDate.of(2027, 1, 10));
            assertThat(cycle.dueDate()).isEqualTo(LocalDate.of(2027, 1, 17));
        }

        @Test
        void januaryInvoiceMayCloseInDecember() {
            InvoiceCycle cycle = InvoiceCycleCalculator.cycleFor(28, 5, YearMonth.of(2027, 1));
            assertThat(cycle.closingDate()).isEqualTo(LocalDate.of(2026, 12, 28));
            assertThat(cycle.dueDate()).isEqualTo(LocalDate.of(2027, 1, 5));
        }
    }

    @Test
    void closingDatesGrowStrictlyWithReferenceMonth() {
        // Guarantees the purchase assignment is unique for every configuration.
        for (int closing = 1; closing <= 31; closing++) {
            for (int due = 1; due <= 31; due++) {
                LocalDate previous = null;
                for (YearMonth month = YearMonth.of(2026, 1);
                        month.isBefore(YearMonth.of(2028, 6)); month = month.plusMonths(1)) {
                    LocalDate closingDate = InvoiceCycleCalculator.cycleFor(closing, due, month).closingDate();
                    if (previous != null) {
                        assertThat(closingDate).isAfter(previous);
                    }
                    previous = closingDate;
                }
            }
        }
    }

    @Test
    void everyPurchaseDateIsOnOrBeforeItsInvoiceClosingDate() {
        for (int closing : new int[] {1, 10, 28, 31}) {
            for (int due : new int[] {1, 5, 17, 31}) {
                for (LocalDate date = LocalDate.of(2026, 1, 1);
                        date.isBefore(LocalDate.of(2027, 3, 1)); date = date.plusDays(1)) {
                    InvoiceCycle cycle = InvoiceCycleCalculator.cycleForPurchase(closing, due, date);
                    assertThat(cycle.closingDate())
                            .as("closing %d due %d purchase %s", closing, due, date)
                            .isAfterOrEqualTo(date);
                    // And the previous cycle must have closed before the purchase.
                    InvoiceCycle previous = InvoiceCycleCalculator.cycleFor(
                            closing, due, cycle.referenceMonth().minusMonths(1));
                    assertThat(previous.closingDate()).isBefore(date);
                }
            }
        }
    }
}
