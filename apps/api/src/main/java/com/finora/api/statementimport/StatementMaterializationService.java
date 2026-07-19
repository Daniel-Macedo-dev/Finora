package com.finora.api.statementimport;

import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.category.Category;
import com.finora.api.category.CategoryRepository;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.statementimport.StatementImportDtos.ItemResult;
import com.finora.api.statementimport.StatementImportDtos.ItemResultCode;
import com.finora.api.transaction.PaymentMethod;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materializes one confirmed import item into one ordinary transaction —
 * atomically, in its own transaction, so one bad row never rolls back its
 * siblings and no orphan transaction can survive a failure.
 *
 * <p>Never calls HTTP endpoints: it reuses the transaction-domain rules
 * directly (owner-scoped category/account resolution, type compatibility,
 * money normalization) and writes the immutable
 * {@code statement_import_item_id} link whose partial unique index is the
 * database backstop against double materialization under concurrency.
 */
@Service
public class StatementMaterializationService {

    private final StatementImportItemRepository items;
    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final CategoryMappingRuleRepository rules;

    public StatementMaterializationService(StatementImportItemRepository items,
                                           TransactionRepository transactions,
                                           AccountRepository accounts,
                                           CategoryRepository categories,
                                           CategoryMappingRuleRepository rules) {
        this.items = items;
        this.transactions = transactions;
        this.accounts = accounts;
        this.categories = categories;
        this.rules = rules;
    }

