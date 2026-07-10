package com.finora.api.transaction;

import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.category.Category;
import com.finora.api.category.CategoryRepository;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.common.web.PageResponse;
import com.finora.api.transaction.TransactionDtos.TransactionRequest;
import com.finora.api.transaction.TransactionDtos.TransactionResponse;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TransactionService {

    static final int MAX_PAGE_SIZE = 100;

    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    private final AccountRepository accounts;

    public TransactionService(TransactionRepository transactions,
                              CategoryRepository categories,
                              AccountRepository accounts) {
        this.transactions = transactions;
        this.categories = categories;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> search(YearMonth month,
                                                    TransactionType type,
                                                    Long categoryId,
                                                    Long accountId,
                                                    String search,
                                                    int page,
                                                    int size) {
        Specification<Transaction> spec = Specification.unrestricted();
        if (month != null) {
            spec = spec.and(TransactionSpecifications.occurredBetween(month.atDay(1), month.atEndOfMonth()));
        }
        if (type != null) {
            spec = spec.and(TransactionSpecifications.hasType(type));
        }
        if (categoryId != null) {
            spec = spec.and(TransactionSpecifications.hasCategory(categoryId));
        }
        if (accountId != null) {
            spec = spec.and(TransactionSpecifications.hasAccount(accountId));
        }
        if (search != null && !search.isBlank()) {
            spec = spec.and(TransactionSpecifications.descriptionContains(search.trim()));
        }
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "occurredOn").and(Sort.by(Sort.Direction.DESC, "id")));
        return PageResponse.from(transactions.findAll(spec, pageable).map(TransactionResponse::from));
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(Long id) {
        return TransactionResponse.from(find(id));
    }

    public TransactionResponse create(TransactionRequest request) {
        Category category = resolveCategory(request);
        Transaction transaction = new Transaction(
                request.type(),
                MoneyRules.normalize(request.amount()),
                request.description().trim(),
                request.date(),
                category);
        applyOptionalFields(transaction, request);
        return TransactionResponse.from(transactions.save(transaction));
    }

    public TransactionResponse update(Long id, TransactionRequest request) {
        Transaction transaction = find(id);
        Category category = resolveCategory(request);
        transaction.setType(request.type());
        transaction.setAmount(MoneyRules.normalize(request.amount()));
        transaction.setDescription(request.description().trim());
        transaction.setOccurredOn(request.date());
        transaction.setCategory(category);
        applyOptionalFields(transaction, request);
        return TransactionResponse.from(transaction);
    }

    public void delete(Long id) {
        transactions.delete(find(id));
    }

    private void applyOptionalFields(Transaction transaction, TransactionRequest request) {
        if (request.accountId() != null) {
            Account account = accounts.findById(request.accountId())
                    .orElseThrow(() -> new NotFoundException("Conta", request.accountId()));
            transaction.setAccount(account);
        } else {
            transaction.setAccount(null);
        }
        transaction.setPaymentMethod(request.paymentMethod());
        transaction.setNotes(request.notes() != null && !request.notes().isBlank()
                ? request.notes().trim()
                : null);
    }

    private Category resolveCategory(TransactionRequest request) {
        Category category = categories.findById(request.categoryId())
                .orElseThrow(() -> new NotFoundException("Categoria", request.categoryId()));
        if (!category.getType().name().equals(request.type().name())) {
            throw new BusinessRuleException("CATEGORY_TYPE_MISMATCH",
                    "A categoria selecionada não corresponde ao tipo da transação.");
        }
        return category;
    }

    private Transaction find(Long id) {
        return transactions.findById(id).orElseThrow(() -> new NotFoundException("Transação", id));
    }
}
