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
import com.finora.api.common.money.MoneyRules;
import com.finora.api.settings.SettingsService;
import com.finora.api.transaction.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly budgets per expense category. Consumption is always derived from the
 * month's transactions at read time — never stored — so budget figures cannot
 * drift from the transaction history. A budget is WARNING at the configurable
 * threshold (settings) and EXCEEDED at 100%; percentUsed may exceed 100.
 */
@Service
@Transactional
public class BudgetService {

    private final BudgetRepository budgets;
    private final CategoryRepository categories;
    private final TransactionRepository transactions;
    private final SettingsService settings;

    public BudgetService(BudgetRepository budgets,
                         CategoryRepository categories,
                         TransactionRepository transactions,
                         SettingsService settings) {
        this.budgets = budgets;
        this.categories = categories;
        this.transactions = transactions;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public BudgetSummaryResponse summary(YearMonth month) {
        List<BudgetResponse> items = budgets.findAllByMonthRefOrderByIdAsc(month.atDay(1)).stream()
                .map(this::toResponse)
                .toList();
        BigDecimal totalLimit = items.stream()
                .map(BudgetResponse::limitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalConsumed = items.stream()
                .map(BudgetResponse::consumedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BudgetSummaryResponse(
                month,
                MoneyRules.normalize(totalLimit),
                MoneyRules.normalize(totalConsumed),
                MoneyRules.normalize(totalLimit.subtract(totalConsumed)),
                percent(totalConsumed, totalLimit),
                (int) items.stream().filter(b -> b.status() == BudgetStatus.EXCEEDED).count(),
                (int) items.stream().filter(b -> b.status() == BudgetStatus.WARNING).count(),
                items);
    }

    @Transactional(readOnly = true)
    public BudgetResponse get(Long id) {
        return toResponse(find(id));
    }

    public BudgetResponse create(BudgetRequest request) {
        Category category = categories.findById(request.categoryId())
                .orElseThrow(() -> new NotFoundException("Categoria", request.categoryId()));
        if (category.getType() != CategoryType.EXPENSE) {
            throw new BusinessRuleException("BUDGET_CATEGORY_NOT_EXPENSE",
                    "Orçamentos só podem ser definidos para categorias de despesa.");
        }
        budgets.findByMonthRefAndCategoryId(request.month().atDay(1), request.categoryId())
                .ifPresent(existing -> {
                    throw new BusinessRuleException("BUDGET_ALREADY_EXISTS",
                            "Já existe um orçamento para essa categoria nesse mês.");
                });
        Budget budget = new Budget(request.month(), category, MoneyRules.normalize(request.limitAmount()));
        return toResponse(budgets.save(budget));
    }

    public BudgetResponse update(Long id, BudgetRequest request) {
        Budget budget = find(id);
        if (!budget.getMonth().equals(request.month())
                || !budget.getCategory().getId().equals(request.categoryId())) {
            throw new BusinessRuleException("BUDGET_KEY_IMMUTABLE",
                    "O mês e a categoria de um orçamento não podem ser alterados. Exclua e crie um novo.");
        }
        budget.setLimitAmount(MoneyRules.normalize(request.limitAmount()));
        return toResponse(budget);
    }

    public void delete(Long id) {
        budgets.delete(find(id));
    }

    private Budget find(Long id) {
        return budgets.findById(id).orElseThrow(() -> new NotFoundException("Orçamento", id));
    }

    private BudgetResponse toResponse(Budget budget) {
        YearMonth month = budget.getMonth();
        BigDecimal consumed = transactions.sumExpensesByCategoryAndPeriod(
                budget.getCategory().getId(), month.atDay(1), month.atEndOfMonth());
        BigDecimal limit = budget.getLimitAmount();
        BigDecimal percentUsed = percent(consumed, limit);
        return new BudgetResponse(
                budget.getId(),
                month,
                new BudgetCategory(
                        budget.getCategory().getId(),
                        budget.getCategory().getName(),
                        budget.getCategory().getType()),
                MoneyRules.normalize(limit),
                MoneyRules.normalize(consumed),
                MoneyRules.normalize(limit.subtract(consumed)),
                percentUsed,
                status(consumed, limit));
    }

    private BudgetStatus status(BigDecimal consumed, BigDecimal limit) {
        if (limit.signum() <= 0) {
            return BudgetStatus.HEALTHY;
        }
        BigDecimal ratio = consumed.divide(limit, MoneyRules.RATE_SCALE, RoundingMode.HALF_UP);
        if (ratio.compareTo(BigDecimal.ONE) >= 0) {
            return BudgetStatus.EXCEEDED;
        }
        if (ratio.compareTo(settings.current().getBudgetWarningThreshold()) >= 0) {
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
