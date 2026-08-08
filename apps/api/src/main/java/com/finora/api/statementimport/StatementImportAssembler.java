package com.finora.api.statementimport;

import com.finora.api.account.Account;
import com.finora.api.category.Category;
import com.finora.api.category.CategoryRepository;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.statementimport.CategoryRuleEngine.RuleConfidence;
import com.finora.api.statementimport.StatementImportDtos.BatchDetailResponse;
import com.finora.api.statementimport.StatementImportDtos.BatchTotals;
import com.finora.api.statementimport.StatementImportDtos.CsvMappingRequest;
import com.finora.api.statementimport.StatementImportDtos.CsvMappingSuggestion;
import com.finora.api.statementimport.StatementImportDtos.ItemResponse;
import com.finora.api.statementimport.StatementImportDtos.MatchedTransactionSummary;
import com.finora.api.statementimport.StatementImportDtos.StatementCurrencyContext;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Builds import responses with bulk lookups only: one query for the user's
 * categories, one for the matched transactions, one for the generated
 * transactions and one for the rules referenced by the page — never one
 * lookup per item.
 */
@Component
public class StatementImportAssembler {

    private final CategoryRepository categories;
    private final TransactionRepository transactions;
    private final CategoryMappingRuleRepository rules;

    public StatementImportAssembler(CategoryRepository categories,
                                    TransactionRepository transactions,
                                    CategoryMappingRuleRepository rules) {
        this.categories = categories;
        this.transactions = transactions;
        this.rules = rules;
    }

    public BatchDetailResponse detail(StatementImportBatch batch, Account account,
                                      List<StatementImportItem> items, String sourceAccountHint,
                                      boolean fileAlreadyImported, CsvMappingRequest csvMapping,
                                      CsvMappingSuggestion suggestion,
                                      List<List<String>> csvRawPreview) {
        CurrencyCode currency = account.getCurrency();
        List<ItemResponse> itemResponses = toItemResponses(batch.getUserId(), items, currency);
        return new BatchDetailResponse(
                batch.getId(),
                batch.getCreatedAt(),
                batch.getAccountId(),
                account.getName(),
                currencyContext(batch, currency),
                batch.getOriginalFilename(),
                batch.getFormat(),
                batch.getStatus(),
                batch.getFileSha256(),
                batch.getFileSizeBytes(),
                sourceAccountHint,
                fileAlreadyImported,
                csvMapping,
                suggestion,
                csvRawPreview,
                batch.getConfirmedAt(),
                batch.getUndoneAt(),
                totals(batch, items, currency),
                itemResponses);
    }

    /**
     * The batch's self-describing currency contract.
     *
     * <p>{@code effectiveCurrency} is the account currency for every source
     * without exception — a FILE batch cannot exist unless the declaration
     * matched — and {@code valuesAreConverted} is structurally false: no
     * exchange rate exists anywhere in Finora to convert with.
     *
     * <p>Acknowledgement is asked for only where it can still change an
     * outcome: a source whose denomination is an assumption, in a batch that
     * can still materialize something. A finished LEGACY_UNKNOWN import stays
     * truthfully LEGACY_UNKNOWN without demanding consent for work that is
     * already done.
     */
    public static StatementCurrencyContext currencyContext(StatementImportBatch batch,
                                                           CurrencyCode accountCurrency) {
        boolean pending = batch.getStatus() == StatementImportStatus.PREVIEW_READY
                || batch.getStatus() == StatementImportStatus.PARTIALLY_COMPLETED;
        return new StatementCurrencyContext(
                accountCurrency,
                batch.getCurrencySource(),
                batch.getDeclaredCurrency(),
                accountCurrency,
                false,
                batch.getCurrencySource().requiresAccountCurrencyAcknowledgement() && pending);
    }

