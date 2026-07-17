package com.finora.api.legacyconversion;

import com.finora.api.common.web.PageResponse;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.legacyconversion.LegacyConversionDtos.ConversionInventoryItem;
import com.finora.api.legacyconversion.LegacyConversionDtos.ConversionInventoryResponse;
import com.finora.api.legacyconversion.LegacyConversionDtos.ConversionInventorySummary;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paginated inventory of the user's legacy-credit transactions with their
 * conversion states. The page query never loads more than one page of rows;
 * conversion metadata is attached with a single bulk query per page.
 */
@Service
@Transactional(readOnly = true)
public class LegacyConversionInventoryService {

    static final int MAX_PAGE_SIZE = 100;

    /** Optional inventory filters; null fields are simply not applied. */
    public record InventoryFilters(YearMonth month,
                                   LocalDate from,
                                   LocalDate to,
                                   Long categoryId,
                                   BigDecimal minAmount,
                                   BigDecimal maxAmount,
                                   ConversionInventoryState state) {
    }

    private final TransactionRepository transactions;
    private final LegacyConversionRepository conversions;
    private final LegacyConversionEligibilityService eligibility;
    private final CurrentUserProvider currentUser;

    public LegacyConversionInventoryService(TransactionRepository transactions,
                                            LegacyConversionRepository conversions,
                                            LegacyConversionEligibilityService eligibility,
                                            CurrentUserProvider currentUser) {
        this.transactions = transactions;
        this.conversions = conversions;
        this.eligibility = eligibility;
        this.currentUser = currentUser;
    }

    public ConversionInventoryResponse inventory(InventoryFilters filters, int page, int size) {
        Long userId = currentUser.currentUserId();
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "occurredOn").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Transaction> result = transactions.findAll(specification(userId, filters), pageable);

        // One bulk query attaches every page row's conversion history.
        Map<Long, LegacyCreditConversion> active = new HashMap<>();
        Map<Long, LegacyCreditConversion> latest = new HashMap<>();
        List<Long> ids = result.getContent().stream().map(Transaction::getId).toList();
        if (!ids.isEmpty()) {
            for (LegacyCreditConversion conversion
                    : conversions.findAllByUserIdAndSourceTransactionIdIn(userId, ids)) {
                latest.put(conversion.getSourceTransactionId(), conversion);
                if (conversion.getStatus() == ConversionStatus.ACTIVE) {
                    active.put(conversion.getSourceTransactionId(), conversion);
                }
            }
        }

        Page<ConversionInventoryItem> items = result.map(source ->
                toItem(source, active.get(source.getId()), latest.get(source.getId())));
        return new ConversionInventoryResponse(summary(userId), PageResponse.from(items));
    }

    private ConversionInventorySummary summary(Long userId) {
        return new ConversionInventorySummary(
                conversions.countConvertibleSources(userId),
                conversions.countByUserIdAndStatus(userId, ConversionStatus.ACTIVE),
                conversions.countReversedSources(userId),
                MoneyRules.normalize(conversions.sumConvertibleSourceAmount(userId)));
    }

    private ConversionInventoryItem toItem(Transaction source,
                                           LegacyCreditConversion activeConversion,
                                           LegacyCreditConversion latestConversion) {
        var result = eligibility.evaluate(
                source,
                activeConversion != null,
                latestConversion != null
                        && latestConversion.getStatus() == ConversionStatus.REVERSED);
        ConversionInventoryState state = switch (result.status()) {
            case ALREADY_CONVERTED -> ConversionInventoryState.CONVERTED;
            case REVERSED_CONVERSION -> ConversionInventoryState.REVERSED;
            case ELIGIBLE -> ConversionInventoryState.ELIGIBLE;
            case INCOMPATIBLE_SOURCE, BLOCKED -> ConversionInventoryState.BLOCKED;
        };
        LegacyCreditConversion shown = activeConversion != null ? activeConversion : latestConversion;
        return new ConversionInventoryItem(
                source.getId(),
                source.getDescription(),
                MoneyRules.normalize(source.getAmount()),
                source.getOccurredOn(),
                new LegacyConversionDtos.CategorySummary(
                        source.getCategory().getId(), source.getCategory().getName()),
                source.getAccount() != null ? source.getAccount().getName() : null,
                state,
                result.reasonCode(),
                result.message(),
                shown != null ? shown.getId() : null,
                activeConversion != null ? activeConversion.getCardPurchaseId() : null,
                activeConversion != null ? activeConversion.getCardId() : null);
    }

    /**
     * Mandatory ownership + legacy-credit root predicate; filters are ANDed.
     * The state filter is resolved in the database so pagination stays exact.
     */
    private Specification<Transaction> specification(Long userId, InventoryFilters filters) {
        Specification<Transaction> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("userId"), userId),
                cb.isTrue(root.get("legacyCredit")));
        if (filters.month() != null) {
            spec = spec.and(between(filters.month().atDay(1), filters.month().atEndOfMonth()));
        }
        if (filters.from() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("occurredOn"), filters.from()));
        }
        if (filters.to() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.lessThanOrEqualTo(root.get("occurredOn"), filters.to()));
        }
        if (filters.categoryId() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("category").get("id"), filters.categoryId()));
        }
        if (filters.minAmount() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("amount"), filters.minAmount()));
        }
        if (filters.maxAmount() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.lessThanOrEqualTo(root.get("amount"), filters.maxAmount()));
        }
        if (filters.state() != null) {
            spec = spec.and(stateSpecification(filters.state()));
        }
        return spec;
    }

    private static Specification<Transaction> between(LocalDate from, LocalDate to) {
        return (root, query, cb) -> cb.between(root.get("occurredOn"), from, to);
    }

    private static Specification<Transaction> stateSpecification(ConversionInventoryState state) {
        return switch (state) {
            case CONVERTED -> (root, query, cb) -> cb.isFalse(root.get("financiallyActive"));
            case REVERSED -> (root, query, cb) -> cb.and(
                    cb.isTrue(root.get("financiallyActive")),
                    hasConversionWithStatus(root, query, cb, ConversionStatus.REVERSED));
            case ELIGIBLE -> (root, query, cb) -> cb.and(
                    convertibleShape(root, cb),
                    cb.not(hasConversionWithStatus(root, query, cb, ConversionStatus.REVERSED)));
            case BLOCKED -> (root, query, cb) -> cb.and(
                    cb.isTrue(root.get("financiallyActive")),
                    cb.not(convertibleShape(root, cb)));
        };
    }

    /** The database shape of "convertible": mirrors the eligibility rules. */
    private static Predicate convertibleShape(jakarta.persistence.criteria.Root<Transaction> root,
                                              jakarta.persistence.criteria.CriteriaBuilder cb) {
        return cb.and(
                cb.isTrue(root.get("financiallyActive")),
                cb.equal(root.get("type"), TransactionType.EXPENSE),
                cb.isNull(root.get("commitmentId")),
                cb.isNull(root.get("wishlistItemId")),
                cb.greaterThan(root.get("amount"), BigDecimal.ZERO));
    }

    private static Predicate hasConversionWithStatus(
            jakarta.persistence.criteria.Root<Transaction> root,
            jakarta.persistence.criteria.CriteriaQuery<?> query,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            ConversionStatus status) {
        Subquery<Long> subquery = query.subquery(Long.class);
        var conversion = subquery.from(LegacyCreditConversion.class);
        subquery.select(cb.literal(1L))
                .where(cb.equal(conversion.get("sourceTransactionId"), root.get("id")),
                        cb.equal(conversion.get("status"), status));
        return cb.exists(subquery);
    }
}
