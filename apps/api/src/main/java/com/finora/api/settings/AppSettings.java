package com.finora.api.settings;

import com.finora.api.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Per-user financial assumptions used by budget status and the purchase
 * analysis engine. One row per user, created at registration with
 * conservative defaults.
 */
@Entity
@Table(name = "app_settings")
public class AppSettings extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false, unique = true)
    private Long userId;

    /** Cash the user never wants to go below (emergency liquidity). */
    @Column(name = "minimum_cash_buffer", nullable = false, precision = 14, scale = 2)
    private BigDecimal minimumCashBuffer;

    /** Max share of monthly income that installments + commitments may take (0-1). */
    @Column(name = "max_installment_commitment_ratio", nullable = false, precision = 5, scale = 4)
    private BigDecimal maxInstallmentCommitmentRatio;

    /** Monthly reference rate used for present-value comparison (0-0.2). */
    @Column(name = "monthly_opportunity_rate", nullable = false, precision = 7, scale = 6)
    private BigDecimal monthlyOpportunityRate;

    /** Budget consumption ratio (0-1) at which a budget is flagged as WARNING. */
    @Column(name = "budget_warning_threshold", nullable = false, precision = 5, scale = 4)
    private BigDecimal budgetWarningThreshold;

    protected AppSettings() {
    }

    /** Creates the user's settings row with the documented conservative defaults. */
    public static AppSettings withDefaults(Long userId) {
        AppSettings settings = new AppSettings();
        settings.userId = userId;
        settings.minimumCashBuffer = new BigDecimal("0.00");
        settings.maxInstallmentCommitmentRatio = new BigDecimal("0.3000");
        settings.monthlyOpportunityRate = new BigDecimal("0.000000");
        settings.budgetWarningThreshold = new BigDecimal("0.8000");
        return settings;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public BigDecimal getMinimumCashBuffer() {
        return minimumCashBuffer;
    }

    public void setMinimumCashBuffer(BigDecimal minimumCashBuffer) {
        this.minimumCashBuffer = minimumCashBuffer;
    }

    public BigDecimal getMaxInstallmentCommitmentRatio() {
        return maxInstallmentCommitmentRatio;
    }

    public void setMaxInstallmentCommitmentRatio(BigDecimal maxInstallmentCommitmentRatio) {
        this.maxInstallmentCommitmentRatio = maxInstallmentCommitmentRatio;
    }

    public BigDecimal getMonthlyOpportunityRate() {
        return monthlyOpportunityRate;
    }

    public void setMonthlyOpportunityRate(BigDecimal monthlyOpportunityRate) {
        this.monthlyOpportunityRate = monthlyOpportunityRate;
    }

    public BigDecimal getBudgetWarningThreshold() {
        return budgetWarningThreshold;
    }

    public void setBudgetWarningThreshold(BigDecimal budgetWarningThreshold) {
        this.budgetWarningThreshold = budgetWarningThreshold;
    }
}
