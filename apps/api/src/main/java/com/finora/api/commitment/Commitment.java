package com.finora.api.commitment;

import com.finora.api.account.Account;
import com.finora.api.category.Category;
import com.finora.api.common.persistence.AuditableEntity;
import com.finora.api.creditcard.CreditCard;
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

@Entity
@Table(name = "commitments")
public class Commitment extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", nullable = false, length = 20)
    private ExecutionMode executionMode = ExecutionMode.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", nullable = false, length = 30)
    private RecurrenceTarget targetKind = RecurrenceTarget.PROJECTION_ONLY;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credit_card_id")
    private CreditCard creditCard;

    @Column(name = "installment_count", nullable = false)
    private int installmentCount = 1;

    /**
     * Earliest date automatic processing may reach when catching up. Set when
     * a legacy CREDIT definition is mapped to a real card so automation never
     * backfills historical occurrences; NULL keeps the original behavior
     * (catch-up from the start date).
     */
    @Column(name = "automation_from")
    private LocalDate automationFrom;

    protected Commitment() {
    }

    public Commitment(Long userId, String description, BigDecimal amount, Category category,
                      CommitmentCadence cadence, Integer dueDay, LocalDate startDate) {
        this.userId = userId;
        this.description = description;
        this.amount = amount;
        this.category = category;
        this.cadence = cadence;
        this.dueDay = dueDay;
        this.startDate = startDate;
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
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

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(ExecutionMode executionMode) {
        this.executionMode = executionMode;
    }

    public RecurrenceTarget getTargetKind() {
        return targetKind;
    }

    public void setTargetKind(RecurrenceTarget targetKind) {
        this.targetKind = targetKind;
    }

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public CreditCard getCreditCard() {
        return creditCard;
    }

    public void setCreditCard(CreditCard creditCard) {
        this.creditCard = creditCard;
    }

    public int getInstallmentCount() {
        return installmentCount;
    }

    public void setInstallmentCount(int installmentCount) {
        this.installmentCount = installmentCount;
    }

    public LocalDate getAutomationFrom() {
        return automationFrom;
    }

    public void setAutomationFrom(LocalDate automationFrom) {
        this.automationFrom = automationFrom;
    }
}
