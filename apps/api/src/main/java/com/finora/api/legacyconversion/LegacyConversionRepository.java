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

    /**
     * Scalar lookup of the conversion's source transaction, used before taking
     * locks. Loading the entity here and re-reading it under lock in the same
     * persistence context would blow up with an optimistic-locking conflict
     * when a concurrent reversal bumps the version in between.
     */
    @Query("""
            select c.sourceTransactionId from LegacyCreditConversion c
            where c.id = :id and c.userId = :userId
            """)
    Optional<Long> findSourceTransactionIdByIdAndUserId(@Param("id") Long id,
                                                        @Param("userId") Long userId);

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

    /** Any conversion history at all — such a source is a protected audit anchor. */
    boolean existsBySourceTransactionId(Long sourceTransactionId);

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

    // ── inventory summary ────────────────────────────────────────────────────
    // These aggregate the user's legacy-credit *transactions*; they live here
    // because the "convertible" shape is a conversion-domain concept.

    /** Sources convertible right now (includes reversed-then-restorable ones). */
    @Query("""
            select count(t) from Transaction t
            where t.userId = :userId
              and t.legacyCredit = true
              and t.financiallyActive = true
              and t.type = com.finora.api.transaction.TransactionType.EXPENSE
              and t.commitmentId is null
              and t.wishlistItemId is null
              and t.amount > 0
            """)
    long countConvertibleSources(@Param("userId") Long userId);

    /** Historical amount still awaiting review (sum over convertible sources). */
    @Query("""
            select coalesce(sum(t.amount), 0) from Transaction t
            where t.userId = :userId
              and t.legacyCredit = true
              and t.financiallyActive = true
              and t.type = com.finora.api.transaction.TransactionType.EXPENSE
              and t.commitmentId is null
              and t.wishlistItemId is null
              and t.amount > 0
            """)
    java.math.BigDecimal sumConvertibleSourceAmount(@Param("userId") Long userId);

    /** Sources whose latest conversion was reversed and that have no active one. */
    @Query("""
            select count(distinct c.sourceTransactionId) from LegacyCreditConversion c
            where c.userId = :userId
              and c.status = com.finora.api.legacyconversion.ConversionStatus.REVERSED
              and not exists (
                  select 1 from LegacyCreditConversion a
                  where a.sourceTransactionId = c.sourceTransactionId
                    and a.status = com.finora.api.legacyconversion.ConversionStatus.ACTIVE)
            """)
    long countReversedSources(@Param("userId") Long userId);
}
