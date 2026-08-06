package com.finora.api.purchaseanalysis;

import java.math.BigDecimal;

/**
 * Snapshot of the user's financial situation used as input for deterministic
 * analysis. Average fields are null when there is no history to compute them
 * from — callers must distinguish "zero" from "unknown".
 *
 * <p><strong>Temporary, and currency-blind.</strong> Every scalar here was
 * produced by adding amounts together regardless of the currency they are
 * denominated in, so a user with a dollar account and a real account gets an
 * {@code availableCash} that is reais plus dollars, and an average whose divisor
 * counted months in which only the other currency moved.
 *
 * <p>{@link PurchaseFinancialContext} is the currency-aware replacement, and the
 * purchase engine already uses it. This record survives for exactly one reason:
 * {@code InsightService} still reads it, and migrating that is a separate piece
 * of work. No new production consumer may depend on it, it must not be expanded,
 * and it is deleted once insights move over.
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
