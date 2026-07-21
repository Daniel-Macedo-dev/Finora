package com.finora.api.notification;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferencesRepository
        extends JpaRepository<NotificationPreferences, Long> {

    Optional<NotificationPreferences> findByUserId(Long userId);
}
