package com.finora.api.legacyconversion;

import com.finora.api.common.error.NotFoundException;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single authority on whether a transaction can be converted into a real
 * card purchase. Every entry point — inventory, preview, single conversion,
 * batch conversion — evaluates through this service, and the conversion
 * engine re-evaluates inside its transaction before writing anything.
 */
@Service
@Transactional(readOnly = true)
public class LegacyConversionEligibilityService {

    /** Eligibility of one source with a stable machine code and a safe message. */
    public record Eligibility(EligibilityStatus status, String reasonCode, String message) {

        public boolean convertible() {
            return status.convertible();
        }

        static Eligibility eligible() {
            return new Eligibility(EligibilityStatus.ELIGIBLE, "ELIGIBLE",
                    "Elegível para conversão.");
        }
    }

    private final TransactionRepository transactions;
    private final LegacyConversionRepository conversions;

    public LegacyConversionEligibilityService(TransactionRepository transactions,
                                              LegacyConversionRepository conversions) {
        this.transactions = transactions;
        this.conversions = conversions;
    }

    /**
     * Owner-scoped evaluation: another user's transaction id behaves as
     * absent, exactly like every other owner-scoped lookup in the API.
     */
    public Eligibility evaluate(Long userId, Long transactionId) {
        Transaction source = transactions.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new NotFoundException("Transação", transactionId));
        return evaluate(source);
    }

    /**
     * Evaluates an already-loaded, owner-verified source. Used by bulk paths
     * (inventory pages, batch conversion) to avoid one lookup per row; the
     * conversion history is still queried per call — callers that already hold
     * it should use {@link #evaluate(Transaction, boolean, boolean)}.
     */
    public Eligibility evaluate(Transaction source) {
        boolean activeConversion = conversions.existsBySourceTransactionIdAndStatus(
                source.getId(), ConversionStatus.ACTIVE);
        boolean reversedConversion = !activeConversion
                && conversions.existsBySourceTransactionIdAndStatus(
                        source.getId(), ConversionStatus.REVERSED);
        return evaluate(source, activeConversion, reversedConversion);
    }

    /** Pure rule evaluation over pre-fetched state — no queries, safe for bulk use. */
    public Eligibility evaluate(Transaction source, boolean hasActiveConversion,
                                boolean hasReversedConversion) {
        if (hasActiveConversion) {
            return new Eligibility(EligibilityStatus.ALREADY_CONVERTED, "ALREADY_CONVERTED",
                    "Esta transação já foi convertida em compra de cartão.");
        }
        if (!source.isLegacyCredit()) {
            return new Eligibility(EligibilityStatus.INCOMPATIBLE_SOURCE, "NOT_LEGACY_CREDIT",
                    "Apenas transações de crédito legado podem ser convertidas.");
        }
        if (source.getType() != TransactionType.EXPENSE) {
            return new Eligibility(EligibilityStatus.INCOMPATIBLE_SOURCE, "NOT_EXPENSE",
                    "Apenas despesas podem ser convertidas.");
        }
        if (source.getAmount() == null || source.getAmount().signum() <= 0) {
            return new Eligibility(EligibilityStatus.INCOMPATIBLE_SOURCE, "AMOUNT_NOT_POSITIVE",
                    "O valor da transação precisa ser maior que zero.");
        }
        if (source.getCommitmentId() != null) {
            return new Eligibility(EligibilityStatus.BLOCKED, "SOURCE_FROM_RECURRING",
                    "Transações geradas por recorrentes são gerenciadas pela ocorrência "
                            + "e não podem ser convertidas.");
        }
        if (source.getWishlistItemId() != null) {
            return new Eligibility(EligibilityStatus.BLOCKED, "SOURCE_FROM_WISHLIST",
                    "Transações geradas pela execução da lista de desejos não podem "
                            + "ser convertidas.");
        }
        if (!source.isFinanciallyActive()) {
            // Deactivated without an active conversion should be impossible
            // (the check constraint and the engine keep them in sync); treat
            // defensively as blocked rather than eligible.
            return new Eligibility(EligibilityStatus.BLOCKED, "SOURCE_INACTIVE",
                    "Esta transação está inativa e não pode ser convertida.");
        }
        if (hasReversedConversion) {
            return new Eligibility(EligibilityStatus.REVERSED_CONVERSION, "REVERSED_CONVERSION",
                    "A conversão anterior foi estornada; a transação pode ser convertida novamente.");
        }
        return Eligibility.eligible();
    }

    /** The latest conversion of a source, if any (for detail/inventory metadata). */
    public Optional<LegacyCreditConversion> latestConversion(Long userId, Long transactionId) {
        return conversions
                .findAllByUserIdAndSourceTransactionIdIn(userId, java.util.List.of(transactionId))
                .stream()
                .reduce((first, second) -> second);
    }
}
