package com.finora.api.account;

import com.finora.api.creditcard.payment.InvoicePaymentRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single formula for an account's current balance:
 *
 * <pre>
 * balance = opening balance
 *         + account-linked incomes − account-linked regular expenses
 *         − completed, non-reversed credit-card invoice payments
 * </pre>
 *
 * <p>Card purchases never touch a bank account — only paying the invoice
 * moves cash, and it moves it exactly once (reversals restore it once).
 */
@Service
@Transactional(readOnly = true)
public class AccountBalanceService {

    private final AccountRepository accounts;
    private final InvoicePaymentRepository invoicePayments;

    public AccountBalanceService(AccountRepository accounts,
                                 InvoicePaymentRepository invoicePayments) {
        this.accounts = accounts;
        this.invoicePayments = invoicePayments;
    }

    public BigDecimal currentBalance(Account account) {
        BigDecimal movement = accounts.netMovement(account.getId(), account.getUserId());
        BigDecimal settled = invoicePayments.sumCompletedByAccount(
                account.getId(), account.getUserId());
        return account.getOpeningBalance()
                .add(movement != null ? movement : BigDecimal.ZERO)
                .subtract(settled);
    }

    /**
     * The same formula for a whole list of accounts, in two queries total.
     *
     * <p>Calling {@link #currentBalance} in a loop costs two queries per row,
     * which an overview of every account turns into a scan per account. The
     * grouped queries below are read once and joined in memory against a map
     * bounded by the user's own account count.
     *
     * @param accountList accounts already loaded and owner-verified by the caller
     * @return balance per account id, in the caller's iteration order
     */
    public Map<Long, BigDecimal> currentBalances(Long userId, List<Account> accountList) {
        Map<Long, BigDecimal> movements = toMap(accounts.netMovementGroupedByAccount(userId));
        Map<Long, BigDecimal> settled = toMap(invoicePayments.sumCompletedGroupedByAccount(userId));
        Map<Long, BigDecimal> balances = new LinkedHashMap<>();
        for (Account account : accountList) {
            balances.put(
                    account.getId(),
                    account.getOpeningBalance()
                            .add(movements.getOrDefault(account.getId(), BigDecimal.ZERO))
                            .subtract(settled.getOrDefault(account.getId(), BigDecimal.ZERO)));
        }
        return balances;
    }

    private static Map<Long, BigDecimal> toMap(List<Object[]> rows) {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) {
                continue;
            }
            map.put((Long) row[0], row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1]);
        }
        return map;
    }
}
