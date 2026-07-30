package com.finora.api.offlinesync;

import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.offlinesync.MutationHandler.AppliedMutation;
import com.finora.api.offlinesync.OfflineSyncDtos.ConflictDetail;
import com.finora.api.offlinesync.OfflineSyncDtos.ErrorDetail;
import com.finora.api.offlinesync.OfflineSyncDtos.MutationBatchRequest;
import com.finora.api.offlinesync.OfflineSyncDtos.MutationBatchResponse;
import com.finora.api.offlinesync.OfflineSyncDtos.MutationEnvelope;
import com.finora.api.offlinesync.OfflineSyncDtos.MutationResult;
import com.finora.api.offlinesync.OfflineSyncDtos.ResourceTarget;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Replays a bounded batch of queued offline mutations.
 *
 * <p>Deliberately <em>not</em> transactional: each mutation gets its own
 * transaction inside {@link MutationExecutor}, so one bad operation cannot roll
 * back the good ones submitted with it. Input order is preserved and every
 * input produces exactly one result.
 *
 * <p>The idempotency decision happens here, before any handler runs:
 *
 * <ul>
 *   <li>no receipt for this owner and key — apply it;</li>
 *   <li>a receipt with the same fingerprint — the side effect already happened
 *       (very likely the client simply lost the response), so return the stored
 *       result and change nothing;</li>
 *   <li>a receipt with a different fingerprint — the key was reused for
 *       different content. The stored receipt is never overwritten and the new
 *       payload is never applied.</li>
 * </ul>
 */
@Service
public class OfflineSyncService {

    private static final Logger log = LoggerFactory.getLogger(OfflineSyncService.class);

    private final Map<SyncResourceType, MutationHandler> handlers =
            new EnumMap<>(SyncResourceType.class);
    private final MutationReceiptRepository receipts;
    private final MutationExecutor executor;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper mapper;

