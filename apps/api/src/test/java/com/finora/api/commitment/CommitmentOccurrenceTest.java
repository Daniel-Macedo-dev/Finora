package com.finora.api.commitment;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.api.category.Category;
import com.finora.api.category.CategoryType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class CommitmentOccurrenceTest {

    private final Category category = new Category(1L, "Assinaturas", CategoryType.EXPENSE);

    private Commitment monthly(int dueDay, LocalDate start) {
        return new Commitment(1L, "Internet", new BigDecimal("99.90"), category,
                CommitmentCadence.MONTHLY, dueDay, start);
    }

    @Test
    void monthlyOccursOnDueDay() {
        Commitment c = monthly(15, LocalDate.of(2026, 1, 1));
        assertThat(c.occurrenceIn(YearMonth.of(2026, 7)))
                .contains(LocalDate.of(2026, 7, 15));
    }

    @Test
    void dueDayIsClampedToShortMonths() {
        Commitment c = monthly(31, LocalDate.of(2026, 1, 1));
        assertThat(c.occurrenceIn(YearMonth.of(2026, 2)))
                .contains(LocalDate.of(2026, 2, 28));
        assertThat(c.occurrenceIn(YearMonth.of(2028, 2)))
                .contains(LocalDate.of(2028, 2, 29)); // leap year
    }

    @Test
    void doesNotOccurBeforeStartMonth() {
        Commitment c = monthly(10, LocalDate.of(2026, 7, 1));
        assertThat(c.occurrenceIn(YearMonth.of(2026, 6))).isEmpty();
        assertThat(c.occurrenceIn(YearMonth.of(2026, 7))).isPresent();
    }

    @Test
    void doesNotOccurWhenDueDateFallsBeforeStartDateInsideStartMonth() {
        Commitment c = monthly(5, LocalDate.of(2026, 7, 20));
        assertThat(c.occurrenceIn(YearMonth.of(2026, 7))).isEmpty();
        assertThat(c.occurrenceIn(YearMonth.of(2026, 8)))
                .contains(LocalDate.of(2026, 8, 5));
    }

    @Test
    void doesNotOccurAfterEndDate() {
        Commitment c = monthly(10, LocalDate.of(2026, 1, 1));
        c.setEndDate(LocalDate.of(2026, 7, 9));
        assertThat(c.occurrenceIn(YearMonth.of(2026, 6))).isPresent();
        assertThat(c.occurrenceIn(YearMonth.of(2026, 7))).isEmpty();
        assertThat(c.occurrenceIn(YearMonth.of(2026, 8))).isEmpty();
    }

    @Test
    void inactiveCommitmentNeverOccurs() {
        Commitment c = monthly(10, LocalDate.of(2026, 1, 1));
        c.setActive(false);
        assertThat(c.occurrenceIn(YearMonth.of(2026, 7))).isEmpty();
    }

    @Test
    void yearlyOccursOnlyOnAnniversaryMonth() {
        Commitment c = new Commitment(1L, "Seguro", new BigDecimal("1200.00"), category,
                CommitmentCadence.YEARLY, null, LocalDate.of(2025, 3, 20));
        assertThat(c.occurrenceIn(YearMonth.of(2026, 3)))
                .contains(LocalDate.of(2026, 3, 20));
        assertThat(c.occurrenceIn(YearMonth.of(2026, 4))).isEmpty();
    }
}
