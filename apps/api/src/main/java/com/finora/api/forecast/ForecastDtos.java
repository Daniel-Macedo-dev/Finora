package com.finora.api.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public final class ForecastDtos {

    private ForecastDtos() {
    }

    /**
     * Deterministic origin of one forecast event — the UI explains every value
     * through it. ACTUAL_* sources are recorded data; the others are
     * projections derived from recurring definitions and card cycles.
     */
    public enum ForecastSource {
        ACTUAL_TRANSACTION,
        RECURRING_ACCOUNT_OCCURRENCE,
        CARD_INVOICE,
        PROJECTED_RECURRING_CARD_PURCHASE
    }

    /**
     * One projected cash movement. {@code amount} is signed: positive cash in,
     * negative cash out. Unassigned events (no account can be responsible for
     * the movement) are disclosed but never change an account balance.
     */
    public record ForecastEvent(
            LocalDate date,
            String description,
            BigDecimal amount,
            ForecastSource source,
            Long accountId,
            String accountName,
            boolean unassigned,
            Long commitmentId,
            Long transactionId,
            Long invoiceId,
            Long creditCardId,
            /** Projected balance after this event; null for unassigned events. */
            BigDecimal balanceAfter) {
    }

    /** Internal builder step: events are created without a balance, then sealed. */
    public static ForecastEvent withBalance(ForecastEvent event, BigDecimal balance) {
        return new ForecastEvent(event.date(), event.description(), event.amount(),
                event.source(), event.accountId(), event.accountName(), event.unassigned(),
                event.commitmentId(), event.transactionId(), event.invoiceId(),
                event.creditCardId(), balance);
    }

    public record ForecastMonth(
            YearMonth month,
            BigDecimal inflows,
            BigDecimal outflows,
            BigDecimal net,
            BigDecimal endBalance) {
    }

    public record ForecastResponse(
            LocalDate from,
            LocalDate to,
            Long accountId,
            BigDecimal openingBalance,
            BigDecimal projectedIncome,
            BigDecimal projectedAccountExpenses,
            BigDecimal projectedInvoiceOutflows,
            BigDecimal closingBalance,
            BigDecimal lowestBalance,
            LocalDate lowestBalanceDate,
            LocalDate firstNegativeDate,
            BigDecimal unassignedInflows,
            BigDecimal unassignedOutflows,
            List<ForecastEvent> events,
            List<ForecastMonth> months) {
    }
}
