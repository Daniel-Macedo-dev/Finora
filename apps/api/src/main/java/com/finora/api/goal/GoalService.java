package com.finora.api.goal;

import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.goal.GoalDtos.ContributionRequest;
import com.finora.api.goal.GoalDtos.GoalRequest;
import com.finora.api.goal.GoalDtos.GoalResponse;
import com.finora.api.goal.GoalDtos.GoalStatus;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.settings.SettingsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class GoalService {

    private final GoalRepository goals;
    private final CurrentUserProvider currentUser;
    private final SettingsService settings;

    public GoalService(GoalRepository goals, CurrentUserProvider currentUser,
            SettingsService settings) {
        this.goals = goals;
        this.currentUser = currentUser;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public List<GoalResponse> list() {
        return listForUser(currentUser.currentUserId());
    }

    /** Owner-explicit variant used by the dashboard aggregation. */
    @Transactional(readOnly = true)
    public List<GoalResponse> listForUser(Long userId) {
        LocalDate today = LocalDate.now();
        return goals.findAllByUserIdOrderByArchivedAscNameAsc(userId).stream()
                .map(goal -> toResponse(goal, today))
                .toList();
    }

    @Transactional(readOnly = true)
    public GoalResponse get(Long id) {
        return toResponse(find(id), LocalDate.now());
    }

    public GoalResponse create(GoalRequest request) {
        return create(request, null);
    }

    /**
     * Same rules as {@link #create(GoalRequest)}, with the stable identity a
     * goal created offline already carries. The identity has to be attached
     * before the insert — the column is insert-only.
     *
     * @param clientResourceId null for anything created online
     */
    public GoalResponse create(GoalRequest request, java.util.UUID clientResourceId) {
        Long userId = currentUser.currentUserId();
        CurrencyCode currency = resolveCurrency(request.currency(), userId);
        MoneyRules.validateScale(request.targetAmount(), currency);
        MoneyRules.validateScale(request.currentAmount(), currency);
        Goal goal = new Goal(
                userId,
                request.name().trim(),
                MoneyRules.normalize(request.targetAmount(), currency),
                request.currentAmount() != null
                        ? MoneyRules.normalize(request.currentAmount(), currency)
                        : MoneyRules.normalize(BigDecimal.ZERO, currency),
                request.targetDate());
        goal.setCurrency(currency);
        if (request.archived() != null) {
            goal.setArchived(request.archived());
        }
        goal.setClientResourceId(clientResourceId);
        return toResponse(goals.save(goal), LocalDate.now());
    }

    public GoalResponse update(Long id, GoalRequest request) {
        Goal goal = find(id);
        assertCurrencyUnchanged(goal, request.currency());
        CurrencyCode currency = goal.getCurrency();
        MoneyRules.validateScale(request.targetAmount(), currency);
        MoneyRules.validateScale(request.currentAmount(), currency);
        goal.setName(request.name().trim());
        goal.setTargetAmount(MoneyRules.normalize(request.targetAmount(), currency));
        if (request.currentAmount() != null) {
            goal.setCurrentAmount(MoneyRules.normalize(request.currentAmount(), currency));
        }
        goal.setTargetDate(request.targetDate());
        if (request.archived() != null) {
            goal.setArchived(request.archived());
        }
        return toResponse(goal, LocalDate.now());
    }

    /** Adds (or, with a negative amount, withdraws) a contribution to the goal. */
    public GoalResponse contribute(Long id, ContributionRequest request) {
        Goal goal = find(id);
        if (request.amount().signum() == 0) {
            throw new BusinessRuleException("GOAL_CONTRIBUTION_ZERO",
                    "O valor do aporte não pode ser zero.");
        }
        // A contribution is denominated in the goal's own currency; there is
        // no second currency in play, so the addition stays homogeneous.
        MoneyRules.validateScale(request.amount(), goal.getCurrency());
        BigDecimal updated = goal.getCurrentAmount().add(request.amount());
        if (updated.signum() < 0) {
            throw new BusinessRuleException("GOAL_BALANCE_NEGATIVE",
                    "A retirada deixaria a meta com valor negativo.");
        }
        goal.setCurrentAmount(MoneyRules.normalize(updated, goal.getCurrency()));
        return toResponse(goal, LocalDate.now());
    }

    public void delete(Long id) {
        goals.delete(find(id));
    }

    /**
     * An omitted currency means the user's base currency; a foreign one is
     * never inferred.
     */
    private CurrencyCode resolveCurrency(String requested, Long userId) {
        CurrencyCode explicit = CurrencyCode.parseOrNull(requested);
        return explicit != null ? explicit : settings.forUser(userId).getBaseCurrency();
    }

    /**
     * A goal's currency is immutable: its balance and every past contribution
     * are denominated in it, so a change would reinterpret them.
     */
    private void assertCurrencyUnchanged(Goal goal, String requested) {
        CurrencyCode explicit = CurrencyCode.parseOrNull(requested);
        if (explicit != null && explicit != goal.getCurrency()) {
            throw new BusinessRuleException("CURRENCY_IMMUTABLE",
                    ("A moeda de uma meta não pode ser alterada (%s). Alterá-la "
                            + "reinterpretaria o saldo e os aportes já registrados.")
                            .formatted(goal.getCurrency().name()));
        }
    }

    private Goal find(Long id) {
        return goals.findByIdAndUserId(id, currentUser.currentUserId())
                .orElseThrow(() -> new NotFoundException("Meta", id));
    }

    private GoalResponse toResponse(Goal goal, LocalDate today) {
        BigDecimal remaining = goal.getTargetAmount().subtract(goal.getCurrentAmount()).max(BigDecimal.ZERO);
        BigDecimal percent = goal.getTargetAmount().signum() > 0
                ? goal.getCurrentAmount()
                        .multiply(BigDecimal.valueOf(100))
                        .divide(goal.getTargetAmount(), 1, RoundingMode.HALF_UP)
                        .min(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;
        GoalStatus status;
        if (goal.isArchived()) {
            status = GoalStatus.ARCHIVED;
        } else if (goal.getCurrentAmount().compareTo(goal.getTargetAmount()) >= 0) {
            status = GoalStatus.COMPLETED;
        } else {
            status = GoalStatus.IN_PROGRESS;
        }
        return new GoalResponse(
                goal.getId(),
                goal.getName(),
                MoneyRules.normalize(goal.getTargetAmount(), goal.getCurrency()),
                MoneyRules.normalize(goal.getCurrentAmount(), goal.getCurrency()),
                MoneyRules.normalize(remaining, goal.getCurrency()),
                percent,
                goal.getTargetDate(),
                status,
                suggestedMonthlyContribution(goal, remaining, today),
                goal.getCurrency().name(),
                goal.getVersion());
    }

    /**
     * Remaining amount divided by the number of months (>= 1) until the target
     * date. Null when there is no future target date or nothing remains.
     */
    private static BigDecimal suggestedMonthlyContribution(Goal goal, BigDecimal remaining, LocalDate today) {
        if (goal.getTargetDate() == null || remaining.signum() <= 0 || goal.isArchived()) {
            return null;
        }
        long months = ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(goal.getTargetDate()));
        if (months < 0) {
            return null;
        }
        long installments = Math.max(months, 1);
        return remaining.divide(BigDecimal.valueOf(installments), MoneyRules.SCALE, MoneyRules.ROUNDING);
    }
}
