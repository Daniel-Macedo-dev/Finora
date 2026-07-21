package com.finora.api.notification;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    Optional<Notification> findByUserIdAndSourceKey(Long userId, String sourceKey);

    List<Notification> findAllByUserIdAndSourceKeyIn(Long userId, Collection<String> sourceKeys);
}
