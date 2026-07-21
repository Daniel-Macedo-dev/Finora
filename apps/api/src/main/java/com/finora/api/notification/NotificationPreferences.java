package com.finora.api.notification;

import com.finora.api.common.persistence.AuditableEntity;
import com.finora.api.forecast.DueEventDtos.DueEventSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "notification_preferences")
public class NotificationPreferences extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "upcoming_lead_days", nullable = false)
    private int upcomingLeadDays = 7;

    @Column(name = "recurring_due_enabled", nullable = false)
    private boolean recurringDueEnabled = true;

    @Column(name = "invoice_due_enabled", nullable = false)
    private boolean invoiceDueEnabled = true;

    @Column(name = "execution_failure_enabled", nullable = false)
    private boolean executionFailureEnabled = true;

    @Column(name = "cash_risk_enabled", nullable = false)
    private boolean cashRiskEnabled = true;

    @Column(name = "browser_enabled", nullable = false)
    private boolean browserEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "browser_minimum_severity", nullable = false, length = 10)
    private DueEventSeverity browserMinimumSeverity = DueEventSeverity.WARNING;

    @Column(name = "browser_show_amounts", nullable = false)
    private boolean browserShowAmounts;

    @Column(name = "browser_enabled_at")
    private Instant browserEnabledAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected NotificationPreferences() {
    }

    public static NotificationPreferences withDefaults(Long userId) {
        NotificationPreferences preferences = new NotificationPreferences();
        preferences.userId = userId;
        return preferences;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public boolean isEnabled() { return enabled; }
    public int getUpcomingLeadDays() { return upcomingLeadDays; }
    public boolean isRecurringDueEnabled() { return recurringDueEnabled; }
    public boolean isInvoiceDueEnabled() { return invoiceDueEnabled; }
    public boolean isExecutionFailureEnabled() { return executionFailureEnabled; }
    public boolean isCashRiskEnabled() { return cashRiskEnabled; }
    public boolean isBrowserEnabled() { return browserEnabled; }
    public DueEventSeverity getBrowserMinimumSeverity() { return browserMinimumSeverity; }
    public boolean isBrowserShowAmounts() { return browserShowAmounts; }
    public Instant getBrowserEnabledAt() { return browserEnabledAt; }
}
