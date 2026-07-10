package com.finora.api.commitment;

import com.finora.api.category.Category;
import com.finora.api.category.CategoryRepository;
import com.finora.api.category.CategoryType;
import com.finora.api.commitment.CommitmentDtos.CommitmentCategory;
import com.finora.api.commitment.CommitmentDtos.CommitmentRequest;
import com.finora.api.commitment.CommitmentDtos.CommitmentResponse;
import com.finora.api.commitment.CommitmentDtos.UpcomingCommitment;
import com.finora.api.commitment.CommitmentDtos.UpcomingResponse;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CommitmentService {

    private final CommitmentRepository commitments;
    private final CategoryRepository categories;

    public CommitmentService(CommitmentRepository commitments, CategoryRepository categories) {
        this.commitments = commitments;
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public List<CommitmentResponse> list() {
        LocalDate today = LocalDate.now();
        return commitments.findAllByOrderByActiveDescDescriptionAsc().stream()
                .map(c -> toResponse(c, today))
                .toList();
    }

    @Transactional(readOnly = true)
    public CommitmentResponse get(Long id) {
        return toResponse(find(id), LocalDate.now());
    }

    /**
     * Projects the occurrences of active commitments due between {@code from}
     * (inclusive) and the end of the window of {@code months} months.
     */
    @Transactional(readOnly = true)
    public UpcomingResponse upcoming(LocalDate from, int months) {
        int window = Math.clamp(months, 1, 12);
        LocalDate to = YearMonth.from(from).plusMonths(window - 1L).atEndOfMonth();
        List<UpcomingCommitment> items = new ArrayList<>();
        for (Commitment commitment : commitments.findAllByActiveTrue()) {
            YearMonth cursor = YearMonth.from(from);
            YearMonth last = YearMonth.from(to);
            while (!cursor.isAfter(last)) {
                commitment.occurrenceIn(cursor)
                        .filter(due -> !due.isBefore(from) && !due.isAfter(to))
                        .ifPresent(due -> items.add(new UpcomingCommitment(
                                commitment.getId(),
                                commitment.getDescription(),
                                MoneyRules.normalize(commitment.getAmount()),
                                toCategory(commitment.getCategory()),
                                due,
                                commitment.getPaymentMethod())));
                cursor = cursor.plusMonths(1);
            }
        }
        items.sort(Comparator.comparing(UpcomingCommitment::dueDate)
                .thenComparing(UpcomingCommitment::description));
        BigDecimal total = items.stream()
                .map(UpcomingCommitment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new UpcomingResponse(from, to, MoneyRules.normalize(total), items);
    }

    /** Total of active commitments falling due inside the given month. */
    @Transactional(readOnly = true)
    public BigDecimal monthlyTotal(YearMonth month) {
        return commitments.findAllByActiveTrue().stream()
                .filter(c -> c.occurrenceIn(month).isPresent())
                .map(Commitment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public CommitmentResponse create(CommitmentRequest request) {
        Category category = resolveCategory(request.categoryId());
        validate(request);
        Commitment commitment = new Commitment(
                request.description().trim(),
                MoneyRules.normalize(request.amount()),
                category,
                request.cadence(),
                request.dueDay(),
                request.startDate());
        commitment.setEndDate(request.endDate());
        commitment.setPaymentMethod(request.paymentMethod());
        if (request.active() != null) {
            commitment.setActive(request.active());
        }
        return toResponse(commitments.save(commitment), LocalDate.now());
    }

    public CommitmentResponse update(Long id, CommitmentRequest request) {
        Commitment commitment = find(id);
        Category category = resolveCategory(request.categoryId());
        validate(request);
        commitment.setDescription(request.description().trim());
        commitment.setAmount(MoneyRules.normalize(request.amount()));
        commitment.setCategory(category);
        commitment.setCadence(request.cadence());
        commitment.setDueDay(request.dueDay());
        commitment.setStartDate(request.startDate());
        commitment.setEndDate(request.endDate());
        commitment.setPaymentMethod(request.paymentMethod());
        if (request.active() != null) {
            commitment.setActive(request.active());
        }
        return toResponse(commitment, LocalDate.now());
    }

    public void delete(Long id) {
        commitments.delete(find(id));
    }

    private void validate(CommitmentRequest request) {
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new BusinessRuleException("COMMITMENT_INVALID_PERIOD",
                    "A data final não pode ser anterior à data de início.");
        }
        if (request.cadence() == CommitmentCadence.MONTHLY && request.dueDay() == null) {
            throw new BusinessRuleException("COMMITMENT_DUE_DAY_REQUIRED",
                    "Informe o dia de vencimento para compromissos mensais.");
        }
    }

    private Category resolveCategory(Long categoryId) {
        Category category = categories.findById(categoryId)
                .orElseThrow(() -> new NotFoundException("Categoria", categoryId));
        if (category.getType() != CategoryType.EXPENSE) {
            throw new BusinessRuleException("COMMITMENT_CATEGORY_NOT_EXPENSE",
                    "Compromissos recorrentes usam categorias de despesa.");
        }
        return category;
    }

    private Commitment find(Long id) {
        return commitments.findById(id).orElseThrow(() -> new NotFoundException("Compromisso", id));
    }

    private CommitmentResponse toResponse(Commitment commitment, LocalDate reference) {
        return new CommitmentResponse(
                commitment.getId(),
                commitment.getDescription(),
                MoneyRules.normalize(commitment.getAmount()),
                toCategory(commitment.getCategory()),
                commitment.getCadence(),
                commitment.getDueDay(),
                commitment.getStartDate(),
                commitment.getEndDate(),
                commitment.isActive(),
                commitment.getPaymentMethod(),
                nextDueDate(commitment, reference));
    }

    private static LocalDate nextDueDate(Commitment commitment, LocalDate reference) {
        YearMonth cursor = YearMonth.from(reference);
        for (int i = 0; i < 13; i++) {
            LocalDate due = commitment.occurrenceIn(cursor).orElse(null);
            if (due != null && !due.isBefore(reference)) {
                return due;
            }
            cursor = cursor.plusMonths(1);
        }
        return null;
    }

    private static CommitmentCategory toCategory(Category category) {
        return new CommitmentCategory(category.getId(), category.getName(), category.getType());
    }
}
