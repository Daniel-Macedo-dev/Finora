package com.finora.api.settings;

import com.finora.api.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Singleton row (id = 1) with the financial assumptions used by budget status
 * and the purchase analysis engine.
 */
@Entity
@Table(name = "app_settings")
public class AppSettings extends AuditableEntity {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

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

    public Long getId() {
        return id;
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
