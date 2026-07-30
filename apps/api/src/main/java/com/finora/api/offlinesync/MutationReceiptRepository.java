package com.finora.api.offlinesync;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Every lookup is owner-scoped by construction: there is no method that finds a
 * receipt by mutation id alone, so one owner can never observe — or collide
 * with — another owner's idempotency keys.
 */
public interface MutationReceiptRepository extends JpaRepository<MutationReceipt, Long> {

    Optional<MutationReceipt> findByUserIdAndClientMutationId(Long userId, UUID clientMutationId);
}
