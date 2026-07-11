package com.finora.api.creditcard.invoice;

/**
 * Derived invoice status — never stored. Computed deterministically from the
 * current date, the invoice's snapshot dates, its derived total and the sum of
 * completed payments. Precedence: PAID, then OVERDUE, then PARTIALLY_PAID,
 * then date-based CLOSED/OPEN/UPCOMING.
 */
public enum InvoiceStatus {
    /** Invoice of a future cycle: its predecessor has not closed yet. */
    UPCOMING,
    /** Current active cycle: purchases assigned to it are still accumulating. */
    OPEN,
    /** After the closing date, before the due date, nothing paid yet. */
    CLOSED,
    /** Some payment exists but an outstanding balance remains (not yet overdue). */
    PARTIALLY_PAID,
    /** Due date passed with an outstanding balance. */
    OVERDUE,
    /** Outstanding balance is zero (fully settled, or no charges at all after closing). */
    PAID
}
