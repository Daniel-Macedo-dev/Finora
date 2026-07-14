package com.finora.api.commitment.occurrence;

import com.finora.api.commitment.Commitment;
import com.finora.api.commitment.CommitmentRepository;
import com.finora.api.commitment.RecurrenceCalculator;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.web.PageResponse;
import com.finora.api.commitment.occurrence.OccurrenceDtos.OccurrencePreviewResponse;
import com.finora.api.commitment.occurrence.OccurrenceDtos.OccurrenceResponse;
import com.finora.api.commitment.occurrence.OccurrenceDtos.ProcessDueResponse;
import com.finora.api.creditcard.purchase.CardPurchaseService;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Occurrence lifecycle (preview, history, skip, reschedule, reversal) and the
 * orchestration around the transactional {@link OccurrenceMaterializer}.
 *
 * <p>Manual execution, automatic processing and downtime catch-up all funnel
 * through {@link #materialize} — one idempotent path. Occurrence identities
 * derive from the calendar (never from scheduler ticks), so work missed while
 * the application was offline is found again on the next scan.
 */
@Service
public class OccurrenceService {

    static final int MAX_PREVIEW_DAYS = 731; // ~24 months

    private static final Logger log = LoggerFactory.getLogger(OccurrenceService.class);

    private final CommitmentOccurrenceRepository occurrences;
    private final CommitmentRepository commitments;
    private final TransactionRepository transactions;
    private final CardPurchaseService cardPurchases;
    private final OccurrenceMaterializer materializer;
    private final CurrentUserProvider currentUser;
    private final Clock clock;

    public OccurrenceService(CommitmentOccurrenceRepository occurrences,
                             CommitmentRepository commitments,
                             TransactionRepository transactions,
                             CardPurchaseService cardPurchases,
                             OccurrenceMaterializer materializer,
                             CurrentUserProvider currentUser,
                             Clock clock) {
        this.occurrences = occurrences;
        this.commitments = commitments;
        this.transactions = transactions;
        this.cardPurchases = cardPurchases;
        this.materializer = materializer;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    // ── queries ───────────────────────────────────────────────────────────────

    /** Calculated occurrences overlaid with their persisted lifecycle state. */
    @Transactional(readOnly = true)
    public OccurrencePreviewResponse preview(Long commitmentId, LocalDate from, LocalDate to) {
        Long userId = currentUser.currentUserId();
        Commitment commitment = find(userId, commitmentId);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAX_PREVIEW_DAYS) {
            throw new BusinessRuleException("OCCURRENCE_RANGE_INVALID",
                    "O período consultado deve ser crescente e de no máximo 24 meses.");
        }
        Map<LocalDate, CommitmentOccurrence> persisted = new HashMap<>();
        for (CommitmentOccurrence occurrence : occurrences
                .findAllByCommitmentIdAndUserIdAndScheduledDateBetween(
                        commitmentId, userId, from, to)) {
            persisted.put(occurrence.getScheduledDate(), occurrence);
        }
        List<OccurrenceResponse> items = new ArrayList<>();
        for (LocalDate date : RecurrenceCalculator.occurrencesBetween(commitment, from, to)) {
            CommitmentOccurrence row = persisted.remove(date);
            items.add(row != null
                    ? OccurrenceResponse.from(row)
                    : OccurrenceResponse.virtualScheduled(commitmentId, date));
        }
        // Rows whose scheduled date the current schedule no longer produces
        // (definition edits, historical entries) remain visible.
        persisted.values().forEach(row -> items.add(OccurrenceResponse.from(row)));
        items.sort(Comparator.comparing(OccurrenceResponse::effectiveDate)
                .thenComparing(OccurrenceResponse::scheduledDate));
        return new OccurrencePreviewResponse(from, to, items);
    }

    @Transactional(readOnly = true)
    public PageResponse<OccurrenceResponse> history(Long commitmentId, int page, int size) {
        Long userId = currentUser.currentUserId();
        find(userId, commitmentId);
        return PageResponse.from(occurrences
                .findAllByCommitmentIdAndUserIdOrderByScheduledDateDesc(
                        commitmentId, userId,
                        PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)))
                .map(OccurrenceResponse::from));
    }

    // ── lifecycle actions (authenticated user) ────────────────────────────────

    /** Manual materialization; also the retry path for FAILED occurrences. */
    public OccurrenceResponse materializeManually(Long commitmentId, LocalDate scheduledDate) {
        Long userId = currentUser.currentUserId();
        return OccurrenceResponse.from(materialize(userId, commitmentId, scheduledDate, false));
    }

    @Transactional
    public OccurrenceResponse skip(Long commitmentId, LocalDate scheduledDate) {
        Long userId = currentUser.currentUserId();
        Commitment commitment = find(userId, commitmentId);
        CommitmentOccurrence occurrence = materializer.claim(userId, commitment, scheduledDate);
        if (occurrence.getStatus() != OccurrenceStatus.SCHEDULED
                && occurrence.getStatus() != OccurrenceStatus.FAILED) {
            throw new BusinessRuleException("OCCURRENCE_NOT_SKIPPABLE",
                    "Apenas ocorrências pendentes podem ser puladas.");
        }
        occurrence.markSkipped();
        return OccurrenceResponse.from(occurrence);
    }

    /** Undoes a skip, returning the occurrence to the schedule. */
    @Transactional
    public OccurrenceResponse unskip(Long commitmentId, LocalDate scheduledDate) {
        Long userId = currentUser.currentUserId();
        Commitment commitment = find(userId, commitmentId);
        CommitmentOccurrence occurrence = materializer.claim(userId, commitment, scheduledDate);
        if (occurrence.getStatus() != OccurrenceStatus.SKIPPED) {
            throw new BusinessRuleException("OCCURRENCE_NOT_SKIPPED",
                    "Esta ocorrência não está pulada.");
        }
        occurrence.markScheduled();
        return OccurrenceResponse.from(occurrence);
    }

    /** Moves the effective date; the identity (scheduled date) never changes. */
    @Transactional
    public OccurrenceResponse reschedule(Long commitmentId, LocalDate scheduledDate,
                                         LocalDate newDate) {
        Long userId = currentUser.currentUserId();
        Commitment commitment = find(userId, commitmentId);
        CommitmentOccurrence occurrence = materializer.claim(userId, commitment, scheduledDate);
        if (occurrence.getStatus() != OccurrenceStatus.SCHEDULED
                && occurrence.getStatus() != OccurrenceStatus.FAILED) {
            throw new BusinessRuleException("OCCURRENCE_NOT_RESCHEDULABLE",
                    "Apenas ocorrências pendentes podem ser reagendadas.");
        }
        occurrence.setEffectiveDate(newDate);
        return OccurrenceResponse.from(occurrence);
    }

    /**
     * Undoes a materialized occurrence exactly once. The generated transaction
     * is deleted (unlinked first); a generated card purchase is cancelled via
     * the card domain's own rules — a settled invoice therefore blocks the
     * reversal with the card domain's error. Terminal: never reprocessed.
     */
    @Transactional
    public OccurrenceResponse reverse(Long commitmentId, LocalDate scheduledDate) {
        Long userId = currentUser.currentUserId();
        Commitment commitment = find(userId, commitmentId);
        CommitmentOccurrence occurrence = occurrences
                .findByIdentityForUpdate(commitmentId, scheduledDate, userId)
                .orElseThrow(() -> new NotFoundException("Ocorrência", commitmentId));
        if (occurrence.getStatus() != OccurrenceStatus.MATERIALIZED) {
            throw new BusinessRuleException("OCCURRENCE_NOT_MATERIALIZED",
                    "Apenas ocorrências executadas podem ser estornadas.");
        }
        if (occurrence.getTransactionId() != null) {
            Transaction transaction = transactions
                    .findByIdAndUserId(occurrence.getTransactionId(), userId)
                    .orElseThrow(() -> new NotFoundException(
                            "Transação", occurrence.getTransactionId()));
            occurrence.clearTransactionLink();
            occurrences.flush();
            transactions.delete(transaction);
        } else if (occurrence.getCardPurchaseId() != null) {
            cardPurchases.cancelGenerated(userId, commitment.getCreditCard().getId(),
                    occurrence.getCardPurchaseId());
        }
        occurrence.markReversed(clock.instant());
        return OccurrenceResponse.from(occurrence);
    }

    // ── materialization funnel ────────────────────────────────────────────────

    /**
     * The single idempotent execution path. Attempts in its own transaction;
     * a business failure is recorded on the occurrence (FAILED, retryable)
     * and rethrown for the caller to surface.
     */
    public CommitmentOccurrence materialize(Long userId, Long commitmentId,
                                            LocalDate scheduledDate, boolean automatic) {
        try {
            return materializer.attempt(userId, commitmentId, scheduledDate, automatic);
        } catch (DataIntegrityViolationException race) {
            // Another processor inserted the identity first; converge on it.
            return materializer.attempt(userId, commitmentId, scheduledDate, automatic);
        } catch (BusinessRuleException failure) {
            if (isMaterializationFailure(failure)) {
                materializer.recordFailure(userId, commitmentId, scheduledDate,
                        failure.getCode(), failure.getMessage());
            }
            throw failure;
        }
    }

    /**
     * Processes everything due for one user through the current business date:
     * the scheduled runs, the after-downtime catch-up and the explicit API
     * trigger all land here. Every occurrence gets its own transaction, so a
     * large backlog neither holds one giant transaction nor stops at the first
     * failure.
     */
    public ProcessDueResponse processDueForUser(Long userId) {
        LocalDate today = LocalDate.now(clock);
        int materialized = 0;
        int failed = 0;
        int alreadyProcessed = 0;
        for (Commitment commitment : commitments.findAllAutomaticForUser(userId)) {
            List<LocalDate> due = RecurrenceCalculator.occurrencesBetween(
                    commitment, commitment.getStartDate(), today);
            Map<LocalDate, CommitmentOccurrence> persisted = new HashMap<>();
            for (CommitmentOccurrence occurrence : occurrences
                    .findAllByCommitmentIdAndUserId(commitment.getId(), userId)) {
                persisted.put(occurrence.getScheduledDate(), occurrence);
            }
            for (LocalDate date : due) {
                CommitmentOccurrence existing = persisted.get(date);
                if (existing != null && existing.getStatus() != OccurrenceStatus.SCHEDULED) {
                    alreadyProcessed++;
                    continue;
                }
                if (existing != null && existing.getEffectiveDate().isAfter(today)) {
                    continue; // rescheduled into the future — not due yet
                }
                try {
                    materialize(userId, commitment.getId(), date, true);
                    materialized++;
                } catch (BusinessRuleException failure) {
                    log.warn("Recurring occurrence {} of commitment {} failed: {}",
                            date, commitment.getId(), failure.getCode());
                    failed++;
                }
            }
            // Rescheduled occurrences pulled before their original date.
            for (CommitmentOccurrence occurrence : persisted.values()) {
                if (occurrence.getStatus() == OccurrenceStatus.SCHEDULED
                        && !occurrence.getEffectiveDate().isAfter(today)
                        && !due.contains(occurrence.getScheduledDate())) {
                    try {
                        materialize(userId, commitment.getId(),
                                occurrence.getScheduledDate(), true);
                        materialized++;
                    } catch (BusinessRuleException failure) {
                        failed++;
                    }
                }
            }
        }
        return new ProcessDueResponse(materialized, failed, alreadyProcessed);
    }

    /** API entry: processes the authenticated user's due occurrences. */
    public ProcessDueResponse processDueForCurrentUser() {
        return processDueForUser(currentUser.currentUserId());
    }

    /**
     * Failures that describe the occurrence's own state (already materialized,
     * skipped, reversed) must not overwrite that state with FAILED.
     */
    private static boolean isMaterializationFailure(BusinessRuleException failure) {
        return switch (failure.getCode()) {
            case "OCCURRENCE_ALREADY_MATERIALIZED", "OCCURRENCE_SKIPPED",
                 "OCCURRENCE_REVERSED", "OCCURRENCE_DATE_INVALID" -> false;
            default -> true;
        };
    }

    private Commitment find(Long userId, Long commitmentId) {
        return commitments.findByIdAndUserId(commitmentId, userId)
                .orElseThrow(() -> new NotFoundException("Compromisso", commitmentId));
    }
}
