package com.finora.api.wishlist;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WishlistItemRepository extends JpaRepository<WishlistItem, Long> {

    List<WishlistItem> findAllByUserIdOrderByStatusAscPriorityDescNameAsc(Long userId);

    List<WishlistItem> findAllByUserIdAndStatusIn(Long userId, List<WishlistStatus> statuses);

    Optional<WishlistItem> findByIdAndUserId(Long id, Long userId);
}
