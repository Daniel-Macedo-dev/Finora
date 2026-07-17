package com.finora.api.legacyconversion;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LegacyConversionRepository extends JpaRepository<LegacyCreditConversion, Long> {

    Optional<LegacyCreditConversion> findByIdAndUserId(Long id, Long userId);

    /** Locked load for reversal: serializes racing lifecycle changes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from LegacyCreditConversion c
            where c.id = :id and c.userId = :userId
            """)
    Optional<LegacyCreditConversion> findByIdAndUserIdForUpdate(@Param("id") Long id,
                                                                @Param("userId") Long userId);

    Optional<LegacyCreditConversion> findBySourceTransactionIdAndStatus(
            Long sourceTransactionId, ConversionStatus status);

    /** Locked variant used by the conversion engine's idempotency check. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from LegacyCreditConversion c
            where c.sourceTransactionId = :sourceTransactionId
              and c.userId = :userId
              and c.status = com.finora.api.legacyconversion.ConversionStatus.ACTIVE
            """)
    Optional<LegacyCreditConversion> findActiveBySourceForUpdate(
            @Param("sourceTransactionId") Long sourceTransactionId,
            @Param("userId") Long userId);

    boolean existsBySourceTransactionIdAndStatus(Long sourceTransactionId, ConversionStatus status);

    Optional<LegacyCreditConversion> findByCardPurchaseIdAndUserId(Long cardPurchaseId, Long userId);

    /** Latest conversion per source, for response metadata (one bulk query per page). */
    @Query("""
            select c from LegacyCreditConversion c
            where c.userId = :userId
              and c.sourceTransactionId in :sourceIds
            order by c.convertedAt asc, c.id asc
            """)
    List<LegacyCreditConversion> findAllByUserIdAndSourceTransactionIdIn(
            @Param("userId") Long userId,
            @Param("sourceIds") Collection<Long> sourceIds);

    long countByUserIdAndStatus(Long userId, ConversionStatus status);
}
