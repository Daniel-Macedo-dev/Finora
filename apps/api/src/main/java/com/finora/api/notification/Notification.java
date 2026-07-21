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
