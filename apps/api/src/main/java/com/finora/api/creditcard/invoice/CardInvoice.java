package com.finora.api.creditcard.invoice;

import com.finora.api.common.persistence.AuditableEntity;
import com.finora.api.creditcard.CreditCard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * One card invoice per reference month. Closing and due dates are snapshots
 * computed when the invoice is created; they never change afterwards, even if
 * the card's closing/due days are reconfigured. Financial totals are always
 * derived from installments, adjustments and payments — never stored here.
 */
@Entity
@Table(name = "credit_card_invoices")
public class CardInvoice extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false, updatable = false)
    private CreditCard card;

    /** First day of the invoice's reference month. */
    @Column(name = "reference_month", nullable = false, updatable = false)
    private LocalDate referenceMonth;

    @Column(name = "closing_date", nullable = false, updatable = false)
    private LocalDate closingDate;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    protected CardInvoice() {
    }

    public CardInvoice(Long userId, CreditCard card, YearMonth referenceMonth,
                       LocalDate closingDate, LocalDate dueDate) {
        this.userId = userId;
        this.card = card;
        this.referenceMonth = referenceMonth.atDay(1);
        this.closingDate = closingDate;
        this.dueDate = dueDate;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public CreditCard getCard() {
        return card;
    }

    public YearMonth getReferenceMonth() {
        return YearMonth.from(referenceMonth);
    }

    public LocalDate getClosingDate() {
        return closingDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }
}
