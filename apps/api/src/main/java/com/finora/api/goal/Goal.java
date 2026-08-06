package com.finora.api.goal;

import com.finora.api.common.money.CurrencyCode;
import com.finora.api.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "goals")
public class Goal extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /**
     * The currency this goal accumulates in. Target, balance and every
     * contribution share it.
     *
     * <p>Immutable: changing it would reinterpret history rather than
     * restate it.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3, updatable = false)
    private CurrencyCode currency = CurrencyCode.BRL;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "target_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal targetAmount;

    @Column(name = "current_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal currentAmount;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(nullable = false)
    private boolean archived;

    /** Client-side identity of a goal created offline; NULL when created online. */
    @Column(name = "client_resource_id", updatable = false)
    private UUID clientResourceId;

    /** Optimistic concurrency token for offline UPDATE/DELETE conflict detection. */
    @Version
    private long version;

    protected Goal() {
    }

    public Goal(Long userId, String name, BigDecimal targetAmount, BigDecimal currentAmount,
                LocalDate targetDate) {
        this.userId = userId;
        this.name = name;
        this.targetAmount = targetAmount;
        this.currentAmount = currentAmount;
        this.targetDate = targetDate;
        this.archived = false;
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
     * Set once, at creation. The column is insert-only, so a later change
     * cannot reach the database even if some caller attempts it.
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

    public BigDecimal getTargetAmount() {
        return targetAmount;
    }

    public void setTargetAmount(BigDecimal targetAmount) {
        this.targetAmount = targetAmount;
    }

    public BigDecimal getCurrentAmount() {
        return currentAmount;
    }

    public void setCurrentAmount(BigDecimal currentAmount) {
        this.currentAmount = currentAmount;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public void setTargetDate(LocalDate targetDate) {
        this.targetDate = targetDate;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
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
