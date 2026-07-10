package com.finora.api.wishlist;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WishlistItemRepository extends JpaRepository<WishlistItem, Long> {

    List<WishlistItem> findAllByOrderByStatusAscPriorityDescNameAsc();

    List<WishlistItem> findAllByStatusIn(List<WishlistStatus> statuses);
}
