package com.finora.api.offlinesync.handler;

import com.finora.api.common.money.MoneyRules;
import com.finora.api.goal.Goal;
import com.finora.api.goal.GoalDtos.GoalRequest;
import com.finora.api.goal.GoalDtos.GoalResponse;
import com.finora.api.goal.GoalService;
import com.finora.api.offlinesync.MutationHandler;
import com.finora.api.offlinesync.PayloadCodec;
import com.finora.api.offlinesync.ResourceResolver;
import com.finora.api.offlinesync.SyncOperation;
import com.finora.api.offlinesync.SyncResourceType;
import com.finora.api.offlinesync.VersionGuard;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Offline CRUD for savings goals.
 *
 * <p>Only the goal's own fields move through here. The contribution endpoint is
 * deliberately unreachable: a contribution is an <em>event</em> applied to
 * whatever balance the goal currently holds, so replaying one hours later would
 * add to a number the user never saw. Setting {@code currentAmount} through an
 * ordinary update is a different, explicit statement — "the balance is this" —
 * and that is what a queued edit is allowed to say.
 */
@Component
public class GoalMutationHandler implements MutationHandler {

    private final GoalService goals;
    private final PayloadCodec codec;

    public GoalMutationHandler(GoalService goals, PayloadCodec codec) {
        this.goals = goals;
        this.codec = codec;
    }

    @Override
    public SyncResourceType resourceType() {
        return SyncResourceType.GOAL;
    }

    @Override
    public Object canonicalize(SyncOperation operation, JsonNode payload) {
        if (operation == SyncOperation.DELETE) {
            codec.requireEmpty(payload);
            return null;
        }
        GoalRequest request = codec.parse(payload, GoalRequest.class);
        return new GoalRequest(
                request.name().trim(),
                MoneyRules.normalize(request.targetAmount()),
                request.currentAmount() == null ? null : MoneyRules.normalize(request.currentAmount()),
                request.targetDate(),
                // Upper-cased for a stable fingerprint; left null when absent so
                // a pre-multi-currency entry keeps its legacy canonical shape.
                request.currency() == null || request.currency().isBlank()
                        ? null
                        : request.currency().trim().toUpperCase(java.util.Locale.ROOT),
                request.archived());
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
        GoalResponse created = goals.create(
                (GoalRequest) command.payload(), command.target().clientResourceId());
        return new AppliedMutation(command.target().clientResourceId(), created.id(),
                created.version(), created);
    }

    private AppliedMutation update(MutationCommand command) {
        Goal existing = require(command);
        VersionGuard.require(existing.getVersion(), command.baseVersion(), goals.get(existing.getId()),
                "Esta meta foi alterada em outro dispositivo depois da sua edição offline.");
        goals.update(existing.getId(), (GoalRequest) command.payload());
        // The optimistic version is assigned at flush; read it only afterwards.
        command.flush().run();
        GoalResponse updated = goals.get(existing.getId());
        return new AppliedMutation(existing.getClientResourceId(), updated.id(),
                updated.version(), updated);
    }

    private AppliedMutation delete(MutationCommand command) {
        Goal existing = require(command);
        VersionGuard.require(existing.getVersion(), command.baseVersion(), goals.get(existing.getId()),
                "Esta meta foi alterada em outro dispositivo depois da sua exclusão offline.");
        Long id = existing.getId();
        goals.delete(id);
        return new AppliedMutation(existing.getClientResourceId(), id, null, null);
    }

    private Goal require(MutationCommand command) {
        return command.resolver().findGoal(command.userId(), command.target())
                .orElseThrow(() -> ResourceResolver.remoteDeleted(
                        "Esta meta não existe mais no servidor."));
    }
}
