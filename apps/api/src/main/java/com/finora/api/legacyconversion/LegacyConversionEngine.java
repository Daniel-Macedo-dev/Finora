package com.finora.api.legacyconversion;

import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.purchase.CardPurchase;
import com.finora.api.creditcard.purchase.CardPurchaseService;
import com.finora.api.creditcard.purchase.PurchaseDtos.PurchaseRequest;
import com.finora.api.legacyconversion.ConversionPreviewDtos.ConversionPreviewResponse;
import com.finora.api.legacyconversion.ConversionPreviewDtos.PreviewMessage;
import com.finora.api.legacyconversion.LegacyConversionPreviewService.PreviewInput;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional core of a single conversion. Everything happens in one
 * database transaction (REQUIRES_NEW so batch items stay independent):
 *
 * <ol>
 *   <li>the source transaction row is claimed under a pessimistic lock —
 *       concurrent conversions and reversals of the same source serialize;</li>
 *   <li>eligibility and the full allocation validation re-run inside the
 *       transaction through the preview service;</li>
 *   <li>the real purchase is created through the card-purchase domain (which
 *       locks the card and re-checks the limit);</li>
 *   <li>the conversion record is persisted and the source financially
 *       deactivated — atomically.</li>
 * </ol>
 *
 * <p>A failure rolls everything back: no conversion row, no orphan purchase,
 * no orphan installment, and the source stays financially active. Lock order
 * is always source transaction → card, matching the reversal flow, so the
 * two lifecycles cannot deadlock. The partial unique indexes
 * ({@code uq_legacy_conversions_active_source},
 * {@code uq_credit_card_purchases_legacy_tx}) are the database backstop.
 */
@Service
public class LegacyConversionEngine {

    private final LegacyConversionRepository conversions;
    private final TransactionRepository transactions;
    private final LegacyConversionPreviewService previews;
    private final CardPurchaseService cardPurchases;
    private final Clock clock;

    public LegacyConversionEngine(LegacyConversionRepository conversions,
                                  TransactionRepository transactions,
                                  LegacyConversionPreviewService previews,
                                  CardPurchaseService cardPurchases,
                                  Clock clock) {
        this.conversions = conversions;
        this.transactions = transactions;
        this.previews = previews;
        this.cardPurchases = cardPurchases;
        this.clock = clock;
    }

    /**
     * Converts one source transaction. Idempotent: when an ACTIVE conversion
     * already exists for the source, it is returned unchanged — a retried
     * request can never create a second purchase.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LegacyCreditConversion convert(Long userId, PreviewInput input) {
        // Claim the source first: every lifecycle operation on a conversion
        // starts by locking this row.
        Transaction source = transactions.findByIdAndUserIdForUpdate(input.transactionId(), userId)
                .orElseThrow(() -> new NotFoundException("Transação", input.transactionId()));

        var existing = conversions.findActiveBySourceForUpdate(source.getId(), userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Full re-validation inside the transaction; the preview also computes
        // the confirmed first invoice month used in the persisted record.
        ConversionPreviewResponse preview = previews.previewForUser(userId, input);
        if (!preview.convertible()) {
            PreviewMessage blocker = preview.blockers().getFirst();
            throw new BusinessRuleException(blocker.code(), blocker.message());
        }

        // The purchase domain locks the card and re-checks the limit under it.
        CardPurchase purchase = cardPurchases.createForLegacyConversion(
                userId,
                input.cardId(),
                new PurchaseRequest(
                        source.getDescription(),
                        null,
                        source.getCategory().getId(),
                        input.effectivePurchaseDate(),
                        MoneyRules.normalize(source.getAmount()),
                        input.installmentCount(),
                        "Convertida do crédito legado \"%s\" de %s."
                                .formatted(source.getDescription(), source.getOccurredOn())),
                source.getId());

        LegacyCreditConversion conversion = conversions.save(new LegacyCreditConversion(
                userId,
                source.getId(),
                purchase.getId(),
                input.cardId(),
                source.getOccurredOn(),
                input.effectivePurchaseDate(),
                input.installmentCount(),
                preview.firstInvoiceMonth(),
                clock.instant()));

        // The original stays visible as an audit record but stops counting:
        // from here on the generated installments are the expense source.
        source.setFinanciallyActive(false);
        return conversion;
    }
}
