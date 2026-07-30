package com.finora.api.offlinesync.handler;

import com.finora.api.common.money.MoneyRules;
import com.finora.api.offlinesync.MutationHandler;
import com.finora.api.offlinesync.PayloadCodec;
import com.finora.api.offlinesync.ResourceResolver;
import com.finora.api.offlinesync.SyncOperation;
import com.finora.api.offlinesync.SyncResourceType;
import com.finora.api.offlinesync.VersionGuard;
import com.finora.api.wishlist.WishlistDtos.WishlistItemDetailResponse;
import com.finora.api.wishlist.WishlistDtos.WishlistItemRequest;
import com.finora.api.wishlist.WishlistItem;
import com.finora.api.wishlist.WishlistService;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Offline CRUD for wishlist items.
 *
 * <p>An item created here becomes a legitimate parent for purchase options and
 * price observations created in the same outbox, addressed by its client
 * identity until replay assigns a server id. Deletion keeps the documented
 * cascade — options and their observation history go with the item — which is
 * why the client asks for confirmation when an offline delete would take
 * unsynchronized children with it.
 */
@Component
public class WishlistItemMutationHandler implements MutationHandler {

    private final WishlistService wishlist;
    private final PayloadCodec codec;

    public WishlistItemMutationHandler(WishlistService wishlist, PayloadCodec codec) {
        this.wishlist = wishlist;
        this.codec = codec;
    }

    @Override
    public SyncResourceType resourceType() {
        return SyncResourceType.WISHLIST_ITEM;
    }

    @Override
    public Object canonicalize(SyncOperation operation, JsonNode payload) {
        if (operation == SyncOperation.DELETE) {
            codec.requireEmpty(payload);
            return null;
        }
        WishlistItemRequest request = codec.parse(payload, WishlistItemRequest.class);
        return new WishlistItemRequest(
                request.name().trim(),
                blankToNull(request.notes()),
                request.categoryId(),
                normalizeOrNull(request.referencePrice()),
                normalizeOrNull(request.targetPrice()),
                request.priority(),
                request.desiredDate(),
                request.status());
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
        WishlistItemDetailResponse created = wishlist.create(
                (WishlistItemRequest) command.payload(), command.target().clientResourceId());
        return new AppliedMutation(command.target().clientResourceId(), created.id(),
                created.version(), created);
    }

    private AppliedMutation update(MutationCommand command) {
        WishlistItem existing = require(command);
        VersionGuard.require(existing.getVersion(), command.baseVersion(),
                wishlist.get(existing.getId()),
                "Este item foi alterado em outro dispositivo depois da sua edição offline.");
        wishlist.update(existing.getId(), (WishlistItemRequest) command.payload());
        // The optimistic version is assigned at flush; read it only afterwards.
        command.flush().run();
        WishlistItemDetailResponse updated = wishlist.get(existing.getId());
        return new AppliedMutation(existing.getClientResourceId(), updated.id(),
                updated.version(), updated);
    }

    private AppliedMutation delete(MutationCommand command) {
        WishlistItem existing = require(command);
        VersionGuard.require(existing.getVersion(), command.baseVersion(),
                wishlist.get(existing.getId()),
                "Este item foi alterado em outro dispositivo depois da sua exclusão offline.");
        Long id = existing.getId();
        wishlist.delete(id);
        return new AppliedMutation(existing.getClientResourceId(), id, null, null);
    }

    private WishlistItem require(MutationCommand command) {
        return command.resolver().findWishlistItem(command.userId(), command.target())
                .orElseThrow(() -> ResourceResolver.remoteDeleted(
                        "Este item da lista de desejos não existe mais no servidor."));
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static BigDecimal normalizeOrNull(BigDecimal value) {
        return value != null ? MoneyRules.normalize(value) : null;
    }
}
