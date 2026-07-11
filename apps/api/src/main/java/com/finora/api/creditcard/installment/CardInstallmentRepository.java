package com.finora.api.creditcard.installment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardInstallmentRepository extends JpaRepository<CardInstallment, Long> {

    List<CardInstallment> findAllByPurchaseIdAndUserIdOrderBySequenceNumberAsc(
            Long purchaseId, Long userId);

    List<CardInstallment> findAllByInvoiceIdAndUserIdOrderByPurchasePurchaseDateAscIdAsc(
            Long invoiceId, Long userId);

    /** Active charge total of one invoice (cancelled installments excluded). */
    @Query("""
            select coalesce(sum(i.amount), 0)
            from CardInstallment i
            where i.invoice.id = :invoiceId
              and i.userId = :userId
              and i.status = com.finora.api.creditcard.installment.InstallmentStatus.ACTIVE
            """)
    BigDecimal sumActiveByInvoice(@Param("invoiceId") Long invoiceId, @Param("userId") Long userId);

    /** Per-invoice active totals for a card: [invoiceId, total, count]. */
    @Query("""
            select i.invoice.id, sum(i.amount), count(i)
            from CardInstallment i
            where i.invoice.card.id = :cardId
              and i.userId = :userId
              and i.status = com.finora.api.creditcard.installment.InstallmentStatus.ACTIVE
            group by i.invoice.id
            """)
    List<Object[]> sumActiveGroupedByInvoice(@Param("cardId") Long cardId, @Param("userId") Long userId);

    /** All active installment obligations of one card (consumes credit limit). */
    @Query("""
            select coalesce(sum(i.amount), 0)
            from CardInstallment i
            where i.invoice.card.id = :cardId
              and i.userId = :userId
              and i.status = com.finora.api.creditcard.installment.InstallmentStatus.ACTIVE
            """)
    BigDecimal sumActiveByCard(@Param("cardId") Long cardId, @Param("userId") Long userId);

    /** Every active installment obligation of the user, across all cards and months. */
    @Query("""
            select coalesce(sum(i.amount), 0)
            from CardInstallment i
            where i.userId = :userId
              and i.status = com.finora.api.creditcard.installment.InstallmentStatus.ACTIVE
            """)
    BigDecimal sumActiveByUser(@Param("userId") Long userId);

    /** Card expense recognized in a month: active installments of that invoice month. */
    @Query("""
            select coalesce(sum(i.amount), 0)
            from CardInstallment i
            where i.userId = :userId
              and i.status = com.finora.api.creditcard.installment.InstallmentStatus.ACTIVE
              and i.invoice.referenceMonth = :referenceMonth
            """)
    BigDecimal sumActiveByMonth(@Param("userId") Long userId,
                                @Param("referenceMonth") LocalDate referenceMonth);

    /** Active installments of one category in one invoice month (budget consumption). */
    @Query("""
            select coalesce(sum(i.amount), 0)
            from CardInstallment i
            where i.userId = :userId
              and i.status = com.finora.api.creditcard.installment.InstallmentStatus.ACTIVE
              and i.purchase.category.id = :categoryId
              and i.invoice.referenceMonth = :referenceMonth
            """)
    BigDecimal sumActiveByCategoryAndMonth(@Param("userId") Long userId,
                                           @Param("categoryId") Long categoryId,
                                           @Param("referenceMonth") LocalDate referenceMonth);

    /** Active installment totals of a month grouped by category: [id, name, total]. */
    @Query("""
            select i.purchase.category.id, i.purchase.category.name, sum(i.amount)
            from CardInstallment i
            where i.userId = :userId
              and i.status = com.finora.api.creditcard.installment.InstallmentStatus.ACTIVE
              and i.invoice.referenceMonth = :referenceMonth
            group by i.purchase.category.id, i.purchase.category.name
            """)
    List<Object[]> sumActiveGroupedByCategory(@Param("userId") Long userId,
                                              @Param("referenceMonth") LocalDate referenceMonth);

    /** Future active installment totals by month, from a month on: [month, total]. */
    @Query("""
            select i.invoice.referenceMonth, sum(i.amount)
            from CardInstallment i
            where i.userId = :userId
              and i.status = com.finora.api.creditcard.installment.InstallmentStatus.ACTIVE
              and i.invoice.referenceMonth >= :fromMonth
            group by i.invoice.referenceMonth
            order by i.invoice.referenceMonth
            """)
    List<Object[]> sumActiveByMonthFrom(@Param("userId") Long userId,
                                        @Param("fromMonth") LocalDate fromMonth);

    boolean existsByInvoiceIdAndStatus(Long invoiceId, InstallmentStatus status);

    /** True when any invoice holding an active installment of the purchase already closed. */
    @Query("""
            select count(i) > 0
            from CardInstallment i
            where i.purchase.id = :purchaseId
              and i.status = com.finora.api.creditcard.installment.InstallmentStatus.ACTIVE
              and i.invoice.closingDate < :today
            """)
    boolean existsActiveWithClosedInvoice(@Param("purchaseId") Long purchaseId,
                                          @Param("today") LocalDate today);

    int countByInvoiceIdAndUserIdAndStatus(Long invoiceId, Long userId, InstallmentStatus status);
}
