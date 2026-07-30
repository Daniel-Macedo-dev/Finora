package com.finora.api.offlinesync;

import com.finora.api.offlinesync.OfflineSyncDtos.ResourceTarget;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Applies one resource type's offline mutations.
 *
 * <p>Dispatch is an explicit registry keyed by {@link SyncResourceType} — never
 * reflection, never a class name from the request. A handler that does not
 * exist simply cannot be reached.
 *
 * <p>Handlers reuse the domain services that already own the financial rules;
 * they exist to translate a queued envelope into those calls and to enforce the
 * offline-specific guards (ownership of referenced resources, optimistic
 * version comparison, protected-record refusal). They never restate a financial
 * rule and never call a controller.
 */
public interface MutationHandler {

    SyncResourceType resourceType();

    /**
     * Parses, validates and normalizes the raw payload into the canonical typed
     * form used both for the request fingerprint and for the mutation itself.
     *
     * <p>Pure by contract: no database access, no clock, no randomness. Two
     * calls with the same bytes must produce equal objects, otherwise a retry
     * could be mistaken for a different request.
     *
     * @return a record; {@code null} when the operation carries no payload
     * @throws SyncRejectedException when the payload is malformed or invalid
     */
    Object canonicalize(SyncOperation operation, JsonNode payload);

    /**
     * Performs the mutation inside the caller's transaction.
     *
     * @throws SyncConflictException      when server state moved on
     * @throws SyncRejectedException      when a domain rule or ownership refuses it
     * @throws DependencyMissingException when a referenced offline parent is absent
     */
    AppliedMutation apply(MutationCommand command);

    /**
     * What actually happened, as the client needs to see it.
     *
     * @param clientResourceId the stable client identity, echoed for CREATE
     * @param resourceId       the server id (retained for DELETE as an audit anchor)
     * @param version          the new optimistic version, null after DELETE
     * @param result           the resource's public representation, null after DELETE
     */
    record AppliedMutation(UUID clientResourceId, Long resourceId, Long version, Object result) {
    }

    /**
     * One mutation, ready to apply.
     *
     * @param userId      always from the authenticated session, never the request
     * @param baseVersion the version the offline edit saw; null for CREATE
     * @param payload     the canonical object this handler produced
     * @param flush       forces pending changes to the database. An update must
     *                    call this before reading the new optimistic version:
     *                    Hibernate assigns it at flush time, so a version read
     *                    beforehand is the old one — and a client that stored it
     *                    would conflict with itself on its next edit.
     */
    record MutationCommand(Long userId,
                           SyncOperation operation,
                           ResourceTarget target,
                           Long baseVersion,
                           Object payload,
                           ResourceResolver resolver,
                           Runnable flush) {
    }
}
