package com.finora.api.wishlist;

import com.finora.api.category.Category;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.common.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "wishlist_items")
public class WishlistItem extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /**
     * The currency this item is priced in. Options, shipping, fees and every
     * price snapshot inherit it.
     *
     * <p>Immutable: changing it would reinterpret history rather than
     * restate it.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3, updatable = false)
    private CurrencyCode currency = CurrencyCode.BRL;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "text")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "reference_price", precision = 14, scale = 2)
    private BigDecimal referencePrice;

    @Column(name = "target_price", precision = 14, scale = 2)
    private BigDecimal targetPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WishlistPriority priority;

    @Column(name = "desired_date")
    private LocalDate desiredDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WishlistStatus status;

    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id asc")
    private List<PurchaseOption> options = new ArrayList<>();

    /**
     * Client-side identity of an item created offline; NULL when created online.
     * Offline-created purchase options and price snapshots reference their
     * parent through this value until the item receives a server id.
     */
    @Column(name = "client_resource_id", updatable = false)
    private UUID clientResourceId;

    /** Optimistic concurrency token for offline UPDATE/DELETE conflict detection. */
    @Version
    private long version;

    protected WishlistItem() {
    }

    public WishlistItem(Long userId, String name, WishlistPriority priority) {
        this.userId = userId;
        this.name = name;
        this.priority = priority;
        this.status = WishlistStatus.PLANNING;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    /**
     * Set once, at creation. Options, price snapshots and the executed purchase
     * all inherit it, so the column is insert-only.
     */
    public void setCurrency(CurrencyCode currency) {
        this.currency = currency;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public BigDecimal getReferencePrice() {
        return referencePrice;
    }

    public void setReferencePrice(BigDecimal referencePrice) {
        this.referencePrice = referencePrice;
    }

    public BigDecimal getTargetPrice() {
        return targetPrice;
    }

    public void setTargetPrice(BigDecimal targetPrice) {
        this.targetPrice = targetPrice;
    }

    public WishlistPriority getPriority() {
        return priority;
    }

    public void setPriority(WishlistPriority priority) {
        this.priority = priority;
    }

    public LocalDate getDesiredDate() {
        return desiredDate;
    }

    public void setDesiredDate(LocalDate desiredDate) {
        this.desiredDate = desiredDate;
    }

    public WishlistStatus getStatus() {
        return status;
    }

    public void setStatus(WishlistStatus status) {
        this.status = status;
    }

    public List<PurchaseOption> getOptions() {
        return options;
    }

    public UUID getClientResourceId() {
        return clientResourceId;
    }

    public void setClientResourceId(UUID clientResourceId) {
        this.clientResourceId = clientResourceId;
    }

    public long getVersion() {
        return version;
    }
}
