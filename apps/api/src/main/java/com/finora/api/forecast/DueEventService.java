package com.finora.api.forecast;

import com.finora.api.commitment.Commitment;
import com.finora.api.commitment.CommitmentRepository;
import com.finora.api.commitment.RecurrenceCalculator;
import com.finora.api.commitment.RecurrenceTarget;
import com.finora.api.commitment.occurrence.CommitmentOccurrence;
import com.finora.api.commitment.occurrence.CommitmentOccurrenceRepository;
import com.finora.api.commitment.occurrence.OccurrenceStatus;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.CreditCardRepository;
import com.finora.api.creditcard.invoice.InvoiceService;
import com.finora.api.creditcard.invoice.InvoiceDtos.InvoiceSummaryResponse;
import com.finora.api.forecast.DueEventDtos.DueEvent;
import com.finora.api.forecast.DueEventDtos.DueEventSeverity;
import com.finora.api.forecast.DueEventDtos.DueEventType;
import com.finora.api.forecast.DueEventDtos.DueEventsResponse;
import com.finora.api.identity.CurrentUserProvider;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoritative due events, derived on demand for the public feed and the
 * persistent notification synchronizer. Sources: recurring occurrences (due, overdue,
 * failed), card invoices (due, overdue) and the forecast's first projected
 * negative-cash date.
 */
@Service
@Transactional(readOnly = true)
public class DueEventService {

    static final int MAX_RANGE_DAYS = 92;
    private static final int DUE_SOON_DAYS = 7;

    private final CommitmentRepository commitments;
    private final CommitmentOccurrenceRepository occurrences;
    private final CreditCardRepository cards;
    private final InvoiceService invoiceService;
    private final ForecastService forecastService;
    private final CurrentUserProvider currentUser;
    private final Clock clock;

    public DueEventService(CommitmentRepository commitments,
                           CommitmentOccurrenceRepository occurrences,
                           CreditCardRepository cards,
                           InvoiceService invoiceService,
                           ForecastService forecastService,
                           CurrentUserProvider currentUser,
                           Clock clock) {
        this.commitments = commitments;
        this.occurrences = occurrences;
        this.cards = cards;
        this.invoiceService = invoiceService;
        this.forecastService = forecastService;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public DueEventsResponse events(LocalDate from, LocalDate to) {
        return eventsForUser(currentUser.currentUserId(), from, to, DUE_SOON_DAYS);
    }

    /** Trusted internal entry point used by owner-by-owner background delivery. */
    public DueEventsResponse eventsForUser(Long userId, LocalDate from, LocalDate to) {
        return eventsForUser(userId, from, to, DUE_SOON_DAYS);
    }

    public DueEventsResponse eventsForUser(Long userId, LocalDate from, LocalDate to,
                                           int upcomingLeadDays) {
        LocalDate today = LocalDate.now(clock);
        LocalDate start = from != null ? from : today.minusDays(DUE_SOON_DAYS);
        LocalDate end = to != null ? to : today.plusDays(DUE_SOON_DAYS);
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw new BusinessRuleException("DUE_EVENT_RANGE_INVALID",
                    "O período consultado deve ser crescente e de no máximo 92 dias.");
        }

        List<DueEvent> events = new ArrayList<>();
        collectRecurringEvents(userId, today, start, end, upcomingLeadDays, events);
        collectInvoiceEvents(userId, today, start, end, upcomingLeadDays, events);
        collectInsufficientCash(userId, today, end, events);
        events.sort(Comparator.comparing(DueEvent::date).thenComparing(DueEvent::id));
        return new DueEventsResponse(start, end, events);
    }

    private void collectRecurringEvents(Long userId, LocalDate today, LocalDate start,
                                        LocalDate end, int leadDays, List<DueEvent> events) {
        // One window-bounded query covers every definition's overlay rows.
        Map<Long, Map<LocalDate, CommitmentOccurrence>> overlay = new HashMap<>();
        for (CommitmentOccurrence occurrence : occurrences
                .findAllByUserTouchingWindow(userId, start, end)) {
            overlay.computeIfAbsent(occurrence.getCommitment().getId(), key -> new HashMap<>())
                    .put(occurrence.getScheduledDate(), occurrence);
        }
        for (Commitment commitment : commitments.findAllByUserIdAndActiveTrue(userId)) {
            Map<LocalDate, CommitmentOccurrence> persisted =
                    overlay.getOrDefault(commitment.getId(), Map.of());
            for (LocalDate date : RecurrenceCalculator.occurrencesBetween(commitment, start, end)) {
                CommitmentOccurrence occurrence = persisted.get(date);
                OccurrenceStatus status = occurrence != null
                        ? occurrence.getStatus()
                        : OccurrenceStatus.SCHEDULED;
                LocalDate effective = occurrence != null ? occurrence.getEffectiveDate() : date;
                if (status == OccurrenceStatus.FAILED) {
                    events.add(recurringEvent(commitment, date, effective,
                            DueEventType.RECURRING_FAILED, DueEventSeverity.CRITICAL,
                            "Falha ao executar \"%s\"".formatted(commitment.getDescription())));
                    continue;
                }
                if (status != OccurrenceStatus.SCHEDULED) {
                    continue;
                }
                if (effective.isBefore(today)) {
                    // A projection-only definition has nothing to execute, so a
                    // past occurrence is plain history — never "overdue".
                    if (commitment.getTargetKind() != RecurrenceTarget.PROJECTION_ONLY) {
                        events.add(recurringEvent(commitment, date, effective,
                                DueEventType.RECURRING_OVERDUE, DueEventSeverity.CRITICAL,
                                "\"%s\" venceu e não foi executado".formatted(
                                        commitment.getDescription())));
                    }
                } else if (effective.equals(today)) {
                    events.add(recurringEvent(commitment, date, effective,
                            DueEventType.RECURRING_DUE_TODAY, DueEventSeverity.WARNING,
                            "\"%s\" vence hoje".formatted(commitment.getDescription())));
                } else if (!effective.isAfter(today.plusDays(leadDays))) {
                    events.add(recurringEvent(commitment, date, effective,
                            DueEventType.RECURRING_DUE_SOON, DueEventSeverity.INFO,
                            "\"%s\" vence em breve".formatted(commitment.getDescription())));
                }
            }
        }
    }

