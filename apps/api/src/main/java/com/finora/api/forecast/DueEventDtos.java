package com.finora.api.forecast;

import com.finora.api.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public final class DueEventDtos {

    private DueEventDtos() {
    }

    public enum DueEventType {
        RECURRING_DUE_SOON,
        RECURRING_DUE_TODAY,
        RECURRING_OVERDUE,
        RECURRING_FAILED,
        INVOICE_DUE_SOON,
        INVOICE_DUE_TODAY,
        INVOICE_OVERDUE,
        INSUFFICIENT_CASH_PROJECTED
    }

    public enum DueEventSeverity {
        INFO,
        WARNING,
        CRITICAL
    }

    /**
     * One notification-ready event. Events are derived deterministically from
     * current data. The legacy id remains type-specific for compatibility;
     * sourceKey is the stable lifecycle identity used for delivery.
     *
     * @param currency the authoritative denomination of {@code amount}, taken
     *     from the resource that owns the money — the commitment, the billing
     *     card, or the forecast series of that one currency. Never the user's
     *     base currency and never the request: an event about dollars that
     *     inherited a BRL base would be a wrong number, not a wrong label.
     *     Required even when {@code amount} is absent, so an amount can never
     *     travel without the denomination that gives it meaning, and so the
     *     NOT NULL currency of the notification row it becomes is always
     *     resolvable.
     */
    public record DueEvent(
            String id,
            /** Stable delivery identity independent of lifecycle type/severity. */
            String sourceKey,
            DueEventType type,
            DueEventSeverity severity,
            LocalDate date,
            String title,
            BigDecimal amount,
            CurrencyCode currency,
            String resourceType,
            Long resourceId,
            /** Frontend route that resolves the event. */
            String route) {

        public DueEvent {
            Objects.requireNonNull(currency, "currency");
        }
    }

    public record DueEventsResponse(LocalDate from, LocalDate to, List<DueEvent> events) {
    }
}
