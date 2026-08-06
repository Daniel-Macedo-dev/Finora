package com.finora.api.budget;

import com.finora.api.budget.BudgetDtos.BudgetCategory;
import com.finora.api.budget.BudgetDtos.BudgetRequest;
import com.finora.api.budget.BudgetDtos.BudgetResponse;
import com.finora.api.budget.BudgetDtos.BudgetStatus;
import com.finora.api.budget.BudgetDtos.BudgetSummaryResponse;
import com.finora.api.category.Category;
import com.finora.api.category.CategoryRepository;
import com.finora.api.category.CategoryType;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.common.money.CurrencyTotals;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.adjustment.InvoiceAdjustmentRepository;
import com.finora.api.creditcard.installment.CardInstallmentRepository;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.settings.SettingsService;
import com.finora.api.transaction.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly budgets per expense category, always scoped to the authenticated
 * owner. Consumption is derived from the owner's transactions at read time —
 * never stored — so budget figures cannot drift from the transaction history.
 * A budget is WARNING at the owner's configurable threshold and EXCEEDED at
 * 100%; percentUsed may exceed 100.
 *
 * <p>A budget is denominated in the owner's base currency. Spending in the same
 * category can be denominated in another one, and Finora has no rates to bring
 * it in. Rather than treating that spending as zero — which would let a blown
 * budget report as HEALTHY — those budgets are marked INCOMPLETE, their
 * foreign totals are reported alongside, and the remaining amount and
 * percentage are withheld instead of understated.
 */
@Service
@Transactional
public class BudgetService {

    private final BudgetRepository budgets;
    private final CategoryRepository categories;
    private final TransactionRepository transactions;
    private final CardInstallmentRepository installments;
    private final InvoiceAdjustmentRepository adjustments;
    private final SettingsService settings;
    private final CurrentUserProvider currentUser;

    public BudgetService(BudgetRepository budgets,
                         CategoryRepository categories,
                         TransactionRepository transactions,
                         CardInstallmentRepository installments,
                         InvoiceAdjustmentRepository adjustments,
                         SettingsService settings,
                         CurrentUserProvider currentUser) {
        this.budgets = budgets;
        this.categories = categories;
        this.transactions = transactions;
        this.installments = installments;
        this.adjustments = adjustments;
        this.settings = settings;
        this.currentUser = currentUser;
    }

