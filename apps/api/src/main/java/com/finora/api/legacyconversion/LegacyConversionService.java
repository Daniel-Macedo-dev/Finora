package com.finora.api.legacyconversion;

import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.CreditCardRepository;
import com.finora.api.creditcard.payment.InvoicePaymentRepository;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.legacyconversion.LegacyConversionDtos.ConversionResponse;
import com.finora.api.legacyconversion.LegacyConversionPreviewService.PreviewInput;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Facade over the conversion lifecycle for the API: single conversion,
 * detail and response mapping with the current reversal eligibility.
 */
@Service
public class LegacyConversionService {

    private final LegacyConversionEngine engine;
    private final LegacyConversionRepository conversions;
    private final TransactionRepository transactions;
    private final CreditCardRepository cards;
    private final InvoicePaymentRepository payments;
    private final CurrentUserProvider currentUser;

    public LegacyConversionService(LegacyConversionEngine engine,
                                   LegacyConversionRepository conversions,
                                   TransactionRepository transactions,
                                   CreditCardRepository cards,
                                   InvoicePaymentRepository payments,
                                   CurrentUserProvider currentUser) {
        this.engine = engine;
        this.conversions = conversions;
        this.transactions = transactions;
        this.cards = cards;
        this.payments = payments;
        this.currentUser = currentUser;
    }

    public ConversionResponse convert(PreviewInput input) {
        Long userId = currentUser.currentUserId();
        return toResponse(engine.convert(userId, input));
    }

    @Transactional(readOnly = true)
    public ConversionResponse get(Long id) {
        Long userId = currentUser.currentUserId();
        LegacyCreditConversion conversion = conversions.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Conversão", id));
        return toResponse(conversion);
    }

    @Transactional(readOnly = true)
    public ConversionResponse toResponse(LegacyCreditConversion conversion) {
        Transaction source = transactions
                .findByIdAndUserId(conversion.getSourceTransactionId(), conversion.getUserId())
                .orElse(null);
        String cardName = cards
                .findByIdAndUserId(conversion.getCardId(), conversion.getUserId())
                .map(card -> card.getName())
                .orElse(null);

        boolean reversible = false;
        String blockedCode = null;
        String blockedMessage = null;
        if (conversion.getStatus() == ConversionStatus.ACTIVE) {
            boolean settled = payments
                    .existsCompletedForPurchaseInvoices(conversion.getCardPurchaseId());
            if (settled) {
                blockedCode = "CONVERSION_SETTLED";
                blockedMessage = "Uma fatura da compra gerada já recebeu pagamento; "
                        + "estorne o pagamento antes de estornar a conversão.";
            } else {
                reversible = true;
            }
        } else {
            blockedCode = "CONVERSION_NOT_ACTIVE";
            blockedMessage = "Esta conversão já foi estornada.";
        }

        return new ConversionResponse(
                conversion.getId(),
                conversion.getSourceTransactionId(),
                source != null ? source.getDescription() : null,
                source != null ? MoneyRules.normalize(source.getAmount()) : null,
                conversion.getOriginalTransactionDate(),
                conversion.getCardPurchaseId(),
                conversion.getCardId(),
                cardName,
                conversion.getEffectivePurchaseDate(),
                conversion.getInstallmentCount(),
                conversion.getFirstInvoiceMonth(),
                conversion.getStatus(),
                conversion.getConvertedAt(),
                conversion.getReversedAt(),
                conversion.getReversalReason(),
                reversible,
                blockedCode,
                blockedMessage);
    }
}
