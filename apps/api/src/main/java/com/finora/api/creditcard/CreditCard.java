package com.finora.api.creditcard;

import com.finora.api.account.Account;
import com.finora.api.common.persistence.AuditableEntity;
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
 * A user's credit card. Only billing metadata is stored — never the full card
 * number, CVV or expiration date; Finora plans finances, it does not process
 * cards. Closing/due days configure how new invoices are created; existing
 * invoices keep their snapshot dates when these change.
 */
@Entity
@Table(name = "credit_cards")
public class CreditCard extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String issuer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CreditCardBrand brand;

    @Column(name = "last_four_digits", length = 4)
    private String lastFourDigits;

    @Column(name = "credit_limit", nullable = false, precision = 14, scale = 2)
    private BigDecimal creditLimit;

    @Column(name = "closing_day", nullable = false)
    private int closingDay;

    @Column(name = "due_day", nullable = false)
    private int dueDay;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_payment_account_id")
    private Account defaultPaymentAccount;

    @Column(nullable = false)
    private boolean archived;

    protected CreditCard() {
    }

    public CreditCard(Long userId, String name, CreditCardBrand brand,
                      BigDecimal creditLimit, int closingDay, int dueDay) {
        this.userId = userId;
        this.name = name;
        this.brand = brand;
        this.creditLimit = creditLimit;
        this.closingDay = closingDay;
        this.dueDay = dueDay;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public CreditCardBrand getBrand() {
        return brand;
    }

    public void setBrand(CreditCardBrand brand) {
        this.brand = brand;
    }

    public String getLastFourDigits() {
        return lastFourDigits;
    }

    public void setLastFourDigits(String lastFourDigits) {
        this.lastFourDigits = lastFourDigits;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    public int getClosingDay() {
        return closingDay;
    }

    public void setClosingDay(int closingDay) {
        this.closingDay = closingDay;
    }

    public int getDueDay() {
        return dueDay;
    }

    public void setDueDay(int dueDay) {
        this.dueDay = dueDay;
    }

    public Account getDefaultPaymentAccount() {
        return defaultPaymentAccount;
    }

    public void setDefaultPaymentAccount(Account defaultPaymentAccount) {
        this.defaultPaymentAccount = defaultPaymentAccount;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }
}
