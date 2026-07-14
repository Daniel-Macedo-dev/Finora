package com.finora.api.commitment;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The single authority for recurrence date math. No other class may derive
 * occurrence dates from a recurring definition.
 *
 * <ul>
 *   <li><b>WEEKLY</b> — anchored to {@code startDate}, repeating every seven
 *       calendar days. No timezone or DST arithmetic is involved: business
 *       dates are plain {@link LocalDate}s.</li>
 *   <li><b>MONTHLY</b> — the configured due day (falling back to the start
 *       date's day) in every month, clamped to the month's last valid day:
 *       day 31 occurs on Feb 28/29 and Apr 30, then returns to the 31st.</li>
 *   <li><b>YEARLY</b> — the start date's month and day every year; February 29
 *       clamps to February 28 in non-leap years.</li>
 * </ul>
 *
 * <p>{@code startDate} and {@code endDate} are inclusive boundaries. An
 * inactive (paused) definition produces no occurrences at all.
 */
public final class RecurrenceCalculator {

    /** Hard cap on any single expansion, guarding unbounded date windows. */
    static final int MAX_OCCURRENCES = 1000;

    private RecurrenceCalculator() {
    }

    /**
     * Occurrence dates of {@code commitment} between {@code from} and
     * {@code to} (both inclusive), in ascending order. The window is clipped
     * to the definition's own [start, end] period.
     */
    public static List<LocalDate> occurrencesBetween(Commitment commitment,
                                                     LocalDate from, LocalDate to) {
        if (!commitment.isActive() || to.isBefore(from)) {
            return List.of();
        }
        LocalDate lower = max(from, commitment.getStartDate());
        LocalDate upper = commitment.getEndDate() != null
                ? min(to, commitment.getEndDate())
                : to;
        if (upper.isBefore(lower)) {
            return List.of();
        }
        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = firstOnOrAfter(commitment, lower);
        while (cursor != null && !cursor.isAfter(upper) && dates.size() < MAX_OCCURRENCES) {
            dates.add(cursor);
            cursor = next(commitment, cursor);
        }
        return dates;
    }

    /** The first occurrence on or after {@code reference}, if any remains. */
    public static Optional<LocalDate> nextOccurrence(Commitment commitment, LocalDate reference) {
        if (!commitment.isActive()) {
            return Optional.empty();
        }
        LocalDate candidate = firstOnOrAfter(commitment, max(reference, commitment.getStartDate()));
        if (candidate == null
                || (commitment.getEndDate() != null && candidate.isAfter(commitment.getEndDate()))) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    private static LocalDate firstOnOrAfter(Commitment commitment, LocalDate reference) {
        LocalDate start = commitment.getStartDate();
        return switch (commitment.getCadence()) {
            case WEEKLY -> {
                if (!reference.isAfter(start)) {
                    yield start;
                }
                long days = ChronoUnit.DAYS.between(start, reference);
                long weeks = (days + 6) / 7; // ceil
                yield start.plusWeeks(weeks);
            }
            case MONTHLY -> {
                LocalDate inMonth = monthlyDate(commitment, YearMonth.from(reference));
                yield !inMonth.isBefore(reference) && !inMonth.isBefore(start)
                        ? inMonth
                        : monthlyAfter(commitment, YearMonth.from(reference));
            }
            case YEARLY -> {
                LocalDate inYear = yearlyDate(start, reference.getYear());
                yield !inYear.isBefore(reference) && !inYear.isBefore(start)
                        ? inYear
                        : yearlyDate(start, reference.getYear() + 1);
            }
        };
    }

    private static LocalDate next(Commitment commitment, LocalDate current) {
        return switch (commitment.getCadence()) {
            case WEEKLY -> current.plusWeeks(1);
            case MONTHLY -> monthlyDate(commitment, YearMonth.from(current).plusMonths(1));
            case YEARLY -> yearlyDate(commitment.getStartDate(), current.getYear() + 1);
        };
    }

    private static LocalDate monthlyAfter(Commitment commitment, YearMonth month) {
        LocalDate candidate = monthlyDate(commitment, month.plusMonths(1));
        // The start month itself may host the first occurrence only when the
        // due day is on/after the start date; otherwise the next month does.
        return candidate.isBefore(commitment.getStartDate())
                ? monthlyDate(commitment, month.plusMonths(2))
                : candidate;
    }

    private static LocalDate monthlyDate(Commitment commitment, YearMonth month) {
        int day = commitment.getDueDay() != null
                ? commitment.getDueDay()
                : commitment.getStartDate().getDayOfMonth();
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }

    private static LocalDate yearlyDate(LocalDate anchor, int year) {
        MonthDay monthDay = MonthDay.from(anchor);
        YearMonth month = YearMonth.of(year, monthDay.getMonth());
        return month.atDay(Math.min(monthDay.getDayOfMonth(), month.lengthOfMonth()));
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
