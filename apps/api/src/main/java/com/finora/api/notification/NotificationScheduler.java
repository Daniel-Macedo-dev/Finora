package com.finora.api.notification;

import com.finora.api.identity.UserRepository;
import com.finora.api.identity.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "finora.notifications.auto-sync.enabled",
        havingValue = "true", matchIfMissing = true)
public class NotificationScheduler {
    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);
    private final UserRepository users;
    private final NotificationSynchronizationService synchronization;
    private final int batchSize;

    public NotificationScheduler(UserRepository users,
                                 NotificationSynchronizationService synchronization,
                                 @Value("${finora.notifications.user-batch-size:100}") int batchSize) {
        this.users = users;
        this.synchronization = synchronization;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
    }

    @Scheduled(initialDelayString = "${finora.notifications.initial-delay:PT1M}",
            fixedDelayString = "${finora.notifications.sync-interval:PT15M}")
    public void synchronizeActiveUsers() {
        int page = 0;
        org.springframework.data.domain.Page<com.finora.api.identity.User> batch;
        do {
            batch = users.findAllByStatus(UserStatus.ACTIVE, PageRequest.of(page++, batchSize));
            for (var user : batch.getContent()) {
                try {
                    var result = synchronization.synchronize(user.getId());
                    log.info("Notification sync for user {}: created={}, updated={}, escalated={}, "
                                    + "resolved={}, reactivated={}, unchanged={}",
                            user.getId(), result.created(), result.updated(), result.escalated(),
                            result.resolved(), result.reactivated(), result.unchanged());
                } catch (RuntimeException ex) {
                    log.error("Notification sync failed for user {}", user.getId(), ex);
                }
            }
        } while (batch.hasNext());
    }
}
