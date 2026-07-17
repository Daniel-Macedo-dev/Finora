package com.finora.api.legacyconversion;

import com.finora.api.common.persistence.AuditableEntity;
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
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * The auditable record of one legacy-credit conversion: which pre-card-era
 * CREDIT transaction was converted, into which purchase on which card, with
 * which effective date, installment count and confirmed first invoice.
 *
 * <p>The row is never deleted. While ACTIVE it explains why the source
 * transaction is financially inactive; once REVERSED it documents that the
 * generated purchase was cancelled and the source restored. The partial
 * unique index {@code uq_legacy_conversions_active_source} guarantees at most
 * one ACTIVE conversion per source, whatever races the application loses.
 */
@Entity
@Table(name = "legacy_credit_conversions")
public class LegacyCreditConversion extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "source_transaction_id", nullable = false, updatable = false)
    private Long sourceTransactionId;

    @Column(name = "card_purchase_id", nullable = false, updatable = false)
    private Long cardPurchaseId;

    @Column(name = "card_id", nullable = false, updatable = false)
    private Long cardId;

    /** Snapshot of the source's date at conversion time (audit stability). */
    @Column(name = "original_transaction_date", nullable = false, updatable = false)
    private LocalDate originalTransactionDate;

    @Column(name = "effective_purchase_date", nullable = false, updatable = false)
    private LocalDate effectivePurchaseDate;

    @Column(name = "installment_count", nullable = false, updatable = false)
    private int installmentCount;

    /** First invoice reference month, stored as its first day. */
    @Column(name = "first_invoice_month", nullable = false, updatable = false)
    private LocalDate firstInvoiceMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversionStatus status = ConversionStatus.ACTIVE;

    @Column(name = "converted_at", nullable = false, updatable = false)
    private Instant convertedAt;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "reversal_reason", length = 300)
    private String reversalReason;

    /** Optimistic guard: a reversal racing another writer fails cleanly. */
    @Version
    @Column(nullable = false)
    private long version;

    protected LegacyCreditConversion() {
    }

    public LegacyCreditConversion(Long userId, Long sourceTransactionId, Long cardPurchaseId,
                                  Long cardId, LocalDate originalTransactionDate,
                                  LocalDate effectivePurchaseDate, int installmentCount,
                                  YearMonth firstInvoiceMonth, Instant convertedAt) {
        this.userId = userId;
        this.sourceTransactionId = sourceTransactionId;
        this.cardPurchaseId = cardPurchaseId;
        this.cardId = cardId;
        this.originalTransactionDate = originalTransactionDate;
        this.effectivePurchaseDate = effectivePurchaseDate;
        this.installmentCount = installmentCount;
        this.firstInvoiceMonth = firstInvoiceMonth.atDay(1);
        this.convertedAt = convertedAt;
    }

    /** Marks the conversion reversed; the caller restores the source transaction. */
    public void markReversed(Instant when, String reason) {
        this.status = ConversionStatus.REVERSED;
        this.reversedAt = when;
        this.reversalReason = reason;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getSourceTransactionId() {
        return sourceTransactionId;
    }

    public Long getCardPurchaseId() {
        return cardPurchaseId;
    }

    public Long getCardId() {
        return cardId;
    }

    public LocalDate getOriginalTransactionDate() {
        return originalTransactionDate;
    }

    public LocalDate getEffectivePurchaseDate() {
        return effectivePurchaseDate;
    }

    public int getInstallmentCount() {
        return installmentCount;
    }

    public YearMonth getFirstInvoiceMonth() {
        return YearMonth.from(firstInvoiceMonth);
    }

    public ConversionStatus getStatus() {
        return status;
    }

    public Instant getConvertedAt() {
        return convertedAt;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public String getReversalReason() {
        return reversalReason;
    }
}
