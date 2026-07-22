package com.finora.api.notification;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notification n where n.userId = :userId and n.sourceKey = :sourceKey")
    Optional<Notification> lockByUserIdAndSourceKey(@Param("userId") Long userId,
                                                     @Param("sourceKey") String sourceKey);

    List<Notification> findAllByUserIdAndSourceKeyIn(Long userId, Collection<String> sourceKeys);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notification n where n.userId = :userId and n.resolvedAt is null")
    List<Notification> lockAllActiveByUserId(@Param("userId") Long userId);

    Page<Notification> findAllByUserId(Long userId, Pageable pageable);

    @Query("select n from Notification n where n.userId = :userId and ("
            + ":filter = 'ALL' or (:filter = 'RESOLVED' and n.resolvedAt is not null) or "
            + "(:filter = 'ACTIVE' and n.resolvedAt is null and "
            + "(n.dismissedRevision is null or n.dismissedRevision < n.revision) and "
            + "(n.snoozedUntil is null or n.snoozedUntil <= :now or n.snoozedRevision < n.revision)) or "
            + "(:filter = 'UNREAD' and n.resolvedAt is null and "
            + "(n.readRevision is null or n.readRevision < n.revision)) or "
            + "(:filter = 'DISMISSED' and n.dismissedRevision = n.revision) or "
            + "(:filter = 'SNOOZED' and n.snoozedRevision = n.revision and n.snoozedUntil > :now))")
    Page<Notification> list(@Param("userId") Long userId, @Param("filter") String filter,
                            @Param("now") Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notification n where n.id = :id and n.userId = :userId")
    Optional<Notification> lockByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Query(value = "select pg_advisory_xact_lock(:userId)", nativeQuery = true)
    void lockSynchronization(@Param("userId") Long userId);

    @Query("select count(n) from Notification n where n.userId = :userId "
            + "and n.resolvedAt is null and (n.readRevision is null or n.readRevision < n.revision) "
            + "and (n.dismissedRevision is null or n.dismissedRevision < n.revision) "
            + "and (n.snoozedUntil is null or n.snoozedUntil <= :now or n.snoozedRevision < n.revision)")
    long countUnread(@Param("userId") Long userId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("update Notification n set n.readRevision = n.revision where n.userId = :userId "
            + "and n.resolvedAt is null and (n.readRevision is null or n.readRevision < n.revision)")
    int markAllRead(@Param("userId") Long userId);

    @Query(value = """
            SELECT n.* FROM notifications n
            WHERE n.user_id = :userId AND n.resolved_at IS NULL
              AND (n.dismissed_revision IS NULL OR n.dismissed_revision < n.revision)
              AND (n.snoozed_until IS NULL OR n.snoozed_until <= :now
                   OR n.snoozed_revision < n.revision)
              AND (n.browser_delivered_revision IS NULL
                   OR n.browser_delivered_revision < n.revision)
              AND n.revision_changed_at >= :baseline
              AND CASE n.severity WHEN 'CRITICAL' THEN 3 WHEN 'WARNING' THEN 2 ELSE 1 END
                  >= :minimumRank
            ORDER BY CASE n.severity WHEN 'CRITICAL' THEN 3 WHEN 'WARNING' THEN 2 ELSE 1 END DESC,
                     n.event_date, n.id
            LIMIT :limit FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> lockBrowserCandidates(@Param("userId") Long userId,
                                              @Param("now") Instant now,
                                              @Param("baseline") Instant baseline,
                                              @Param("minimumRank") int minimumRank,
                                              @Param("limit") int limit);
}