    public OfflineSyncService(List<MutationHandler> handlers,
                              MutationReceiptRepository receipts,
                              MutationExecutor executor,
                              CurrentUserProvider currentUser,
                              ObjectMapper mapper) {
        // An explicit registry keyed by the enum: dispatch can only ever reach
        // a handler that exists, and nothing in the request selects code.
        for (MutationHandler handler : handlers) {
            this.handlers.put(handler.resourceType(), handler);
        }
        this.receipts = receipts;
        this.executor = executor;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    public MutationBatchResponse replay(MutationBatchRequest request) {
        Long userId = currentUser.currentUserId();
        requireBoundedBatch(request.mutations());
        List<MutationResult> results = new ArrayList<>(request.mutations().size());
        for (MutationEnvelope envelope : request.mutations()) {
            results.add(process(userId, envelope));
        }
        return new MutationBatchResponse(results);
    }

    private MutationResult process(Long userId, MutationEnvelope envelope) {
        MutationHandler handler;
        Object canonical;
        String requestHash;
        // Shape and payload validation happens before anything is attempted, so
        // a malformed mutation costs no transaction — and produces no hash to
        // reconcile against later.
        try {
            requireValidTarget(envelope);
            handler = handlers.get(envelope.resourceType());
            if (handler == null) {
                throw new SyncRejectedException("SYNC_RESOURCE_UNSUPPORTED",
                        "Este tipo de recurso não pode ser sincronizado offline.");
            }
            canonical = handler.canonicalize(envelope.operation(), envelope.payload());
            requestHash = RequestFingerprint.of(envelope, canonical, mapper);
        } catch (SyncRejectedException e) {
            return MutationResult.rejected(envelope,
                    new ErrorDetail(e.getCode(), e.getMessage(), e.getFieldErrors()));
        }

        try {
            Optional<MutationReceipt> stored = findReceipt(userId, envelope.clientMutationId());
            if (stored.isPresent()) {
                return replayStored(envelope, stored.get(), requestHash);
            }

            AppliedMutation applied = executor.applyAndRecord(
                    userId, envelope, handler, canonical, requestHash);
            return MutationResult.applied(envelope, applied.clientResourceId(),
                    applied.resourceId(), applied.version(), mapper.valueToTree(applied.result()));
        } catch (SyncConflictException e) {
            return orStoredResult(userId, envelope, requestHash,
                    () -> MutationResult.conflict(envelope, new ConflictDetail(
                            e.getConflictType(), envelope.baseVersion(), e.getServerVersion(),
                            e.getServerSnapshot() == null
                                    ? null : mapper.valueToTree(e.getServerSnapshot()),
                            e.getResolutionOptions(), e.getMessage())));
        } catch (SyncRejectedException e) {
            return orStoredResult(userId, envelope, requestHash,
                    () -> MutationResult.rejected(envelope,
                            new ErrorDetail(e.getCode(), e.getMessage(), e.getFieldErrors())));
        } catch (DependencyMissingException e) {
            return MutationResult.dependencyMissing(envelope,
                    ErrorDetail.of(e.getCode(), e.getMessage()));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Only reachable when two requests raced on the owner/idempotency
            // unique constraint: the other one won, so its receipt is now the
            // authority. Never log the payload — only safe identifiers.
            log.info("Offline sync receipt race for mutation {}", envelope.clientMutationId());
            return orStoredResult(userId, envelope, requestHash,
                    () -> MutationResult.rejected(envelope, ErrorDetail.of(
                            "SYNC_CONFLICTING_WRITE",
                            "Esta operação conflitou com outra sincronização simultânea.")));
        }
    }

    /**
     * Resolves a failure that may actually be this mutation's own twin winning
     * the race.
     *
     * <p>Two tabs replaying the same queue reach the receipt check before either
     * writes, so both proceed — and the loser fails on a unique index or an
     * optimistic lock. That failure is not something to show the user: the side
     * effect they asked for did happen. Re-reading the receipt after the fact
     * tells the two cases apart, because a receipt now exists under this exact
     * key with this exact fingerprint only if this very mutation was applied.
     */
    private MutationResult orStoredResult(Long userId, MutationEnvelope envelope,
                                          String requestHash,
                                          java.util.function.Supplier<MutationResult> failure) {
        return findReceipt(userId, envelope.clientMutationId())
                .filter(receipt -> receipt.getRequestHash().equals(requestHash))
                .map(receipt -> storedResult(envelope, receipt))
                .orElseGet(failure);
    }

    /**
     * The same key twice. Identical content is the lost-response case and must
     * be answered from the receipt; different content is an abuse of the key.
     */
    private MutationResult replayStored(MutationEnvelope envelope, MutationReceipt receipt,
                                        String requestHash) {
        if (!receipt.getRequestHash().equals(requestHash)) {
            return MutationResult.conflict(envelope, new ConflictDetail(
                    ConflictType.IDEMPOTENCY_KEY_REUSED, envelope.baseVersion(),
                    receipt.getResourceVersion(), null,
                    List.of(ResolutionOption.DISCARD_LOCAL, ResolutionOption.EDIT_AND_RETRY),
                    "Esta operação já foi enviada com dados diferentes. Ela não foi aplicada "
                            + "novamente."));
        }
        return storedResult(envelope, receipt);
    }

    private MutationResult storedResult(MutationEnvelope envelope, MutationReceipt receipt) {
        JsonNode payload = mapper.readTree(receipt.getResponsePayload());
        return MutationResult.alreadyApplied(envelope, receipt.getClientResourceId(),
                receipt.getResourceId(), receipt.getResourceVersion(),
                payload.get("result"));
    }

    @Transactional(readOnly = true)
    Optional<MutationReceipt> findReceipt(Long userId, java.util.UUID clientMutationId) {
        return receipts.findByUserIdAndClientMutationId(userId, clientMutationId);
    }

    /**
     * Shape rules the envelope itself cannot express. A CREATE must bring a
     * client identity and nothing else; an UPDATE or DELETE must name exactly
     * one existing resource and the version it was based on.
     */
    private static void requireValidTarget(MutationEnvelope envelope) {
        ResourceTarget target = envelope.target();
        boolean hasServerId = target.serverId() != null;
        boolean hasClientId = target.clientResourceId() != null;
        if (hasServerId && hasClientId) {
            throw new SyncRejectedException("SYNC_TARGET_AMBIGUOUS",
                    "Uma operação não pode identificar o recurso de duas formas ao mesmo tempo.");
        }
        if (!hasServerId && !hasClientId) {
            throw new SyncRejectedException("SYNC_TARGET_REQUIRED",
                    "Informe o recurso alvo da operação.");
        }
        if (envelope.operation() == SyncOperation.CREATE) {
            if (!hasClientId) {
                throw new SyncRejectedException("SYNC_CREATE_REQUIRES_CLIENT_ID",
                        "Uma criação offline precisa do identificador gerado no dispositivo.");
            }
            if (envelope.baseVersion() != null) {
                throw new SyncRejectedException("SYNC_CREATE_WITH_BASE_VERSION",
                        "Uma criação não tem versão anterior.");
            }
            return;
        }
        if (envelope.baseVersion() == null) {
            throw new SyncRejectedException("SYNC_BASE_VERSION_REQUIRED",
                    "Informe a versão do registro em que a alteração offline foi baseada.");
        }
        if (envelope.baseVersion() < 0) {
            throw new SyncRejectedException("SYNC_BASE_VERSION_INVALID",
                    "A versão informada é inválida.");
        }
    }

    /**
     * Size limits are checked before any processing so an oversized batch costs
     * one rejection instead of twenty-five transactions.
     */
    private void requireBoundedBatch(List<MutationEnvelope> mutations) {
        if (mutations.size() > OfflineSyncDtos.MAX_BATCH_SIZE) {
            throw new SyncBatchRejectedException("SYNC_BATCH_TOO_LARGE",
                    "Envie no máximo %d operações por lote."
                            .formatted(OfflineSyncDtos.MAX_BATCH_SIZE));
        }
        long total = 0;
        for (MutationEnvelope envelope : mutations) {
            long size = mapper.writeValueAsString(envelope.payload())
                    .getBytes(StandardCharsets.UTF_8).length;
            if (size > OfflineSyncDtos.MAX_PAYLOAD_BYTES) {
                throw new SyncBatchRejectedException("SYNC_PAYLOAD_TOO_LARGE",
                        "Uma das operações excede o tamanho máximo permitido.");
            }
            total += size;
        }
        if (total > OfflineSyncDtos.MAX_BATCH_PAYLOAD_BYTES) {
            throw new SyncBatchRejectedException("SYNC_BATCH_PAYLOAD_TOO_LARGE",
                    "O lote de operações excede o tamanho máximo permitido.");
        }
    }
}
