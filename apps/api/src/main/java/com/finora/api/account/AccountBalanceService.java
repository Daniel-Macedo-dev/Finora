package com.finora.api.account;

import com.finora.api.creditcard.payment.InvoicePaymentRepository;
import java.math.BigDecimal;
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
}
