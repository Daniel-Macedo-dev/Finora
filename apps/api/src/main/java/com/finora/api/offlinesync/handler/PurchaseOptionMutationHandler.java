package com.finora.api.offlinesync.handler;

import com.finora.api.common.money.MoneyRules;
import com.finora.api.offlinesync.MutationHandler;
import com.finora.api.offlinesync.PayloadCodec;
import com.finora.api.offlinesync.ResourceResolver;
import com.finora.api.offlinesync.SyncOperation;
import com.finora.api.offlinesync.SyncRejectedException;
import com.finora.api.offlinesync.SyncResourceType;
import com.finora.api.offlinesync.VersionGuard;
import com.finora.api.offlinesync.handler.OfflinePayloads.OptionPayload;
import com.finora.api.wishlist.PurchaseOption;
import com.finora.api.wishlist.WishlistDtos.PurchaseOptionRequest;
import com.finora.api.wishlist.WishlistDtos.PurchaseOptionResponse;
import com.finora.api.wishlist.WishlistItem;
import com.finora.api.wishlist.WishlistService;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Offline CRUD for purchase options.
 *
 * <p>The parent item may be one that already exists on the server or one
 * created in the same outbox, addressed by its client identity. Everything else
 * — cash/installment shape, the one-cent-per-installment reconciliation
 * tolerance, shipping and fees, card ownership and the refusal of archived
 * cards — remains {@link WishlistService}'s. Cards themselves are never created
 * offline, so a card reference must already resolve to one of the owner's.
 */
@Component
public class PurchaseOptionMutationHandler implements MutationHandler {

    private final WishlistService wishlist;
    private final PayloadCodec codec;

    public PurchaseOptionMutationHandler(WishlistService wishlist, PayloadCodec codec) {
        this.wishlist = wishlist;
        this.codec = codec;
    }

    @Override
    public SyncResourceType resourceType() {
        return SyncResourceType.PURCHASE_OPTION;
    }

    @Override
    public Object canonicalize(SyncOperation operation, JsonNode payload) {
        if (operation == SyncOperation.DELETE) {
            codec.requireEmpty(payload);
            return null;
        }
        OptionPayload request = codec.parse(payload, OptionPayload.class);
        return new OptionPayload(
                request.item(),
                request.merchant().trim(),
                request.kind(),
                MoneyRules.normalize(request.basePrice()),
                MoneyRules.normalize(orZero(request.shipping())),
                MoneyRules.normalize(orZero(request.fees())),
                request.installmentCount(),
                request.installmentAmount() == null
                        ? null : MoneyRules.normalize(request.installmentAmount()),
                request.creditCardId(),
                request.notes() != null && !request.notes().isBlank() ? request.notes().trim() : null);
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
        OptionPayload payload = (OptionPayload) command.payload();
        if (payload.item() == null) {
            throw new SyncRejectedException("SYNC_OPTION_ITEM_REQUIRED",
                    "Informe o item da lista de desejos desta opção de compra.");
        }
        WishlistItem item = command.resolver().requireParentItem(command.userId(), payload.item());
        PurchaseOptionResponse created = wishlist.addOption(
                item.getId(), toRequest(payload), command.target().clientResourceId());
        return new AppliedMutation(command.target().clientResourceId(), created.id(),
                created.version(), created);
    }

    private AppliedMutation update(MutationCommand command) {
        PurchaseOption existing = require(command);
        VersionGuard.require(existing.getVersion(), command.baseVersion(),
                PurchaseOptionResponse.from(existing),
                "Esta opção de compra foi alterada em outro dispositivo depois da sua edição offline.");
        PurchaseOptionResponse updated = wishlist.updateOption(
                existing.getItem().getId(), existing.getId(),
                toRequest((OptionPayload) command.payload()));
        return new AppliedMutation(existing.getClientResourceId(), updated.id(),
                updated.version(), updated);
    }

    private AppliedMutation delete(MutationCommand command) {
        PurchaseOption existing = require(command);
        VersionGuard.require(existing.getVersion(), command.baseVersion(),
                PurchaseOptionResponse.from(existing),
                "Esta opção de compra foi alterada em outro dispositivo depois da sua exclusão offline.");
        Long id = existing.getId();
        wishlist.deleteOption(existing.getItem().getId(), id);
        return new AppliedMutation(existing.getClientResourceId(), id, null, null);
    }

    private PurchaseOption require(MutationCommand command) {
        return command.resolver().findPurchaseOption(command.userId(), command.target())
                .orElseThrow(() -> ResourceResolver.remoteDeleted(
                        "Esta opção de compra não existe mais no servidor."));
    }

    private static PurchaseOptionRequest toRequest(OptionPayload payload) {
        return new PurchaseOptionRequest(payload.merchant(), payload.kind(), payload.basePrice(),
                payload.shipping(), payload.fees(), payload.installmentCount(),
                payload.installmentAmount(), payload.creditCardId(), payload.notes());
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
