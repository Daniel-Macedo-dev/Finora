package com.finora.api.creditcard.purchase;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardPurchaseRepository extends JpaRepository<CardPurchase, Long> {

    Optional<CardPurchase> findByIdAndCardIdAndUserId(Long id, Long cardId, Long userId);

    Page<CardPurchase> findAllByCardIdAndUserIdOrderByPurchaseDateDescIdDesc(
            Long cardId, Long userId, Pageable pageable);

    List<CardPurchase> findTop5ByUserIdAndStatusOrderByPurchaseDateDescIdDesc(
            Long userId, PurchaseStatus status);

    Optional<CardPurchase> findByWishlistItemIdAndUserId(Long wishlistItemId, Long userId);

    /** Guard for card archival/deletion decisions. */
    boolean existsByCardId(Long cardId);

    boolean existsByCategoryId(Long categoryId);

    /** Ids of the invoices that hold this purchase's active installments. */
    @Query("""
            select distinct i.invoice.id
            from CardInstallment i
            where i.purchase.id = :purchaseId
              and i.status = com.finora.api.creditcard.installment.InstallmentStatus.ACTIVE
            """)
    List<Long> activeInvoiceIds(@Param("purchaseId") Long purchaseId);
}
