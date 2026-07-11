package com.finora.api.creditcard.invoice;

import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.CreditCard;
import com.finora.api.creditcard.CreditCardRepository;
import com.finora.api.creditcard.InvoiceCycleCalculator;
import com.finora.api.creditcard.InvoiceCycleCalculator.InvoiceCycle;
import com.finora.api.creditcard.adjustment.InvoiceAdjustmentRepository;
import com.finora.api.creditcard.installment.CardInstallmentRepository;
import com.finora.api.creditcard.installment.InstallmentStatus;
import com.finora.api.creditcard.invoice.InvoiceDtos.InvoiceAdjustmentLine;
import com.finora.api.creditcard.invoice.InvoiceDtos.InvoiceDetailResponse;
import com.finora.api.creditcard.invoice.InvoiceDtos.InvoiceInstallmentLine;
import com.finora.api.creditcard.invoice.InvoiceDtos.InvoicePaymentLine;
import com.finora.api.creditcard.invoice.InvoiceDtos.InvoiceSummaryResponse;
import com.finora.api.creditcard.payment.InvoicePaymentRepository;
import com.finora.api.identity.CurrentUserProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoice queries and lifecycle. Invoice rows are created lazily, only when a
 * charge (installment or adjustment) needs one; their closing/due dates are
 * snapshotted at creation from the card's configuration at that moment.
 * Status and totals are always derived — never stored.
 */
@Service
@Transactional
public class InvoiceService {

    private final CardInvoiceRepository invoices;
    private final CreditCardRepository cards;
    private final CardInstallmentRepository installments;
    private final InvoiceAdjustmentRepository adjustments;
    private final InvoicePaymentRepository payments;
    private final CurrentUserProvider currentUser;

    public InvoiceService(CardInvoiceRepository invoices,
                          CreditCardRepository cards,
                          CardInstallmentRepository installments,
                          InvoiceAdjustmentRepository adjustments,
                          InvoicePaymentRepository payments,
                          CurrentUserProvider currentUser) {
        this.invoices = invoices;
        this.cards = cards;
        this.installments = installments;
        this.adjustments = adjustments;
        this.payments = payments;
        this.currentUser = currentUser;
    }

    /**
     * Deterministic status from the current date, snapshot dates and derived
     * totals. Precedence: PAID → OVERDUE → PARTIALLY_PAID → CLOSED, falling
     * back to OPEN/UPCOMING for cycles still accumulating charges. An invoice
     * without any charge or payment is never PAID before it closes — it is
     * simply the (empty) open or upcoming cycle.
     */
    static InvoiceStatus deriveStatus(LocalDate today, LocalDate closingDate, LocalDate dueDate,
                                      BigDecimal invoiceTotal, BigDecimal amountPaid) {
        boolean settled = invoiceTotal.subtract(amountPaid).signum() <= 0;
        if (settled && (amountPaid.signum() > 0 || today.isAfter(closingDate))) {
            return InvoiceStatus.PAID;
        }
        if (!settled) {
            if (today.isAfter(dueDate)) {
                return InvoiceStatus.OVERDUE;
            }
            if (amountPaid.signum() > 0) {
                return InvoiceStatus.PARTIALLY_PAID;
            }
            if (today.isAfter(closingDate)) {
                return InvoiceStatus.CLOSED;
            }
        }
        // Billing cycles are monthly: the previous statement closed roughly one
        // month before this one, so anything past that point is the open cycle.
        return today.isAfter(closingDate.minusMonths(1)) ? InvoiceStatus.OPEN : InvoiceStatus.UPCOMING;
    }

    /**
     * Finds or creates the invoice row of {@code cycle} for the card. Existing
     * rows keep their original snapshot dates — a card reconfiguration never
     * rewrites history.
     */
    public CardInvoice ensureInvoice(CreditCard card, InvoiceCycle cycle) {
        return invoices.findByUserIdAndCardIdAndReferenceMonth(
                        card.getUserId(), card.getId(), cycle.referenceMonth().atDay(1))
                .orElseGet(() -> invoices.save(new CardInvoice(
                        card.getUserId(), card, cycle.referenceMonth(),
                        cycle.closingDate(), cycle.dueDate())));
    }

    @Transactional(readOnly = true)
    public List<InvoiceSummaryResponse> listForCard(Long cardId, LocalDate today) {
        Long userId = currentUser.currentUserId();
        CreditCard card = cards.findByIdAndUserId(cardId, userId)
                .orElseThrow(() -> new NotFoundException("Cartão", cardId));

        // Three grouped queries assemble every invoice's totals without N+1.
        Map<Long, BigDecimal> purchaseTotals = new HashMap<>();
        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : installments.sumActiveGroupedByInvoice(card.getId(), userId)) {
            purchaseTotals.put((Long) row[0], (BigDecimal) row[1]);
            counts.put((Long) row[0], ((Long) row[2]).intValue());
        }
        Map<Long, BigDecimal> adjustmentTotals = new HashMap<>();
        for (Object[] row : adjustments.sumActiveNetGroupedByInvoice(card.getId(), userId)) {
            adjustmentTotals.put((Long) row[0], (BigDecimal) row[1]);
        }
        Map<Long, BigDecimal> paymentTotals = new HashMap<>();
        for (Object[] row : payments.sumCompletedGroupedByInvoice(card.getId(), userId)) {
            paymentTotals.put((Long) row[0], (BigDecimal) row[1]);
        }

