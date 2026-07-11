package com.finora.api.creditcard;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditCardRepository extends JpaRepository<CreditCard, Long> {

    List<CreditCard> findAllByUserIdOrderByArchivedAscNameAsc(Long userId);

    Optional<CreditCard> findByIdAndUserId(Long id, Long userId);

    Optional<CreditCard> findByUserIdAndNameIgnoreCase(Long userId, String name);

    /**
     * Owner-scoped lookup that takes a pessimistic write lock on the card row.
     * Serializes limit-consuming operations (purchase creation, wishlist
     * execution, cancellation): two concurrent purchases cannot both read a
     * stale available limit and overspend the card.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CreditCard c where c.id = :id and c.userId = :userId")
    Optional<CreditCard> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
