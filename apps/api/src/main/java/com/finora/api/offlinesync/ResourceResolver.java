package com.finora.api.offlinesync;

import com.finora.api.budget.Budget;
import com.finora.api.budget.BudgetRepository;
import com.finora.api.goal.Goal;
import com.finora.api.goal.GoalRepository;
import com.finora.api.offlinesync.OfflineSyncDtos.ResourceTarget;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.wishlist.PriceSnapshot;
import com.finora.api.wishlist.PriceSnapshotRepository;
import com.finora.api.wishlist.PurchaseOption;
import com.finora.api.wishlist.PurchaseOptionRepository;
import com.finora.api.wishlist.WishlistItem;
import com.finora.api.wishlist.WishlistItemRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Turns the two kinds of resource reference a queued mutation may carry — a
 * server id, or the client id a resource was created with offline — into an
 * owned entity.
 *
 * <p>Every lookup is scoped to the authenticated owner, so a reference to
 * another user's resource behaves exactly as if it did not exist. That is a
 * deliberate information-hiding choice: "not yours" and "not there" must be
 * indistinguishable, otherwise the endpoint becomes an existence oracle over
 * other people's finances.
 *
 * <p>The two reference kinds fail differently on purpose. A missing
 * <em>server</em> id means the resource was deleted elsewhere — a conflict the
 * user must resolve. A missing <em>client</em> id means a parent created
 * offline has not been applied yet — a dependency to wait for, never a reason
 * to fabricate the parent.
 */
@Component
public class ResourceResolver {

    private final TransactionRepository transactions;
    private final BudgetRepository budgets;
    private final GoalRepository goals;
    private final WishlistItemRepository items;
    private final PurchaseOptionRepository options;
    private final PriceSnapshotRepository snapshots;

    public ResourceResolver(TransactionRepository transactions,
                            BudgetRepository budgets,
                            GoalRepository goals,
                            WishlistItemRepository items,
                            PurchaseOptionRepository options,
                            PriceSnapshotRepository snapshots) {
        this.transactions = transactions;
        this.budgets = budgets;
        this.goals = goals;
        this.items = items;
        this.options = options;
        this.snapshots = snapshots;
    }

    public Optional<Transaction> findTransaction(Long userId, ResourceTarget target) {
        return find(userId, target,
                id -> transactions.findByIdAndUserId(id, userId),
                client -> transactions.findByUserIdAndClientResourceId(userId, client));
    }

    public Optional<Budget> findBudget(Long userId, ResourceTarget target) {
        return find(userId, target,
                id -> budgets.findByIdAndUserId(id, userId),
                client -> budgets.findByUserIdAndClientResourceId(userId, client));
    }

    public Optional<Goal> findGoal(Long userId, ResourceTarget target) {
        return find(userId, target,
                id -> goals.findByIdAndUserId(id, userId),
                client -> goals.findByUserIdAndClientResourceId(userId, client));
    }

    public Optional<WishlistItem> findWishlistItem(Long userId, ResourceTarget target) {
        return find(userId, target,
                id -> items.findByIdAndUserId(id, userId),
                client -> items.findByUserIdAndClientResourceId(userId, client));
    }

    public Optional<PurchaseOption> findPurchaseOption(Long userId, ResourceTarget target) {
        return find(userId, target,
                id -> options.findByIdAndUserId(id, userId),
                client -> options.findByUserIdAndClientResourceId(userId, client));
    }

    /**
     * Price snapshots predate this stage and already carry an owner-scoped
     * {@code client_request_id}; V14 reuses it rather than adding a second,
     * redundant client identity column.
     */
    public Optional<PriceSnapshot> findPriceSnapshot(Long userId, ResourceTarget target) {
        return find(userId, target,
                id -> snapshots.findById(id).filter(s -> s.getUserId().equals(userId)),
                client -> snapshots.findByUserIdAndClientRequestId(userId, client));
    }

    /**
     * Resolves a <em>parent</em> wishlist item referenced by a child mutation.
     * A missing client id is a dependency to wait for; a missing server id is a
     * plain rejection, because the child cannot be attached to anything.
     */
    public WishlistItem requireParentItem(Long userId, ResourceTarget parent) {
        return findWishlistItem(userId, parent).orElseThrow(() -> parentFailure(parent,
                "O item da lista de desejos referenciado ainda não foi sincronizado.",
                "O item da lista de desejos referenciado não existe mais."));
    }

    /** Same contract as {@link #requireParentItem}, for purchase options. */
    public PurchaseOption requireParentOption(Long userId, ResourceTarget parent) {
        return findPurchaseOption(userId, parent).orElseThrow(() -> parentFailure(parent,
                "A opção de compra referenciada ainda não foi sincronizada.",
                "A opção de compra referenciada não existe mais."));
    }

    private static RuntimeException parentFailure(ResourceTarget parent, String pending, String gone) {
        if (parent.clientResourceId() != null) {
            return new DependencyMissingException("SYNC_DEPENDENCY_MISSING", pending);
        }
        return new SyncRejectedException("SYNC_DEPENDENCY_NOT_FOUND", gone);
    }

    /**
     * Signals that the target of an UPDATE or DELETE is gone. Recreating it
     * automatically is never correct: the user decides whether the deletion or
     * their offline edit wins.
     */
    public static SyncConflictException remoteDeleted(String detail) {
        return new SyncConflictException(ConflictType.REMOTE_DELETED, detail, null, null,
                List.of(ResolutionOption.KEEP_SERVER, ResolutionOption.DISCARD_LOCAL));
    }

    private <T> Optional<T> find(Long userId, ResourceTarget target,
                                 Function<Long, Optional<T>> byServerId,
                                 Function<UUID, Optional<T>> byClientId) {
        if (target == null) {
            return Optional.empty();
        }
        if (target.serverId() != null) {
            return byServerId.apply(target.serverId());
        }
        if (target.clientResourceId() != null) {
            return byClientId.apply(target.clientResourceId());
        }
        return Optional.empty();
    }
}
