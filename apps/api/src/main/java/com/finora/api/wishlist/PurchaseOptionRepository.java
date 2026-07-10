package com.finora.api.wishlist;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOptionRepository extends JpaRepository<PurchaseOption, Long> {

    Optional<PurchaseOption> findByIdAndItemId(Long id, Long itemId);
}
