package com.finora.api.creditcard.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The deterministic status rule, exercised state by state around one cycle:
 * closing 2031-03-10, due 2031-03-17. Status precedence is
 * PAID → OVERDUE → PARTIALLY_PAID → CLOSED → OPEN → UPCOMING.
 */
class InvoiceStatusDerivationTest {

    private static final LocalDate CLOSING = LocalDate.of(2031, 3, 10);
    private static final LocalDate DUE = LocalDate.of(2031, 3, 17);
    private static final BigDecimal TOTAL = new BigDecimal("500.00");

    private static InvoiceStatus statusOn(LocalDate today, String paid) {
        return InvoiceService.deriveStatus(today, CLOSING, DUE, TOTAL, new BigDecimal(paid));
    }

    @Test
    void upcomingBeforeTheCycleStarts() {
        assertThat(statusOn(LocalDate.of(2031, 1, 15), "0.00"))
                .isEqualTo(InvoiceStatus.UPCOMING);
    }

    @Test
    void openDuringTheActiveCycle() {
        assertThat(statusOn(LocalDate.of(2031, 2, 20), "0.00")).isEqualTo(InvoiceStatus.OPEN);
        assertThat(statusOn(CLOSING, "0.00")).isEqualTo(InvoiceStatus.OPEN);
    }

    @Test
    void closedAfterClosingAndBeforeDueWithoutPayment() {
        assertThat(statusOn(CLOSING.plusDays(1), "0.00")).isEqualTo(InvoiceStatus.CLOSED);
        assertThat(statusOn(DUE, "0.00")).isEqualTo(InvoiceStatus.CLOSED);
    }

    @Test
    void partiallyPaidOncePaymentExistsAndBalanceRemains() {
        assertThat(statusOn(DUE.minusDays(2), "100.00"))
                .isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }

    @Test
    void overdueAfterDueDateWhileBalanceRemains() {
        assertThat(statusOn(DUE.plusDays(1), "0.00")).isEqualTo(InvoiceStatus.OVERDUE);
        assertThat(statusOn(DUE.plusDays(1), "499.99")).isEqualTo(InvoiceStatus.OVERDUE);
    }

    @Test
    void paidBeatsEveryOtherState() {
        assertThat(statusOn(DUE.minusDays(2), "500.00")).isEqualTo(InvoiceStatus.PAID);
        assertThat(statusOn(DUE.plusDays(30), "500.00")).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void emptyInvoiceIsNeverPaidBeforeItCloses() {
        InvoiceStatus status = InvoiceService.deriveStatus(
                LocalDate.of(2031, 3, 5), CLOSING, DUE, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(status).isEqualTo(InvoiceStatus.OPEN);
    }
}
