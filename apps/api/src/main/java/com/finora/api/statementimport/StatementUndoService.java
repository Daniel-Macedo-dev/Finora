package com.finora.api.statementimport;

import com.finora.api.common.error.NotFoundException;
import com.finora.api.statementimport.StatementImportDtos.ConfirmResponse;
import com.finora.api.statementimport.StatementImportDtos.ItemResult;
import com.finora.api.statementimport.StatementImportDtos.ItemResultCode;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audited, idempotent import undo. The generated transaction is removed
 * (its financial effect disappears exactly once); the import item is
 * preserved as the audit record, marked UNDONE with its timestamp. The
 * ledger itself is never deleted, and the strong identity is released —
 * a documented policy: after an undo, a future upload may import that row
 * again as a deliberate user action.
 */
@Service
public class StatementUndoService {

    private static final Logger log = LoggerFactory.getLogger(StatementUndoService.class);

    private final StatementImportBatchRepository batches;
    private final StatementImportItemRepository items;
    private final TransactionRepository transactions;
    private final StatementImportAssembler assembler;
    private final com.finora.api.identity.CurrentUserProvider currentUser;

    public StatementUndoService(StatementImportBatchRepository batches,
                                StatementImportItemRepository items,
                                TransactionRepository transactions,
                                StatementImportAssembler assembler,
                                com.finora.api.identity.CurrentUserProvider currentUser) {
        this.batches = batches;
        this.items = items;
        this.transactions = transactions;
        this.assembler = assembler;
        this.currentUser = currentUser;
    }

    public ConfirmResponse undoItem(Long batchId, Long itemId) {
        Long userId = currentUser.currentUserId();
        requireBatch(batchId, userId);
        ItemResult result = undoSafely(userId, batchId, itemId);
        return finish(batchId, userId, List.of(result));
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
        return finish(batchId, userId, results);
    }

    private ItemResult undoSafely(Long userId, Long batchId, Long itemId) {
        try {
            return undoOne(userId, batchId, itemId);
        } catch (RuntimeException e) {
            log.error("Falha ao desfazer item {}", itemId, e);
            return new ItemResult(itemId, ItemResultCode.FAILED, null,
                    "STATEMENT_UNDO_FAILED", "Não foi possível desfazer este lançamento.");
        }
    }

    /** One undo in its own transaction, claimed under the item row lock. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected ItemResult undoOne(Long userId, Long batchId, Long itemId) {
        StatementImportItem item = items.lockByIdAndUserId(itemId, userId).orElse(null);
        if (item == null || !Objects.equals(item.getBatchId(), batchId)) {
            return new ItemResult(itemId, ItemResultCode.FAILED, null,
                    "STATEMENT_ITEM_NOT_FOUND", "Lançamento não encontrado neste lote.");
        }
        if (item.getStatus() == StatementImportItemStatus.UNDONE) {
            return new ItemResult(item.getId(), ItemResultCode.ALREADY_UNDONE, null,
                    "STATEMENT_ALREADY_UNDONE", "Este lançamento já havia sido desfeito.");
        }
        if (item.getStatus() != StatementImportItemStatus.IMPORTED) {
            return new ItemResult(item.getId(), ItemResultCode.SKIPPED, null,
                    "STATEMENT_ITEM_NOT_IMPORTED", "Apenas lançamentos importados podem ser desfeitos.");
        }
        Transaction transaction = transactions
                .findByUserIdAndStatementImportItemId(userId, item.getId())
                .orElse(null);
        if (transaction != null) {
            // Defensive: a generated transaction that became an anchor for
            // another domain would corrupt that domain if removed here.
            if (transaction.getCommitmentId() != null || transaction.getWishlistItemId() != null
                    || !transaction.isFinanciallyActive()) {
                return new ItemResult(item.getId(), ItemResultCode.BLOCKED, transaction.getId(),
                        "STATEMENT_UNDO_BLOCKED",
                        "Esta transação está vinculada a outra área do Finora e não pode ser "
                                + "desfeita pela importação.");
            }
            transactions.delete(transaction);
        }
        item.setStatus(StatementImportItemStatus.UNDONE);
        item.setUndoneAt(Instant.now());
        item.setResult("STATEMENT_UNDONE", "Importação desfeita.");
        return new ItemResult(item.getId(), ItemResultCode.UNDONE, null,
                "STATEMENT_UNDONE", "Importação desfeita.");
    }

    /** Batch becomes UNDONE only when no imported item remains. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected ConfirmResponse finish(Long batchId, Long userId, List<ItemResult> results) {
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

    private void requireBatch(Long batchId, Long userId) {
        batches.findByIdAndUserId(batchId, userId)
                .orElseThrow(() -> new NotFoundException("Importação de extrato", batchId));
    }
}
