package com.finora.api.notification;

import com.finora.api.common.persistence.AuditableEntity;
import com.finora.api.forecast.DueEventDtos.DueEventSeverity;
import com.finora.api.forecast.DueEventDtos.DueEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "notifications")
public class Notification extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "source_key", nullable = false, length = 255, updatable = false)
    private String sourceKey;

    @Column(name = "source_event_id", nullable = false, length = 255)
    private String sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private DueEventType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DueEventSeverity severity;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "resource_type", nullable = false, length = 40)
    private String resourceType;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(nullable = false, length = 500)
    private String route;

    @Column(nullable = false)
    private int revision;

    @Column(name = "read_revision")
    private Integer readRevision;

    @Column(name = "dismissed_revision")
    private Integer dismissedRevision;

    @Column(name = "browser_delivered_revision")
    private Integer browserDeliveredRevision;

    @Column(name = "snoozed_revision")
    private Integer snoozedRevision;

    @Column(name = "snoozed_until")
    private Instant snoozedUntil;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "revision_changed_at", nullable = false)
    private Instant revisionChangedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Notification() {
    }

    public Notification(Long userId, String sourceKey, String sourceEventId,
                        DueEventType type, DueEventSeverity severity, LocalDate eventDate,
                        String title, BigDecimal amount, String resourceType, Long resourceId,
                        String route, Instant now) {
        this.userId = userId;
        this.sourceKey = sourceKey;
        this.revision = 1;
        this.firstSeenAt = now;
        applyEvent(sourceEventId, type, severity, eventDate, title, amount,
                resourceType, resourceId, route, now);
        this.revisionChangedAt = now;
    }

    public boolean refresh(String sourceEventId, DueEventType type, DueEventSeverity severity,
                           LocalDate eventDate, String title, BigDecimal amount,
                           String resourceType, Long resourceId, String route, Instant now) {
        boolean revisionChange = resolvedAt != null
                || severity.ordinal() > this.severity.ordinal()
                || lifecycleRank(type) > lifecycleRank(this.type)
                || !eventDate.equals(this.eventDate);
        if (revisionChange) {
            revision++;
            revisionChangedAt = now;
            resolvedAt = null;
            snoozedRevision = null;
            snoozedUntil = null;
        }
        applyEvent(sourceEventId, type, severity, eventDate, title, amount,
                resourceType, resourceId, route, now);
        return revisionChange;
    }

    private void applyEvent(String sourceEventId, DueEventType type, DueEventSeverity severity,
                            LocalDate eventDate, String title, BigDecimal amount,
                            String resourceType, Long resourceId, String route, Instant now) {
        this.sourceEventId = sourceEventId;
        this.type = type;
        this.severity = severity;
        this.eventDate = eventDate;
        this.title = title;
        this.amount = amount;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.route = route;
        this.lastSeenAt = now;
    }

    private static int lifecycleRank(DueEventType type) {
        return switch (type) {
            case RECURRING_DUE_SOON, INVOICE_DUE_SOON -> 1;
            case RECURRING_DUE_TODAY, INVOICE_DUE_TODAY -> 2;
            case RECURRING_OVERDUE, INVOICE_OVERDUE -> 3;
            case RECURRING_FAILED -> 4;
            case INSUFFICIENT_CASH_PROJECTED -> 3;
        };
    }

    public void resolve(Instant now) { if (resolvedAt == null) resolvedAt = now; }
    public void markRead() { readRevision = revision; }
    public void markUnread() { if (readRevision != null && readRevision == revision) readRevision = null; }
    public void dismiss() { dismissedRevision = revision; }
    public void restore() { if (dismissedRevision != null && dismissedRevision == revision) dismissedRevision = null; }
    public void snooze(Instant until) { snoozedRevision = revision; snoozedUntil = until; }
    public void claimBrowserDelivery() { browserDeliveredRevision = revision; }
    public boolean isUnread() { return readRevision == null || readRevision < revision; }
    public boolean isDismissed() { return dismissedRevision != null && dismissedRevision == revision; }
    public boolean isSnoozed(Instant now) {
        return snoozedRevision != null && snoozedRevision == revision && snoozedUntil.isAfter(now);
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getSourceKey() { return sourceKey; }
    public String getSourceEventId() { return sourceEventId; }
    public DueEventType getType() { return type; }
    public DueEventSeverity getSeverity() { return severity; }
    public LocalDate getEventDate() { return eventDate; }
    public String getTitle() { return title; }
    public BigDecimal getAmount() { return amount; }
    public String getResourceType() { return resourceType; }
    public Long getResourceId() { return resourceId; }
    public String getRoute() { return route; }
    public int getRevision() { return revision; }
    public Integer getReadRevision() { return readRevision; }
    public Integer getDismissedRevision() { return dismissedRevision; }
    public Integer getBrowserDeliveredRevision() { return browserDeliveredRevision; }
    public Integer getSnoozedRevision() { return snoozedRevision; }
    public Instant getSnoozedUntil() { return snoozedUntil; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getRevisionChangedAt() { return revisionChangedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
