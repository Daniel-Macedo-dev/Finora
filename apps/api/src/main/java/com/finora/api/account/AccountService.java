package com.finora.api.account;

import com.finora.api.account.AccountDtos.AccountRequest;
import com.finora.api.account.AccountDtos.AccountResponse;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
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

    public AccountService(AccountRepository accounts, TransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list() {
        return accounts.findAllByOrderByDisplayOrderAscNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(Long id) {
        return toResponse(find(id));
    }

    public AccountResponse create(AccountRequest request) {
        accounts.findByNameIgnoreCase(request.name().trim()).ifPresent(existing -> {
            throw new BusinessRuleException("ACCOUNT_NAME_TAKEN", "Já existe uma conta com esse nome.");
        });
        Account account = new Account(
                request.name().trim(),
                request.type(),
                MoneyRules.normalize(request.openingBalance()),
                request.displayOrder() != null ? request.displayOrder() : 0);
        return toResponse(accounts.save(account));
    }

    public AccountResponse update(Long id, AccountRequest request) {
        Account account = find(id);
        accounts.findByNameIgnoreCase(request.name().trim()).ifPresent(existing -> {
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
        if (transactions.existsByAccountId(id)) {
            throw new BusinessRuleException(
                    "ACCOUNT_HAS_TRANSACTIONS",
                    "Esta conta possui transações e não pode ser excluída. Arquive a conta para preservá-la no histórico.");
        }
        accounts.delete(account);
    }

    private Account find(Long id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("Conta", id));
    }

    private AccountResponse toResponse(Account account) {
        BigDecimal movement = account.getId() != null ? accounts.netMovement(account.getId()) : null;
        BigDecimal current = account.getOpeningBalance()
                .add(movement != null ? movement : BigDecimal.ZERO);
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