    /**
     * The month's budgets, consumption included.
     *
     * <p>Consumption for every budget comes from three grouped queries covering
     * the whole month, not three per budget: a summary of twenty budgets costs
     * the same round trips as a summary of one.
     */
    @Transactional(readOnly = true)
    public BudgetSummaryResponse summary(YearMonth month) {
        Long userId = currentUser.currentUserId();
        var userSettings = settings.forUser(userId);
        CurrencyCode base = userSettings.getBaseCurrency();
        BigDecimal threshold = userSettings.getBudgetWarningThreshold();

        Map<Long, List<CurrencyTotals.Entry>> consumption = consumptionByCategory(userId, month);

        List<BudgetResponse> items = budgets
                .findAllByUserIdAndMonthRefOrderByIdAsc(userId, month.atDay(1)).stream()
                .map(budget -> toResponse(
                        budget,
                        consumption.getOrDefault(budget.getCategory().getId(), List.of()),
                        base,
                        threshold))
                .toList();

        BigDecimal totalLimit = items.stream()
                .map(BudgetResponse::limitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalConsumed = items.stream()
                .map(BudgetResponse::consumedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Grouping across budgets is safe: each entry keeps its own currency.
        List<CurrencyTotals.Entry> everything = new ArrayList<>();
        for (List<CurrencyTotals.Entry> entries : consumption.values()) {
            everything.addAll(entries);
        }
        CurrencyTotals consumedTotals = CurrencyTotals.of(everything, base);

        int incompleteCount =
                (int) items.stream().filter(b -> b.status() == BudgetStatus.INCOMPLETE).count();
        boolean complete = incompleteCount == 0;

        return new BudgetSummaryResponse(
                month,
                base.name(),
                MoneyRules.normalize(totalLimit, base),
                MoneyRules.normalize(totalConsumed, base),
                consumedTotals,
                complete ? MoneyRules.normalize(totalLimit.subtract(totalConsumed), base) : null,
                complete ? percent(totalConsumed, totalLimit) : null,
                (int) items.stream().filter(b -> b.status() == BudgetStatus.EXCEEDED).count(),
                (int) items.stream().filter(b -> b.status() == BudgetStatus.WARNING).count(),
                incompleteCount,
                items);
    }

    @Transactional(readOnly = true)
    public BudgetResponse get(Long id) {
        Budget budget = find(id);
        var userSettings = settings.forUser(budget.getUserId());
        return toResponse(
                budget,
                consumptionOf(budget),
                userSettings.getBaseCurrency(),
                userSettings.getBudgetWarningThreshold());
    }

    public BudgetResponse create(BudgetRequest request) {
        return create(request, null);
    }

    /**
     * Same rules as {@link #create(BudgetRequest)}, with the stable identity a
     * budget created offline already carries. The identity has to be attached
     * before the insert — the column is insert-only.
     *
     * @param clientResourceId null for anything created online
     */
    public BudgetResponse create(BudgetRequest request, java.util.UUID clientResourceId) {
        Long userId = currentUser.currentUserId();
        Category category = categories.findByIdAndUserId(request.categoryId(), userId)
                .orElseThrow(() -> new NotFoundException("Categoria", request.categoryId()));
        if (category.getType() != CategoryType.EXPENSE) {
            throw new BusinessRuleException("BUDGET_CATEGORY_NOT_EXPENSE",
                    "Orçamentos só podem ser definidos para categorias de despesa.");
        }
        budgets.findByUserIdAndMonthRefAndCategoryId(userId, request.month().atDay(1), request.categoryId())
                .ifPresent(existing -> {
                    throw new BusinessRuleException("BUDGET_ALREADY_EXISTS",
                            "Já existe um orçamento para essa categoria nesse mês.");
                });
        CurrencyCode base = settings.forUser(userId).getBaseCurrency();
        MoneyRules.validateScale(request.limitAmount(), base);
        Budget budget = new Budget(userId, request.month(), category,
                MoneyRules.normalize(request.limitAmount(), base));
        budget.setClientResourceId(clientResourceId);
        return reread(budgets.save(budget));
    }

    public BudgetResponse update(Long id, BudgetRequest request) {
        Budget budget = find(id);
        if (!budget.getMonth().equals(request.month())
                || !budget.getCategory().getId().equals(request.categoryId())) {
            throw new BusinessRuleException("BUDGET_KEY_IMMUTABLE",
                    "O mês e a categoria de um orçamento não podem ser alterados. Exclua e crie um novo.");
        }
        CurrencyCode base = settings.forUser(budget.getUserId()).getBaseCurrency();
        MoneyRules.validateScale(request.limitAmount(), base);
        budget.setLimitAmount(MoneyRules.normalize(request.limitAmount(), base));
        return reread(budget);
    }

    public void delete(Long id) {
        budgets.delete(find(id));
    }

    private Budget find(Long id) {
        return budgets.findByIdAndUserId(id, currentUser.currentUserId())
                .orElseThrow(() -> new NotFoundException("Orçamento", id));
    }

    private BudgetResponse reread(Budget budget) {
        var userSettings = settings.forUser(budget.getUserId());
        return toResponse(
                budget,
                consumptionOf(budget),
                userSettings.getBaseCurrency(),
                userSettings.getBudgetWarningThreshold());
    }

    /**
     * Everything spent in every budgeted category this month, tagged with the
     * currency it was spent in.
     *
     * <p>Consumption = regular expenses in the month + active card installments
     * whose invoice falls in the month + net card debit adjustments
     * (fees/interest minus categorized credits). Invoice payments never appear
     * here — the installments already did. Transactions carry their own
     * currency; card rows inherit the billing card's.
     */
    private Map<Long, List<CurrencyTotals.Entry>> consumptionByCategory(Long userId, YearMonth month) {
        Map<Long, List<CurrencyTotals.Entry>> byCategory = new LinkedHashMap<>();
        collect(byCategory, transactions.sumExpensesGroupedByCategoryAndCurrency(
                userId, month.atDay(1), month.atEndOfMonth()));
        collect(byCategory, installments.sumActiveGroupedByCategoryAndCurrency(
                userId, month.atDay(1)));
        collect(byCategory, adjustments.sumActiveNetGroupedByCategoryAndCurrency(
                userId, month.atDay(1)));
        return byCategory;
    }

    /** Rows are {@code [categoryId, categoryName, currency, total]}. */
    private static void collect(Map<Long, List<CurrencyTotals.Entry>> target, List<Object[]> rows) {
        for (Object[] row : rows) {
            Long categoryId = (Long) row[0];
            if (categoryId == null) {
                continue;
            }
            target.computeIfAbsent(categoryId, key -> new ArrayList<>())
                    .add(new CurrencyTotals.Entry((BigDecimal) row[3], (CurrencyCode) row[2]));
        }
    }

    /** Single-budget consumption, for the read paths that fetch one row. */
    private List<CurrencyTotals.Entry> consumptionOf(Budget budget) {
        YearMonth month = budget.getMonth();
        Long userId = budget.getUserId();
        Long categoryId = budget.getCategory().getId();
        List<CurrencyTotals.Entry> entries = new ArrayList<>();
        for (Object[] row : transactions.sumExpensesByCategoryAndPeriodGroupedByCurrency(
                userId, categoryId, month.atDay(1), month.atEndOfMonth())) {
            entries.add(new CurrencyTotals.Entry((BigDecimal) row[1], (CurrencyCode) row[0]));
        }
        Map<Long, List<CurrencyTotals.Entry>> cardRows = new LinkedHashMap<>();
        collect(cardRows, installments.sumActiveGroupedByCategoryAndCurrency(userId, month.atDay(1)));
        collect(cardRows, adjustments.sumActiveNetGroupedByCategoryAndCurrency(userId, month.atDay(1)));
        entries.addAll(cardRows.getOrDefault(categoryId, List.of()));
        return entries;
    }

    private BudgetResponse toResponse(Budget budget,
                                      List<CurrencyTotals.Entry> consumption,
                                      CurrencyCode base,
                                      BigDecimal warningThreshold) {
        CurrencyTotals consumedTotals = CurrencyTotals.of(consumption, base);
        // The base-currency portion is known even when the rest is not, and
        // reporting it is what keeps INCOMPLETE from reading as "no data".
        BigDecimal consumedInBase = consumption.stream()
                .filter(entry -> entry.currency() == base)
                .map(CurrencyTotals.Entry::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal limit = MoneyRules.normalize(budget.getLimitAmount(), base);
        boolean complete = consumedTotals.baseComplete();

        return new BudgetResponse(
                budget.getId(),
                budget.getMonth(),
                new BudgetCategory(
                        budget.getCategory().getId(),
                        budget.getCategory().getName(),
                        budget.getCategory().getType()),
                limit,
                base.name(),
                MoneyRules.normalize(consumedInBase, base),
                consumedTotals,
                complete ? MoneyRules.normalize(limit.subtract(consumedInBase), base) : null,
                complete ? percent(consumedInBase, limit) : null,
                complete ? status(consumedInBase, limit, warningThreshold) : BudgetStatus.INCOMPLETE,
                budget.getVersion());
    }

    private static BudgetStatus status(BigDecimal consumed, BigDecimal limit,
            BigDecimal warningThreshold) {
        if (limit.signum() <= 0) {
            return BudgetStatus.HEALTHY;
        }
        BigDecimal ratio = consumed.divide(limit, MoneyRules.RATE_SCALE, RoundingMode.HALF_UP);
        if (ratio.compareTo(BigDecimal.ONE) >= 0) {
            return BudgetStatus.EXCEEDED;
        }
        if (ratio.compareTo(warningThreshold) >= 0) {
            return BudgetStatus.WARNING;
        }
        return BudgetStatus.HEALTHY;
    }

    /** Percentage used (0-100+), 1 decimal place; 0 when the limit is zero. */
    private static BigDecimal percent(BigDecimal consumed, BigDecimal limit) {
        if (limit.signum() <= 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return consumed
                .multiply(BigDecimal.valueOf(100))
                .divide(limit, 1, RoundingMode.HALF_UP);
    }
}
