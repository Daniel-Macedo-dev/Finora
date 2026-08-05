package com.finora.api.offlinesync.handler;

import com.finora.api.common.money.MoneyRules;
import com.finora.api.offlinesync.MutationHandler;
import com.finora.api.offlinesync.PayloadCodec;
import com.finora.api.offlinesync.ResourceResolver;
import com.finora.api.offlinesync.SyncOperation;
import com.finora.api.offlinesync.SyncRejectedException;
import com.finora.api.offlinesync.SyncResourceType;
import com.finora.api.offlinesync.VersionGuard;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionDtos.TransactionRequest;
import com.finora.api.transaction.TransactionDtos.TransactionResponse;
import com.finora.api.transaction.TransactionService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Offline CRUD for ordinary, user-entered transactions.
 *
 * <p>Every financial rule — category ownership and type match, account
 * ownership, money normalization, the refusal of new generic CREDIT spending,
 * field limits — stays in {@link TransactionService}; this handler only
 * translates a queued envelope into those calls.
 *
 * <p>What it does add is an offline-specific refusal list. Online, editing a
 * transaction that came from a statement import is a reasonable thing to do
 * next to the import ledger that explains it. Replayed blindly from a queue,
 * hours later, against a row that another workflow owns, it is not: the
 * import, recurring, wishlist and legacy-credit ledgers each maintain their own
 * audit trail and their own idempotency anchors, and a sync write would desync
 * them. Those records are therefore untouchable through this endpoint.
 */
@Component
public class TransactionMutationHandler implements MutationHandler {

    private final TransactionService transactions;
    private final PayloadCodec codec;

    public TransactionMutationHandler(TransactionService transactions, PayloadCodec codec) {
        this.transactions = transactions;
        this.codec = codec;
    }

    @Override
    public SyncResourceType resourceType() {
        return SyncResourceType.TRANSACTION;
    }

    @Override
    public Object canonicalize(SyncOperation operation, JsonNode payload) {
        if (operation == SyncOperation.DELETE) {
            codec.requireEmpty(payload);
            return null;
        }
        return normalize(codec.parse(payload, TransactionRequest.class));
    }

    /**
     * Normalizes exactly as the service will, so the fingerprint of a retry
     * cannot differ from the fingerprint of the original because of whitespace
     * or a trailing zero.
     */
    private static TransactionRequest normalize(TransactionRequest request) {
        return new TransactionRequest(
                request.type(),
                MoneyRules.normalize(request.amount()),
                request.description().trim(),
                request.date(),
                request.categoryId(),
                request.accountId(),
                // Upper-cased so "usd" and "USD" from different client builds
                // cannot fingerprint as two different requests.
                request.currency() == null || request.currency().isBlank()
                        ? null
                        : request.currency().trim().toUpperCase(java.util.Locale.ROOT),
                request.paymentMethod(),
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
        TransactionRequest request = (TransactionRequest) command.payload();
        TransactionResponse created = transactions.create(
                request, command.target().clientResourceId());
        return new AppliedMutation(command.target().clientResourceId(), created.id(),
                created.version(), created);
    }

    private AppliedMutation update(MutationCommand command) {
        Transaction existing = require(command);
        requireOrdinary(existing);
        VersionGuard.require(existing.getVersion(), command.baseVersion(),
                TransactionResponse.from(existing),
                "Esta transação foi alterada em outro dispositivo depois da sua edição offline.");
        transactions.update(existing.getId(), (TransactionRequest) command.payload());
        // The optimistic version is assigned at flush; read it only afterwards.
        command.flush().run();
        TransactionResponse updated = TransactionResponse.from(existing);
        return new AppliedMutation(existing.getClientResourceId(), updated.id(),
                updated.version(), updated);
    }

    private AppliedMutation delete(MutationCommand command) {
        Transaction existing = require(command);
        requireOrdinary(existing);
        VersionGuard.require(existing.getVersion(), command.baseVersion(),
                TransactionResponse.from(existing),
                "Esta transação foi alterada em outro dispositivo depois da sua exclusão offline.");
        Long id = existing.getId();
        transactions.delete(id);
        return new AppliedMutation(existing.getClientResourceId(), id, null, null);
    }

    private Transaction require(MutationCommand command) {
        return command.resolver().findTransaction(command.userId(), command.target())
                .orElseThrow(() -> ResourceResolver.remoteDeleted(
                        "Esta transação não existe mais no servidor."));
    }

    /**
     * Refuses every transaction another workflow generated and still owns.
     * Each of these is undone or edited through the ledger that created it.
     */
    private static void requireOrdinary(Transaction transaction) {
        if (transaction.getStatementImportItemId() != null) {
            throw new SyncRejectedException("SYNC_TRANSACTION_IMPORTED",
                    "Esta transação veio de um extrato importado e só pode ser alterada "
                            + "na área Importar extrato, com conexão.");
        }
        if (transaction.getCommitmentId() != null) {
            throw new SyncRejectedException("SYNC_TRANSACTION_FROM_RECURRING",
                    "Esta transação foi gerada por um recorrente e só pode ser alterada "
                            + "na área de Recorrentes, com conexão.");
        }
        if (transaction.getWishlistItemId() != null) {
            throw new SyncRejectedException("SYNC_TRANSACTION_FROM_WISHLIST",
                    "Esta transação foi gerada pela compra de um item da lista de desejos "
                            + "e só pode ser alterada com conexão.");
        }
        // A financially inactive row is always a legacy-credit one (V10 enforces
        // that invariant), so the more specific reason has to be checked first
        // or it would never be reported.
        if (!transaction.isFinanciallyActive()) {
            throw new SyncRejectedException("SYNC_TRANSACTION_CONVERTED",
                    "Esta transação foi convertida em compra de cartão e é um registro "
                            + "de auditoria. Estorne a conversão com conexão para editá-la.");
        }
        if (transaction.isLegacyCredit()) {
            throw new SyncRejectedException("SYNC_TRANSACTION_LEGACY_CREDIT",
                    "Esta transação é um registro histórico de crédito e só pode ser "
                            + "alterada com conexão.");
        }
    }
}
