package com.finora.api.statementimport;

import com.finora.api.common.error.NotFoundException;
import com.finora.api.statementimport.StatementImportDtos.ConfirmResponse;
import com.finora.api.statementimport.StatementImportDtos.ItemResult;
import com.finora.api.statementimport.StatementImportDtos.ItemResultCode;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Audited, idempotent import undo. Each imported item is undone
 * independently by {@link StatementMaterializationService#undo} (its own
 * transaction under the item row lock); the import ledger itself is never
 * deleted, and a second undo reports ALREADY_UNDONE instead of failing.
 * Releasing the strong identity after undo is the documented policy: a
 * future upload may deliberately import that row again.
 */
@Service
public class StatementUndoService {

    private static final Logger log = LoggerFactory.getLogger(StatementUndoService.class);

    private final StatementImportBatchRepository batches;
    private final StatementImportItemRepository items;
    private final StatementMaterializationService worker;
    private final StatementBatchStatusService batchStatus;
    private final com.finora.api.identity.CurrentUserProvider currentUser;

    public StatementUndoService(StatementImportBatchRepository batches,
                                StatementImportItemRepository items,
                                StatementMaterializationService worker,
                                StatementBatchStatusService batchStatus,
                                com.finora.api.identity.CurrentUserProvider currentUser) {
        this.batches = batches;
        this.items = items;
        this.worker = worker;
        this.batchStatus = batchStatus;
        this.currentUser = currentUser;
    }

    public ConfirmResponse undoItem(Long batchId, Long itemId) {
        Long userId = currentUser.currentUserId();
        requireBatch(batchId, userId);
        ItemResult result = undoSafely(userId, batchId, itemId);
        return batchStatus.undoOutcome(batchId, userId, List.of(result));
    }

    /** Undoes every imported item independently — one result per item. */
    public ConfirmResponse undoBatch(Long batchId) {
        Long userId = currentUser.currentUserId();
        requireBatch(batchId, userId);
        List<Long> importedIds = items
                .findAllByBatchIdAndUserIdOrderBySourceIndexAsc(batchId, userId).stream()
                .filter(item -> item.getStatus() == StatementImportItemStatus.IMPORTED)
                .map(StatementImportItem::getId)
                .toList();
        List<ItemResult> results = new ArrayList<>(importedIds.size());
        for (Long itemId : importedIds) {
            results.add(undoSafely(userId, batchId, itemId));
        }
        return batchStatus.undoOutcome(batchId, userId, results);
    }

    private ItemResult undoSafely(Long userId, Long batchId, Long itemId) {
        try {
            return worker.undo(userId, batchId, itemId);
        } catch (RuntimeException e) {
            log.error("Falha ao desfazer item {}", itemId, e);
            return new ItemResult(itemId, ItemResultCode.FAILED, null,
                    "STATEMENT_UNDO_FAILED", "Não foi possível desfazer este lançamento.");
        }
    }

    private void requireBatch(Long batchId, Long userId) {
        batches.findByIdAndUserId(batchId, userId)
                .orElseThrow(() -> new NotFoundException("Importação de extrato", batchId));
    }
}
