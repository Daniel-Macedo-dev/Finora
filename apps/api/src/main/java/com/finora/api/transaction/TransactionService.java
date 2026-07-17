package com.finora.api.transaction;

import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.category.Category;
import com.finora.api.category.CategoryRepository;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.common.web.PageResponse;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.legacyconversion.ConversionStatus;
import com.finora.api.legacyconversion.LegacyConversionRepository;
import com.finora.api.legacyconversion.LegacyCreditConversion;
import com.finora.api.transaction.TransactionDtos.TransactionRequest;
import com.finora.api.transaction.TransactionDtos.TransactionResponse;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final LegacyConversionRepository conversions;
    private final CurrentUserProvider currentUser;

    public TransactionService(TransactionRepository transactions,
                              CategoryRepository categories,
                              AccountRepository accounts,
                              LegacyConversionRepository conversions,
                              CurrentUserProvider currentUser) {
        this.transactions = transactions;
        this.categories = categories;
        this.accounts = accounts;
        this.conversions = conversions;
        this.currentUser = currentUser;
    }

    /**
     * Paginated search over the authenticated user's transactions. The
     * ownership predicate is the mandatory root of the Specification — every
     * filter combination (and the pagination count query) runs inside it.
     * Filters are ANDed; {@code month} covers the calendar month; results are
     * ordered by date (newest first), then id.
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> search(YearMonth month,
                                                    TransactionType type,
                                                    Long categoryId,
                                                    Long accountId,
                                                    String search,
                                                    int page,
                                                    int size) {
        Specification<Transaction> spec =
                TransactionSpecifications.ownedBy(currentUser.currentUserId());
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
        var result = transactions.findAll(spec, pageable);
        // Conversion audit metadata for the whole page in one bulk query.
        Map<Long, LegacyCreditConversion> latest = latestConversionsBySource(
                currentUser.currentUserId(), result.getContent());
        return PageResponse.from(result.map(t -> withConversion(t, latest.get(t.getId()))));
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(Long id) {
        Transaction transaction = find(id);
        Map<Long, LegacyCreditConversion> latest = latestConversionsBySource(
                transaction.getUserId(), List.of(transaction));
        return withConversion(transaction, latest.get(transaction.getId()));
    }

    /**
     * Latest conversion per legacy source on the page. Only legacy-credit rows
     * can have conversions, so the lookup is skipped entirely for pages
     * without them.
     */
    private Map<Long, LegacyCreditConversion> latestConversionsBySource(
            Long userId, List<Transaction> page) {
        List<Long> legacyIds = page.stream()
                .filter(Transaction::isLegacyCredit)
                .map(Transaction::getId)
                .toList();
        if (legacyIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, LegacyCreditConversion> latest = new HashMap<>();
        for (LegacyCreditConversion conversion
                : conversions.findAllByUserIdAndSourceTransactionIdIn(userId, legacyIds)) {
            // Ordered by conversion time: active/most recent wins.
            latest.put(conversion.getSourceTransactionId(), conversion);
        }
        return latest;
    }

    private static TransactionResponse withConversion(Transaction transaction,
                                                      LegacyCreditConversion conversion) {
        if (conversion == null) {
            return TransactionResponse.from(transaction);
        }
        return TransactionResponse.from(
                transaction,
                conversion.getStatus(),
                conversion.getStatus() == ConversionStatus.ACTIVE
                        ? conversion.getCardPurchaseId()
                        : null);
    }

    public TransactionResponse create(TransactionRequest request) {
        Long userId = currentUser.currentUserId();
        Category category = resolveCategory(userId, request);
        Transaction transaction = new Transaction(
                userId,
                request.type(),
                MoneyRules.normalize(request.amount()),
                request.description().trim(),
                request.date(),
                category);
        applyOptionalFields(userId, transaction, request);
        return TransactionResponse.from(transactions.save(transaction));
    }

    public TransactionResponse update(Long id, TransactionRequest request) {
        Long userId = currentUser.currentUserId();
        Transaction transaction = find(id);
        // A converted source is the immutable audit record of its conversion:
        // description, amount, category and date must stay as they were.
        if (!transaction.isFinanciallyActive()) {
            throw new BusinessRuleException("TRANSACTION_CONVERTED",
                    "Esta transação foi convertida em compra de cartão e é um registro de "
                            + "auditoria. Estorne a conversão para editá-la.");
        }
        Category category = resolveCategory(userId, request);
        transaction.setType(request.type());
        transaction.setAmount(MoneyRules.normalize(request.amount()));
        transaction.setDescription(request.description().trim());
        transaction.setOccurredOn(request.date());
        transaction.setCategory(category);
        applyOptionalFields(userId, transaction, request);
        return TransactionResponse.from(transaction);
    }

    public void delete(Long id) {
        Transaction transaction = find(id);
        // Recurring-generated transactions are undone through their occurrence,
        // keeping the recurring ledger and the account history in sync.
        if (transaction.getCommitmentId() != null) {
            throw new BusinessRuleException("TRANSACTION_FROM_RECURRING",
                    "Esta transação foi gerada por um recorrente. "
                            + "Estorne a ocorrência na área de Recorrentes.");
        }
        // Sources with conversion history anchor the conversion audit trail
        // (active or reversed) and can never be removed.
        if (transaction.isLegacyCredit() && conversions.existsBySourceTransactionId(id)) {
            throw new BusinessRuleException("TRANSACTION_HAS_CONVERSION_HISTORY",
                    "Esta transação possui histórico de conversão de crédito legado "
                            + "e não pode ser excluída.");
        }
        transactions.delete(transaction);
    }

    private void applyOptionalFields(Long userId, Transaction transaction, TransactionRequest request) {
        // Generic CREDIT belongs to the pre-card era: new credit spending goes
        // through the credit-card domain, where it gets invoices and limits.
        // Editing a legacy credit entry that keeps its CREDIT method is safe.
        if (request.paymentMethod() == PaymentMethod.CREDIT && !transaction.isLegacyCredit()) {
            throw new BusinessRuleException("USE_CREDIT_CARD_PURCHASE",
                    "Para registrar uma nova compra no crédito, use a área de Cartões.");
        }
        if (request.accountId() != null) {
            // Owner-scoped lookup: another user's account id behaves as absent.
            Account account = accounts.findByIdAndUserId(request.accountId(), userId)
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

    private Category resolveCategory(Long userId, TransactionRequest request) {
        Category category = categories.findByIdAndUserId(request.categoryId(), userId)
                .orElseThrow(() -> new NotFoundException("Categoria", request.categoryId()));
        if (!category.getType().name().equals(request.type().name())) {
            throw new BusinessRuleException("CATEGORY_TYPE_MISMATCH",
                    "A categoria selecionada não corresponde ao tipo da transação.");
        }
        return category;
    }

    private Transaction find(Long id) {
        return transactions.findByIdAndUserId(id, currentUser.currentUserId())
                .orElseThrow(() -> new NotFoundException("Transação", id));
    }
}
