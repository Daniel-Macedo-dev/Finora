package com.finora.api.commitment.occurrence;

import com.finora.api.account.Account;
import com.finora.api.category.CategoryType;
import com.finora.api.commitment.Commitment;
import com.finora.api.commitment.CommitmentRepository;
import com.finora.api.commitment.RecurrenceCalculator;
import com.finora.api.commitment.RecurrenceTarget;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.purchase.CardPurchase;
import com.finora.api.creditcard.purchase.CardPurchaseService;
import com.finora.api.creditcard.purchase.PurchaseDtos.PurchaseRequest;
import com.finora.api.transaction.PaymentMethod;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional core of occurrence materialization. Each attempt runs in
 * its own transaction (REQUIRES_NEW): the occurrence row is claimed under a
 * pessimistic lock, the artifact is created through the owning domain's rules
 * and the occurrence is marked — atomically. A failed attempt leaves no
 * partial artifact; the failure is then recorded in a separate transaction so
 * the rollback cannot erase it.
 *
 * <p>Duplicate protection is layered: the identity lock serializes racing
 * processors, the unique (commitment, scheduled date) constraint stops
 * concurrent inserts, and the partial unique indexes on the artifact links
 * are the final database backstop.
 */
@Service
public class OccurrenceMaterializer {

    private final CommitmentOccurrenceRepository occurrences;
    private final CommitmentRepository commitments;
    private final TransactionRepository transactions;
    private final CardPurchaseService cardPurchases;
    private final Clock clock;

    public OccurrenceMaterializer(CommitmentOccurrenceRepository occurrences,
                                  CommitmentRepository commitments,
                                  TransactionRepository transactions,
                                  CardPurchaseService cardPurchases,
                                  Clock clock) {
        this.occurrences = occurrences;
        this.commitments = commitments;
        this.transactions = transactions;
        this.cardPurchases = cardPurchases;
        this.clock = clock;
    }

