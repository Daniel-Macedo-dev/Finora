package com.finora.api.commitment;

import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.category.Category;
import com.finora.api.category.CategoryRepository;
import com.finora.api.category.CategoryType;
import com.finora.api.commitment.CommitmentDtos.CommitmentCategory;
import com.finora.api.commitment.CommitmentDtos.CommitmentRequest;
import com.finora.api.commitment.CommitmentDtos.CommitmentResponse;
import com.finora.api.commitment.CommitmentDtos.UpcomingCommitment;
import com.finora.api.commitment.CommitmentDtos.UpcomingResponse;
import com.finora.api.commitment.occurrence.CommitmentOccurrence;
import com.finora.api.commitment.occurrence.CommitmentOccurrenceRepository;
import com.finora.api.commitment.occurrence.OccurrenceStatus;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.common.money.CurrencyTotals;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.CreditCard;
import com.finora.api.creditcard.CreditCardRepository;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.settings.SettingsService;
import com.finora.api.transaction.PaymentMethod;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recurring definitions (commitments). A definition is planning data until it
 * gains a concrete target; occurrences and materialization live in the
 * {@code occurrence} subpackage. Editing a definition never rewrites
 * materialized history — it reshapes only the future.
 */
@Service
@Transactional
public class CommitmentService {

    private final CommitmentRepository commitments;
    private final CommitmentOccurrenceRepository occurrences;
    private final CategoryRepository categories;
    private final AccountRepository accounts;
    private final CreditCardRepository cards;
    private final CurrentUserProvider currentUser;
    private final Clock clock;
    private final SettingsService settings;

