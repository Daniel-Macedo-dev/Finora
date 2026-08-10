package com.finora.api.notification;

import com.finora.api.common.money.CurrencyCode;
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

    /**
     * One inbox row.
     *
     * @param currency the authoritative denomination of {@code amount}. The
     *     constructor refuses an amount without one, so no client ever has to
     *     decide what an unlabelled figure is worth.
     */
    public record NotificationResponse(
            Long id, String sourceKey, DueEventType type, DueEventSeverity severity,
            LocalDate eventDate, String title, BigDecimal amount, CurrencyCode currency,
            String resourceType, Long resourceId, String route, int revision, boolean unread,
            boolean dismissed, boolean snoozed, Instant snoozedUntil,
            Instant firstSeenAt, Instant lastSeenAt, Instant resolvedAt) {

        public NotificationResponse {
            if (amount != null && currency == null) {
                throw new IllegalArgumentException(
                        "notificação %s tem valor sem moeda".formatted(id));
            }
        }

        static NotificationResponse from(Notification n, Instant now) {
            return new NotificationResponse(n.getId(), n.getSourceKey(), n.getType(),
                    n.getSeverity(), n.getEventDate(), n.getTitle(), n.getAmount(),
                    n.getCurrency(), n.getResourceType(), n.getResourceId(), n.getRoute(),
                    n.getRevision(), n.isUnread(), n.isDismissed(), n.isSnoozed(now),
                    n.getSnoozedUntil(), n.getFirstSeenAt(), n.getLastSeenAt(), n.getResolvedAt());
        }
    }

    public record SyncResponse(int created, int updated, int escalated,
                               int resolved, int reactivated, int unchanged) { }
    public record UnreadCountResponse(long count) { }
    public record SnoozeRequest(@NotNull @Future Instant until) { }
    /**
     * One foreground browser delivery, which is the least private surface the
     * product has: it leaves the authenticated page and is drawn by the
     * operating system.
     *
     * <p>{@code amount} and {@code currency} are a single indivisible pair,
     * enforced here rather than trusted to the caller. Adding a currency to
     * every notification must not turn a hidden amount into
     * {@code {"amount": null, "currency": "USD"}} — knowing that the sum
     * withheld is in dollars is most of what withholding it was protecting.
     *
     * <p>There is deliberately no source key. Tabs de-duplicate on
     * {@code id} + {@code revision}, which is already unique per owner, while
     * a source key names its resource — and the cash-risk key names the very
     * denomination this claim may be forbidden to disclose. The durable inbox
     * response still carries it; this one has no use for it.
     */
    public record BrowserClaimResponse(Long id, int revision, String title,
                                       BigDecimal amount, CurrencyCode currency, String route) {

        public BrowserClaimResponse {
            if ((amount == null) != (currency == null)) {
                throw new IllegalArgumentException(
                        "valor e moeda de uma entrega no navegador aparecem juntos ou não aparecem");
            }
        }
    }
}
