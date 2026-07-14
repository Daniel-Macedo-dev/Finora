package com.finora.api.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
     * current data — nothing is persisted — so the id is a stable composition
     * of type and resource identity, safe for future delivery deduplication.
     */
    public record DueEvent(
            String id,
            DueEventType type,
            DueEventSeverity severity,
            LocalDate date,
            String title,
            BigDecimal amount,
            String resourceType,
            Long resourceId,
            /** Frontend route that resolves the event. */
            String route) {
    }

    public record DueEventsResponse(LocalDate from, LocalDate to, List<DueEvent> events) {
    }
}
