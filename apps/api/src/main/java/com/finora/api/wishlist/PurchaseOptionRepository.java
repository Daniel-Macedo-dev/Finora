package com.finora.api.wishlist;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOptionRepository extends JpaRepository<PurchaseOption, Long> {

    /**
     * Ownership is inherited through the parent wishlist item: the query walks
     * item.userId so a guessed option/item id pair of another user resolves to
     * empty (presented as 404).
     */
    Optional<PurchaseOption> findByIdAndItemIdAndItemUserId(Long id, Long itemId, Long userId);

    /** Owner-scoped lookup by server id alone, without knowing the parent item. */
    Optional<PurchaseOption> findByIdAndUserId(Long id, Long userId);

    /** Owner-scoped: another user's client identity resolves to empty. */
    Optional<PurchaseOption> findByUserIdAndClientResourceId(Long userId, UUID clientResourceId);
}
