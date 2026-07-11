package com.finora.api.creditcard.adjustment;

import com.finora.api.category.Category;
import com.finora.api.common.persistence.AuditableEntity;
import com.finora.api.creditcard.invoice.CardInvoice;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * An auditable adjustment on an invoice: fees, interest and other debits
 * increase the invoice total; credits and refunds reduce it. Adjustments are
 * never deleted — incorrect ones are reversed and stay in history.
 */
@Entity
@Table(name = "credit_card_adjustments")
public class InvoiceAdjustment extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private CardInvoice invoice;

    /** Required for debit kinds (they are expenses); optional for credits. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", updatable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private AdjustmentKind kind;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdjustmentStatus status = AdjustmentStatus.ACTIVE;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    protected InvoiceAdjustment() {
    }

    public InvoiceAdjustment(Long userId, CardInvoice invoice, Category category,
                             AdjustmentKind kind, String description, BigDecimal amount) {
        this.userId = userId;
        this.invoice = invoice;
        this.category = category;
        this.kind = kind;
        this.description = description;
        this.amount = amount;
    }

    public void reverse(Instant when) {
        this.status = AdjustmentStatus.REVERSED;
        this.reversedAt = when;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public CardInvoice getInvoice() {
        return invoice;
    }

    public Category getCategory() {
        return category;
    }

    public AdjustmentKind getKind() {
        return kind;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public AdjustmentStatus getStatus() {
        return status;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }
}
