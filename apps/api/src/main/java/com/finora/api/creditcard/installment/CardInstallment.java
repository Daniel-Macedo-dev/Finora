package com.finora.api.creditcard.installment;

import com.finora.api.common.persistence.AuditableEntity;
import com.finora.api.creditcard.invoice.CardInvoice;
import com.finora.api.creditcard.purchase.CardPurchase;
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

/**
 * One installment of a card purchase, assigned to exactly one invoice. The
 * installment is the unit of expense recognition: it counts toward monthly
 * expenses and category budgets in its invoice's reference month.
 */
@Entity
@Table(name = "credit_card_installments")
public class CardInstallment extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_id", nullable = false, updatable = false)
    private CardPurchase purchase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private CardInvoice invoice;

    /** 1-based position within the purchase's schedule. */
    @Column(name = "sequence_number", nullable = false, updatable = false)
    private int sequenceNumber;

    @Column(name = "total_installments", nullable = false)
    private int totalInstallments;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InstallmentStatus status = InstallmentStatus.ACTIVE;

    protected CardInstallment() {
    }

    public CardInstallment(Long userId, CardPurchase purchase, CardInvoice invoice,
                           int sequenceNumber, int totalInstallments, BigDecimal amount) {
        this.userId = userId;
        this.purchase = purchase;
        this.invoice = invoice;
        this.sequenceNumber = sequenceNumber;
        this.totalInstallments = totalInstallments;
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public CardPurchase getPurchase() {
        return purchase;
    }

    public CardInvoice getInvoice() {
        return invoice;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public int getTotalInstallments() {
        return totalInstallments;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public InstallmentStatus getStatus() {
        return status;
    }

    public void setStatus(InstallmentStatus status) {
        this.status = status;
    }
}
