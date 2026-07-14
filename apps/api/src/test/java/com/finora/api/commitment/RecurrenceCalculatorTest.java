package com.finora.api.commitment;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.api.category.Category;
import com.finora.api.category.CategoryType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecurrenceCalculatorTest {

    private final Category category = new Category(1L, "Assinaturas", CategoryType.EXPENSE);

    private Commitment weekly(LocalDate start) {
        return new Commitment(1L, "Feira", new BigDecimal("120.00"), category,
                CommitmentCadence.WEEKLY, null, start);
    }

    private Commitment monthly(int dueDay, LocalDate start) {
        return new Commitment(1L, "Internet", new BigDecimal("99.90"), category,
                CommitmentCadence.MONTHLY, dueDay, start);
    }

    private Commitment yearly(LocalDate start) {
        return new Commitment(1L, "Seguro", new BigDecimal("1200.00"), category,
                CommitmentCadence.YEARLY, null, start);
    }

    private static List<LocalDate> between(Commitment c, String from, String to) {
        return RecurrenceCalculator.occurrencesBetween(c, LocalDate.parse(from), LocalDate.parse(to));
    }

    // ── weekly ────────────────────────────────────────────────────────────────

    @Test
    void weeklyRepeatsEverySevenDaysFromTheAnchor() {
        Commitment c = weekly(LocalDate.of(2026, 7, 1)); // a Wednesday
        assertThat(between(c, "2026-07-01", "2026-07-31")).containsExactly(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 8),
                LocalDate.of(2026, 7, 15),
                LocalDate.of(2026, 7, 22),
                LocalDate.of(2026, 7, 29));
    }

    @Test
    void weeklyWindowStartingMidCycleFindsTheNextAlignedDate() {
        Commitment c = weekly(LocalDate.of(2026, 7, 1));
        assertThat(between(c, "2026-07-10", "2026-07-20")).containsExactly(
                LocalDate.of(2026, 7, 15));
    }

    @Test
    void weeklyRespectsInclusiveStartAndEnd() {
        Commitment c = weekly(LocalDate.of(2026, 7, 1));
        c.setEndDate(LocalDate.of(2026, 7, 15));
        assertThat(between(c, "2026-06-01", "2026-08-31")).containsExactly(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 8),
                LocalDate.of(2026, 7, 15));
    }

    @Test
    void weeklyCrossesYearBoundary() {
        Commitment c = weekly(LocalDate.of(2026, 12, 28)); // a Monday
        assertThat(between(c, "2026-12-28", "2027-01-15")).containsExactly(
                LocalDate.of(2026, 12, 28),
                LocalDate.of(2027, 1, 4),
                LocalDate.of(2027, 1, 11));
    }

    // ── monthly ───────────────────────────────────────────────────────────────

    @Test
    void monthlyOccursOnDueDay() {
        Commitment c = monthly(15, LocalDate.of(2026, 1, 1));
        assertThat(between(c, "2026-07-01", "2026-07-31"))
                .containsExactly(LocalDate.of(2026, 7, 15));
    }

    @Test
    void monthlyDay31ClampsAndReturns() {
        Commitment c = monthly(31, LocalDate.of(2026, 1, 1));
        assertThat(between(c, "2026-01-01", "2026-04-30")).containsExactly(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 28),   // non-leap February
                LocalDate.of(2026, 3, 31),   // back to the 31st
                LocalDate.of(2026, 4, 30));  // 30-day month
        assertThat(between(c, "2028-02-01", "2028-02-29"))
                .containsExactly(LocalDate.of(2028, 2, 29)); // leap year
    }

    @Test
    void monthlyDoesNotOccurBeforeStart() {
        Commitment c = monthly(10, LocalDate.of(2026, 7, 1));
        assertThat(between(c, "2026-06-01", "2026-06-30")).isEmpty();
        assertThat(between(c, "2026-07-01", "2026-07-31"))
                .containsExactly(LocalDate.of(2026, 7, 10));
    }

    @Test
    void monthlyDueDayBeforeStartDateSkipsTheStartMonth() {
        Commitment c = monthly(5, LocalDate.of(2026, 7, 20));
        assertThat(between(c, "2026-07-01", "2026-08-31"))
                .containsExactly(LocalDate.of(2026, 8, 5));
    }

    @Test
    void monthlyStopsAtEndDate() {
        Commitment c = monthly(10, LocalDate.of(2026, 1, 1));
        c.setEndDate(LocalDate.of(2026, 7, 9));
        assertThat(between(c, "2026-06-01", "2026-08-31"))
                .containsExactly(LocalDate.of(2026, 6, 10));
    }

    // ── yearly ────────────────────────────────────────────────────────────────

    @Test
    void yearlyOccursOnlyOnTheAnniversary() {
        Commitment c = yearly(LocalDate.of(2025, 3, 20));
        assertThat(between(c, "2026-01-01", "2026-12-31"))
                .containsExactly(LocalDate.of(2026, 3, 20));
    }

    @Test
    void yearlyFebruary29ClampsInNonLeapYears() {
        Commitment c = yearly(LocalDate.of(2028, 2, 29));
        assertThat(between(c, "2029-01-01", "2029-12-31"))
                .containsExactly(LocalDate.of(2029, 2, 28));
        assertThat(between(c, "2032-01-01", "2032-12-31"))
                .containsExactly(LocalDate.of(2032, 2, 29));
    }

    // ── shared behavior ───────────────────────────────────────────────────────

    @Test
    void inactiveDefinitionNeverOccurs() {
        Commitment c = monthly(10, LocalDate.of(2026, 1, 1));
        c.setActive(false);
        assertThat(between(c, "2026-01-01", "2026-12-31")).isEmpty();
        assertThat(RecurrenceCalculator.nextOccurrence(c, LocalDate.of(2026, 1, 1))).isEmpty();
    }

    @Test
    void nextOccurrenceFindsTheFirstDateOnOrAfterTheReference() {
        Commitment c = monthly(10, LocalDate.of(2026, 1, 1));
        assertThat(RecurrenceCalculator.nextOccurrence(c, LocalDate.of(2026, 7, 10)))
                .contains(LocalDate.of(2026, 7, 10));
        assertThat(RecurrenceCalculator.nextOccurrence(c, LocalDate.of(2026, 7, 11)))
                .contains(LocalDate.of(2026, 8, 10));
        c.setEndDate(LocalDate.of(2026, 7, 31));
        assertThat(RecurrenceCalculator.nextOccurrence(c, LocalDate.of(2026, 8, 1))).isEmpty();
    }

    @Test
    void expansionIsBoundedEvenForHugeWindows() {
        Commitment c = weekly(LocalDate.of(2000, 1, 3));
        assertThat(between(c, "2000-01-01", "2100-01-01"))
                .hasSize(RecurrenceCalculator.MAX_OCCURRENCES);
    }
}
