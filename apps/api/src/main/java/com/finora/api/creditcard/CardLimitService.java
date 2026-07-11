package com.finora.api.creditcard;

import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.adjustment.InvoiceAdjustmentRepository;
import com.finora.api.creditcard.installment.CardInstallmentRepository;
import com.finora.api.creditcard.payment.InvoicePaymentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single implementation of the available-limit formula:
 *
 * <pre>
 * usedLimit = active installment obligations (all invoices, past and future)
 *           + net active adjustments (debits − credits)
 *           − completed, non-reversed invoice payments
 *
 * availableLimit = creditLimit − usedLimit
 * </pre>
 *
 * <p>Every unpaid installment consumes limit from the moment the purchase is
 * created; paying an invoice restores it; cancelling a purchase releases it.
 * Business rules elsewhere (payments never exceed outstanding, credits never
 * exceed outstanding) keep {@code usedLimit} non-negative.
 */
@Service
@Transactional(readOnly = true)
public class CardLimitService {

    public record CardLimit(BigDecimal creditLimit,
                            BigDecimal usedLimit,
                            BigDecimal availableLimit,
                            BigDecimal utilizationPercent) {
    }

    private final CardInstallmentRepository installments;
    private final InvoiceAdjustmentRepository adjustments;
    private final InvoicePaymentRepository payments;

    public CardLimitService(CardInstallmentRepository installments,
                            InvoiceAdjustmentRepository adjustments,
                            InvoicePaymentRepository payments) {
        this.installments = installments;
        this.adjustments = adjustments;
        this.payments = payments;
    }

    public CardLimit limitOf(CreditCard card) {
        BigDecimal used = usedLimit(card);
        BigDecimal available = card.getCreditLimit().subtract(used);
        return new CardLimit(
                MoneyRules.normalize(card.getCreditLimit()),
                MoneyRules.normalize(used),
                MoneyRules.normalize(available),
                utilization(used, card.getCreditLimit()));
    }

    public BigDecimal availableLimit(CreditCard card) {
        return card.getCreditLimit().subtract(usedLimit(card));
    }

    private BigDecimal usedLimit(CreditCard card) {
        return installments.sumActiveByCard(card.getId(), card.getUserId())
                .add(adjustments.sumActiveNetByCard(card.getId(), card.getUserId()))
                .subtract(payments.sumCompletedByCard(card.getId(), card.getUserId()));
    }

    /** Percentage of the limit in use (0-100+), 1 decimal place. */
    private static BigDecimal utilization(BigDecimal used, BigDecimal limit) {
        if (limit.signum() <= 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return used.multiply(BigDecimal.valueOf(100)).divide(limit, 1, RoundingMode.HALF_UP);
    }
}