        return invoices.findAllByCardIdAndUserIdOrderByReferenceMonthAsc(card.getId(), userId).stream()
                .map(invoice -> summarize(
                        invoice,
                        purchaseTotals.getOrDefault(invoice.getId(), BigDecimal.ZERO),
                        adjustmentTotals.getOrDefault(invoice.getId(), BigDecimal.ZERO),
                        paymentTotals.getOrDefault(invoice.getId(), BigDecimal.ZERO),
                        counts.getOrDefault(invoice.getId(), 0),
                        today))
                .toList();
    }

    @Transactional(readOnly = true)
    public InvoiceDetailResponse detail(Long cardId, Long invoiceId, LocalDate today) {
        Long userId = currentUser.currentUserId();
        CardInvoice invoice = invoices.findByIdAndCardIdAndUserId(invoiceId, cardId, userId)
                .orElseThrow(() -> new NotFoundException("Fatura", invoiceId));

        List<InvoiceInstallmentLine> lines = installments
                .findAllByInvoiceIdAndUserIdOrderByPurchasePurchaseDateAscIdAsc(invoice.getId(), userId)
                .stream()
                .map(i -> new InvoiceInstallmentLine(
                        i.getId(),
                        i.getPurchase().getId(),
                        i.getPurchase().getDescription(),
                        i.getPurchase().getMerchant(),
                        i.getPurchase().getCategory().getName(),
                        i.getPurchase().getPurchaseDate(),
                        i.getSequenceNumber(),
                        i.getTotalInstallments(),
                        MoneyRules.normalize(i.getAmount()),
                        i.getStatus()))
                .toList();

        List<InvoiceAdjustmentLine> adjustmentLines = adjustments
                .findAllByInvoiceIdAndUserIdOrderByIdAsc(invoice.getId(), userId)
                .stream()
                .map(a -> new InvoiceAdjustmentLine(
                        a.getId(),
                        a.getKind(),
                        a.getDescription(),
                        a.getCategory() != null ? a.getCategory().getName() : null,
                        MoneyRules.normalize(a.getAmount()),
                        a.getStatus(),
                        a.getReversedAt()))
                .toList();

        List<InvoicePaymentLine> paymentLines = payments
                .findAllByInvoiceIdAndUserIdOrderByPaidOnAscIdAsc(invoice.getId(), userId)
                .stream()
                .map(p -> new InvoicePaymentLine(
                        p.getId(),
                        p.getAccount().getId(),
                        p.getAccount().getName(),
                        MoneyRules.normalize(p.getAmount()),
                        p.getPaidOn(),
                        p.getStatus(),
                        p.getNotes(),
                        p.getReversedAt()))
                .toList();

        return new InvoiceDetailResponse(summarize(invoice, today), lines, adjustmentLines, paymentLines);
    }

    /** Summary of one invoice, deriving its totals with individual sums. */
    @Transactional(readOnly = true)
    public InvoiceSummaryResponse summarize(CardInvoice invoice, LocalDate today) {
        Long userId = invoice.getUserId();
        BigDecimal purchaseTotal = installments.sumActiveByInvoice(invoice.getId(), userId);
        int count = installments.countByInvoiceIdAndUserIdAndStatus(
                invoice.getId(), userId, InstallmentStatus.ACTIVE);
        BigDecimal adjustmentsNet = adjustments.sumActiveNetByInvoice(invoice.getId(), userId);
        BigDecimal paid = payments.sumCompletedByInvoice(invoice.getId(), userId);
        return summarize(invoice, purchaseTotal, adjustmentsNet, paid, count, today);
    }

    /** The invoice cycle a purchase made today would enter (may not exist as a row yet). */
    public static InvoiceCycle currentCycle(CreditCard card, LocalDate today) {
        return InvoiceCycleCalculator.cycleForPurchase(card.getClosingDay(), card.getDueDay(), today);
    }

    private InvoiceSummaryResponse summarize(CardInvoice invoice,
                                             BigDecimal purchaseTotal,
                                             BigDecimal adjustmentsNet,
                                             BigDecimal paid,
                                             int installmentCount,
                                             LocalDate today) {
        BigDecimal total = purchaseTotal.add(adjustmentsNet);
        return new InvoiceSummaryResponse(
                invoice.getId(),
                invoice.getCard().getId(),
                YearMonth.from(invoice.getReferenceMonth()),
                invoice.getClosingDate(),
                invoice.getDueDate(),
                deriveStatus(today, invoice.getClosingDate(), invoice.getDueDate(), total, paid),
                MoneyRules.normalize(purchaseTotal),
                MoneyRules.normalize(adjustmentsNet),
                MoneyRules.normalize(total),
                MoneyRules.normalize(paid),
                MoneyRules.normalize(total.subtract(paid)),
                installmentCount);
    }
}
