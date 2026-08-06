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
     *
     * @param currency the authoritative currency of {@code amount}, derived
     *     from the source resource — the transaction, the commitment, or the
     *     billing card — never from the request
     * @param balanceAfter projected balance <em>of this event's currency</em>
     *     after it; null for unassigned events
     */
    public record ForecastEvent(
            LocalDate date,
            String description,
            BigDecimal amount,
            String currency,
            ForecastSource source,
            Long accountId,
            String accountName,
            boolean unassigned,
            Long commitmentId,
            Long transactionId,
            Long invoiceId,
            Long creditCardId,
            BigDecimal balanceAfter) {
    }

    /** Internal builder step: events are created without a balance, then sealed. */
    public static ForecastEvent withBalance(ForecastEvent event, BigDecimal balance) {
        return new ForecastEvent(event.date(), event.description(), event.amount(),
                event.currency(), event.source(), event.accountId(), event.accountName(),
                event.unassigned(), event.commitmentId(), event.transactionId(),
                event.invoiceId(), event.creditCardId(), balance);
    }

    public record ForecastMonth(
            YearMonth month,
            BigDecimal inflows,
            BigDecimal outflows,
            BigDecimal net,
            BigDecimal endBalance) {
    }

    /**
     * One currency's own running forecast.
     *
     * <p>A balance is a single number that only means something in one
     * denomination. Rather than one figure that silently added reais to
     * dollars, each currency gets an independent opening balance, series and
     * set of conclusions — every one of them a real, addable number.
     *
     * @param assignedEventCount events that actually moved this balance;
     *     unassigned ones are reported apart because no account settles them
     */
    public record ForecastCurrencySummary(
            String currency,
            BigDecimal openingBalance,
            BigDecimal income,
            BigDecimal accountExpenses,
            BigDecimal invoiceOutflows,
            BigDecimal closingBalance,
            BigDecimal lowestBalance,
            LocalDate lowestBalanceDate,
            LocalDate firstNegativeDate,
            BigDecimal unassignedInflows,
            BigDecimal unassignedOutflows,
            int assignedEventCount,
            List<ForecastMonth> months) {
    }

    /**
     * The forecast, partitioned by currency.
     *
     * <p>{@code byCurrency} is always the authoritative answer. The scalar
     * fields beside it are the pre-multi-currency contract, kept for callers
     * and offline copies that already hold that shape — and they are populated
     * <em>only</em> when the forecast is homogeneous, with {@code currency}
     * naming the denomination they are in. A mixed forecast leaves every one of
     * them null rather than sending a number that would be acted on.
     *
     * <p>An account-filtered forecast is homogeneous by construction: it holds
     * exactly the events that settle in that account's currency.
     *
     * @param currency the single denomination of the scalar fields, or null
     *     when the forecast spans more than one currency
     */
    public record ForecastResponse(
            LocalDate from,
            LocalDate to,
            Long accountId,
            String baseCurrency,
            String currency,
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
            List<ForecastCurrencySummary> byCurrency,
            List<ForecastEvent> events,
            List<ForecastMonth> months) {
    }
}
