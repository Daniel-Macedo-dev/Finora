package com.finora.api.notification;

import com.finora.api.forecast.DueEventDtos.DueEvent;
import com.finora.api.forecast.DueEventDtos.DueEventType;
import com.finora.api.forecast.DueEventService;
import com.finora.api.notification.NotificationDtos.SyncResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationSynchronizationService {
    static final int OVERDUE_LOOKBACK_DAYS = 30;

    private final NotificationRepository notifications;
    private final NotificationPreferencesService preferencesService;
    private final DueEventService dueEvents;
    private final Clock clock;

    public NotificationSynchronizationService(NotificationRepository notifications,
                                              NotificationPreferencesService preferencesService,
                                              DueEventService dueEvents, Clock clock) {
        this.notifications = notifications;
        this.preferencesService = preferencesService;
        this.dueEvents = dueEvents;
        this.clock = clock;
    }

    /** One owner per transaction; the advisory lock collapses scheduler/manual races. */
    @Transactional
    public SyncResponse synchronize(Long userId) {
        notifications.lockSynchronization(userId);
        NotificationPreferences preferences = preferencesService.forUser(userId);
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock);
        List<DueEvent> events = preferences.isEnabled()
                ? dueEvents.eventsForUser(userId, today.minusDays(OVERDUE_LOOKBACK_DAYS),
                        today.plusDays(preferences.getUpcomingLeadDays()),
                        preferences.getUpcomingLeadDays()).events().stream()
                        .filter(event -> enabled(preferences, event.type())).toList()
                : List.of();

        Set<String> seen = new HashSet<>();
        int created = 0, updated = 0, escalated = 0, reactivated = 0, unchanged = 0;
        for (DueEvent event : events) {
            seen.add(event.sourceKey());
            Notification existing = notifications.findByUserIdAndSourceKey(userId, event.sourceKey())
                    .orElse(null);
            if (existing == null) {
                notifications.save(new Notification(userId, event.sourceKey(), event.id(), event.type(),
                        event.severity(), event.date(), event.title(), event.amount(),
                        event.resourceType(), event.resourceId(), event.route(), now));
                created++;
                continue;
            }
            boolean wasResolved = existing.getResolvedAt() != null;
            boolean metadataChanged = !existing.getSourceEventId().equals(event.id())
                    || !existing.getTitle().equals(event.title())
                    || !java.util.Objects.equals(existing.getAmount(), event.amount())
                    || !existing.getRoute().equals(event.route());
            boolean changedRevision = existing.refresh(event.id(), event.type(), event.severity(),
                    event.date(), event.title(), event.amount(), event.resourceType(),
                    event.resourceId(), event.route(), now);
            if (wasResolved) reactivated++;
            else if (changedRevision) escalated++;
            else if (metadataChanged) updated++;
            else unchanged++;
        }
        int resolved = 0;
        for (Notification notification : notifications.findAllByUserIdAndResolvedAtIsNull(userId)) {
            if (!seen.contains(notification.getSourceKey())) {
                notification.resolve(now);
                resolved++;
            }
        }
        return new SyncResponse(created, updated, escalated, resolved, reactivated, unchanged);
    }

    private static boolean enabled(NotificationPreferences p, DueEventType type) {
        return switch (type) {
            case RECURRING_DUE_SOON, RECURRING_DUE_TODAY, RECURRING_OVERDUE -> p.isRecurringDueEnabled();
            case RECURRING_FAILED -> p.isExecutionFailureEnabled();
            case INVOICE_DUE_SOON, INVOICE_DUE_TODAY, INVOICE_OVERDUE -> p.isInvoiceDueEnabled();
            case INSUFFICIENT_CASH_PROJECTED -> p.isCashRiskEnabled();
        };
    }
}
