package com.finora.api.statementimport;

import com.finora.api.statementimport.parser.Fingerprints;
import com.finora.api.statementimport.parser.TextNormalizer;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Classifies every item of a batch against the user's existing data and the
 * rest of its own file — always in bulk, always owner-scoped.
 *
 * <ul>
 *   <li><b>EXACT_DUPLICATE</b> (blocked): the strong identity — external id
 *       (OFX FITID / mapped CSV id) — was already IMPORTED into the same
 *       account. Only strong identities block: content matches can be
 *       legitimate repetitions, so they never silently block.</li>
 *   <li><b>POSSIBLE_DUPLICATE</b> (user decides): same type, amount and
 *       canonical description within a {@value #DATE_WINDOW_DAYS}-day window
 *       of an existing live transaction, or the same content fingerprint
 *       already imported without a strong id.</li>
 *   <li><b>DUPLICATE_WITHIN_FILE</b>: repeats an earlier row of the same
 *       upload (same external id or same content fingerprint).</li>
 * </ul>
 */
@Service
public class DuplicateDetectionService {

    /** Possible-duplicate tolerance for settlement-date drift. */
    static final int DATE_WINDOW_DAYS = 3;

    private final TransactionRepository transactions;
    private final StatementImportItemRepository items;

    public DuplicateDetectionService(TransactionRepository transactions,
                                     StatementImportItemRepository items) {
        this.transactions = transactions;
        this.items = items;
    }

    /**
     * Recomputes fingerprint and duplicate classification for every given
     * item (typically a whole batch, after parse, reparse, account change or
     * item edit). Resets previous classifications; explicit overrides are
     * cleared only when the classification changed.
     */
    public void classify(Long userId, Long accountId, List<StatementImportItem> batchItems) {
        List<StatementImportItem> candidates = batchItems.stream()
                .filter(item -> item.getStatus() == StatementImportItemStatus.READY
                        || item.getStatus() == StatementImportItemStatus.INVALID
                        || item.getStatus() == StatementImportItemStatus.FAILED)
                .toList();
        for (StatementImportItem item : candidates) {
            if (item.getType() != null && item.getAmount() != null
                    && item.getPostedDate() != null) {
                item.setFingerprint(Fingerprints.contentFingerprint(
                        userId, accountId, item.getPostedDate(), item.getType(),
                        item.getAmount(), item.getNormalizedDescription()));
            } else {
                item.setFingerprint(null);
            }
        }

        Map<Long, DuplicateStatus> classification = new HashMap<>();
        Map<Long, Long> matchedTransaction = new HashMap<>();

        markWithinFile(candidates, classification);
        markExactDuplicates(userId, accountId, candidates, classification);
        markPossibleDuplicates(userId, accountId, candidates, classification, matchedTransaction);

        for (StatementImportItem item : candidates) {
            DuplicateStatus status = classification.getOrDefault(item.getId(),
                    DuplicateStatus.UNIQUE);
            if (item.getDuplicateStatus() != status) {
                item.setDuplicateStatus(status);
                item.setDuplicateOverride(false);
            }
            item.setMatchedTransactionId(matchedTransaction.get(item.getId()));
        }
    }

    /** Later copies of an identity already seen in the same file. */
    private void markWithinFile(List<StatementImportItem> candidates,
                                Map<Long, DuplicateStatus> classification) {
        Set<String> seenExternal = new HashSet<>();
        Set<String> seenFingerprint = new HashSet<>();
        for (StatementImportItem item : candidates) {
            if (item.getExternalId() != null) {
                if (!seenExternal.add(item.getExternalId())) {
                    classification.put(item.getId(), DuplicateStatus.DUPLICATE_WITHIN_FILE);
                }
            } else if (item.getFingerprint() != null
                    && !seenFingerprint.add(item.getFingerprint())) {
                classification.put(item.getId(), DuplicateStatus.DUPLICATE_WITHIN_FILE);
            }
        }
    }

    /** Strong identity already imported into this account (blocked). */
    private void markExactDuplicates(Long userId, Long accountId,
                                     List<StatementImportItem> candidates,
                                     Map<Long, DuplicateStatus> classification) {
        List<String> externalIds = candidates.stream()
                .map(StatementImportItem::getExternalId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (externalIds.isEmpty()) {
            return;
        }
        Set<String> imported = new HashSet<>(
                items.findImportedExternalIds(userId, accountId, externalIds));
        if (imported.isEmpty()) {
            return;
        }
        for (StatementImportItem item : candidates) {
            if (item.getExternalId() != null && imported.contains(item.getExternalId())) {
                classification.put(item.getId(), DuplicateStatus.EXACT_DUPLICATE);
            }
        }
    }

    /**
     * Content matches that require a human decision: never auto-blocked,
     * never auto-merged. The whole candidate transaction pool is loaded in
     * one query over the padded statement date range.
     */
    private void markPossibleDuplicates(Long userId, Long accountId,
                                        List<StatementImportItem> candidates,
                                        Map<Long, DuplicateStatus> classification,
                                        Map<Long, Long> matchedTransaction) {
        List<StatementImportItem> open = candidates.stream()
                .filter(item -> !classification.containsKey(item.getId()))
                .filter(item -> item.getFingerprint() != null)
                .toList();
        if (open.isEmpty()) {
            return;
        }

        // Fingerprints already imported without a strong id (e.g. overlapping
        // statement periods re-exported by the bank).
        Set<String> importedFingerprints = new HashSet<>(items.findImportedFingerprints(
                userId, accountId,
                open.stream().map(StatementImportItem::getFingerprint).distinct().toList()));
        for (StatementImportItem item : open) {
            if (item.getExternalId() == null
                    && importedFingerprints.contains(item.getFingerprint())) {
                classification.put(item.getId(), DuplicateStatus.POSSIBLE_DUPLICATE);
            }
        }

        LocalDate min = null;
        LocalDate max = null;
        for (StatementImportItem item : open) {
            LocalDate date = item.getPostedDate();
            min = min == null || date.isBefore(min) ? date : min;
            max = max == null || date.isAfter(max) ? date : max;
        }
        List<Transaction> pool =
                transactions.findAllByUserIdAndAccountIdAndFinanciallyActiveTrueAndOccurredOnBetween(
                        userId, accountId,
                        min.minusDays(DATE_WINDOW_DAYS), max.plusDays(DATE_WINDOW_DAYS));
        if (pool.isEmpty()) {
            return;
        }
        // Group the pool by (type, amount, canonical description).
        Map<String, List<Transaction>> byIdentity = new HashMap<>();
        for (Transaction transaction : pool) {
            byIdentity.computeIfAbsent(identityKey(transaction), k -> new ArrayList<>())
                    .add(transaction);
        }
        for (StatementImportItem item : open) {
            if (classification.containsKey(item.getId())) {
                continue;
            }
            List<Transaction> matches = byIdentity.get(identityKey(item));
            if (matches == null) {
                continue;
            }
            Transaction best = null;
            for (Transaction match : matches) {
                // Never match the item's own previously generated transaction.
                if (Objects.equals(match.getStatementImportItemId(), item.getId())) {
                    continue;
                }
                long distance = Math.abs(
                        match.getOccurredOn().toEpochDay() - item.getPostedDate().toEpochDay());
                if (distance <= DATE_WINDOW_DAYS
                        && (best == null || distance < Math.abs(best.getOccurredOn().toEpochDay()
                                - item.getPostedDate().toEpochDay()))) {
                    best = match;
                }
            }
            if (best != null) {
                classification.put(item.getId(), DuplicateStatus.POSSIBLE_DUPLICATE);
                matchedTransaction.put(item.getId(), best.getId());
            }
        }
    }

    private static String identityKey(Transaction transaction) {
        return transaction.getType().name() + '|'
                + transaction.getAmount().stripTrailingZeros().toPlainString() + '|'
                + TextNormalizer.canonical(transaction.getDescription());
    }

    private static String identityKey(StatementImportItem item) {
        return item.getType().name() + '|'
                + item.getAmount().stripTrailingZeros().toPlainString() + '|'
                + item.getNormalizedDescription();
    }
}