    /**
     * Claims and materializes one occurrence. Throws a business error when the
     * occurrence is not in a materializable state or the target rejects the
     * artifact (e.g. insufficient card limit) — in that case the transaction
     * rolls back completely.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommitmentOccurrence attempt(Long userId, Long commitmentId, LocalDate scheduledDate,
                                        boolean automatic) {
        Commitment commitment = commitments.findByIdAndUserId(commitmentId, userId)
                .orElseThrow(() -> new NotFoundException("Compromisso", commitmentId));
        CommitmentOccurrence occurrence = claim(userId, commitment, scheduledDate);
        switch (occurrence.getStatus()) {
            case MATERIALIZED -> throw new BusinessRuleException("OCCURRENCE_ALREADY_MATERIALIZED",
                    "Esta ocorrência já foi executada.");
            case SKIPPED -> throw new BusinessRuleException("OCCURRENCE_SKIPPED",
                    "Esta ocorrência foi pulada. Reative-a antes de executar.");
            case REVERSED -> throw new BusinessRuleException("OCCURRENCE_REVERSED",
                    "Esta ocorrência foi estornada e não pode ser executada novamente.");
            case SCHEDULED, FAILED -> { /* materializable */ }
        }

        Instant now = clock.instant();
        switch (commitment.getTargetKind()) {
            case ACCOUNT_TRANSACTION -> {
                Transaction transaction = createTransaction(userId, commitment, occurrence);
                occurrence.markMaterialized(transaction.getId(), null, automatic, now);
            }
            case CREDIT_CARD_PURCHASE -> {
                CardPurchase purchase = createCardPurchase(userId, commitment, occurrence);
                occurrence.markMaterialized(null, purchase.getId(), automatic, now);
            }
            case PROJECTION_ONLY -> throw new BusinessRuleException("COMMITMENT_NO_TARGET",
                    "Este recorrente é apenas de planejamento. "
                            + "Defina uma conta ou cartão de destino para executá-lo.");
        }
        return occurrence;
    }

    /**
     * Records a failed attempt in its own transaction, after the attempt's
     * rollback. Only a SCHEDULED or already FAILED occurrence is updated —
     * a concurrent success is never overwritten.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long userId, Long commitmentId, LocalDate scheduledDate,
                              String code, String message) {
        Commitment commitment = commitments.findByIdAndUserId(commitmentId, userId).orElse(null);
        if (commitment == null) {
            return;
        }
        CommitmentOccurrence occurrence = claim(userId, commitment, scheduledDate);
        if (occurrence.getStatus() == OccurrenceStatus.SCHEDULED
                || occurrence.getStatus() == OccurrenceStatus.FAILED) {
            occurrence.markFailed(code, truncate(message, 300));
        }
    }

    /**
     * Loads (locked) or lazily persists the occurrence identity. A new row may
     * only be created for a date the recurrence actually produces — persisted
     * rows are trusted as-is (they may predate a definition edit).
     */
    CommitmentOccurrence claim(Long userId, Commitment commitment, LocalDate scheduledDate) {
        return occurrences.findByIdentityForUpdate(commitment.getId(), scheduledDate, userId)
                .orElseGet(() -> {
                    if (RecurrenceCalculator.occurrencesBetween(
                            commitment, scheduledDate, scheduledDate).isEmpty()) {
                        throw new BusinessRuleException("OCCURRENCE_DATE_INVALID",
                                "Esta data não pertence à recorrência configurada.");
                    }
                    CommitmentOccurrence created =
                            new CommitmentOccurrence(userId, commitment, scheduledDate);
                    // The unique identity constraint is the backstop for two
                    // processors creating the same row at once.
                    return occurrences.saveAndFlush(created);
                });
    }

    private Transaction createTransaction(Long userId, Commitment commitment,
                                          CommitmentOccurrence occurrence) {
        Account account = commitment.getAccount();
        if (account == null) {
            throw new BusinessRuleException("COMMITMENT_ACCOUNT_REQUIRED",
                    "Este recorrente não tem conta de destino configurada.");
        }
        if (account.isArchived()) {
            throw new BusinessRuleException("ACCOUNT_ARCHIVED",
                    "A conta de destino deste recorrente está arquivada.");
        }
        if (commitment.getPaymentMethod() == PaymentMethod.CREDIT) {
            throw new BusinessRuleException("USE_CREDIT_CARD_PURCHASE",
                    "Recorrentes no crédito exigem um cartão de destino.");
        }
        TransactionType type = commitment.getCategory().getType() == CategoryType.INCOME
                ? TransactionType.INCOME
                : TransactionType.EXPENSE;
        Transaction transaction = new Transaction(
                userId,
                type,
                MoneyRules.normalize(commitment.getAmount()),
                commitment.getDescription(),
                occurrence.getEffectiveDate(),
                commitment.getCategory());
        transaction.setAccount(account);
        transaction.setPaymentMethod(commitment.getPaymentMethod());
        transaction.setCommitmentId(commitment.getId());
        transaction.setNotes("Gerado pelo recorrente \"%s\"."
                .formatted(commitment.getDescription()));
        return transactions.saveAndFlush(transaction);
    }

    private CardPurchase createCardPurchase(Long userId, Commitment commitment,
                                            CommitmentOccurrence occurrence) {
        if (commitment.getCreditCard() == null) {
            throw new BusinessRuleException("COMMITMENT_CARD_REQUIRED",
                    "Este recorrente não tem cartão de destino configurado.");
        }
        return cardPurchases.createForCommitment(
                userId,
                commitment.getCreditCard().getId(),
                new PurchaseRequest(
                        commitment.getDescription(),
                        null,
                        commitment.getCategory().getId(),
                        occurrence.getEffectiveDate(),
                        MoneyRules.normalize(commitment.getAmount()),
                        commitment.getInstallmentCount(),
                        "Gerado pelo recorrente \"%s\".".formatted(commitment.getDescription())),
                commitment.getId());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
