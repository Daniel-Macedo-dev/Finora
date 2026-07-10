package com.finora.api.goal;

import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.goal.GoalDtos.ContributionRequest;
import com.finora.api.goal.GoalDtos.GoalRequest;
import com.finora.api.goal.GoalDtos.GoalResponse;
import com.finora.api.goal.GoalDtos.GoalStatus;
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

    public GoalService(GoalRepository goals) {
        this.goals = goals;
    }

    @Transactional(readOnly = true)
    public List<GoalResponse> list() {
        LocalDate today = LocalDate.now();
        return goals.findAllByOrderByArchivedAscNameAsc().stream()
                .map(goal -> toResponse(goal, today))
                .toList();
    }

    @Transactional(readOnly = true)
    public GoalResponse get(Long id) {
        return toResponse(find(id), LocalDate.now());
    }

    public GoalResponse create(GoalRequest request) {
        Goal goal = new Goal(
                request.name().trim(),
                MoneyRules.normalize(request.targetAmount()),
                request.currentAmount() != null
                        ? MoneyRules.normalize(request.currentAmount())
                        : MoneyRules.normalize(BigDecimal.ZERO),
                request.targetDate());
        if (request.archived() != null) {
            goal.setArchived(request.archived());
        }
        return toResponse(goals.save(goal), LocalDate.now());
    }

    public GoalResponse update(Long id, GoalRequest request) {
        Goal goal = find(id);
        goal.setName(request.name().trim());
        goal.setTargetAmount(MoneyRules.normalize(request.targetAmount()));
        if (request.currentAmount() != null) {
            goal.setCurrentAmount(MoneyRules.normalize(request.currentAmount()));
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
        BigDecimal updated = goal.getCurrentAmount().add(request.amount());
        if (updated.signum() < 0) {
            throw new BusinessRuleException("GOAL_BALANCE_NEGATIVE",
                    "A retirada deixaria a meta com valor negativo.");
        }
        goal.setCurrentAmount(MoneyRules.normalize(updated));
        return toResponse(goal, LocalDate.now());
    }

    public void delete(Long id) {
        goals.delete(find(id));
    }

    private Goal find(Long id) {
        return goals.findById(id).orElseThrow(() -> new NotFoundException("Meta", id));
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
                MoneyRules.normalize(goal.getTargetAmount()),
                MoneyRules.normalize(goal.getCurrentAmount()),
                MoneyRules.normalize(remaining),
                percent,
                goal.getTargetDate(),
                status,
                suggestedMonthlyContribution(goal, remaining, today));
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
