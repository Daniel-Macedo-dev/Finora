package com.finora.api.statementimport;

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

    /** Items to confirm; empty/null means every eligible item of the batch. */
    public record ConfirmRequest(List<Long> itemIds) {
    }

    // ── Responses ───────────────────────────────────────────────────────────

    /** Summary of an existing transaction shown in duplicate review. */
    public record MatchedTransactionSummary(
            Long id,
            LocalDate date,
            String description,
            BigDecimal amount,
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

    /** Derived batch totals — computed from items on read, never stored. */
    public record BatchTotals(
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

    public record BatchSummaryResponse(
            Long id,
            Instant createdAt,
            Long accountId,
            String accountName,
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

    /** Preview of a candidate CSV mapping before the authoritative parse. */
    public record MappingPreviewResponse(
            Long batchId,
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
