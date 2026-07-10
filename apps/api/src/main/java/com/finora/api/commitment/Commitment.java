package com.finora.api.commitment;

import com.finora.api.category.Category;
import com.finora.api.common.persistence.AuditableEntity;
import com.finora.api.transaction.PaymentMethod;
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
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

@Entity
@Table(name = "commitments")
public class Commitment extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommitmentCadence cadence;

    /** Day of month a MONTHLY commitment is due (clamped to month length). */
    @Column(name = "due_day")
    private Integer dueDay;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(nullable = false)
    private boolean active;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    protected Commitment() {
    }

    public Commitment(String description, BigDecimal amount, Category category,
                      CommitmentCadence cadence, Integer dueDay, LocalDate startDate) {
        this.description = description;
        this.amount = amount;
        this.category = category;
        this.cadence = cadence;
        this.dueDay = dueDay;
        this.startDate = startDate;
        this.active = true;
    }

    /**
     * The date this commitment falls due inside the given month, or empty when
     * it does not occur in that month (inactive, before start, after end, or a
     * YEARLY commitment anchored to another month).
     */
    public Optional<LocalDate> occurrenceIn(YearMonth month) {
        if (!active) {
            return Optional.empty();
        }
        YearMonth startMonth = YearMonth.from(startDate);
        if (month.isBefore(startMonth)) {
            return Optional.empty();
        }
        if (endDate != null && month.isAfter(YearMonth.from(endDate))) {
            return Optional.empty();
        }
        LocalDate due;
        if (cadence == CommitmentCadence.MONTHLY) {
            int day = dueDay != null ? dueDay : startDate.getDayOfMonth();
            due = month.atDay(Math.min(day, month.lengthOfMonth()));
        } else {
            if (month.getMonthValue() != startDate.getMonthValue()) {
                return Optional.empty();
            }
            due = month.atDay(Math.min(startDate.getDayOfMonth(), month.lengthOfMonth()));
        }
        if (due.isBefore(startDate) || (endDate != null && due.isAfter(endDate))) {
            return Optional.empty();
        }
        return Optional.of(due);
    }

    public Long getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public CommitmentCadence getCadence() {
        return cadence;
    }

    public void setCadence(CommitmentCadence cadence) {
        this.cadence = cadence;
    }

    public Integer getDueDay() {
        return dueDay;
    }

    public void setDueDay(Integer dueDay) {
        this.dueDay = dueDay;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }
}
