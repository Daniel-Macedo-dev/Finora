package com.finora.api.statementimport;

import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.statementimport.StatementImportDtos.ConfirmResponse;
import com.finora.api.statementimport.StatementImportDtos.ItemResult;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes a batch's lifecycle truthfully from its items after a
 * confirmation or undo run. Lives in its own bean so the orchestrators call
 * it through the transaction proxy (never self-invocation).
 */
@Service
public class StatementBatchStatusService {

    private final StatementImportBatchRepository batches;
    private final StatementImportItemRepository items;
    private final AccountRepository accounts;
    private final StatementImportAssembler assembler;

    public StatementBatchStatusService(StatementImportBatchRepository batches,
                                       StatementImportItemRepository items,
                                       AccountRepository accounts,
                                       StatementImportAssembler assembler) {
        this.batches = batches;
        this.items = items;
        this.accounts = accounts;
        this.assembler = assembler;
    }

    /**
     * The batch's denomination, read once per outcome rather than per item.
     * Owner-scoped: a batch is tied by a composite foreign key to an account of
     * the same user, so this can only be absent if the database is inconsistent.
     */
    private CurrencyCode currencyOf(StatementImportBatch batch) {
        return accounts.findByIdAndUserId(batch.getAccountId(), batch.getUserId())
                .map(Account::getCurrency)
                .orElseThrow(() -> new IllegalStateException(
                        "Lote de importação sem conta de destino acessível: "
                                + batch.getAccountId()));
    }

    /** COMPLETED when nothing importable or failed remains; else partial. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfirmResponse confirmOutcome(Long batchId, Long userId, List<ItemResult> results) {
        StatementImportBatch batch = batches.lockByIdAndUserId(batchId, userId).orElseThrow();
        List<StatementImportItem> all =
                items.findAllByBatchIdAndUserIdOrderBySourceIndexAsc(batchId, userId);
        long imported = all.stream()
                .filter(item -> item.getStatus() == StatementImportItemStatus.IMPORTED)
                .count();
        boolean pendingWork = all.stream().anyMatch(item ->
                item.getStatus() == StatementImportItemStatus.FAILED
                        || StatementImportAssembler.importable(item)
                        || pendingDuplicateDecision(item));
        if (imported > 0) {
            batch.setStatus(pendingWork ? StatementImportStatus.PARTIALLY_COMPLETED
                    : StatementImportStatus.COMPLETED);
            if (batch.getConfirmedAt() == null) {
                batch.setConfirmedAt(Instant.now());
            }
        }
        return new ConfirmResponse(batch.getId(), batch.getStatus(), List.copyOf(results),
                assembler.totals(batch, all, currencyOf(batch)));
    }

    /**
     * A skipped possible duplicate that the user never decided on keeps the
     * batch PARTIALLY_COMPLETED (and therefore editable): "importar mesmo
     * assim" must remain available after the first confirmation run.
     */
    private static boolean pendingDuplicateDecision(StatementImportItem item) {
        return item.getStatus() == StatementImportItemStatus.SKIPPED
                && item.isIncluded()
                && item.getDuplicateStatus() == DuplicateStatus.POSSIBLE_DUPLICATE
                && !item.isDuplicateOverride();
    }

    /** UNDONE only when a confirmed batch keeps no imported item. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfirmResponse undoOutcome(Long batchId, Long userId, List<ItemResult> results) {
        StatementImportBatch batch = batches.lockByIdAndUserId(batchId, userId).orElseThrow();
        List<StatementImportItem> all =
                items.findAllByBatchIdAndUserIdOrderBySourceIndexAsc(batchId, userId);
        boolean anyImported = all.stream()
                .anyMatch(item -> item.getStatus() == StatementImportItemStatus.IMPORTED);
        boolean anyUndone = all.stream()
                .anyMatch(item -> item.getStatus() == StatementImportItemStatus.UNDONE);
        if (!anyImported && anyUndone && batch.getConfirmedAt() != null
                && batch.getStatus() != StatementImportStatus.UNDONE) {
            batch.setStatus(StatementImportStatus.UNDONE);
            batch.setUndoneAt(Instant.now());
        }
        return new ConfirmResponse(batch.getId(), batch.getStatus(), List.copyOf(results),
                assembler.totals(batch, all, currencyOf(batch)));
    }
}
