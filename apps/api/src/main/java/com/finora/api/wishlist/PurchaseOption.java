package com.finora.api.wishlist;

import com.finora.api.common.persistence.AuditableEntity;
import com.finora.api.creditcard.CreditCard;
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

@Entity
@Table(name = "purchase_options")
public class PurchaseOption extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wishlist_item_id", nullable = false)
    private WishlistItem item;

    @Column(nullable = false, length = 150)
    private String merchant;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_kind", nullable = false, length = 20)
    private PurchaseOptionKind kind;

    /** Advertised price: cash price, or total nominal price for installments. */
    @Column(name = "base_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal basePrice;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal shipping;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal fees;

    @Column(name = "installment_count")
    private Integer installmentCount;

    @Column(name = "installment_amount", precision = 14, scale = 2)
    private BigDecimal installmentAmount;

    /** Optional card an INSTALLMENT option would be charged on (same owner as the item). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credit_card_id")
    private CreditCard creditCard;

    @Column(columnDefinition = "text")
    private String notes;

    protected PurchaseOption() {
    }

    public PurchaseOption(WishlistItem item, String merchant, PurchaseOptionKind kind,
                          BigDecimal basePrice, BigDecimal shipping, BigDecimal fees) {
        this.item = item;
        this.merchant = merchant;
        this.kind = kind;
        this.basePrice = basePrice;
        this.shipping = shipping;
        this.fees = fees;
    }

    /** Total nominal cost of taking this option: price + shipping + fees. */
    public BigDecimal nominalCost() {
        return basePrice.add(shipping).add(fees);
    }

    public Long getId() {
        return id;
    }

    public WishlistItem getItem() {
        return item;
    }

    public String getMerchant() {
        return merchant;
    }

    public void setMerchant(String merchant) {
        this.merchant = merchant;
    }

    public PurchaseOptionKind getKind() {
        return kind;
    }

    public void setKind(PurchaseOptionKind kind) {
        this.kind = kind;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public void setBasePrice(BigDecimal basePrice) {
        this.basePrice = basePrice;
    }

    public BigDecimal getShipping() {
        return shipping;
    }

    public void setShipping(BigDecimal shipping) {
        this.shipping = shipping;
    }

    public BigDecimal getFees() {
        return fees;
    }

    public void setFees(BigDecimal fees) {
        this.fees = fees;
    }

    public Integer getInstallmentCount() {
        return installmentCount;
    }

    public void setInstallmentCount(Integer installmentCount) {
        this.installmentCount = installmentCount;
    }

    public BigDecimal getInstallmentAmount() {
        return installmentAmount;
    }

    public void setInstallmentAmount(BigDecimal installmentAmount) {
        this.installmentAmount = installmentAmount;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public CreditCard getCreditCard() {
        return creditCard;
    }

    public void setCreditCard(CreditCard creditCard) {
        this.creditCard = creditCard;
    }
}
