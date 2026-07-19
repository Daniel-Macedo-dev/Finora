package com.finora.api.statementimport;

import com.finora.api.common.persistence.AuditableEntity;
import com.finora.api.transaction.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One normalized statement row: a preview only, until confirmed. The
 * effective fields ({@code postedDate}, {@code amount}, {@code type},
 * {@code description}) are user-editable before confirmation; the immutable
 * {@code original*} copies preserve what was actually parsed for audit.
 */
@Entity
@Table(name = "statement_import_items")
public class StatementImportItem extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private Long batchId;

    /**
     * Denormalized copy of the batch destination, kept in sync while the
     * batch is editable and frozen once imported, so the database identity
     * backstops can be plain indexes.
     */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** 1-based CSV row number or OFX STMTTRN sequence. */
    @Column(name = "source_index", nullable = false, updatable = false)
    private int sourceIndex;

    /** OFX FITID or explicitly mapped CSV external-id column. */
    @Column(name = "external_id", length = 255, updatable = false)
    private String externalId;

    /** OFX TRNTYPE (or CSV); preview information only. */
    @Column(name = "source_type", length = 40, updatable = false)
    private String sourceType;

    @Column(name = "posted_date")
    private LocalDate postedDate;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private TransactionType type;

    @Column(length = 200)
    private String description;

    @Column(name = "original_date", updatable = false)
    private LocalDate originalDate;

    @Column(name = "original_amount", precision = 14, scale = 2, updatable = false)
    private BigDecimal originalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "original_type", length = 10, updatable = false)
    private TransactionType originalType;

    @Column(name = "original_description", length = 500, updatable = false)
    private String originalDescription;

    /** Canonical matching form of the description (case/accent-folded). */
    @Column(name = "normalized_description", length = 200)
    private String normalizedDescription;

    @Column(length = 500, updatable = false)
    private String memo;

    /** Versioned SHA-256 content fingerprint. */
    @Column(length = 64)
    private String fingerprint;

    @Column(name = "suggested_category_id")
    private Long suggestedCategoryId;

    /** Rule that produced the suggestion — informational snapshot, no FK. */
    @Column(name = "matched_rule_id")
    private Long matchedRuleId;

    @Column(name = "selected_category_id")
    private Long selectedCategoryId;

    @Column(nullable = false)
    private boolean included = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "duplicate_status", nullable = false, length = 30)
    private DuplicateStatus duplicateStatus = DuplicateStatus.UNIQUE;

    /** Explicit user decision to import a possible duplicate anyway. */
    @Column(name = "duplicate_override", nullable = false)
    private boolean duplicateOverride;

    /** Existing transaction a possible duplicate matched — informational, no FK. */
    @Column(name = "matched_transaction_id")
    private Long matchedTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatementImportItemStatus status;

    @Column(name = "validation_code", length = 60)
    private String validationCode;

    @Column(name = "validation_message", length = 300)
    private String validationMessage;

    /** Structured outcome of the latest confirmation attempt. */
    @Column(name = "result_code", length = 60)
    private String resultCode;

    @Column(name = "result_message", length = 300)
    private String resultMessage;

    @Column(name = "imported_at")
    private Instant importedAt;

    @Column(name = "undone_at")
    private Instant undoneAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected StatementImportItem() {
    }

    public StatementImportItem(Long userId, Long batchId, Long accountId, int sourceIndex,
                               StatementImportItemStatus status) {
        this.userId = userId;
        this.batchId = batchId;
        this.accountId = accountId;
        this.sourceIndex = sourceIndex;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getBatchId() {
        return batchId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public int getSourceIndex() {
        return sourceIndex;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public LocalDate getPostedDate() {
        return postedDate;
    }

    public void setPostedDate(LocalDate postedDate) {
        this.postedDate = postedDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDate getOriginalDate() {
        return originalDate;
    }

    public void setOriginalDate(LocalDate originalDate) {
        this.originalDate = originalDate;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public void setOriginalAmount(BigDecimal originalAmount) {
        this.originalAmount = originalAmount;
    }

    public TransactionType getOriginalType() {
        return originalType;
    }

    public void setOriginalType(TransactionType originalType) {
        this.originalType = originalType;
    }

    public String getOriginalDescription() {
        return originalDescription;
    }

    public void setOriginalDescription(String originalDescription) {
        this.originalDescription = originalDescription;
    }

    public String getNormalizedDescription() {
        return normalizedDescription;
    }

    public void setNormalizedDescription(String normalizedDescription) {
        this.normalizedDescription = normalizedDescription;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public Long getSuggestedCategoryId() {
        return suggestedCategoryId;
    }

    public void setSuggestedCategoryId(Long suggestedCategoryId) {
        this.suggestedCategoryId = suggestedCategoryId;
    }

    public Long getMatchedRuleId() {
        return matchedRuleId;
    }

    public void setMatchedRuleId(Long matchedRuleId) {
        this.matchedRuleId = matchedRuleId;
    }

    public Long getSelectedCategoryId() {
        return selectedCategoryId;
    }

    public void setSelectedCategoryId(Long selectedCategoryId) {
        this.selectedCategoryId = selectedCategoryId;
    }

    public boolean isIncluded() {
        return included;
    }

    public void setIncluded(boolean included) {
        this.included = included;
    }

    public DuplicateStatus getDuplicateStatus() {
        return duplicateStatus;
    }

    public void setDuplicateStatus(DuplicateStatus duplicateStatus) {
        this.duplicateStatus = duplicateStatus;
    }

    public boolean isDuplicateOverride() {
        return duplicateOverride;
    }

    public void setDuplicateOverride(boolean duplicateOverride) {
        this.duplicateOverride = duplicateOverride;
    }

    public Long getMatchedTransactionId() {
        return matchedTransactionId;
    }

    public void setMatchedTransactionId(Long matchedTransactionId) {
        this.matchedTransactionId = matchedTransactionId;
    }

    public StatementImportItemStatus getStatus() {
        return status;
    }

    public void setStatus(StatementImportItemStatus status) {
        this.status = status;
    }

    public String getValidationCode() {
        return validationCode;
    }

    public String getValidationMessage() {
        return validationMessage;
    }

    public void setValidation(String code, String message) {
        this.validationCode = code;
        this.validationMessage = message;
    }

    public String getResultCode() {
        return resultCode;
    }

    public String getResultMessage() {
        return resultMessage;
    }

    public void setResult(String code, String message) {
        this.resultCode = code;
        this.resultMessage = message;
    }

    public Instant getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(Instant importedAt) {
        this.importedAt = importedAt;
    }

    public Instant getUndoneAt() {
        return undoneAt;
    }

    public void setUndoneAt(Instant undoneAt) {
        this.undoneAt = undoneAt;
    }
}
