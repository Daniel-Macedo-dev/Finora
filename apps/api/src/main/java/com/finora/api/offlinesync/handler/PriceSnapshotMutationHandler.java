package com.finora.api.offlinesync.handler;

import com.finora.api.common.money.MoneyRules;
import com.finora.api.offlinesync.MutationHandler;
import com.finora.api.offlinesync.PayloadCodec;
import com.finora.api.offlinesync.ResourceResolver;
import com.finora.api.offlinesync.SyncOperation;
import com.finora.api.offlinesync.SyncRejectedException;
import com.finora.api.offlinesync.SyncResourceType;
import com.finora.api.offlinesync.VersionGuard;
import com.finora.api.offlinesync.handler.OfflinePayloads.SnapshotPayload;
import com.finora.api.wishlist.PriceHistoryDtos.SnapshotRequest;
import com.finora.api.wishlist.PriceHistoryDtos.SnapshotResponse;
import com.finora.api.wishlist.PriceHistoryDtos.SnapshotUpdateRequest;
import com.finora.api.wishlist.PriceHistoryService;
import com.finora.api.wishlist.PriceSnapshot;
import com.finora.api.wishlist.PurchaseOption;
import com.finora.api.wishlist.WishlistItem;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Offline CRUD for manual price observations — history only.
 *
 * <p>Two online flows have no offline counterpart, by design rather than by
 * omission. <em>Capturing</em> the current option copies whatever the option
 * holds at the moment of capture; replayed later, it would record a price the
 * user never observed and stamp it with the date they were offline. And
 * <em>updating the linked option</em> mutates a second resource as a side
 * effect of recording history, which would slip past the outbox's per-operation
 * accounting. Neither has a field in {@link SnapshotPayload}.
 *
 * <p>Price snapshots already had an owner-scoped {@code client_request_id} and
 * its own idempotency rule before this stage existed. V14 reuses that column as
 * the snapshot's client identity instead of adding a second one, so an offline
 * creation and an online one converge on exactly the same duplicate protection.
 */
@Component
public class PriceSnapshotMutationHandler implements MutationHandler {

    private final PriceHistoryService history;
    private final PayloadCodec codec;

    public PriceSnapshotMutationHandler(PriceHistoryService history, PayloadCodec codec) {
        this.history = history;
        this.codec = codec;
    }

    @Override
    public SyncResourceType resourceType() {
        return SyncResourceType.PRICE_SNAPSHOT;
    }

    @Override
    public Object canonicalize(SyncOperation operation, JsonNode payload) {
        if (operation == SyncOperation.DELETE) {
            codec.requireEmpty(payload);
            return null;
        }
        SnapshotPayload request = codec.parse(payload, SnapshotPayload.class);
        return new SnapshotPayload(
                request.item(),
                request.purchaseOption(),
                request.merchant().trim(),
                request.paymentKind(),
                MoneyRules.normalize(request.basePrice()),
                MoneyRules.normalize(orZero(request.shipping())),
                MoneyRules.normalize(orZero(request.fees())),
                request.installmentCount(),
                request.installmentAmount() == null
                        ? null : MoneyRules.normalize(request.installmentAmount()),
                request.observedOn(),
                blankToNull(request.offerUrl()),
                blankToNull(request.notes()));
    }

    @Override
    public AppliedMutation apply(MutationCommand command) {
        return switch (command.operation()) {
            case CREATE -> create(command);
            case UPDATE -> update(command);
            case DELETE -> delete(command);
        };
    }

    private AppliedMutation create(MutationCommand command) {
        SnapshotPayload payload = (SnapshotPayload) command.payload();
        if (payload.item() == null) {
            throw new SyncRejectedException("SYNC_SNAPSHOT_ITEM_REQUIRED",
                    "Informe o item da lista de desejos desta observação de preço.");
        }
        WishlistItem item = command.resolver().requireParentItem(command.userId(), payload.item());
        Long optionId = null;
        if (payload.purchaseOption() != null) {
            PurchaseOption option = command.resolver()
                    .requireParentOption(command.userId(), payload.purchaseOption());
            optionId = option.getId();
        }
        UUID clientResourceId = command.target().clientResourceId();
        SnapshotResponse created = history.create(item.getId(), new SnapshotRequest(
                clientResourceId, optionId, payload.merchant(), payload.paymentKind(),
                payload.basePrice(), payload.shipping(), payload.fees(),
                payload.installmentCount(), payload.installmentAmount(), payload.observedOn(),
                payload.offerUrl(), payload.notes(),
                // History only: never touch the option this observation refers to.
                false));
        return new AppliedMutation(clientResourceId, created.id(), null, created);
    }

    private AppliedMutation update(MutationCommand command) {
        PriceSnapshot existing = require(command);
        SnapshotPayload payload = (SnapshotPayload) command.payload();
        VersionGuard.require(existing.getVersion(), command.baseVersion(), snapshotOf(existing),
                "Esta observação foi alterada em outro dispositivo depois da sua edição offline.");
        Long optionId = null;
        if (payload.purchaseOption() != null) {
            optionId = command.resolver()
                    .requireParentOption(command.userId(), payload.purchaseOption()).getId();
        }
        SnapshotResponse updated = history.update(existing.getItem().getId(), existing.getId(),
                new SnapshotUpdateRequest(optionId, payload.merchant(), payload.paymentKind(),
                        payload.basePrice(), payload.shipping(), payload.fees(),
                        payload.installmentCount(), payload.installmentAmount(),
                        payload.observedOn(), payload.offerUrl(), payload.notes()));
        // The optimistic version is assigned at flush; read it only afterwards.
        command.flush().run();
        return new AppliedMutation(existing.getClientRequestId(), updated.id(),
                existing.getVersion(), updated);
    }

    private AppliedMutation delete(MutationCommand command) {
        PriceSnapshot existing = require(command);
        VersionGuard.require(existing.getVersion(), command.baseVersion(), snapshotOf(existing),
                "Esta observação foi alterada em outro dispositivo depois da sua exclusão offline.");
        Long id = existing.getId();
        history.delete(existing.getItem().getId(), id);
        return new AppliedMutation(existing.getClientRequestId(), id, null, null);
    }

    private PriceSnapshot require(MutationCommand command) {
        return command.resolver().findPriceSnapshot(command.userId(), command.target())
                .orElseThrow(() -> ResourceResolver.remoteDeleted(
                        "Esta observação de preço não existe mais no servidor."));
    }

    /** The public representation, read back through the owner-scoped service. */
    private SnapshotResponse snapshotOf(PriceSnapshot snapshot) {
        return history.get(snapshot.getItem().getId(), snapshot.getId());
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
