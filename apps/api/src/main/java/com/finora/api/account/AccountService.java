package com.finora.api.account;

import com.finora.api.account.AccountDtos.AccountRequest;
import com.finora.api.account.AccountDtos.AccountResponse;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.payment.InvoicePaymentRepository;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.settings.SettingsService;
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
    private final SettingsService settings;

    public AccountService(AccountRepository accounts,
                          TransactionRepository transactions,
                          AccountBalanceService balances,
                          InvoicePaymentRepository invoicePayments,
                          CurrentUserProvider currentUser,
                          SettingsService settings) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.balances = balances;
        this.invoicePayments = invoicePayments;
        this.currentUser = currentUser;
        this.settings = settings;
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
        CurrencyCode currency = resolveCurrency(request.currency(), userId);
        MoneyRules.validateScale(request.openingBalance(), currency);
        Account account = new Account(
                userId,
                request.name().trim(),
                request.type(),
                MoneyRules.normalize(request.openingBalance(), currency),
                request.displayOrder() != null ? request.displayOrder() : 0,
                currency);
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
        assertCurrencyUnchanged(account, request.currency());
        MoneyRules.validateScale(request.openingBalance(), account.getCurrency());
        account.setName(request.name().trim());
        account.setType(request.type());
        account.setOpeningBalance(
                MoneyRules.normalize(request.openingBalance(), account.getCurrency()));
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

    /**
     * An omitted currency means the user's base currency. A foreign currency is
     * never inferred: the client must ask for it explicitly.
     */
    private CurrencyCode resolveCurrency(String requested, Long userId) {
        CurrencyCode explicit = CurrencyCode.parseOrNull(requested);
        return explicit != null ? explicit : settings.forUser(userId).getBaseCurrency();
    }

    /**
     * An account's currency is immutable: changing it would reinterpret every
     * movement already recorded against it instead of converting them. Omitting
     * the field keeps the current currency, so old clients keep working.
     */
    private void assertCurrencyUnchanged(Account account, String requested) {
        CurrencyCode explicit = CurrencyCode.parseOrNull(requested);
        if (explicit != null && explicit != account.getCurrency()) {
            throw new BusinessRuleException(
                    "CURRENCY_IMMUTABLE",
                    ("A moeda de uma conta não pode ser alterada (%s). Alterá-la "
                            + "reinterpretaria todos os lançamentos já registrados nela.")
                            .formatted(account.getCurrency().name()));
        }
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
                MoneyRules.normalize(account.getOpeningBalance(), account.getCurrency()),
                MoneyRules.normalize(current, account.getCurrency()),
                account.getCurrency().name(),
                account.isArchived(),
                account.getDisplayOrder());
    }
}
