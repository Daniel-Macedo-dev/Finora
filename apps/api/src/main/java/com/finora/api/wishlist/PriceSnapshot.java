package com.finora.api.wishlist;

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
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "wishlist_price_snapshots")
public class PriceSnapshot extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wishlist_item_id", nullable = false, updatable = false)
    private WishlistItem item;

    @Column(name = "purchase_option_id")
    private Long purchaseOptionId;

    @Column(name = "series_key", nullable = false, length = 220)
    private String seriesKey;

    @Column(name = "client_request_id", nullable = false, updatable = false)
    private UUID clientRequestId;

    @Column(nullable = false, length = 150)
    private String merchant;

    @Column(name = "merchant_normalized", nullable = false, length = 150)
    private String merchantNormalized;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_kind", nullable = false, length = 20)
    private PurchaseOptionKind kind;

    @Column(name = "base_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal basePrice;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal shipping;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal fees;

    @Column(name = "nominal_cost", nullable = false, precision = 14, scale = 2)
    private BigDecimal nominalCost;

    @Column(name = "installment_count")
    private Integer installmentCount;

    @Column(name = "installment_amount", precision = 14, scale = 2)
    private BigDecimal installmentAmount;

    @Column(name = "observed_on", nullable = false)
    private LocalDate observedOn;

    @Column(name = "offer_url", length = 2000)
    private String offerUrl;

    @Column(length = 2000)
    private String notes;

    @Version
    private long version;

    protected PriceSnapshot() {
    }

    public PriceSnapshot(Long userId, WishlistItem item, UUID clientRequestId) {
        this.userId = userId;
        this.item = item;
        this.clientRequestId = clientRequestId;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public WishlistItem getItem() { return item; }
    public Long getPurchaseOptionId() { return purchaseOptionId; }
    public void setPurchaseOptionId(Long value) { purchaseOptionId = value; }
    public String getSeriesKey() { return seriesKey; }
    public void setSeriesKey(String value) { seriesKey = value; }
    public UUID getClientRequestId() { return clientRequestId; }
    public String getMerchant() { return merchant; }
    public void setMerchant(String value) { merchant = value; }
    public String getMerchantNormalized() { return merchantNormalized; }
    public void setMerchantNormalized(String value) { merchantNormalized = value; }
    public PurchaseOptionKind getKind() { return kind; }
    public void setKind(PurchaseOptionKind value) { kind = value; }
    public BigDecimal getBasePrice() { return basePrice; }
    public void setBasePrice(BigDecimal value) { basePrice = value; }
    public BigDecimal getShipping() { return shipping; }
    public void setShipping(BigDecimal value) { shipping = value; }
    public BigDecimal getFees() { return fees; }
    public void setFees(BigDecimal value) { fees = value; }
    public BigDecimal getNominalCost() { return nominalCost; }
    public void setNominalCost(BigDecimal value) { nominalCost = value; }
    public Integer getInstallmentCount() { return installmentCount; }
    public void setInstallmentCount(Integer value) { installmentCount = value; }
    public BigDecimal getInstallmentAmount() { return installmentAmount; }
    public void setInstallmentAmount(BigDecimal value) { installmentAmount = value; }
    public LocalDate getObservedOn() { return observedOn; }
    public void setObservedOn(LocalDate value) { observedOn = value; }
    public String getOfferUrl() { return offerUrl; }
    public void setOfferUrl(String value) { offerUrl = value; }
    public String getNotes() { return notes; }
    public void setNotes(String value) { notes = value; }
}
