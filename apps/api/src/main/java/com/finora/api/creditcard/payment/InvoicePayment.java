package com.finora.api.creditcard.payment;

import com.finora.api.account.Account;
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
import java.time.LocalDate;

/**
 * A payment against a card invoice, settled from one of the user's accounts.
 * A completed payment reduces the account balance and the invoice outstanding
 * amount, and restores card limit — it is never an expense (the installments
 * it settles already were). Payments are never deleted: mistakes are undone
 * through an explicit reversal that keeps the record.
 */
@Entity
@Table(name = "credit_card_payments")
public class InvoicePayment extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private CardInvoice invoice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.COMPLETED;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    protected InvoicePayment() {
    }

    public InvoicePayment(Long userId, CardInvoice invoice, Account account,
                          BigDecimal amount, LocalDate paidOn) {
        this.userId = userId;
        this.invoice = invoice;
        this.account = account;
        this.amount = amount;
        this.paidOn = paidOn;
    }

    /** Marks this payment as reversed; the row remains as an audit record. */
    public void reverse(Instant when) {
        this.status = PaymentStatus.REVERSED;
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

    public Account getAccount() {
        return account;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getPaidOn() {
        return paidOn;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }
}
