package com.finora.api.statementimport;

import com.finora.api.common.money.CurrencyCode;
import com.finora.api.statementimport.CategoryRuleEngine.RuleConfidence;
import com.finora.api.statementimport.parser.csv.CsvDelimiter;
import com.finora.api.statementimport.parser.csv.CsvEncoding;
import com.finora.api.statementimport.parser.csv.CsvMappingConfig;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class StatementImportDtos {

    private StatementImportDtos() {
    }

    // ── Requests ────────────────────────────────────────────────────────────

    /** CSV interpretation confirmed by the user (mirrors CsvMappingConfig). */
    public record CsvMappingRequest(
            @NotNull(message = "Informe a codificação do arquivo.")
            CsvEncoding encoding,

            @NotNull(message = "Informe o delimitador.")
            CsvDelimiter delimiter,

            boolean hasHeader,

            @NotNull(message = "Informe o padrão de data.")
            String datePattern,

            @NotNull(message = "Informe o separador decimal.")
            CsvMappingConfig.Separator decimalSeparator,

            @NotNull(message = "Informe o separador de milhar.")
            CsvMappingConfig.Separator thousandsSeparator,

            @NotNull(message = "Informe a coluna de data.")
            @Min(value = 0, message = "Coluna inválida.")
            Integer dateColumn,

            @NotNull(message = "Informe a coluna de descrição.")
            @Min(value = 0, message = "Coluna inválida.")
            Integer descriptionColumn,

            @Min(value = 0, message = "Coluna inválida.")
            Integer amountColumn,

            @Min(value = 0, message = "Coluna inválida.")
            Integer debitColumn,

            @Min(value = 0, message = "Coluna inválida.")
            Integer creditColumn,

            @Min(value = 0, message = "Coluna inválida.")
            Integer externalIdColumn,

            @Min(value = 0, message = "Coluna inválida.")
            Integer memoColumn) {

        public CsvMappingConfig toConfig() {
            return new CsvMappingConfig(encoding, delimiter, hasHeader, datePattern,
                    decimalSeparator, thousandsSeparator, dateColumn, descriptionColumn,
                    amountColumn, debitColumn, creditColumn, externalIdColumn, memoColumn);
        }

        public static CsvMappingRequest from(CsvMappingConfig config) {
            return new CsvMappingRequest(config.encoding(), config.delimiter(),
                    config.hasHeader(), config.datePattern(), config.decimalSeparator(),
                    config.thousandsSeparator(), config.dateColumn(), config.descriptionColumn(),
                    config.amountColumn(), config.debitColumn(), config.creditColumn(),
                    config.externalIdColumn(), config.memoColumn());
        }
    }

    /** Destination-account change while the batch is still editable. */
    public record AccountChangeRequest(
            @NotNull(message = "Informe a conta de destino.")
            Long accountId) {
    }

    /** Pre-confirmation edits; only present fields are applied. */
    public record ItemPatchRequest(
            Boolean included,
            Long selectedCategoryId,
            @Size(max = 200, message = "A descrição pode ter no máximo 200 caracteres.")
            String description,
            LocalDate postedDate,
            com.finora.api.transaction.TransactionType type,
            @Positive(message = "O valor deve ser maior que zero.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal amount,
            Boolean duplicateOverride) {
    }

    /**
     * Items to confirm; empty/null means every eligible item of the batch.
     *
     * @param acknowledgeAccountCurrency explicit confirmation that the
     *     destination account's currency is the right reading of this file's
     *     amounts. Required only when the batch's currency source is an
     *     assumption rather than a declaration
     *     ({@link StatementCurrencySource#requiresAccountCurrencyAcknowledgement()}).
     *     This is consent, not financial identity: it is absent from every
     *     fingerprint, from the external-id and duplicate identities, from the
     *     imported transaction and from undo, so repeating a confirmation with
     *     the same financial inputs stays idempotent.
     */
    public record ConfirmRequest(List<Long> itemIds, Boolean acknowledgeAccountCurrency) {

        /** Consent is never implied: absent means not given. */
        public boolean acknowledged() {
            return Boolean.TRUE.equals(acknowledgeAccountCurrency);
        }
    }

    // ── Responses ───────────────────────────────────────────────────────────

    /**
     * The one currency a statement batch is read in, and how well Finora knows
     * it agrees with the source.
     *
     * <p>Bundled into a single record rather than scattered across the batch
     * response so a consumer cannot pick up an amount while leaving the
     * denomination behind.
     *
     * @param accountCurrency currency of the destination account — the
     *     authoritative denomination of every amount in this batch
     * @param currencySource  provenance of that denomination
     * @param declaredCurrency currency the file itself declared, present only
     *     for {@link StatementCurrencySource#FILE}
     * @param effectiveCurrency currency the amounts are actually read in.
     *     Equal to {@code accountCurrency} always: a FILE batch only exists
     *     when the declaration matched, and every other source inherits the
     *     account outright.
     * @param valuesAreConverted always false. Finora holds no exchange rates,
     *     so an import reinterprets nothing — the same numbers are simply read
     *     in the destination account's currency. The field exists so clients
     *     state that fact instead of assuming it.
     * @param currencyAcknowledgementRequired whether confirming a new item
     *     needs {@code acknowledgeAccountCurrency}
     */
    public record StatementCurrencyContext(
            CurrencyCode accountCurrency,
            StatementCurrencySource currencySource,
            CurrencyCode declaredCurrency,
            CurrencyCode effectiveCurrency,
            boolean valuesAreConverted,
            boolean currencyAcknowledgementRequired) {
    }

    /**
     * Summary of an existing transaction shown in duplicate review.
     *
     * @param currency denomination of {@code amount}, taken from the matched
     *     transaction itself rather than assumed from the batch
     */
    public record MatchedTransactionSummary(
            Long id,
            LocalDate date,
            String description,
            BigDecimal amount,
            CurrencyCode currency,
            com.finora.api.transaction.TransactionType type,
            String categoryName) {
    }

    public record ItemResponse(
            Long id,
            int sourceIndex,
            String externalId,
            String sourceType,
            LocalDate postedDate,
            BigDecimal amount,
            /**
             * Denomination of {@code amount} and {@code originalAmount}: the
             * destination account's currency. Present on the item itself
             * because a single item is also a response in its own right (the
             * edit endpoint returns one), so it must never travel without it.
             */
            CurrencyCode currency,
            com.finora.api.transaction.TransactionType type,
            String description,
            String memo,
            LocalDate originalDate,
            BigDecimal originalAmount,
            com.finora.api.transaction.TransactionType originalType,
            String originalDescription,
            Long suggestedCategoryId,
            String suggestedCategoryName,
            Long matchedRuleId,
            String matchedRulePattern,
            RuleConfidence ruleConfidence,
            Long selectedCategoryId,
            String selectedCategoryName,
            boolean included,
            DuplicateStatus duplicateStatus,
            boolean duplicateOverride,
            MatchedTransactionSummary matchedTransaction,
            StatementImportItemStatus status,
            String validationCode,
            String validationMessage,
            String resultCode,
            String resultMessage,
            Long transactionId,
            Instant importedAt,
            Instant undoneAt,
            /** Whether confirming now would try to materialize this item. */
            boolean importable) {
    }

    /**
     * Derived batch totals — computed from items on read, never stored.
     *
     * @param currency denomination of every monetary total below. First field
     *     on purpose: these totals also travel inside {@code ConfirmResponse},
     *     which carries no other currency, so an unlabelled sum would be
     *     renderable on its own.
     */
    public record BatchTotals(
            CurrencyCode currency,
            int totalRows,
            int readyCount,
            int invalidCount,
            int importedCount,
            int failedCount,
            int skippedCount,
            int undoneCount,
            int excludedCount,
            int includedPendingCount,
            int exactDuplicateCount,
            int possibleDuplicateCount,
            int withinFileDuplicateCount,
            int unmappedCategoryCount,
            BigDecimal pendingIncomeTotal,
            BigDecimal pendingExpenseTotal,
            BigDecimal pendingNetEffect) {
    }

    /**
     * History row.
     *
     * @param accountCurrency currency of the destination account, resolved for
     *     the current owner. Account currency is immutable, so this is the
     *     denomination the batch always had — safe to resolve now.
     * @param declaredCurrency what the file declared, for
     *     {@link StatementCurrencySource#FILE} only. A
     *     {@code LEGACY_UNKNOWN} row never gets an invented declaration.
     */
    public record BatchSummaryResponse(
            Long id,
            Instant createdAt,
            Long accountId,
            String accountName,
            CurrencyCode accountCurrency,
            StatementCurrencySource currencySource,
            CurrencyCode declaredCurrency,
            String originalFilename,
            StatementImportFormat format,
            StatementImportStatus status,
            int totalRows,
            long importedCount,
            long failedCount,
            Instant confirmedAt,
            Instant undoneAt) {
    }

    /** Suggested starting point for the CSV mapping step. */
    public record CsvMappingSuggestion(
            CsvEncoding encoding,
            CsvDelimiter delimiter,
            boolean hasHeader,
            List<String> datePatterns) {
    }

    public record BatchDetailResponse(
            Long id,
            Instant createdAt,
            Long accountId,
            String accountName,
            /** The one currency this batch is read in, and its provenance. */
            StatementCurrencyContext currency,
            String originalFilename,
            StatementImportFormat format,
            StatementImportStatus status,
            String fileSha256,
            long fileSizeBytes,
            /** Masked bank/account hint from the file — preview only. */
            String sourceAccountHint,
            /** True when this exact file was already uploaded to this account. */
            boolean fileAlreadyImported,
            CsvMappingRequest csvMapping,
            CsvMappingSuggestion csvMappingSuggestion,
            /** First raw rows, present only while a CSV waits for mapping. */
            List<List<String>> csvRawPreview,
            Instant confirmedAt,
            Instant undoneAt,
            BatchTotals totals,
            List<ItemResponse> items) {
    }

    /**
     * Preview of a candidate CSV mapping before the authoritative parse.
     *
     * @param accountCurrency denomination of every {@code ItemPreview.amount}
     *     below — the destination account's currency, because a CSV declares
     *     none of its own. Rows are only ever reachable through this response,
     *     so one currency here denominates them all without duplication.
     */
    public record MappingPreviewResponse(
            Long batchId,
            CurrencyCode accountCurrency,
            int sampleSize,
            int validCount,
            int invalidCount,
            List<ItemPreview> entries) {

        public record ItemPreview(
                int sourceIndex,
                LocalDate postedDate,
                BigDecimal amount,
                com.finora.api.transaction.TransactionType type,
                String description,
                String memo,
                String externalId,
                String validationCode,
                String validationMessage) {
        }
    }

    /** Structured outcome of confirming or undoing one item. */
    public record ItemResult(
            Long itemId,
            ItemResultCode result,
            Long transactionId,
            String code,
            String message) {
    }

    public enum ItemResultCode {
        SUCCESS,
        FAILED,
        SKIPPED,
        EXACT_DUPLICATE,
        ALREADY_IMPORTED,
        UNDONE,
        ALREADY_UNDONE,
        BLOCKED
    }

    public record ConfirmResponse(
            Long batchId,
            StatementImportStatus batchStatus,
            List<ItemResult> results,
            BatchTotals totals) {
    }
}