    public List<ItemResponse> toItemResponses(Long userId, List<StatementImportItem> items,
                                              CurrencyCode currency) {
        Map<Long, String> categoryNames = categoryNames(userId);
        Map<Long, CategoryMappingRule> ruleById = new HashMap<>();
        for (CategoryMappingRule rule : rules.findAllByUserIdOrderByPriorityDescIdAsc(userId)) {
            ruleById.put(rule.getId(), rule);
        }
        List<Long> matchedIds = items.stream()
                .map(StatementImportItem::getMatchedTransactionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Transaction> matched = new HashMap<>();
        if (!matchedIds.isEmpty()) {
            for (Transaction transaction : transactions.findAllByUserIdAndIdIn(userId, matchedIds)) {
                matched.put(transaction.getId(), transaction);
            }
        }
        List<Long> itemIds = items.stream().map(StatementImportItem::getId).toList();
        Map<Long, Transaction> generated = new HashMap<>();
        if (!itemIds.isEmpty()) {
            for (Transaction transaction
                    : transactions.findAllByUserIdAndStatementImportItemIdIn(userId, itemIds)) {
                generated.put(transaction.getStatementImportItemId(), transaction);
            }
        }
        return items.stream()
                .map(item -> toResponse(item, currency, categoryNames, ruleById,
                        matched.get(item.getMatchedTransactionId()),
                        generated.get(item.getId())))
                .toList();
    }

    private ItemResponse toResponse(StatementImportItem item, CurrencyCode currency,
                                    Map<Long, String> categoryNames,
                                    Map<Long, CategoryMappingRule> ruleById,
                                    Transaction matchedTransaction, Transaction generated) {
        CategoryMappingRule matchedRule = item.getMatchedRuleId() == null ? null
                : ruleById.get(item.getMatchedRuleId());
        return new ItemResponse(
                item.getId(),
                item.getSourceIndex(),
                item.getExternalId(),
                item.getSourceType(),
                item.getPostedDate(),
                item.getAmount(),
                currency,
                item.getType(),
                item.getDescription(),
                item.getMemo(),
                item.getOriginalDate(),
                item.getOriginalAmount(),
                item.getOriginalType(),
                item.getOriginalDescription(),
                item.getSuggestedCategoryId(),
                categoryNames.get(item.getSuggestedCategoryId()),
                item.getMatchedRuleId(),
                matchedRule == null ? null : matchedRule.getPattern(),
                matchedRule == null ? null : confidence(matchedRule),
                item.getSelectedCategoryId(),
                categoryNames.get(item.getSelectedCategoryId()),
                item.isIncluded(),
                item.getDuplicateStatus(),
                item.isDuplicateOverride(),
                matchedTransaction == null ? null : new MatchedTransactionSummary(
                        matchedTransaction.getId(),
                        matchedTransaction.getOccurredOn(),
                        matchedTransaction.getDescription(),
                        matchedTransaction.getAmount(),
                        // The matched transaction's own currency, not the
                        // batch's: duplicate matching is account-scoped so they
                        // agree, and reporting the transaction's own value
                        // keeps that agreement observable instead of assumed.
                        matchedTransaction.getCurrency(),
                        matchedTransaction.getType(),
                        matchedTransaction.getCategory().getName()),
                item.getStatus(),
                item.getValidationCode(),
                item.getValidationMessage(),
                item.getResultCode(),
                item.getResultMessage(),
                generated == null ? null : generated.getId(),
                item.getImportedAt(),
                item.getUndoneAt(),
                importable(item));
    }

    /** Whether confirming now would try to materialize this item. */
    public static boolean importable(StatementImportItem item) {
        boolean eligibleStatus = item.getStatus() == StatementImportItemStatus.READY
                || item.getStatus() == StatementImportItemStatus.FAILED
                || item.getStatus() == StatementImportItemStatus.SKIPPED;
        if (!eligibleStatus || !item.isIncluded()) {
            return false;
        }
        if (item.getSelectedCategoryId() == null) {
            return false;
        }
        if (item.getDuplicateStatus() == DuplicateStatus.EXACT_DUPLICATE) {
            return false;
        }
        return item.getDuplicateStatus() != DuplicateStatus.POSSIBLE_DUPLICATE
                || item.isDuplicateOverride();
    }

    public BatchTotals totals(StatementImportBatch batch, List<StatementImportItem> items,
                              CurrencyCode currency) {
        int ready = 0;
        int invalid = 0;
        int imported = 0;
        int failed = 0;
        int skipped = 0;
        int undone = 0;
        int excluded = 0;
        int includedPending = 0;
        int exact = 0;
        int possible = 0;
        int withinFile = 0;
        int unmapped = 0;
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (StatementImportItem item : items) {
            switch (item.getStatus()) {
                case READY -> ready++;
                case INVALID -> invalid++;
                case IMPORTED -> imported++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
                case UNDONE -> undone++;
            }
            boolean pending = item.getStatus() == StatementImportItemStatus.READY
                    || item.getStatus() == StatementImportItemStatus.FAILED
                    || item.getStatus() == StatementImportItemStatus.SKIPPED;
            if (pending && !item.isIncluded()) {
                excluded++;
            }
            switch (item.getDuplicateStatus()) {
                case EXACT_DUPLICATE -> exact++;
                case POSSIBLE_DUPLICATE -> possible++;
                case DUPLICATE_WITHIN_FILE -> withinFile++;
                case UNIQUE -> {
                }
            }
            if (pending && item.isIncluded() && item.getSelectedCategoryId() == null) {
                unmapped++;
            }
            if (importable(item)) {
                includedPending++;
                if (item.getType() == TransactionType.INCOME) {
                    income = income.add(item.getAmount());
                } else if (item.getType() == TransactionType.EXPENSE) {
                    expense = expense.add(item.getAmount());
                }
            }
        }
        return new BatchTotals(currency, batch.getTotalRows(), ready, invalid, imported, failed,
                skipped, undone, excluded, includedPending, exact, possible, withinFile, unmapped,
                income, expense, income.subtract(expense));
    }

    public Map<Long, String> categoryNames(Long userId) {
        Map<Long, String> names = new HashMap<>();
        for (Category category : categories.findAllByUserIdOrderByTypeAscNameAsc(userId)) {
            names.put(category.getId(), category.getName());
        }
        return names;
    }

    private static RuleConfidence confidence(CategoryMappingRule rule) {
        return switch (rule.getOperation()) {
            case EXACT -> RuleConfidence.HIGH;
            case STARTS_WITH -> RuleConfidence.MEDIUM;
            case CONTAINS -> RuleConfidence.LOW;
        };
    }
}
