package com.finora.api.notification;

import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.web.PageResponse;
import com.finora.api.forecast.DueEventDtos.DueEventSeverity;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.notification.NotificationDtos.BrowserClaimResponse;
import com.finora.api.notification.NotificationDtos.NotificationResponse;
import com.finora.api.notification.NotificationDtos.UnreadCountResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
/** Owner-scoped inbox queries, lifecycle actions and foreground browser claims. */
public class NotificationService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final Duration MAX_SNOOZE = Duration.ofDays(30);
    private static final Set<String> FILTERS = Set.of(
            "ACTIVE", "UNREAD", "SNOOZED", "DISMISSED", "RESOLVED", "ALL");

    private final NotificationRepository repository;
    private final NotificationPreferencesService preferencesService;
    private final CurrentUserProvider currentUser;
    private final Clock clock;

    public NotificationService(NotificationRepository repository,
                               NotificationPreferencesService preferencesService,
                               CurrentUserProvider currentUser, Clock clock) {
        this.repository = repository;
        this.preferencesService = preferencesService;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    /** Returns a bounded, deterministically ordered inbox page for the current owner. */
    public PageResponse<NotificationResponse> list(String requestedFilter, int page, int size) {
        String filter = requestedFilter == null ? "ACTIVE" : requestedFilter.toUpperCase();
        if (!FILTERS.contains(filter)) {
            throw new BusinessRuleException("NOTIFICATION_FILTER_INVALID", "Filtro de notificações inválido.");
        }
        int boundedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Instant now = Instant.now(clock);
        var pageable = PageRequest.of(Math.max(0, page), boundedSize,
                Sort.by(Sort.Order.desc("severity"), Sort.Order.asc("eventDate"), Sort.Order.asc("id")));
        return PageResponse.from(repository.list(currentUser.currentUserId(), filter, now, pageable)
                .map(notification -> NotificationResponse.from(notification, now)));
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount() {
        return new UnreadCountResponse(repository.countUnread(
                currentUser.currentUserId(), Instant.now(clock)));
    }

    public NotificationResponse read(Long id) { return mutate(id, Notification::markRead); }
    public NotificationResponse unread(Long id) { return mutate(id, Notification::markUnread); }
    public NotificationResponse dismiss(Long id) { return mutate(id, Notification::dismiss); }
    public NotificationResponse restore(Long id) { return mutate(id, Notification::restore); }

    public NotificationResponse snooze(Long id, Instant until) {
        Instant now = Instant.now(clock);
        if (!until.isAfter(now) || until.isAfter(now.plus(MAX_SNOOZE))) {
            throw new BusinessRuleException("NOTIFICATION_SNOOZE_INVALID",
                    "Escolha um horário futuro nos próximos 30 dias.");
        }
        return mutate(id, notification -> notification.snooze(until));
    }

    public void readAll() {
        repository.markAllRead(currentUser.currentUserId());
    }

    /** Atomically claims at most ten browser-eligible revisions for the current owner. */
    public List<BrowserClaimResponse> claimBrowser() {
        Long userId = currentUser.currentUserId();
        NotificationPreferences preferences = preferencesService.forUser(userId);
        if (!preferences.isBrowserEnabled() || preferences.getBrowserEnabledAt() == null) return List.of();
        Instant now = Instant.now(clock);
        int minimumRank = switch (preferences.getBrowserMinimumSeverity()) {
            case INFO -> 1; case WARNING -> 2; case CRITICAL -> 3;
        };
        List<Notification> claimed = repository.lockBrowserCandidates(userId, now,
                preferences.getBrowserEnabledAt(), minimumRank, 10);
        return claimed.stream().map(notification -> {
            notification.claimBrowserDelivery();
            return new BrowserClaimResponse(notification.getId(), notification.getSourceKey(),
                    notification.getRevision(), notification.getTitle(),
                    preferences.isBrowserShowAmounts() ? notification.getAmount() : null,
                    notification.getRoute());
        }).toList();
    }

    private NotificationResponse mutate(Long id, java.util.function.Consumer<Notification> action) {
        Notification notification = repository.lockByIdAndUserId(id, currentUser.currentUserId())
                .orElseThrow(() -> new NotFoundException("Notificação não encontrada."));
        action.accept(notification);
        return NotificationResponse.from(notification, Instant.now(clock));
    }
}