    private DueEvent recurringEvent(Commitment commitment, LocalDate scheduledDate,
                                    LocalDate effectiveDate, DueEventType type,
                                    DueEventSeverity severity, String title) {
        // The commitment is the authority on what it costs and in what.
        CurrencyCode currency = commitment.getCurrency();
        return new DueEvent(
                "%s:COMMITMENT:%d:%s".formatted(type, commitment.getId(), scheduledDate),
                "COMMITMENT:%d:%s".formatted(commitment.getId(), scheduledDate),
                type,
                severity,
                effectiveDate,
                title,
                MoneyRules.normalize(commitment.getAmount(), currency),
                currency,
                "COMMITMENT",
                commitment.getId(),
                "/commitments");
    }

    private void collectInvoiceEvents(Long userId, LocalDate today, LocalDate start,
                                      LocalDate end, int leadDays, List<DueEvent> events) {
        for (var card : cards.findAllByUserIdOrderByArchivedAscNameAsc(userId)) {
            if (card.isArchived()) {
                continue;
            }
            // The owner-explicit variant: this feed also runs from the
            // scheduler, which has no session to read an owner out of.
            for (InvoiceSummaryResponse invoice
                    : invoiceService.listForCardForUser(userId, card.getId(), today)) {
                if (invoice.outstandingAmount().signum() <= 0
                        || invoice.dueDate().isBefore(start)
                        || invoice.dueDate().isAfter(end)) {
                    continue;
                }
                DueEventType type;
                DueEventSeverity severity;
                String title;
                if (invoice.dueDate().isBefore(today)) {
                    type = DueEventType.INVOICE_OVERDUE;
                    severity = DueEventSeverity.CRITICAL;
                    title = "Fatura %s vencida".formatted(card.getName());
                } else if (invoice.dueDate().equals(today)) {
                    type = DueEventType.INVOICE_DUE_TODAY;
                    severity = DueEventSeverity.WARNING;
                    title = "Fatura %s vence hoje".formatted(card.getName());
                } else if (!invoice.dueDate().isAfter(today.plusDays(leadDays))) {
                    type = DueEventType.INVOICE_DUE_SOON;
                    severity = DueEventSeverity.INFO;
                    title = "Fatura %s vence em breve".formatted(card.getName());
                } else {
                    continue;
                }
                events.add(new DueEvent(
                        "%s:INVOICE:%d".formatted(type, invoice.id()),
                        "CARD_INVOICE:%d".formatted(invoice.id()),
                        type,
                        severity,
                        invoice.dueDate(),
                        title,
                        invoice.outstandingAmount(),
                        // The invoice bills in its card's currency, which the
                        // summary already carries. The card is the authority.
                        CurrencyCode.parse(invoice.currency()),
                        "CARD_INVOICE",
                        invoice.id(),
                        "/credit-cards/%d/invoices/%d".formatted(card.getId(), invoice.id())));
            }
        }
    }

    /**
     * One cash-risk event per currency that actually runs out of money.
     *
     * <p>Deliberately reads {@code byCurrency} rather than the response's
     * scalar fields. Those scalars exist only for a homogeneous forecast and
     * are all null the moment a second currency appears, so reading them meant
     * that acquiring a dollar account silently switched off the reais cash-risk
     * alert as well — the alert disappeared exactly when the finances got more
     * complicated.
     *
     * <p>Each currency is its own risk, with its own first negative date and
     * its own lowest balance, and gets its own lifecycle identity. Two
     * currencies running dry are two facts; adding them would produce a number
     * that is not money in any denomination.
     */
    private void collectInsufficientCash(Long userId, LocalDate today, LocalDate end,
                                         List<DueEvent> events) {
        int horizon = (int) Math.max(1, ChronoUnit.DAYS.between(today, end));
        var forecast = forecastService.forecastForUser(userId, horizon, null);
        for (var summary : forecast.byCurrency()) {
            if (summary.firstNegativeDate() == null) {
                continue;
            }
            CurrencyCode currency = CurrencyCode.parse(summary.currency());
            events.add(new DueEvent(
                    // Two currencies can go negative on the same day, so the
                    // legacy public id names the currency too — otherwise the
                    // feed would emit duplicate ids and sort unstably.
                    "INSUFFICIENT_CASH_PROJECTED:%s:%s"
                            .formatted(currency, summary.firstNegativeDate()),
                    "FORECAST:INSUFFICIENT_CASH:%s".formatted(currency),
                    DueEventType.INSUFFICIENT_CASH_PROJECTED,
                    DueEventSeverity.CRITICAL,
                    summary.firstNegativeDate(),
                    // No currency in the title: the title reaches the browser
                    // even when the user asked for amounts to stay hidden, and
                    // naming the denomination there would disclose the one
                    // thing the hidden amount was supposed to keep private.
                    "Saldo projetado fica negativo em %s".formatted(summary.firstNegativeDate()),
                    summary.lowestBalance(),
                    currency,
                    "FORECAST",
                    null,
                    "/forecast"));
        }
    }
}
