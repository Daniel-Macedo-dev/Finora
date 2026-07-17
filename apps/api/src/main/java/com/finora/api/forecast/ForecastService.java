package com.finora.api.forecast;

import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.category.CategoryType;
import com.finora.api.commitment.Commitment;
import com.finora.api.commitment.CommitmentRepository;
import com.finora.api.commitment.RecurrenceCalculator;
import com.finora.api.commitment.RecurrenceTarget;
import com.finora.api.commitment.occurrence.CommitmentOccurrence;
import com.finora.api.commitment.occurrence.CommitmentOccurrenceRepository;
import com.finora.api.commitment.occurrence.OccurrenceStatus;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.CreditCard;
import com.finora.api.creditcard.InvoiceCycleCalculator;
import com.finora.api.creditcard.InvoiceCycleCalculator.InvoiceCycle;
import com.finora.api.creditcard.adjustment.InvoiceAdjustmentRepository;
import com.finora.api.creditcard.installment.CardInstallmentRepository;
import com.finora.api.creditcard.installment.InstallmentAllocator;
import com.finora.api.creditcard.invoice.CardInvoice;
import com.finora.api.creditcard.invoice.CardInvoiceRepository;
import com.finora.api.creditcard.payment.InvoicePaymentRepository;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic future cash-flow forecast.
 *
 * <p>The forecast models <em>cash movement</em>, not expense recognition:
 * account income/expenses move cash on their transaction dates, while card
 * spending moves cash only when its invoice falls due. The inputs are
 * combined without double counting:
 *
 * <ol>
 *   <li>opening balances derived as of the current date;</li>
 *   <li>already-recorded future-dated transactions (actuals);</li>
 *   <li>unmaterialized recurring account occurrences (projections — a
 *       materialized occurrence appears through its artifact instead; skipped
 *       and reversed occurrences are excluded);</li>
 *   <li>outstanding card invoices at their due dates (net of completed
 *       payments; reversals restore the projection);</li>
 *   <li>projected recurring card purchases, split with the real installment
 *       allocator and placed on the real invoice cycle's due dates.</li>
 * </ol>
 *
 * <p>Wishlist options, budgets and goals are intentions, not scheduled cash
 * events — they are never included. Flows that no account can settle (no
 * default payment account, projection-only definitions, account-less
 * transactions) are disclosed as unassigned and never change a balance.
 */
@Service
@Transactional(readOnly = true)
public class ForecastService {

    public static final int DEFAULT_HORIZON_DAYS = 90;
    public static final int MAX_HORIZON_DAYS = 730;

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final CommitmentRepository commitments;
    private final CommitmentOccurrenceRepository occurrences;
    private final CardInvoiceRepository invoices;
    private final CardInstallmentRepository installments;
    private final InvoiceAdjustmentRepository adjustments;
    private final InvoicePaymentRepository payments;
    private final CurrentUserProvider currentUser;
    private final Clock clock;

