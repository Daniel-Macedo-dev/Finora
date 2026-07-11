package com.finora.api.creditcard.adjustment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceAdjustmentRepository extends JpaRepository<InvoiceAdjustment, Long> {

    Optional<InvoiceAdjustment> findByIdAndInvoiceIdAndUserId(Long id, Long invoiceId, Long userId);

    List<InvoiceAdjustment> findAllByInvoiceIdAndUserIdOrderByIdAsc(Long invoiceId, Long userId);

    /**
     * Net active adjustment of one invoice: debits positive, credits negative.
     */
    @Query("""
            select coalesce(sum(case when a.kind in (
                        com.finora.api.creditcard.adjustment.AdjustmentKind.FEE,
                        com.finora.api.creditcard.adjustment.AdjustmentKind.INTEREST,
                        com.finora.api.creditcard.adjustment.AdjustmentKind.OTHER_DEBIT)
                    then a.amount else -a.amount end), 0)
            from InvoiceAdjustment a
            where a.invoice.id = :invoiceId
              and a.userId = :userId
              and a.status = com.finora.api.creditcard.adjustment.AdjustmentStatus.ACTIVE
            """)
    BigDecimal sumActiveNetByInvoice(@Param("invoiceId") Long invoiceId, @Param("userId") Long userId);

    /** Per-invoice net active adjustments for a card: [invoiceId, net]. */
    @Query("""
            select a.invoice.id,
                   sum(case when a.kind in (
                           com.finora.api.creditcard.adjustment.AdjustmentKind.FEE,
                           com.finora.api.creditcard.adjustment.AdjustmentKind.INTEREST,
                           com.finora.api.creditcard.adjustment.AdjustmentKind.OTHER_DEBIT)
                       then a.amount else -a.amount end)
            from InvoiceAdjustment a
            where a.invoice.card.id = :cardId
              and a.userId = :userId
              and a.status = com.finora.api.creditcard.adjustment.AdjustmentStatus.ACTIVE
            group by a.invoice.id
            """)
    List<Object[]> sumActiveNetGroupedByInvoice(@Param("cardId") Long cardId, @Param("userId") Long userId);

    /** Net active adjustment of one card (part of the used-limit formula). */
    @Query("""
            select coalesce(sum(case when a.kind in (
                        com.finora.api.creditcard.adjustment.AdjustmentKind.FEE,
                        com.finora.api.creditcard.adjustment.AdjustmentKind.INTEREST,
                        com.finora.api.creditcard.adjustment.AdjustmentKind.OTHER_DEBIT)
                    then a.amount else -a.amount end), 0)
            from InvoiceAdjustment a
            where a.invoice.card.id = :cardId
              and a.userId = :userId
              and a.status = com.finora.api.creditcard.adjustment.AdjustmentStatus.ACTIVE
            """)
    BigDecimal sumActiveNetByCard(@Param("cardId") Long cardId, @Param("userId") Long userId);

    /** Net active adjustment recognized in a month (invoice reference month). */
    @Query("""
            select coalesce(sum(case when a.kind in (
                        com.finora.api.creditcard.adjustment.AdjustmentKind.FEE,
                        com.finora.api.creditcard.adjustment.AdjustmentKind.INTEREST,
                        com.finora.api.creditcard.adjustment.AdjustmentKind.OTHER_DEBIT)
                    then a.amount else -a.amount end), 0)
            from InvoiceAdjustment a
            where a.userId = :userId
              and a.status = com.finora.api.creditcard.adjustment.AdjustmentStatus.ACTIVE
              and a.invoice.referenceMonth = :referenceMonth
            """)
    BigDecimal sumActiveNetByMonth(@Param("userId") Long userId,
                                   @Param("referenceMonth") LocalDate referenceMonth);

    /** Net active adjustment of one category in one invoice month (budgets). */
    @Query("""
            select coalesce(sum(case when a.kind in (
                        com.finora.api.creditcard.adjustment.AdjustmentKind.FEE,
                        com.finora.api.creditcard.adjustment.AdjustmentKind.INTEREST,
                        com.finora.api.creditcard.adjustment.AdjustmentKind.OTHER_DEBIT)
                    then a.amount else -a.amount end), 0)
            from InvoiceAdjustment a
            where a.userId = :userId
              and a.status = com.finora.api.creditcard.adjustment.AdjustmentStatus.ACTIVE
              and a.category.id = :categoryId
              and a.invoice.referenceMonth = :referenceMonth
            """)
    BigDecimal sumActiveNetByCategoryAndMonth(@Param("userId") Long userId,
                                              @Param("categoryId") Long categoryId,
                                              @Param("referenceMonth") LocalDate referenceMonth);
}
