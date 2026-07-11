package com.finora.api.purchaseanalysis;

import java.math.BigDecimal;

/**
 * Snapshot of the user's financial situation used as input for deterministic
 * analysis. Average fields are null when there is no history to compute them
 * from — callers must distinguish "zero" from "unknown".
 *
 * <p>Card figures describe obligations and capacity, never spendable money:
 * available card limit is not cash and is never added to {@code availableCash}.
 *
 * @param availableCash             sum of current balances of non-archived accounts
 * @param avgMonthlyIncome          average income of the last complete months with data, or null
 * @param avgMonthlyExpense         average expense of the last complete months with data, or null
 * @param avgMonthlySurplus         avgMonthlyIncome - avgMonthlyExpense, or null when both are unknown
 * @param monthlyCommitments        total of active recurring commitments due next month
 * @param cardOutstandingTotal      unpaid card obligations across every invoice, present and future
 * @param nextMonthCardInstallments active card installments falling on next month's invoices
 * @param historyMonthsUsed         how many of the inspected months actually had transactions
 */
public record FinancialContext(
        BigDecimal availableCash,
        BigDecimal avgMonthlyIncome,
        BigDecimal avgMonthlyExpense,
        BigDecimal avgMonthlySurplus,
        BigDecimal monthlyCommitments,
        BigDecimal cardOutstandingTotal,
        BigDecimal nextMonthCardInstallments,
        int historyMonthsUsed) {
}