    /**
     * Claims and materializes one item. Runs in its own transaction
     * (REQUIRES_NEW): a thrown failure rolls back only this item's work.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ItemResult materialize(Long userId, Long batchId, Long itemId) {
        StatementImportItem item = items.lockByIdAndUserId(itemId, userId).orElse(null);
        if (item == null || !Objects.equals(item.getBatchId(), batchId)) {
            return new ItemResult(itemId, ItemResultCode.FAILED, null,
                    "STATEMENT_ITEM_NOT_FOUND", "Lançamento não encontrado neste lote.");
        }
        // Idempotency: a repeated confirmation returns the existing result
        // instead of creating another transaction.
        if (item.getStatus() == StatementImportItemStatus.IMPORTED) {
            Long transactionId = transactions
                    .findByUserIdAndStatementImportItemId(userId, item.getId())
                    .map(Transaction::getId).orElse(null);
            return new ItemResult(item.getId(), ItemResultCode.ALREADY_IMPORTED, transactionId,
                    "STATEMENT_ALREADY_IMPORTED", "Este lançamento já foi importado.");
        }
        if (item.getStatus() == StatementImportItemStatus.UNDONE) {
            return skip(item, ItemResultCode.SKIPPED, "STATEMENT_ITEM_UNDONE",
                    "Este lançamento foi desfeito e não é importado novamente neste lote.");
        }
        if (item.getStatus() == StatementImportItemStatus.INVALID) {
            return skip(item, ItemResultCode.SKIPPED, "STATEMENT_ITEM_INVALID",
                    "Lançamento inválido não pode ser importado.");
        }
        if (!item.isIncluded()) {
            return skip(item, ItemResultCode.SKIPPED, "STATEMENT_ITEM_EXCLUDED",
                    "Lançamento excluído da importação.");
        }
        if (item.getDuplicateStatus() == DuplicateStatus.EXACT_DUPLICATE) {
            return recordSkip(item, ItemResultCode.EXACT_DUPLICATE, "STATEMENT_EXACT_DUPLICATE",
                    "Um lançamento com esta identidade já foi importado nesta conta.");
        }
        if (item.getDuplicateStatus() == DuplicateStatus.POSSIBLE_DUPLICATE
                && !item.isDuplicateOverride()) {
            return recordSkip(item, ItemResultCode.SKIPPED, "STATEMENT_POSSIBLE_DUPLICATE",
                    "Possível duplicidade: decida entre pular ou importar mesmo assim.");
        }
        if (item.getPostedDate() == null || item.getAmount() == null || item.getType() == null
                || item.getDescription() == null || item.getDescription().isBlank()) {
            return fail(item, "STATEMENT_ITEM_INCOMPLETE",
                    "O lançamento não possui todos os campos obrigatórios.");
        }
        if (item.getSelectedCategoryId() == null) {
            return fail(item, "STATEMENT_CATEGORY_MISSING",
                    "Escolha uma categoria para este lançamento.");
        }
        Account account = accounts.findByIdAndUserId(item.getAccountId(), userId).orElse(null);
        if (account == null || account.isArchived()) {
            return fail(item, "STATEMENT_ACCOUNT_UNAVAILABLE",
                    "A conta de destino não está mais disponível.");
        }
        Category category = categories.findByIdAndUserId(item.getSelectedCategoryId(), userId)
                .orElse(null);
        if (category == null || !category.isActive()
                || !category.getType().name().equals(item.getType().name())) {
            return fail(item, "STATEMENT_CATEGORY_INCOMPATIBLE",
                    "A categoria selecionada não é mais compatível com este lançamento.");
        }
        // Last-line exact-duplicate recheck under the row lock (the partial
        // unique index on external id remains the database backstop).
        if (item.getExternalId() != null
                && !items.findImportedExternalIds(userId, item.getAccountId(),
                        java.util.List.of(item.getExternalId())).isEmpty()) {
            item.setDuplicateStatus(DuplicateStatus.EXACT_DUPLICATE);
            return recordSkip(item, ItemResultCode.EXACT_DUPLICATE, "STATEMENT_EXACT_DUPLICATE",
                    "Um lançamento com esta identidade já foi importado nesta conta.");
        }

        Transaction transaction = new Transaction(userId, item.getType(),
                MoneyRules.normalize(item.getAmount()), item.getDescription(),
                item.getPostedDate(), category);
        transaction.setAccount(account);
        // A bank statement cannot reveal the instrument; OTHER is the honest
        // non-card representation (never the legacy CREDIT method).
        transaction.setPaymentMethod(PaymentMethod.OTHER);
        transaction.setNotes(item.getMemo());
        transaction.setStatementImportItemId(item.getId());
        transaction = transactions.save(transaction);

        item.setStatus(StatementImportItemStatus.IMPORTED);
        item.setImportedAt(Instant.now());
        item.setResult("STATEMENT_IMPORTED", "Lançamento importado.");
        if (item.getMatchedRuleId() != null
                && Objects.equals(item.getSuggestedCategoryId(), item.getSelectedCategoryId())) {
            rules.findByIdAndUserId(item.getMatchedRuleId(), userId)
                    .ifPresent(rule -> rule.recordUse(Instant.now()));
        }
        return new ItemResult(item.getId(), ItemResultCode.SUCCESS, transaction.getId(),
                "STATEMENT_IMPORTED", "Lançamento importado.");
    }

    /**
     * Marks one item FAILED in its own transaction — used by the
     * confirmation orchestrator after a materialization rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ItemResult markFailed(Long userId, Long itemId, String code, String message) {
        items.lockByIdAndUserId(itemId, userId).ifPresent(item -> {
            if (item.getStatus() == StatementImportItemStatus.READY
                    || item.getStatus() == StatementImportItemStatus.FAILED
                    || item.getStatus() == StatementImportItemStatus.SKIPPED) {
                item.setStatus(StatementImportItemStatus.FAILED);
                item.setResult(code, message);
            }
        });
        return new ItemResult(itemId, ItemResultCode.FAILED, null, code, message);
    }

    /**
     * Re-reads an item after a unique-index race: when another confirmation
     * won, the result is ALREADY_IMPORTED instead of a spurious failure.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ItemResult afterRace(Long userId, Long itemId) {
        return items.findByIdAndUserId(itemId, userId)
                .filter(item -> item.getStatus() == StatementImportItemStatus.IMPORTED)
                .map(item -> new ItemResult(itemId, ItemResultCode.ALREADY_IMPORTED,
                        transactions.findByUserIdAndStatementImportItemId(userId, itemId)
                                .map(Transaction::getId).orElse(null),
                        "STATEMENT_ALREADY_IMPORTED", "Este lançamento já foi importado."))
                .orElse(null);
    }

    private ItemResult fail(StatementImportItem item, String code, String message) {
        item.setStatus(StatementImportItemStatus.FAILED);
        item.setResult(code, message);
        return new ItemResult(item.getId(), ItemResultCode.FAILED, null, code, message);
    }

    /** A skip that should persist on the item (duplicate decisions). */
    private ItemResult recordSkip(StatementImportItem item, ItemResultCode result, String code,
                                  String message) {
        item.setStatus(StatementImportItemStatus.SKIPPED);
        item.setResult(code, message);
        return new ItemResult(item.getId(), result, null, code, message);
    }

    /** A skip that leaves the item state untouched (not requested/eligible). */
    private ItemResult skip(StatementImportItem item, ItemResultCode result, String code,
                            String message) {
        return new ItemResult(item.getId(), result, null, code, message);
    }
}
