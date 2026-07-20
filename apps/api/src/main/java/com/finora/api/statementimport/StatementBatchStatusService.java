package com.finora.api.statementimport;

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
    private final StatementImportAssembler assembler;

    public StatementBatchStatusService(StatementImportBatchRepository batches,
                                       StatementImportItemRepository items,
                                       StatementImportAssembler assembler) {
        this.batches = batches;
        this.items = items;
        this.assembler = assembler;
    }

    /** COMPLETED when nothing importable or failed remains; else partial. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfirmResponse confirmOutcome(Long batchId, Long userId, List<ItemResult> results) {
        StatementImportBatch batch = batches.findByIdAndUserId(batchId, userId).orElseThrow();
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
                assembler.totals(batch, all));
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
        StatementImportBatch batch = batches.findByIdAndUserId(batchId, userId).orElseThrow();
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
                assembler.totals(batch, all));
    }
}
