package com.finora.api.creditcard.invoice;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardInvoiceRepository extends JpaRepository<CardInvoice, Long> {

    Optional<CardInvoice> findByIdAndUserId(Long id, Long userId);

    Optional<CardInvoice> findByIdAndCardIdAndUserId(Long id, Long cardId, Long userId);

    Optional<CardInvoice> findByUserIdAndCardIdAndReferenceMonth(
            Long userId, Long cardId, LocalDate referenceMonth);

    List<CardInvoice> findAllByCardIdAndUserIdOrderByReferenceMonthAsc(Long cardId, Long userId);

    /**
     * Owner-scoped lookup with a pessimistic write lock on the invoice row.
     * Serializes payments and reversals so the overpayment check cannot be
     * raced by a concurrent payment against the same invoice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select i from CardInvoice i
            where i.id = :id and i.card.id = :cardId and i.userId = :userId
            """)
    Optional<CardInvoice> findByIdAndCardIdAndUserIdForUpdate(
            @Param("id") Long id, @Param("cardId") Long cardId, @Param("userId") Long userId);

    /** Invoices of the user's active (non-archived) cards due within a window. */
    @Query("""
            select i from CardInvoice i
            where i.userId = :userId
              and i.card.archived = false
              and i.dueDate >= :from
              and i.dueDate <= :to
            order by i.dueDate asc
            """)
    List<CardInvoice> findAllDueBetween(@Param("userId") Long userId,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to);

    List<CardInvoice> findAllByUserIdAndReferenceMonth(Long userId, LocalDate referenceMonth);
}
