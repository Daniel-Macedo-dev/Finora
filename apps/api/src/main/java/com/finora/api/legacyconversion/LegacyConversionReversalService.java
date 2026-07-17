package com.finora.api.legacyconversion;

import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.creditcard.payment.InvoicePaymentRepository;
import com.finora.api.creditcard.purchase.CardPurchaseService;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auditable conversion reversal. Atomically: the generated purchase is
 * cancelled through the card domain, the source transaction becomes the
 * historical expense source again — exactly once — and the conversion record
 * is marked REVERSED, never deleted.
 *
 * <p>Lock order matches the conversion engine (source transaction first, then
 * conversion row, then the card inside the purchase domain), so a conversion
 * racing a reversal serializes on the source row and ends in one consistent
 * state. Reversal is blocked while any invoice holding an active installment
 * of the generated purchase has a completed payment: money already moved, and
 * rewriting settled history is never allowed.
 */
@Service
@Transactional
public class LegacyConversionReversalService {

    private final LegacyConversionRepository conversions;
    private final TransactionRepository transactions;
    private final InvoicePaymentRepository payments;
    private final CardPurchaseService cardPurchases;
    private final CurrentUserProvider currentUser;
    private final Clock clock;

    public LegacyConversionReversalService(LegacyConversionRepository conversions,
                                           TransactionRepository transactions,
                                           InvoicePaymentRepository payments,
                                           CardPurchaseService cardPurchases,
                                           CurrentUserProvider currentUser,
                                           Clock clock) {
        this.conversions = conversions;
        this.transactions = transactions;
        this.payments = payments;
        this.cardPurchases = cardPurchases;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public LegacyCreditConversion reverse(Long conversionId, String reason) {
        return reverseForUser(currentUser.currentUserId(), conversionId, reason);
    }

    /** Owner-explicit reversal core. */
    public LegacyCreditConversion reverseForUser(Long userId, Long conversionId, String reason) {
        // Unlocked read to discover the source, then the same lock order as
        // the conversion engine: source row first.
        LegacyCreditConversion unlocked = conversions.findByIdAndUserId(conversionId, userId)
                .orElseThrow(() -> new NotFoundException("Conversão", conversionId));
        Long sourceId = unlocked.getSourceTransactionId();
        Transaction source = transactions.findByIdAndUserIdForUpdate(sourceId, userId)
                .orElseThrow(() -> new NotFoundException("Transação", sourceId));

        // Re-read under lock: a concurrent reversal has already flipped it.
        LegacyCreditConversion conversion = conversions
                .findByIdAndUserIdForUpdate(conversionId, userId)
                .orElseThrow(() -> new NotFoundException("Conversão", conversionId));
        if (conversion.getStatus() != ConversionStatus.ACTIVE) {
            throw new BusinessRuleException("CONVERSION_NOT_ACTIVE",
                    "Esta conversão já foi estornada.");
        }

        // Settlement guard with a stable, actionable reason; the purchase
        // domain's own guard remains the backstop inside cancelGenerated.
        if (payments.existsCompletedForPurchaseInvoices(conversion.getCardPurchaseId())) {
            throw new BusinessRuleException("CONVERSION_SETTLED",
                    "Uma fatura da compra gerada já recebeu pagamento; o estorno rescreveria "
                            + "história liquidada. Estorne o pagamento da fatura primeiro, "
                            + "ou use um ajuste de crédito.");
        }

        cardPurchases.cancelGenerated(userId, conversion.getCardId(),
                conversion.getCardPurchaseId());

        // The original transaction is the historical expense source again.
        source.setFinanciallyActive(true);
        conversion.markReversed(clock.instant(), normalizeReason(reason));
        return conversion;
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.trim();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300);
    }
}
