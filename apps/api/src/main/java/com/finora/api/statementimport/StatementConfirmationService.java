package com.finora.api.statementimport;

import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.statementimport.StatementImportDtos.ConfirmRequest;
import com.finora.api.statementimport.StatementImportDtos.ConfirmResponse;
import com.finora.api.statementimport.StatementImportDtos.ItemResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent batch confirmation: items are materialized independently, each
 * in its own database transaction, so a single invalid row never silently
 * rolls back unrelated valid rows and no request ever holds one enormous
 * transaction across thousands of rows. Retrying (FAILED/SKIPPED items) and
 * repeating a confirmation are safe — the per-item claim plus the partial
 * unique index guarantee at most one live transaction per item.
 */
@Service
public class StatementConfirmationService {

    /** Bounded confirmation size per request; the frontend chunks above it. */
    static final int MAX_CONFIRM_ITEMS = 500;

    private static final Logger log = LoggerFactory.getLogger(StatementConfirmationService.class);

    private final StatementImportBatchRepository batches;
    private final StatementImportItemRepository items;
    private final StatementMaterializationService materialization;
    private final StatementBatchStatusService batchStatus;
    private final com.finora.api.identity.CurrentUserProvider currentUser;

    public StatementConfirmationService(StatementImportBatchRepository batches,
                                        StatementImportItemRepository items,
                                        StatementMaterializationService materialization,
                                        StatementBatchStatusService batchStatus,
                                        com.finora.api.identity.CurrentUserProvider currentUser) {
        this.batches = batches;
        this.items = items;
        this.materialization = materialization;
        this.batchStatus = batchStatus;
        this.currentUser = currentUser;
    }

    /**
     * Confirms the requested items (or every eligible one). Deliberately not
     * transactional at this level — each item commits or fails alone.
     */
    public ConfirmResponse confirm(Long batchId, ConfirmRequest request) {
        Long userId = currentUser.currentUserId();
        StatementImportBatch batch = batches.findByIdAndUserId(batchId, userId)
                .orElseThrow(() -> new NotFoundException("Importação de extrato", batchId));
        if (batch.getStatus() != StatementImportStatus.PREVIEW_READY
                && batch.getStatus() != StatementImportStatus.PARTIALLY_COMPLETED
                && batch.getStatus() != StatementImportStatus.COMPLETED) {
            throw new BusinessRuleException("STATEMENT_BATCH_NOT_CONFIRMABLE",
                    batch.getStatus() == StatementImportStatus.NEEDS_MAPPING
                            ? "Configure e processe o mapeamento das colunas antes de confirmar."
                            : "Este lote não está em uma etapa confirmável.");
        }

        List<StatementImportItem> all =
                items.findAllByBatchIdAndUserIdOrderBySourceIndexAsc(batch.getId(), userId);
        List<Long> targetIds;
        if (request != null && request.itemIds() != null && !request.itemIds().isEmpty()) {
            var requested = new java.util.LinkedHashSet<>(request.itemIds());
            targetIds = all.stream()
                    .map(StatementImportItem::getId)
                    .filter(requested::contains)
                    .toList();
            if (targetIds.size() != requested.size()) {
                throw new NotFoundException(
                        "Um ou mais lançamentos não foram encontrados neste lote.");
            }
        } else {
            // Every included pending item is targeted — duplicates and other
            // non-importable rows get structured results instead of being
            // silently omitted.
            targetIds = all.stream()
                    .filter(item -> item.isIncluded()
                            && (item.getStatus() == StatementImportItemStatus.READY
                                    || item.getStatus() == StatementImportItemStatus.FAILED
                                    || item.getStatus() == StatementImportItemStatus.SKIPPED))
                    .map(StatementImportItem::getId)
                    .toList();
        }
        if (targetIds.size() > MAX_CONFIRM_ITEMS) {
            throw new BusinessRuleException("STATEMENT_CONFIRM_TOO_LARGE",
                    "Confirme no máximo %d lançamentos por requisição."
                            .formatted(MAX_CONFIRM_ITEMS));
        }

        List<ItemResult> results = new ArrayList<>(targetIds.size());
        for (Long itemId : targetIds) {
            results.add(materializeSafely(userId, batch.getId(), itemId));
        }
        return batchStatus.confirmOutcome(batch.getId(), userId, results);
    }

    private ItemResult materializeSafely(Long userId, Long batchId, Long itemId) {
        try {
            return materialization.materialize(userId, batchId, itemId);
        } catch (DataIntegrityViolationException e) {
            // Unique-index race: another confirmation may have won this item.
            ItemResult raced = materialization.afterRace(userId, itemId);
            if (raced != null) {
                return raced;
            }
            log.warn("Conflito de integridade ao importar item {}: {}", itemId,
                    e.getMostSpecificCause().getMessage());
            return materialization.markFailed(userId, itemId, "STATEMENT_ITEM_CONFLICT",
                    "O lançamento conflita com dados existentes.");
        } catch (RuntimeException e) {
            // The item's own transaction already rolled back — no orphan
            // transaction exists. Record the failure without internals.
            log.error("Falha ao importar item {}", itemId, e);
            return materialization.markFailed(userId, itemId, "STATEMENT_ITEM_FAILED",
                    "Não foi possível importar este lançamento.");
        }
    }

}