    public CommitmentService(CommitmentRepository commitments,
                             CommitmentOccurrenceRepository occurrences,
                             CategoryRepository categories,
                             AccountRepository accounts,
                             CreditCardRepository cards,
                             CurrentUserProvider currentUser,
                             Clock clock,
                             SettingsService settings) {
        this.commitments = commitments;
        this.occurrences = occurrences;
        this.categories = categories;
        this.accounts = accounts;
        this.cards = cards;
        this.currentUser = currentUser;
        this.clock = clock;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public List<CommitmentResponse> list() {
        Long userId = currentUser.currentUserId();
        LocalDate today = LocalDate.now(clock);
        Map<Long, Long> failedCounts = failedCountsByCommitment(userId);
        return commitments.findAllByUserIdOrderByActiveDescDescriptionAsc(userId).stream()
                .map(c -> toResponse(c, today, failedCounts.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public CommitmentResponse get(Long id) {
        Commitment commitment = find(id);
        Map<Long, Long> failedCounts = failedCountsByCommitment(commitment.getUserId());
        return toResponse(commitment, LocalDate.now(clock),
                failedCounts.getOrDefault(commitment.getId(), 0L));
    }

    /**
     * Projects the occurrences of active definitions due between {@code from}
     * (inclusive) and the end of the window of {@code months} months.
     */
    @Transactional(readOnly = true)
    public UpcomingResponse upcoming(LocalDate from, int months) {
        return upcomingForUser(currentUser.currentUserId(),
                from != null ? from : LocalDate.now(clock), months);
    }

    /** Owner-explicit variant used by services that already resolved identity. */
    @Transactional(readOnly = true)
    public UpcomingResponse upcomingForUser(Long userId, LocalDate from, int months) {
        int window = Math.clamp(months, 1, 12);
        LocalDate to = YearMonth.from(from).plusMonths(window - 1L).atEndOfMonth();
        List<UpcomingCommitment> items = new ArrayList<>();
        for (Commitment commitment : commitments.findAllByUserIdAndActiveTrue(userId)) {
            for (LocalDate due : RecurrenceCalculator.occurrencesBetween(commitment, from, to)) {
                items.add(new UpcomingCommitment(
                        commitment.getId(),
                        commitment.getDescription(),
                        MoneyRules.normalize(commitment.getAmount(), commitment.getCurrency()),
                        commitment.getCurrency().name(),
                        toCategory(commitment.getCategory()),
                        due,
                        commitment.getPaymentMethod()));
            }
        }
        items.sort(Comparator.comparing(UpcomingCommitment::dueDate)
                .thenComparing(UpcomingCommitment::description));
        // Commitments in different currencies cannot be added, so the window
        // reports grouped native totals and offers one consolidated figure only
        // when every commitment already settles in the base currency.
        CurrencyTotals totals = CurrencyTotals.of(
                items.stream()
                        .map(item -> new CurrencyTotals.Entry(
                                item.amount(), CurrencyCode.parse(item.currency())))
                        .toList(),
                settings.forUser(userId).getBaseCurrency());
        return new UpcomingResponse(from, to, totals, items);
    }

    /**
     * Total of the user's active expense definitions falling due inside the
     * month — a WEEKLY definition contributes once per occurrence.
     */
    @Transactional(readOnly = true)
    public BigDecimal monthlyTotal(Long userId, YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        BigDecimal total = BigDecimal.ZERO;
        for (Commitment commitment : commitments.findAllByUserIdAndActiveTrue(userId)) {
            if (commitment.getCategory().getType() != CategoryType.EXPENSE) {
                continue;
            }
            int count = RecurrenceCalculator.occurrencesBetween(commitment, from, to).size();
            if (count > 0) {
                total = total.add(commitment.getAmount().multiply(BigDecimal.valueOf(count)));
            }
        }
        return total;
    }

    public CommitmentResponse create(CommitmentRequest request) {
        Long userId = currentUser.currentUserId();
        Category category = resolveCategory(userId, request.categoryId());
        validate(request, category);
        Commitment commitment = new Commitment(
                userId,
                request.description().trim(),
                MoneyRules.normalize(request.amount()),
                category,
                request.cadence(),
                request.dueDay(),
                request.startDate());
        commitment.setEndDate(request.endDate());
        applyTargetFields(userId, commitment, request);
        CurrencyCode currency = deriveCurrency(userId, commitment, request.currency());
        MoneyRules.validateScale(request.amount(), currency);
        commitment.setCurrency(currency);
        commitment.setAmount(MoneyRules.normalize(request.amount(), currency));
        if (request.active() != null) {
            commitment.setActive(request.active());
        }
        return toResponse(commitments.save(commitment), LocalDate.now(clock), 0L);
    }

    /**
     * Edits reshape the future only. Persisted SCHEDULED occurrences that were
     * never touched by the user (not rescheduled) and no longer match the new
     * recurrence are removed; skipped, failed and materialized history stays.
     */
    public CommitmentResponse update(Long id, CommitmentRequest request) {
        Long userId = currentUser.currentUserId();
        Commitment commitment = find(id);
        Category category = resolveCategory(userId, request.categoryId());
        validate(request, category);
        assertCurrencyUnchanged(commitment, request.currency());
        MoneyRules.validateScale(request.amount(), commitment.getCurrency());
        commitment.setDescription(request.description().trim());
        commitment.setAmount(
                MoneyRules.normalize(request.amount(), commitment.getCurrency()));
        commitment.setCategory(category);
        commitment.setCadence(request.cadence());
        commitment.setDueDay(request.dueDay());
        commitment.setStartDate(request.startDate());
        commitment.setEndDate(request.endDate());
        applyTargetFields(userId, commitment, request);
        assertTargetMatchesCurrency(commitment);
        if (request.active() != null) {
            commitment.setActive(request.active());
        }
        pruneStaleScheduledOccurrences(commitment);
        Map<Long, Long> failedCounts = failedCountsByCommitment(userId);
        return toResponse(commitment, LocalDate.now(clock),
                failedCounts.getOrDefault(commitment.getId(), 0L));
    }

    /**
     * Maps a legacy CREDIT definition (projection-only since V9) to a real
     * credit card. Only the target changes: description, amount, cadence and
     * every past occurrence stay untouched, and the automation horizon is set
     * to today so automatic processing never backfills historical occurrences.
     */
    public CommitmentResponse mapLegacyCredit(Long id,
                                              CommitmentDtos.MapLegacyCreditRequest request) {
        Long userId = currentUser.currentUserId();
        Commitment commitment = find(id);
        boolean legacyCredit = commitment.getPaymentMethod() == PaymentMethod.CREDIT
                && commitment.getTargetKind() == RecurrenceTarget.PROJECTION_ONLY;
        if (!legacyCredit) {
            throw new BusinessRuleException("COMMITMENT_NOT_LEGACY_CREDIT",
                    "Este recorrente não é um crédito legado aguardando migração.");
        }
        if (commitment.getCategory().getType() != CategoryType.EXPENSE) {
            throw new BusinessRuleException("COMMITMENT_CARD_NEEDS_EXPENSE",
                    "Recorrentes no cartão exigem uma categoria de despesa.");
        }
        // Owner-scoped: another user's card id behaves as absent.
        CreditCard card = cards.findByIdAndUserId(request.creditCardId(), userId)
                .orElseThrow(() -> new NotFoundException("Cartão", request.creditCardId()));
        if (card.isArchived()) {
            throw new BusinessRuleException("CARD_ARCHIVED",
                    "Um cartão arquivado não pode receber compras recorrentes.");
        }
        commitment.setTargetKind(RecurrenceTarget.CREDIT_CARD_PURCHASE);
        commitment.setCreditCard(card);
        commitment.setAccount(null);
        commitment.setInstallmentCount(request.installmentCount());
        commitment.setExecutionMode(request.executionMode());
        commitment.setAutomationFrom(LocalDate.now(clock));
        Map<Long, Long> failedCounts = failedCountsByCommitment(userId);
        return toResponse(commitment, LocalDate.now(clock),
                failedCounts.getOrDefault(commitment.getId(), 0L));
    }

    /** Pause: occurrences stop being projected and processed; history stays. */
    public CommitmentResponse pause(Long id) {
        return setActive(id, false);
    }

    public CommitmentResponse resume(Long id) {
        return setActive(id, true);
    }

    /** End: the definition stops recurring after the given date (inclusive). */
    public CommitmentResponse end(Long id, LocalDate endDate) {
        Commitment commitment = find(id);
        if (endDate.isBefore(commitment.getStartDate())) {
            throw new BusinessRuleException("COMMITMENT_INVALID_PERIOD",
                    "A data final não pode ser anterior à data de início.");
        }
        commitment.setEndDate(endDate);
        pruneStaleScheduledOccurrences(commitment);
        Map<Long, Long> failedCounts = failedCountsByCommitment(commitment.getUserId());
        return toResponse(commitment, LocalDate.now(clock),
                failedCounts.getOrDefault(commitment.getId(), 0L));
    }

    /**
     * Hard delete is reserved for definitions without lifecycle history:
     * anything materialized, skipped, failed or reversed must be preserved —
     * pause or end the definition instead. Plain SCHEDULED rows go with it.
     */
    public void delete(Long id) {
        Commitment commitment = find(id);
        boolean hasHistory = occurrences.existsByCommitmentIdAndStatusIn(commitment.getId(),
                List.of(OccurrenceStatus.MATERIALIZED, OccurrenceStatus.SKIPPED,
                        OccurrenceStatus.FAILED, OccurrenceStatus.REVERSED));
        if (hasHistory) {
            throw new BusinessRuleException("COMMITMENT_HAS_HISTORY",
                    "Este recorrente possui ocorrências no histórico e não pode ser excluído. "
                            + "Pause ou encerre o recorrente para preservar o histórico.");
        }
        occurrences.deleteAll(
                occurrences.findAllByCommitmentIdAndUserId(commitment.getId(), commitment.getUserId()));
        commitments.delete(commitment);
    }

    private CommitmentResponse setActive(Long id, boolean active) {
        Commitment commitment = find(id);
        commitment.setActive(active);
        Map<Long, Long> failedCounts = failedCountsByCommitment(commitment.getUserId());
        return toResponse(commitment, LocalDate.now(clock),
                failedCounts.getOrDefault(commitment.getId(), 0L));
    }

    private void validate(CommitmentRequest request, Category category) {
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new BusinessRuleException("COMMITMENT_INVALID_PERIOD",
                    "A data final não pode ser anterior à data de início.");
        }
        if (request.cadence() == CommitmentCadence.MONTHLY && request.dueDay() == null) {
            throw new BusinessRuleException("COMMITMENT_DUE_DAY_REQUIRED",
                    "Informe o dia de vencimento para recorrentes mensais.");
        }
        RecurrenceTarget target = targetOf(request);
        ExecutionMode mode = modeOf(request);
        if (mode == ExecutionMode.AUTOMATIC && target == RecurrenceTarget.PROJECTION_ONLY) {
            throw new BusinessRuleException("COMMITMENT_AUTOMATIC_NEEDS_TARGET",
                    "A execução automática exige uma conta ou um cartão de destino.");
        }
        if (target == RecurrenceTarget.ACCOUNT_TRANSACTION) {
            if (request.accountId() == null) {
                throw new BusinessRuleException("COMMITMENT_ACCOUNT_REQUIRED",
                        "Escolha a conta que receberá os lançamentos deste recorrente.");
            }
            if (request.paymentMethod() == PaymentMethod.CREDIT) {
                throw new BusinessRuleException("USE_CREDIT_CARD_PURCHASE",
                        "Para lançamentos no crédito, use o destino de cartão de crédito.");
            }
        }
        if (target == RecurrenceTarget.CREDIT_CARD_PURCHASE) {
            if (request.creditCardId() == null) {
                throw new BusinessRuleException("COMMITMENT_CARD_REQUIRED",
                        "Escolha o cartão que receberá as compras deste recorrente.");
            }
            if (category.getType() != CategoryType.EXPENSE) {
                throw new BusinessRuleException("COMMITMENT_CARD_NEEDS_EXPENSE",
                        "Recorrentes no cartão exigem uma categoria de despesa.");
            }
        }
    }

    private void applyTargetFields(Long userId, Commitment commitment, CommitmentRequest request) {
        RecurrenceTarget target = targetOf(request);
        commitment.setTargetKind(target);
        commitment.setExecutionMode(modeOf(request));
        commitment.setPaymentMethod(request.paymentMethod());
        commitment.setInstallmentCount(request.installmentCount() != null
                ? request.installmentCount()
                : 1);
        if (target == RecurrenceTarget.ACCOUNT_TRANSACTION) {
            // Owner-scoped: another user's account id behaves as absent.
            Account account = accounts.findByIdAndUserId(request.accountId(), userId)
                    .orElseThrow(() -> new NotFoundException("Conta", request.accountId()));
            if (account.isArchived()) {
                throw new BusinessRuleException("ACCOUNT_ARCHIVED",
                        "Uma conta arquivada não pode receber lançamentos recorrentes.");
            }
            commitment.setAccount(account);
            commitment.setCreditCard(null);
        } else if (target == RecurrenceTarget.CREDIT_CARD_PURCHASE) {
            CreditCard card = cards.findByIdAndUserId(request.creditCardId(), userId)
                    .orElseThrow(() -> new NotFoundException("Cartão", request.creditCardId()));
            if (card.isArchived()) {
                throw new BusinessRuleException("CARD_ARCHIVED",
                        "Um cartão arquivado não pode receber compras recorrentes.");
            }
            commitment.setCreditCard(card);
            commitment.setAccount(null);
        } else {
            commitment.setAccount(null);
            commitment.setCreditCard(null);
        }
    }

    /**
     * The currency a commitment settles in.
     *
     * <p>For an account or card target the destination owns the currency: a
     * materialized occurrence becomes a real movement there, so any other
     * currency would be unpayable. A projection-only commitment has no
     * destination and may name any supported currency, defaulting to the
     * user's base currency.
     */
    private CurrencyCode deriveCurrency(Long userId, Commitment commitment, String requested) {
        CurrencyCode explicit = CurrencyCode.parseOrNull(requested);
        CurrencyCode destination = destinationCurrency(commitment);
        if (destination == null) {
            return explicit != null ? explicit : settings.forUser(userId).getBaseCurrency();
        }
        if (explicit != null && explicit != destination) {
            throw new BusinessRuleException("COMMITMENT_CURRENCY_MISMATCH",
                    ("O destino deste recorrente é em %s e não aceita um compromisso "
                            + "em %s. Não há conversão de moeda.")
                            .formatted(destination.name(), explicit.name()));
        }
        return destination;
    }

    /** Rejects an edit that repoints a commitment at a differently denominated target. */
    private void assertTargetMatchesCurrency(Commitment commitment) {
        CurrencyCode destination = destinationCurrency(commitment);
        if (destination != null && destination != commitment.getCurrency()) {
            throw new BusinessRuleException("COMMITMENT_CURRENCY_MISMATCH",
                    ("Este recorrente é em %s e não pode ter um destino em %s.")
                            .formatted(commitment.getCurrency().name(), destination.name()));
        }
    }

    private static CurrencyCode destinationCurrency(Commitment commitment) {
        if (commitment.getAccount() != null) {
            return commitment.getAccount().getCurrency();
        }
        if (commitment.getCreditCard() != null) {
            return commitment.getCreditCard().getCurrency();
        }
        return null;
    }

    /**
     * A commitment's currency is immutable: its occurrence history and every
     * materialized movement are denominated in it.
     */
    private void assertCurrencyUnchanged(Commitment commitment, String requested) {
        CurrencyCode explicit = CurrencyCode.parseOrNull(requested);
        if (explicit != null && explicit != commitment.getCurrency()) {
            throw new BusinessRuleException("CURRENCY_IMMUTABLE",
                    ("A moeda de um recorrente não pode ser alterada (%s).")
                            .formatted(commitment.getCurrency().name()));
        }
    }

    /** Removes untouched future SCHEDULED rows that the new schedule no longer produces. */
    private void pruneStaleScheduledOccurrences(Commitment commitment) {
        List<CommitmentOccurrence> persisted =
                occurrences.findAllByCommitmentIdAndUserId(commitment.getId(), commitment.getUserId());
        if (persisted.isEmpty()) {
            return;
        }
        LocalDate min = persisted.stream()
                .map(CommitmentOccurrence::getScheduledDate)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        LocalDate max = persisted.stream()
                .map(CommitmentOccurrence::getScheduledDate)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        Set<LocalDate> validDates =
                Set.copyOf(RecurrenceCalculator.occurrencesBetween(commitment, min, max));
        List<CommitmentOccurrence> stale = persisted.stream()
                .filter(o -> o.getStatus() == OccurrenceStatus.SCHEDULED)
                .filter(o -> o.getScheduledDate().equals(o.getEffectiveDate()))
                .filter(o -> !validDates.contains(o.getScheduledDate()))
                .toList();
        occurrences.deleteAll(stale);
    }

    private Map<Long, Long> failedCountsByCommitment(Long userId) {
        return occurrences.findAllByUserIdAndStatus(userId, OccurrenceStatus.FAILED).stream()
                .collect(Collectors.groupingBy(o -> o.getCommitment().getId(), Collectors.counting()));
    }

    private static RecurrenceTarget targetOf(CommitmentRequest request) {
        return Objects.requireNonNullElse(request.targetKind(), RecurrenceTarget.PROJECTION_ONLY);
    }

    private static ExecutionMode modeOf(CommitmentRequest request) {
        return Objects.requireNonNullElse(request.executionMode(), ExecutionMode.MANUAL);
    }

    private Category resolveCategory(Long userId, Long categoryId) {
        // Owner-scoped: another user's category id behaves as absent.
        return categories.findByIdAndUserId(categoryId, userId)
                .orElseThrow(() -> new NotFoundException("Categoria", categoryId));
    }

    private Commitment find(Long id) {
        return commitments.findByIdAndUserId(id, currentUser.currentUserId())
                .orElseThrow(() -> new NotFoundException("Compromisso", id));
    }

    private CommitmentResponse toResponse(Commitment commitment, LocalDate reference,
                                          long failedOccurrences) {
        boolean legacyProjectionOnly = commitment.getPaymentMethod() == PaymentMethod.CREDIT
                && commitment.getTargetKind() == RecurrenceTarget.PROJECTION_ONLY;
        return new CommitmentResponse(
                commitment.getId(),
                commitment.getDescription(),
                MoneyRules.normalize(commitment.getAmount(), commitment.getCurrency()),
                toCategory(commitment.getCategory()),
                commitment.getCadence(),
                commitment.getDueDay(),
                commitment.getStartDate(),
                commitment.getEndDate(),
                commitment.isActive(),
                commitment.getPaymentMethod(),
                RecurrenceCalculator.nextOccurrence(commitment, reference).orElse(null),
                commitment.getExecutionMode(),
                commitment.getTargetKind(),
                Optional.ofNullable(commitment.getAccount()).map(Account::getId).orElse(null),
                Optional.ofNullable(commitment.getAccount()).map(Account::getName).orElse(null),
                Optional.ofNullable(commitment.getCreditCard()).map(CreditCard::getId).orElse(null),
                Optional.ofNullable(commitment.getCreditCard()).map(CreditCard::getName).orElse(null),
                commitment.getInstallmentCount(),
                legacyProjectionOnly,
                failedOccurrences,
                commitment.getCurrency().name());
    }

    private static CommitmentCategory toCategory(Category category) {
        return new CommitmentCategory(category.getId(), category.getName(), category.getType());
    }
}
