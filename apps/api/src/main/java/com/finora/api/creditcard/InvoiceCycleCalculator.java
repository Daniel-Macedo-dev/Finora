package com.finora.api.creditcard;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * The single authority for billing-cycle math. No other class may derive
 * closing or due dates.
 *
 * <p>An invoice is identified by its <em>reference month</em> — the month its
 * due date falls in. For a card with {@code closingDay} and {@code dueDay} and
 * a reference month {@code M}:
 *
 * <ul>
 *   <li>{@code dueDate} = day {@code dueDay} of {@code M}, capped to the last
 *       valid day of {@code M} (day 31 in April becomes April 30);</li>
 *   <li>the closing month is {@code M} itself when {@code closingDay < dueDay}
 *       (statement closes shortly before it is due, same month), otherwise the
 *       month before {@code M} (closing near month-end, due early next month);</li>
 *   <li>{@code closingDate} = day {@code closingDay} of the closing month,
 *       capped the same way (day 31 in February becomes February 28/29).</li>
 * </ul>
 *
 * <p>A purchase belongs to the earliest invoice whose closing date is on or
 * after the purchase date: everything bought up to and including the closing
 * date enters that statement; the next day starts the following one. Closing
 * dates grow strictly with the reference month, so this assignment is unique.
 *
 * <p>These results are snapshotted onto the invoice row at creation time.
 * Reconfiguring the card's days only affects invoices created afterwards.
 */
public final class InvoiceCycleCalculator {

    private InvoiceCycleCalculator() {
    }

    /** Cycle dates for one invoice: the reference month with its snapshot dates. */
    public record InvoiceCycle(YearMonth referenceMonth, LocalDate closingDate, LocalDate dueDate) {
    }

    /** Computes the cycle for a given reference month. */
    public static InvoiceCycle cycleFor(int closingDay, int dueDay, YearMonth referenceMonth) {
        LocalDate dueDate = cappedDate(referenceMonth, dueDay);
        YearMonth closingMonth = closingDay < dueDay ? referenceMonth : referenceMonth.minusMonths(1);
        LocalDate closingDate = cappedDate(closingMonth, closingDay);
        return new InvoiceCycle(referenceMonth, closingDate, dueDate);
    }

    /**
     * The invoice that receives a purchase made on {@code purchaseDate}: the
     * earliest cycle whose closing date is on or after the purchase date.
     */
    public static InvoiceCycle cycleForPurchase(int closingDay, int dueDay, LocalDate purchaseDate) {
        // The candidate due month is never before the purchase month and, with
        // closing in the previous month, never after the month following the
        // month after the purchase — two steps bound the search.
        YearMonth candidate = YearMonth.from(purchaseDate);
        InvoiceCycle cycle = cycleFor(closingDay, dueDay, candidate);
        while (cycle.closingDate().isBefore(purchaseDate)) {
            candidate = candidate.plusMonths(1);
            cycle = cycleFor(closingDay, dueDay, candidate);
        }
        return cycle;
    }

    /** Day-of-month capped to the month's length: day 31 in February → Feb 28/29. */
    private static LocalDate cappedDate(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }
}
