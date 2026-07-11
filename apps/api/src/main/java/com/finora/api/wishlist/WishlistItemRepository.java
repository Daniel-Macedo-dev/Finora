package com.finora.api.wishlist;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WishlistItemRepository extends JpaRepository<WishlistItem, Long> {

    List<WishlistItem> findAllByUserIdOrderByStatusAscPriorityDescNameAsc(Long userId);

    List<WishlistItem> findAllByUserIdAndStatusIn(Long userId, List<WishlistStatus> statuses);

    Optional<WishlistItem> findByIdAndUserId(Long id, Long userId);

    /**
     * Owner-scoped lookup with a pessimistic write lock. Serializes purchase
     * execution so a retry or double-submit sees the PURCHASED status instead
     * of executing the item a second time.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WishlistItem w where w.id = :id and w.userId = :userId")
    Optional<WishlistItem> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
