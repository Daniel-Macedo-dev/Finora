package com.finora.api.commitment.occurrence;

import com.finora.api.commitment.CommitmentRepository;
import com.finora.api.commitment.occurrence.OccurrenceDtos.ProcessDueResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background processing of due automatic occurrences. Because occurrence
 * identities derive from the calendar (not from scheduler ticks), the first
 * run after any downtime naturally catches up everything that became due
 * while the application was offline — the same idempotent engine that manual
 * execution uses guarantees nothing is created twice.
 *
 * <p>Disabled in integration tests via
 * {@code finora.recurring.auto-processing.enabled=false} so deterministic
 * tests drive processing explicitly through the same service.
 */
@Component
@ConditionalOnProperty(name = "finora.recurring.auto-processing.enabled",
        havingValue = "true", matchIfMissing = true)
public class DueOccurrenceScheduler {

    private static final Logger log = LoggerFactory.getLogger(DueOccurrenceScheduler.class);

    private final CommitmentRepository commitments;
    private final OccurrenceService occurrences;

    public DueOccurrenceScheduler(CommitmentRepository commitments,
                                  OccurrenceService occurrences) {
        this.commitments = commitments;
        this.occurrences = occurrences;
    }

    /**
     * First run one minute after startup (downtime catch-up), then on the
     * configured interval. Users are processed independently: one user's
     * failure never blocks another's work.
     */
    @Scheduled(initialDelayString = "${finora.recurring.initial-delay:PT1M}",
            fixedDelayString = "${finora.recurring.processing-interval:PT6H}")
    public void processAllDue() {
        for (Long userId : commitments.findUserIdsWithAutomaticDefinitions()) {
            try {
                ProcessDueResponse result = occurrences.processDueForUser(userId);
                if (result.materialized() > 0 || result.failed() > 0) {
                    log.info("Recurring processing for user {}: {} materialized, {} failed",
                            userId, result.materialized(), result.failed());
                }
            } catch (RuntimeException ex) {
                log.error("Recurring processing failed for user {}", userId, ex);
            }
        }
    }
}