    public ForecastService(AccountRepository accounts,
                           TransactionRepository transactions,
                           CommitmentRepository commitments,
                           CommitmentOccurrenceRepository occurrences,
                           CardInvoiceRepository invoices,
                           CardInstallmentRepository installments,
                           InvoiceAdjustmentRepository adjustments,
                           InvoicePaymentRepository payments,
                           CurrentUserProvider currentUser,
                           Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.commitments = commitments;
        this.occurrences = occurrences;
        this.invoices = invoices;
        this.installments = installments;
        this.adjustments = adjustments;
        this.payments = payments;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public ForecastDtos.ForecastResponse forecast(Integer days, Long accountId) {
        return forecastForUser(currentUser.currentUserId(), days, accountId);
    }

    /** Owner-explicit variant for the dashboard and other internal callers. */
    public ForecastDtos.ForecastResponse forecastForUser(Long userId, Integer days, Long accountId) {
        int horizon = days != null ? days : DEFAULT_HORIZON_DAYS;
        if (horizon < 1 || horizon > MAX_HORIZON_DAYS) {
            throw new BusinessRuleException("FORECAST_HORIZON_INVALID",
                    "O horizonte da previsão deve estar entre 1 dia e 24 meses.");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate end = today.plusDays(horizon);

        List<Account> openAccounts = accounts.findAllByUserIdOrderByDisplayOrderAscNameAsc(userId)
                .stream()
                .filter(account -> !account.isArchived())
                .toList();
        if (accountId != null && openAccounts.stream().noneMatch(a -> a.getId().equals(accountId))) {
            throw new NotFoundException("Conta", accountId);
        }

        BigDecimal opening = BigDecimal.ZERO;
        for (Account account : openAccounts) {
            if (accountId != null && !account.getId().equals(accountId)) {
                continue;
            }
            BigDecimal movement = accounts.netMovementThrough(account.getId(), userId, today);
            BigDecimal settled = payments.sumCompletedByAccount(account.getId(), userId);
            opening = opening.add(account.getOpeningBalance())
                    .add(movement != null ? movement : BigDecimal.ZERO)
                    .subtract(settled);
        }

        // One bulk load feeds both recurring collectors: the active definitions
        // and every occurrence row touching the window, grouped by definition.
        List<Commitment> activeCommitments = commitments.findAllByUserIdAndActiveTrue(userId);
        Map<Long, Map<LocalDate, CommitmentOccurrence>> overlay =
                occurrenceOverlay(userId, today.plusDays(1), end);

        List<ForecastDtos.ForecastEvent> events = new ArrayList<>();
        collectFutureTransactions(userId, today, end, events);
        collectRecurringAccountOccurrences(activeCommitments, overlay, today, end, events);
        collectCardInvoices(userId, today, end, events);
        collectProjectedRecurringCardPurchases(activeCommitments, overlay, today, end, events);

        if (accountId != null) {
            Long filter = accountId;
            events = events.stream()
                    .filter(e -> filter.equals(e.accountId()))
                    .toList();
        }
        events = events.stream()
                .sorted(Comparator.comparing(ForecastDtos.ForecastEvent::date)
                        .thenComparing(ForecastDtos.ForecastEvent::description))
                .toList();

        return summarize(today, end, accountId, opening, events);
    }

    // ── input collectors ─────────────────────────────────────────────────────

    /** Already-recorded transactions dated after today: actual future cash. */
    private void collectFutureTransactions(Long userId, LocalDate today, LocalDate end,
                                           List<ForecastDtos.ForecastEvent> events) {
        for (Transaction t : transactions.findActiveInForecastWindow(userId, today, end)) {
            BigDecimal amount = t.getType() == TransactionType.INCOME
                    ? t.getAmount()
                    : t.getAmount().negate();
            boolean unassigned = t.getAccount() == null;
            events.add(new ForecastDtos.ForecastEvent(
                    t.getOccurredOn(),
                    t.getDescription(),
                    MoneyRules.normalize(amount),
                    ForecastDtos.ForecastSource.ACTUAL_TRANSACTION,
                    unassigned ? null : t.getAccount().getId(),
                    unassigned ? null : t.getAccount().getName(),
                    unassigned,
                    t.getCommitmentId(),
                    t.getId(),
                    null,
                    null,
                    null));
        }
    }

    /**
     * Unmaterialized recurring occurrences with an account (or no) target.
     * Card-target definitions are projected through their invoices instead.
     */
    private void collectRecurringAccountOccurrences(List<Commitment> activeCommitments,
                                                    Map<Long, Map<LocalDate, CommitmentOccurrence>> overlay,
                                                    LocalDate today, LocalDate end,
                                                    List<ForecastDtos.ForecastEvent> events) {
        for (Commitment commitment : activeCommitments) {
            if (commitment.getTargetKind() == RecurrenceTarget.CREDIT_CARD_PURCHASE) {
                continue;
            }
            for (LocalDate date : projectedOccurrenceDates(commitment, overlay, today, end)) {
                boolean unassigned = commitment.getAccount() == null;
                BigDecimal amount = commitment.getCategory().getType() == CategoryType.INCOME
                        ? commitment.getAmount()
                        : commitment.getAmount().negate();
                events.add(new ForecastDtos.ForecastEvent(
                        date,
                        commitment.getDescription(),
                        MoneyRules.normalize(amount),
                        ForecastDtos.ForecastSource.RECURRING_ACCOUNT_OCCURRENCE,
                        unassigned ? null : commitment.getAccount().getId(),
                        unassigned ? null : commitment.getAccount().getName(),
                        unassigned,
                        commitment.getId(),
                        null,
                        null,
                        null,
                        null));
            }
        }
    }

    /** Outstanding invoice balances leave cash on their due dates. */
    private void collectCardInvoices(Long userId, LocalDate today, LocalDate end,
                                     List<ForecastDtos.ForecastEvent> events) {
        Map<Long, BigDecimal> charges = new HashMap<>();
        for (Object[] row : installments.sumActiveGroupedByInvoiceForUser(userId)) {
            charges.merge((Long) row[0], (BigDecimal) row[1], BigDecimal::add);
        }
        for (Object[] row : adjustments.sumActiveNetGroupedByInvoiceForUser(userId)) {
            charges.merge((Long) row[0], (BigDecimal) row[1], BigDecimal::add);
        }
        Map<Long, BigDecimal> paid = new HashMap<>();
        for (Object[] row : payments.sumCompletedGroupedByInvoiceForUser(userId)) {
            paid.put((Long) row[0], (BigDecimal) row[1]);
        }
        for (CardInvoice invoice : invoices
                .findAllByUserIdAndDueDateLessThanEqualOrderByDueDateAsc(userId, end)) {
            BigDecimal outstanding = charges.getOrDefault(invoice.getId(), BigDecimal.ZERO)
                    .subtract(paid.getOrDefault(invoice.getId(), BigDecimal.ZERO));
            if (outstanding.signum() <= 0) {
                continue;
            }
            // Overdue outstanding is payable immediately: it hits cash now.
            LocalDate date = invoice.getDueDate().isBefore(today) ? today : invoice.getDueDate();
            CreditCard card = invoice.getCard();
            Account payingAccount = card.getDefaultPaymentAccount();
            boolean unassigned = payingAccount == null || payingAccount.isArchived();
            events.add(new ForecastDtos.ForecastEvent(
                    date,
                    "Fatura %s · %s".formatted(card.getName(),
                            YearMonth.from(invoice.getReferenceMonth())),
                    MoneyRules.normalize(outstanding.negate()),
                    ForecastDtos.ForecastSource.CARD_INVOICE,
                    unassigned ? null : payingAccount.getId(),
                    unassigned ? null : payingAccount.getName(),
                    unassigned,
                    null,
                    null,
                    invoice.getId(),
                    card.getId(),
                    null));
        }
    }

    /**
     * Recurring card purchases not yet materialized: each projected purchase
     * is split by the real installment allocator and lands on the real cycle's
     * invoice due dates. Existing invoice rows only ever contain materialized
     * charges, so the two sources never overlap.
     */
    private void collectProjectedRecurringCardPurchases(List<Commitment> activeCommitments,
                                                        Map<Long, Map<LocalDate, CommitmentOccurrence>> overlay,
                                                        LocalDate today, LocalDate end,
                                                        List<ForecastDtos.ForecastEvent> events) {
        for (Commitment commitment : activeCommitments) {
            if (commitment.getTargetKind() != RecurrenceTarget.CREDIT_CARD_PURCHASE
                    || commitment.getCreditCard() == null) {
                continue;
            }
            CreditCard card = commitment.getCreditCard();
            Account payingAccount = card.getDefaultPaymentAccount();
            boolean unassigned = payingAccount == null || payingAccount.isArchived();
            for (LocalDate purchaseDate : projectedOccurrenceDates(commitment, overlay, today, end)) {
                List<BigDecimal> amounts;
                try {
                    amounts = InstallmentAllocator.allocate(
                            commitment.getAmount(), commitment.getInstallmentCount());
                } catch (IllegalArgumentException tooSmall) {
                    continue;
                }
                InvoiceCycle first = InvoiceCycleCalculator.cycleForPurchase(
                        card.getClosingDay(), card.getDueDay(), purchaseDate);
                for (int i = 0; i < amounts.size(); i++) {
                    InvoiceCycle cycle = i == 0
                            ? first
                            : InvoiceCycleCalculator.cycleFor(card.getClosingDay(),
                                    card.getDueDay(), first.referenceMonth().plusMonths(i));
                    if (cycle.dueDate().isAfter(end) || !cycle.dueDate().isAfter(today)) {
                        continue;
                    }
                    events.add(new ForecastDtos.ForecastEvent(
                            cycle.dueDate(),
                            "%s (%d/%d) · %s".formatted(commitment.getDescription(),
                                    i + 1, amounts.size(), card.getName()),
                            MoneyRules.normalize(amounts.get(i).negate()),
                            ForecastDtos.ForecastSource.PROJECTED_RECURRING_CARD_PURCHASE,
                            unassigned ? null : payingAccount.getId(),
                            unassigned ? null : payingAccount.getName(),
                            unassigned,
                            commitment.getId(),
                            null,
                            null,
                            card.getId(),
                            null));
                }
            }
        }
    }

    /** Occurrence rows touching the window, one query, grouped by definition. */
    private Map<Long, Map<LocalDate, CommitmentOccurrence>> occurrenceOverlay(
            Long userId, LocalDate from, LocalDate to) {
        Map<Long, Map<LocalDate, CommitmentOccurrence>> overlay = new HashMap<>();
        for (CommitmentOccurrence occurrence : occurrences
                .findAllByUserTouchingWindow(userId, from, to)) {
            overlay.computeIfAbsent(occurrence.getCommitment().getId(), key -> new HashMap<>())
                    .put(occurrence.getScheduledDate(), occurrence);
        }
        return overlay;
    }

    /**
     * Occurrence dates of one definition that remain projections inside
     * {@code (today, end]}: materialized occurrences already count through
     * their artifacts, skipped/reversed ones are excluded, rescheduled ones
     * move to their effective date, and failed ones stay expected.
     */
    private List<LocalDate> projectedOccurrenceDates(Commitment commitment,
                                                     Map<Long, Map<LocalDate, CommitmentOccurrence>> overlay,
                                                     LocalDate today, LocalDate end) {
        Map<LocalDate, CommitmentOccurrence> persisted =
                new HashMap<>(overlay.getOrDefault(commitment.getId(), Map.of()));
        List<LocalDate> calculated = RecurrenceCalculator.occurrencesBetween(
                commitment, today.plusDays(1), end);
        List<LocalDate> projected = new ArrayList<>();
        for (LocalDate date : calculated) {
            CommitmentOccurrence occurrence = persisted.remove(date);
            if (occurrence == null) {
                projected.add(date);
                continue;
            }
            switch (occurrence.getStatus()) {
                case SCHEDULED, FAILED -> {
                    LocalDate effective = occurrence.getEffectiveDate();
                    if (effective.isAfter(today) && !effective.isAfter(end)) {
                        projected.add(effective);
                    }
                }
                case MATERIALIZED, SKIPPED, REVERSED -> { /* excluded */ }
            }
        }
        // Occurrences rescheduled into the window from outside it.
        for (CommitmentOccurrence occurrence : persisted.values()) {
            if ((occurrence.getStatus() == OccurrenceStatus.SCHEDULED
                    || occurrence.getStatus() == OccurrenceStatus.FAILED)
                    && occurrence.getEffectiveDate().isAfter(today)
                    && !occurrence.getEffectiveDate().isAfter(end)
                    && !occurrence.getEffectiveDate().equals(occurrence.getScheduledDate())) {
                projected.add(occurrence.getEffectiveDate());
            }
        }
        return projected;
    }

    // ── aggregation ──────────────────────────────────────────────────────────

    private ForecastDtos.ForecastResponse summarize(LocalDate today, LocalDate end, Long accountId,
                                                    BigDecimal opening,
                                                    List<ForecastDtos.ForecastEvent> events) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal accountExpenses = BigDecimal.ZERO;
        BigDecimal invoiceOutflows = BigDecimal.ZERO;
        BigDecimal unassignedIn = BigDecimal.ZERO;
        BigDecimal unassignedOut = BigDecimal.ZERO;

        BigDecimal balance = opening;
        BigDecimal lowest = opening;
        LocalDate lowestDate = today;
        LocalDate firstNegative = opening.signum() < 0 ? today : null;

        Map<YearMonth, BigDecimal[]> monthly = new LinkedHashMap<>();
        List<ForecastDtos.ForecastEvent> sealed = new ArrayList<>(events.size());
        for (ForecastDtos.ForecastEvent event : events) {
            boolean invoiceLike = event.source() == ForecastDtos.ForecastSource.CARD_INVOICE
                    || event.source() == ForecastDtos.ForecastSource.PROJECTED_RECURRING_CARD_PURCHASE;
            if (event.unassigned()) {
                if (event.amount().signum() >= 0) {
                    unassignedIn = unassignedIn.add(event.amount());
                } else {
                    unassignedOut = unassignedOut.add(event.amount().negate());
                }
                sealed.add(event);
                continue;
            }
            if (event.amount().signum() >= 0) {
                income = income.add(event.amount());
            } else if (invoiceLike) {
                invoiceOutflows = invoiceOutflows.add(event.amount().negate());
            } else {
                accountExpenses = accountExpenses.add(event.amount().negate());
            }

            balance = balance.add(event.amount());
            if (balance.compareTo(lowest) < 0) {
                lowest = balance;
                lowestDate = event.date();
            }
            if (firstNegative == null && balance.signum() < 0) {
                firstNegative = event.date();
            }
            BigDecimal[] month = monthly.computeIfAbsent(
                    YearMonth.from(event.date()),
                    key -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            if (event.amount().signum() >= 0) {
                month[0] = month[0].add(event.amount());
            } else {
                month[1] = month[1].add(event.amount().negate());
            }
            month[2] = balance;
            sealed.add(ForecastDtos.withBalance(event, MoneyRules.normalize(balance)));
        }

        List<ForecastDtos.ForecastMonth> months = monthly.entrySet().stream()
                .map(entry -> new ForecastDtos.ForecastMonth(
                        entry.getKey(),
                        MoneyRules.normalize(entry.getValue()[0]),
                        MoneyRules.normalize(entry.getValue()[1]),
                        MoneyRules.normalize(entry.getValue()[0].subtract(entry.getValue()[1])),
                        MoneyRules.normalize(entry.getValue()[2])))
                .toList();

        return new ForecastDtos.ForecastResponse(
                today,
                end,
                accountId,
                MoneyRules.normalize(opening),
                MoneyRules.normalize(income),
                MoneyRules.normalize(accountExpenses),
                MoneyRules.normalize(invoiceOutflows),
                MoneyRules.normalize(balance),
                MoneyRules.normalize(lowest),
                lowestDate,
                firstNegative,
                MoneyRules.normalize(unassignedIn),
                MoneyRules.normalize(unassignedOut),
                sealed,
                months);
    }
}
