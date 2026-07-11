package com.finora.api.purchaseanalysis;

import com.finora.api.account.AccountBalanceService;
import com.finora.api.account.AccountRepository;
import com.finora.api.commitment.CommitmentService;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the {@link FinancialContext} snapshot from real Finora data.
 *
 * <p>Averages look at the {@value #HISTORY_WINDOW_MONTHS} complete months
 * before the reference date (the current, partial month is excluded so a
 * mid-month analysis is not skewed) and divide by the number of months that
 * actually had transactions.
 */
@Service
public class FinancialContextService {

    static final int HISTORY_WINDOW_MONTHS = 3;

    private final AccountRepository accounts;
    private final AccountBalanceService balances;
    private final TransactionRepository transactions;
    private final CommitmentService commitments;

    public FinancialContextService(AccountRepository accounts,
                                   AccountBalanceService balances,
                                   TransactionRepository transactions,
                                   CommitmentService commitments) {
        this.accounts = accounts;
        this.balances = balances;
        this.transactions = transactions;
        this.commitments = commitments;
    }

    /**
     * Builds the snapshot exclusively from the given owner's data — every
     * query below carries the user predicate, so another user's income,
     * expenses, accounts or commitments can never influence this context.
     */
    @Transactional(readOnly = true)
    public FinancialContext build(Long userId, LocalDate referenceDate) {
        BigDecimal availableCash = accounts.findAllByUserIdOrderByDisplayOrderAscNameAsc(userId)
                .stream()
                .filter(account -> !account.isArchived())
                .map(balances::currentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        YearMonth reference = YearMonth.from(referenceDate);
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        int monthsWithData = 0;
        for (int i = 1; i <= HISTORY_WINDOW_MONTHS; i++) {
            YearMonth month = reference.minusMonths(i);
            BigDecimal income = transactions.sumAmountByTypeAndPeriod(
                    userId, TransactionType.INCOME, month.atDay(1), month.atEndOfMonth());
            BigDecimal expense = transactions.sumAmountByTypeAndPeriod(
                    userId, TransactionType.EXPENSE, month.atDay(1), month.atEndOfMonth());
            if (income.signum() != 0 || expense.signum() != 0) {
                monthsWithData++;
                totalIncome = totalIncome.add(income);
                totalExpense = totalExpense.add(expense);
            }
        }

        BigDecimal avgIncome = null;
        BigDecimal avgExpense = null;
        BigDecimal avgSurplus = null;
        if (monthsWithData > 0) {
            BigDecimal divisor = BigDecimal.valueOf(monthsWithData);
            avgIncome = totalIncome.divide(divisor, MoneyRules.SCALE, MoneyRules.ROUNDING);
            avgExpense = totalExpense.divide(divisor, MoneyRules.SCALE, MoneyRules.ROUNDING);
            avgSurplus = avgIncome.subtract(avgExpense);
        }

        BigDecimal monthlyCommitments = commitments.monthlyTotal(userId, reference.plusMonths(1));

        return new FinancialContext(
                MoneyRules.normalize(availableCash),
                avgIncome,
                avgExpense,
                avgSurplus,
                MoneyRules.normalize(monthlyCommitments),
                monthsWithData);
    }
}
