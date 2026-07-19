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
import java.time.Instant;

/**
 * One deterministic, owner-scoped category-mapping rule: plain text matching
 * ({@code EXACT} / {@code STARTS_WITH} / {@code CONTAINS}) over the canonical
 * normalized description or memo — no regex, no statistics, no AI.
 */
@Entity
@Table(name = "category_mapping_rules")
public class CategoryMappingRule extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 10)
    private TransactionType transactionType;

    /** Optional account scope: an account-specific rule beats a global one. */
    @Column(name = "account_id")
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_field", nullable = false, length = 20)
    private CategoryRuleField matchField = CategoryRuleField.DESCRIPTION;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CategoryRuleOperation operation;

    /** Stored in the same canonical normalization applied to statement text. */
    @Column(nullable = false, length = 200)
    private String pattern;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false)
    private int priority;

    @Column(name = "match_count", nullable = false)
    private long matchCount;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected CategoryMappingRule() {
    }

    public CategoryMappingRule(Long userId, TransactionType transactionType, Long accountId,
                               CategoryRuleField matchField, CategoryRuleOperation operation,
                               String pattern, Long categoryId, int priority) {
        this.userId = userId;
        this.transactionType = transactionType;
        this.accountId = accountId;
        this.matchField = matchField;
        this.operation = operation;
        this.pattern = pattern;
        this.categoryId = categoryId;
        this.priority = priority;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(TransactionType transactionType) {
        this.transactionType = transactionType;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public CategoryRuleField getMatchField() {
        return matchField;
    }

    public void setMatchField(CategoryRuleField matchField) {
        this.matchField = matchField;
    }

    public CategoryRuleOperation getOperation() {
        return operation;
    }

    public void setOperation(CategoryRuleOperation operation) {
        this.operation = operation;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public long getMatchCount() {
        return matchCount;
    }

    public void recordUse(Instant when) {
        this.matchCount++;
        this.lastUsedAt = when;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
