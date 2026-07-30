package com.finora.api.offlinesync;

import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.offlinesync.MutationHandler.AppliedMutation;
import com.finora.api.offlinesync.MutationHandler.MutationCommand;
import com.finora.api.offlinesync.OfflineSyncDtos.MutationEnvelope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import org.hibernate.StaleStateException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs exactly one mutation inside exactly one database transaction.
 *
 * <p>The per-mutation boundary is what lets a batch fail partially without
 * collateral damage: a rejected or conflicting operation rolls back only
 * itself, and the independent mutations submitted alongside it stay committed.
 * {@code REQUIRES_NEW} makes that true even if a caller ever wraps the batch in
 * a transaction of its own.
 *
 * <p>The receipt is written here, inside that same transaction, immediately
 * after the domain change. There is no window in which the side effect exists
 * without its proof — which is the entire basis for replaying safely after a
 * lost response.
 */
@Component
public class MutationExecutor {

    private final MutationReceiptRepository receipts;
    private final ResourceResolver resolver;
    private final ObjectMapper mapper;

    @PersistenceContext
    private EntityManager entityManager;

    public MutationExecutor(MutationReceiptRepository receipts, ResourceResolver resolver,
                            ObjectMapper mapper) {
        this.receipts = receipts;
        this.resolver = resolver;
        this.mapper = mapper;
    }

    /**
     * Applies the mutation and records its receipt atomically.
     *
     * @param userId          the authenticated owner; never taken from the request
     * @param envelope        the queued mutation as submitted
     * @param handler         the handler registered for this resource type
     * @param canonicalPayload the normalized payload the handler produced
     * @param requestHash     the canonical fingerprint stored on the receipt
     * @return what was applied, as the client needs to see it
     * @throws SyncConflictException      server state moved on; nothing was written
     * @throws SyncRejectedException      permanently invalid; nothing was written
     * @throws DependencyMissingException an offline parent has not landed yet
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AppliedMutation applyAndRecord(Long userId, MutationEnvelope envelope,
                                          MutationHandler handler, Object canonicalPayload,
                                          String requestHash) {
        AppliedMutation applied;
        try {
            applied = handler.apply(new MutationCommand(userId, envelope.operation(),
                    envelope.target(), envelope.baseVersion(), canonicalPayload, resolver,
                    entityManager::flush));
            // Force the write out now. Left to commit time it would escape this
            // block entirely and surface as a 500 that the client would treat as
            // a retryable transport failure — for a mutation that must never be
            // retried blindly.
            entityManager.flush();
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException
                 | StaleStateException e) {
            // The row changed — or vanished — between the version comparison and
            // the flush. Hibernate reports this in three shapes depending on the
            // path; all three mean the same thing to the user, and all three
            // must become the same typed conflict rather than a server error.
            throw new SyncConflictException(ConflictType.VERSION_MISMATCH,
                    "Este registro foi alterado no servidor enquanto a sincronização acontecia.",
                    null, null, java.util.List.of(ResolutionOption.KEEP_SERVER,
                            ResolutionOption.APPLY_LOCAL, ResolutionOption.EDIT_AND_RETRY,
                            ResolutionOption.DISCARD_LOCAL));
        } catch (DataIntegrityViolationException e) {
            // A concurrent request created the same resource first: the
            // owner-scoped partial unique index on client_resource_id caught it.
            throw new SyncConflictException(ConflictType.RESOURCE_ALREADY_EXISTS,
                    "Este registro já foi criado por outra sincronização.",
                    null, null, java.util.List.of(ResolutionOption.KEEP_SERVER,
                            ResolutionOption.DISCARD_LOCAL));
        } catch (BusinessRuleException e) {
            throw new SyncRejectedException(e.getCode(), e.getMessage());
        } catch (NotFoundException e) {
            // Owner-scoped lookups already turned "another user's" into
            // "absent", so this only ever means a referenced resource of the
            // owner's own is gone.
            throw new SyncRejectedException("SYNC_REFERENCE_NOT_FOUND", e.getMessage());
        }

        MutationReceipt receipt = new MutationReceipt(userId, envelope.clientMutationId(),
                envelope.resourceType(), envelope.operation(), requestHash,
                SyncStatus.APPLIED.name(), mapper.writeValueAsString(new StoredResult(
                        applied.clientResourceId(), applied.resourceId(), applied.version(),
                        applied.result())));
        receipt.setClientResourceId(applied.clientResourceId());
        receipt.setResourceId(applied.resourceId());
        receipt.setResourceVersion(applied.version());
        // saveAndFlush surfaces the owner/idempotency unique violation here,
        // inside this transaction, instead of at an unrelated commit later.
        receipts.saveAndFlush(receipt);
        return applied;
    }

    /**
     * The stored shape of a successful mutation, replayed verbatim when the
     * same key arrives again. Kept minimal on purpose: only what the client
     * needs to update its cache, never entity internals or owner ids.
     */
    public record StoredResult(java.util.UUID clientResourceId, Long resourceId, Long version,
                               Object result) {
    }
}
