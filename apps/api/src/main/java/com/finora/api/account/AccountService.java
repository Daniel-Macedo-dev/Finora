package com.finora.api.account;

import com.finora.api.account.AccountDtos.AccountRequest;
import com.finora.api.account.AccountDtos.AccountResponse;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.payment.InvoicePaymentRepository;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.transaction.TransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AccountService {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final AccountBalanceService balances;
    private final InvoicePaymentRepository invoicePayments;
    private final CurrentUserProvider currentUser;

    public AccountService(AccountRepository accounts,
                          TransactionRepository transactions,
                          AccountBalanceService balances,
                          InvoicePaymentRepository invoicePayments,
                          CurrentUserProvider currentUser) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.balances = balances;
        this.invoicePayments = invoicePayments;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list() {
        Long userId = currentUser.currentUserId();
        return accounts.findAllByUserIdOrderByDisplayOrderAscNameAsc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(Long id) {
        return toResponse(find(id));
    }

    public AccountResponse create(AccountRequest request) {
        Long userId = currentUser.currentUserId();
        accounts.findByUserIdAndNameIgnoreCase(userId, request.name().trim()).ifPresent(existing -> {
            throw new BusinessRuleException("ACCOUNT_NAME_TAKEN", "Já existe uma conta com esse nome.");
        });
        Account account = new Account(
                userId,
                request.name().trim(),
                request.type(),
                MoneyRules.normalize(request.openingBalance()),
                request.displayOrder() != null ? request.displayOrder() : 0);
        return toResponse(accounts.save(account));
    }

    public AccountResponse update(Long id, AccountRequest request) {
        Long userId = currentUser.currentUserId();
        Account account = find(id);
        accounts.findByUserIdAndNameIgnoreCase(userId, request.name().trim()).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new BusinessRuleException("ACCOUNT_NAME_TAKEN", "Já existe uma conta com esse nome.");
            }
        });
        account.setName(request.name().trim());
        account.setType(request.type());
        account.setOpeningBalance(MoneyRules.normalize(request.openingBalance()));
        if (request.displayOrder() != null) {
            account.setDisplayOrder(request.displayOrder());
        }
        if (request.archived() != null) {
            account.setArchived(request.archived());
        }
        return toResponse(account);
    }

    public void delete(Long id) {
        Account account = find(id);
        if (transactions.existsByAccountId(account.getId())) {
            throw new BusinessRuleException(
                    "ACCOUNT_HAS_TRANSACTIONS",
                    "Esta conta possui transações e não pode ser excluída. Arquive a conta para preservá-la no histórico.");
        }
        if (invoicePayments.existsByAccountId(account.getId())) {
            throw new BusinessRuleException(
                    "ACCOUNT_HAS_INVOICE_PAYMENTS",
                    "Esta conta pagou faturas de cartão e não pode ser excluída. Arquive a conta para preservá-la no histórico.");
        }
        accounts.delete(account);
    }

    /** Foreign-owned ids resolve to 404 — never 403 — to avoid enumeration. */
    private Account find(Long id) {
        return accounts.findByIdAndUserId(id, currentUser.currentUserId())
                .orElseThrow(() -> new NotFoundException("Conta", id));
    }

    private AccountResponse toResponse(Account account) {
        BigDecimal current = account.getId() != null
                ? balances.currentBalance(account)
                : account.getOpeningBalance();
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                MoneyRules.normalize(account.getOpeningBalance()),
                MoneyRules.normalize(current),
                account.isArchived(),
                account.getDisplayOrder());
    }
}
