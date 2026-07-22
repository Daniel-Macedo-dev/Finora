package com.finora.api.notification;

import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.notification.NotificationDtos.PreferencesRequest;
import com.finora.api.notification.NotificationDtos.PreferencesResponse;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
/** Maintains the single notification-preference row owned by each user. */
public class NotificationPreferencesService {
    private final NotificationPreferencesRepository repository;
    private final CurrentUserProvider currentUser;
    private final Clock clock;

    public NotificationPreferencesService(NotificationPreferencesRepository repository,
                                          CurrentUserProvider currentUser, Clock clock) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    /** Returns preferences for a trusted scheduler or synchronization owner id. */
    public NotificationPreferences forUser(Long userId) {
        return repository.findByUserId(userId).orElseThrow(() ->
                new IllegalStateException("notification preferences missing for user " + userId));
    }

    @Transactional(readOnly = true)
    public PreferencesResponse get() { return PreferencesResponse.from(forUser(currentUser.currentUserId())); }

    public PreferencesResponse update(PreferencesRequest request) {
        NotificationPreferences preferences = forUser(currentUser.currentUserId());
        preferences.update(request.enabled(), request.upcomingLeadDays(),
                request.recurringDueEnabled(), request.invoiceDueEnabled(),
                request.executionFailureEnabled(), request.cashRiskEnabled(),
                request.browserEnabled(), request.browserMinimumSeverity(),
                request.browserShowAmounts(), Instant.now(clock));
        return PreferencesResponse.from(preferences);
    }
}
