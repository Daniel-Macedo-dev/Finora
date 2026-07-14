package com.finora.api.commitment.occurrence;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommitmentOccurrenceRepository extends JpaRepository<CommitmentOccurrence, Long> {

    Optional<CommitmentOccurrence> findByCommitmentIdAndScheduledDateAndUserId(
            Long commitmentId, LocalDate scheduledDate, Long userId);

    /** Claim for materialization/reversal: serializes every state transition. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o from CommitmentOccurrence o
            where o.commitment.id = :commitmentId
              and o.scheduledDate = :scheduledDate
              and o.userId = :userId
            """)
    Optional<CommitmentOccurrence> findByIdentityForUpdate(
            @Param("commitmentId") Long commitmentId,
            @Param("scheduledDate") LocalDate scheduledDate,
            @Param("userId") Long userId);

    List<CommitmentOccurrence> findAllByCommitmentIdAndUserIdAndScheduledDateBetween(
            Long commitmentId, Long userId, LocalDate from, LocalDate to);

    Page<CommitmentOccurrence> findAllByCommitmentIdAndUserIdOrderByScheduledDateDesc(
            Long commitmentId, Long userId, Pageable pageable);

    Optional<CommitmentOccurrence> findByTransactionIdAndUserId(Long transactionId, Long userId);

    Optional<CommitmentOccurrence> findByCardPurchaseIdAndUserId(Long cardPurchaseId, Long userId);

    long countByUserIdAndStatus(Long userId, OccurrenceStatus status);

    List<CommitmentOccurrence> findAllByUserIdAndStatus(Long userId, OccurrenceStatus status);

    /** Non-terminal occurrences of one user inside a window (forecast overlay). */
    @Query("""
            select o from CommitmentOccurrence o
            where o.userId = :userId
              and o.effectiveDate between :from and :to
            """)
    List<CommitmentOccurrence> findAllByUserInWindow(@Param("userId") Long userId,
                                                     @Param("from") LocalDate from,
                                                     @Param("to") LocalDate to);

    /** All persisted occurrences of one user's definition (any window). */
    List<CommitmentOccurrence> findAllByCommitmentIdAndUserId(Long commitmentId, Long userId);

    boolean existsByCommitmentIdAndStatusIn(Long commitmentId, List<OccurrenceStatus> statuses);
}
