package com.finora.api.offlinesync.handler;

import com.finora.api.budget.Budget;
import com.finora.api.budget.BudgetDtos.BudgetRequest;
import com.finora.api.budget.BudgetDtos.BudgetResponse;
import com.finora.api.budget.BudgetRepository;
import com.finora.api.budget.BudgetService;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.offlinesync.ConflictType;
import com.finora.api.offlinesync.MutationHandler;
import com.finora.api.offlinesync.PayloadCodec;
import com.finora.api.offlinesync.ResolutionOption;
import com.finora.api.offlinesync.ResourceResolver;
import com.finora.api.offlinesync.SyncConflictException;
import com.finora.api.offlinesync.SyncOperation;
import com.finora.api.offlinesync.SyncResourceType;
import com.finora.api.offlinesync.VersionGuard;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Offline CRUD for monthly budgets.
 *
 * <p>Month/category uniqueness is the interesting case. Two devices can both
 * budget "Groceries, March" while offline; whichever replays second must not
 * quietly overwrite the other's limit, and must not create a second budget for
 * the same key either. It gets a {@link ConflictType#RESOURCE_ALREADY_EXISTS}
 * carrying the budget that already exists, so the user can compare the two
 * limits and choose. A CREATE is never silently promoted to an UPDATE.
 */
@Component
public class BudgetMutationHandler implements MutationHandler {

    private final BudgetService budgets;
    private final BudgetRepository repository;
    private final PayloadCodec codec;

    public BudgetMutationHandler(BudgetService budgets, BudgetRepository repository,
                                 PayloadCodec codec) {
        this.budgets = budgets;
        this.repository = repository;
        this.codec = codec;
    }

    @Override
    public SyncResourceType resourceType() {
        return SyncResourceType.BUDGET;
    }

    @Override
    public Object canonicalize(SyncOperation operation, JsonNode payload) {
        if (operation == SyncOperation.DELETE) {
            codec.requireEmpty(payload);
            return null;
        }
        BudgetRequest request = codec.parse(payload, BudgetRequest.class);
        return new BudgetRequest(request.month(), request.categoryId(),
                MoneyRules.normalize(request.limitAmount()));
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
        BudgetRequest request = (BudgetRequest) command.payload();
        // Checked before delegating so the answer is a comparable conflict
        // rather than the generic "already exists" rejection the online form
        // shows — the offline user has a competing limit to reconcile.
        repository.findByUserIdAndMonthRefAndCategoryId(
                        command.userId(), request.month().atDay(1), request.categoryId())
                .ifPresent(existing -> {
                    throw new SyncConflictException(ConflictType.RESOURCE_ALREADY_EXISTS,
                            "Já existe um orçamento para essa categoria nesse mês, criado em "
                                    + "outro dispositivo.",
                            existing.getVersion(), budgets.get(existing.getId()),
                            List.of(ResolutionOption.KEEP_SERVER, ResolutionOption.EDIT_AND_RETRY,
                                    ResolutionOption.DISCARD_LOCAL));
                });
        BudgetResponse created = budgets.create(request, command.target().clientResourceId());
        return new AppliedMutation(command.target().clientResourceId(), created.id(),
                created.version(), created);
    }

    private AppliedMutation update(MutationCommand command) {
        Budget existing = require(command);
        VersionGuard.require(existing.getVersion(), command.baseVersion(),
                budgets.get(existing.getId()),
                "Este orçamento foi alterado em outro dispositivo depois da sua edição offline.");
        budgets.update(existing.getId(), (BudgetRequest) command.payload());
        // The optimistic version is assigned at flush; read it only afterwards.
        command.flush().run();
        BudgetResponse updated = budgets.get(existing.getId());
        return new AppliedMutation(existing.getClientResourceId(), updated.id(),
                updated.version(), updated);
    }

    private AppliedMutation delete(MutationCommand command) {
        Budget existing = require(command);
        VersionGuard.require(existing.getVersion(), command.baseVersion(),
                budgets.get(existing.getId()),
                "Este orçamento foi alterado em outro dispositivo depois da sua exclusão offline.");
        Long id = existing.getId();
        budgets.delete(id);
        return new AppliedMutation(existing.getClientResourceId(), id, null, null);
    }

    private Budget require(MutationCommand command) {
        return command.resolver().findBudget(command.userId(), command.target())
                .orElseThrow(() -> ResourceResolver.remoteDeleted(
                        "Este orçamento não existe mais no servidor."));
    }
}
