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
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.settings.SettingsService;
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
import java.util.EnumMap;
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
    private final SettingsService settings;
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
                           SettingsService settings,
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
        this.settings = settings;
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

        // Opening balances are per currency: an account can only contribute to
        // the running balance of the denomination it actually settles in.
        Map<CurrencyCode, BigDecimal> openings = new EnumMap<>(CurrencyCode.class);
        for (Account account : openAccounts) {
            if (accountId != null && !account.getId().equals(accountId)) {
                continue;
            }
            BigDecimal movement = accounts.netMovementThrough(account.getId(), userId, today);
            BigDecimal settled = payments.sumCompletedByAccount(account.getId(), userId);
            BigDecimal balance = account.getOpeningBalance()
                    .add(movement != null ? movement : BigDecimal.ZERO)
                    .subtract(settled);
            openings.merge(account.getCurrency(), balance, BigDecimal::add);
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

        return summarize(today, end, accountId,
                settings.forUser(userId).getBaseCurrency(), openings, events);
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
                    MoneyRules.normalize(amount, t.getCurrency()),
                    // The transaction's own currency. For an account-linked row
                    // a database FK already forces it to equal the account's.
                    t.getCurrency().name(),
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
                        MoneyRules.normalize(amount, commitment.getCurrency()),
                        // The commitment settles in its own currency, which the
                        // domain already forces to match its destination account.
                        commitment.getCurrency().name(),
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
                    MoneyRules.normalize(outstanding.negate(), card.getCurrency()),
                    // The card is the authority for everything it bills, and its
                    // default payment account is constrained to the same currency.
                    card.getCurrency().name(),
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
                            MoneyRules.normalize(amounts.get(i).negate(), card.getCurrency()),
                            card.getCurrency().name(),
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

    /**
     * Runs one independent forecast per currency, in a single ordered pass.
     *
     * <p>Every accumulator below is keyed by currency, so an event only ever
     * touches the running balance of the denomination it settles in. That is
     * the whole correction: the previous version kept one balance and added
     * dollars into reais, producing the most actionable wrong number the
     * product could show.
     *
     * <p>Nothing else about the algorithm changes. Events arrive already sorted
     * and are emitted in that same order; unassigned events are still disclosed
     * without moving any balance; invoice outflows are still separated from
     * ordinary account expenses; and card spending still reaches cash exactly
     * once, through its invoice.
     */
    private ForecastDtos.ForecastResponse summarize(LocalDate today, LocalDate end, Long accountId,
                                                    CurrencyCode base,
                                                    Map<CurrencyCode, BigDecimal> openings,
                                                    List<ForecastDtos.ForecastEvent> events) {
        Map<CurrencyCode, Running> byCurrency = new EnumMap<>(CurrencyCode.class);
        // An account's balance seeds its own currency even with no events at
        // all; a zero balance does not, so a base-currency-only user keeps
        // exactly one series and the familiar scalar contract.
        openings.forEach((currency, opening) -> {
            if (opening.signum() != 0) {
                byCurrency.put(currency, new Running(opening, today));
            }
        });

        List<ForecastDtos.ForecastEvent> sealed = new ArrayList<>(events.size());
        for (ForecastDtos.ForecastEvent event : events) {
            CurrencyCode currency = CurrencyCode.parse(event.currency());
            Running state = byCurrency.computeIfAbsent(currency,
                    key -> new Running(openings.getOrDefault(key, BigDecimal.ZERO), today));

            if (event.unassigned()) {
                // Nothing settles this, so it is disclosed without ever moving a
                // balance — grouped under its own currency like everything else.
                if (event.amount().signum() >= 0) {
                    state.unassignedIn = state.unassignedIn.add(event.amount());
                } else {
                    state.unassignedOut = state.unassignedOut.add(event.amount().negate());
                }
                sealed.add(event);
                continue;
            }

            boolean invoiceLike = event.source() == ForecastDtos.ForecastSource.CARD_INVOICE
                    || event.source() == ForecastDtos.ForecastSource.PROJECTED_RECURRING_CARD_PURCHASE;
            if (event.amount().signum() >= 0) {
                state.income = state.income.add(event.amount());
            } else if (invoiceLike) {
                state.invoiceOutflows = state.invoiceOutflows.add(event.amount().negate());
            } else {
                state.accountExpenses = state.accountExpenses.add(event.amount().negate());
            }
            state.assignedEvents++;

            state.balance = state.balance.add(event.amount());
            if (state.balance.compareTo(state.lowest) < 0) {
                state.lowest = state.balance;
                state.lowestDate = event.date();
            }
            if (state.firstNegative == null && state.balance.signum() < 0) {
                state.firstNegative = event.date();
            }
            BigDecimal[] month = state.monthly.computeIfAbsent(
                    YearMonth.from(event.date()),
                    key -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            if (event.amount().signum() >= 0) {
                month[0] = month[0].add(event.amount());
            } else {
                month[1] = month[1].add(event.amount().negate());
            }
            month[2] = state.balance;
            sealed.add(ForecastDtos.withBalance(
                    event, MoneyRules.normalize(state.balance, currency)));
        }

        // A user with nothing at all still gets one base-currency series, so an
        // empty forecast keeps its zeros instead of turning every scalar null.
        if (byCurrency.isEmpty()) {
            byCurrency.put(base, new Running(BigDecimal.ZERO, today));
        }

        List<ForecastDtos.ForecastCurrencySummary> summaries = new ArrayList<>();
        byCurrency.forEach((currency, state) -> summaries.add(
                new ForecastDtos.ForecastCurrencySummary(
                        currency.name(),
                        MoneyRules.normalize(state.opening, currency),
                        MoneyRules.normalize(state.income, currency),
                        MoneyRules.normalize(state.accountExpenses, currency),
                        MoneyRules.normalize(state.invoiceOutflows, currency),
                        MoneyRules.normalize(state.balance, currency),
                        MoneyRules.normalize(state.lowest, currency),
                        state.lowestDate,
                        state.firstNegative,
                        MoneyRules.normalize(state.unassignedIn, currency),
                        MoneyRules.normalize(state.unassignedOut, currency),
                        state.assignedEvents,
                        months(state.monthly, currency))));

        // The scalar contract survives only where it still means something: one
        // currency. A mixed forecast leaves every scalar null rather than
        // sending a figure somebody would act on. An account-filtered forecast
        // is homogeneous by construction, so it always keeps them.
        ForecastDtos.ForecastCurrencySummary only =
                summaries.size() == 1 ? summaries.get(0) : null;

        return new ForecastDtos.ForecastResponse(
                today,
                end,
                accountId,
                base.name(),
                only == null ? null : only.currency(),
                only == null ? null : only.openingBalance(),
                only == null ? null : only.income(),
                only == null ? null : only.accountExpenses(),
                only == null ? null : only.invoiceOutflows(),
                only == null ? null : only.closingBalance(),
                only == null ? null : only.lowestBalance(),
                only == null ? null : only.lowestBalanceDate(),
                only == null ? null : only.firstNegativeDate(),
                only == null ? null : only.unassignedInflows(),
                only == null ? null : only.unassignedOutflows(),
                List.copyOf(summaries),
                sealed,
                only == null ? List.of() : only.months());
    }

    /** One currency's running state through the ordered pass. */
    private static final class Running {
        private BigDecimal income = BigDecimal.ZERO;
        private BigDecimal accountExpenses = BigDecimal.ZERO;
        private BigDecimal invoiceOutflows = BigDecimal.ZERO;
        private BigDecimal unassignedIn = BigDecimal.ZERO;
        private BigDecimal unassignedOut = BigDecimal.ZERO;
        private final BigDecimal opening;
        private BigDecimal balance;
        private BigDecimal lowest;
        private LocalDate lowestDate;
        private LocalDate firstNegative;
        private int assignedEvents;
        private final Map<YearMonth, BigDecimal[]> monthly = new LinkedHashMap<>();

        private Running(BigDecimal opening, LocalDate today) {
            this.opening = opening;
            this.balance = opening;
            this.lowest = opening;
            this.lowestDate = today;
            this.firstNegative = opening.signum() < 0 ? today : null;
        }
    }

    private static List<ForecastDtos.ForecastMonth> months(
            Map<YearMonth, BigDecimal[]> monthly, CurrencyCode currency) {
        return monthly.entrySet().stream()
                .map(entry -> new ForecastDtos.ForecastMonth(
                        entry.getKey(),
                        MoneyRules.normalize(entry.getValue()[0], currency),
                        MoneyRules.normalize(entry.getValue()[1], currency),
                        MoneyRules.normalize(
                                entry.getValue()[0].subtract(entry.getValue()[1]), currency),
                        MoneyRules.normalize(entry.getValue()[2], currency)))
                .toList();
    }
}
