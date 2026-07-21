package com.finora.api.notification;

import com.finora.api.forecast.DueEventDtos.DueEventSeverity;
import com.finora.api.forecast.DueEventDtos.DueEventType;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class NotificationDtos {
    private NotificationDtos() { }

    public record PreferencesRequest(
            boolean enabled,
            @Min(1) @Max(14) int upcomingLeadDays,
            boolean recurringDueEnabled,
            boolean invoiceDueEnabled,
            boolean executionFailureEnabled,
            boolean cashRiskEnabled,
            boolean browserEnabled,
            @NotNull DueEventSeverity browserMinimumSeverity,
            boolean browserShowAmounts) { }

    public record PreferencesResponse(
            boolean enabled, int upcomingLeadDays, boolean recurringDueEnabled,
            boolean invoiceDueEnabled, boolean executionFailureEnabled,
            boolean cashRiskEnabled, boolean browserEnabled,
            DueEventSeverity browserMinimumSeverity, boolean browserShowAmounts,
            Instant browserEnabledAt) {
        static PreferencesResponse from(NotificationPreferences p) {
            return new PreferencesResponse(p.isEnabled(), p.getUpcomingLeadDays(),
                    p.isRecurringDueEnabled(), p.isInvoiceDueEnabled(),
                    p.isExecutionFailureEnabled(), p.isCashRiskEnabled(),
                    p.isBrowserEnabled(), p.getBrowserMinimumSeverity(),
                    p.isBrowserShowAmounts(), p.getBrowserEnabledAt());
        }
    }

    public record NotificationResponse(
            Long id, String sourceKey, DueEventType type, DueEventSeverity severity,
            LocalDate eventDate, String title, BigDecimal amount, String resourceType,
            Long resourceId, String route, int revision, boolean unread,
            boolean dismissed, boolean snoozed, Instant snoozedUntil,
            Instant firstSeenAt, Instant lastSeenAt, Instant resolvedAt) {
        static NotificationResponse from(Notification n, Instant now) {
            return new NotificationResponse(n.getId(), n.getSourceKey(), n.getType(),
                    n.getSeverity(), n.getEventDate(), n.getTitle(), n.getAmount(),
                    n.getResourceType(), n.getResourceId(), n.getRoute(), n.getRevision(),
                    n.isUnread(), n.isDismissed(), n.isSnoozed(now), n.getSnoozedUntil(),
                    n.getFirstSeenAt(), n.getLastSeenAt(), n.getResolvedAt());
        }
    }

    public record SyncResponse(int created, int updated, int escalated,
                               int resolved, int reactivated, int unchanged) { }
    public record UnreadCountResponse(long count) { }
    public record SnoozeRequest(@NotNull @Future Instant until) { }
    public record BrowserClaimResponse(Long id, String sourceKey, int revision,
                                       String title, BigDecimal amount, String route) { }
}
