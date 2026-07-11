package com.finora.api.creditcard.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoicePaymentRepository extends JpaRepository<InvoicePayment, Long> {

    Optional<InvoicePayment> findByIdAndInvoiceIdAndUserId(Long id, Long invoiceId, Long userId);

    List<InvoicePayment> findAllByInvoiceIdAndUserIdOrderByPaidOnAscIdAsc(Long invoiceId, Long userId);

    /** Amount already settled on one invoice (reversed payments excluded). */
    @Query("""
            select coalesce(sum(p.amount), 0)
            from InvoicePayment p
            where p.invoice.id = :invoiceId
              and p.userId = :userId
              and p.status = com.finora.api.creditcard.payment.PaymentStatus.COMPLETED
            """)
    BigDecimal sumCompletedByInvoice(@Param("invoiceId") Long invoiceId, @Param("userId") Long userId);

    /** Per-invoice completed payment totals for a card: [invoiceId, total]. */
    @Query("""
            select p.invoice.id, sum(p.amount)
            from InvoicePayment p
            where p.invoice.card.id = :cardId
              and p.userId = :userId
              and p.status = com.finora.api.creditcard.payment.PaymentStatus.COMPLETED
            group by p.invoice.id
            """)
    List<Object[]> sumCompletedGroupedByInvoice(@Param("cardId") Long cardId, @Param("userId") Long userId);

    /** Everything already paid on one card (restores its available limit). */
    @Query("""
            select coalesce(sum(p.amount), 0)
            from InvoicePayment p
            where p.invoice.card.id = :cardId
              and p.userId = :userId
              and p.status = com.finora.api.creditcard.payment.PaymentStatus.COMPLETED
            """)
    BigDecimal sumCompletedByCard(@Param("cardId") Long cardId, @Param("userId") Long userId);

    /** Cash settled out of one account by invoice payments (reduces its balance). */
    @Query("""
            select coalesce(sum(p.amount), 0)
            from InvoicePayment p
            where p.account.id = :accountId
              and p.userId = :userId
              and p.status = com.finora.api.creditcard.payment.PaymentStatus.COMPLETED
            """)
    BigDecimal sumCompletedByAccount(@Param("accountId") Long accountId, @Param("userId") Long userId);

    boolean existsByInvoiceIdAndStatus(Long invoiceId, PaymentStatus status);

    boolean existsByAccountId(Long accountId);

    /** True when any invoice holding an active installment of this purchase has a completed payment. */
    @Query("""
            select count(p) > 0
            from InvoicePayment p
            where p.status = com.finora.api.creditcard.payment.PaymentStatus.COMPLETED
              and p.invoice.id in (
                  select i.invoice.id from CardInstallment i
                  where i.purchase.id = :purchaseId
                    and i.status = com.finora.api.creditcard.installment.InstallmentStatus.ACTIVE)
            """)
    boolean existsCompletedForPurchaseInvoices(@Param("purchaseId") Long purchaseId);
}
