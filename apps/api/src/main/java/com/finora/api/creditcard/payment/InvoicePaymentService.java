package com.finora.api.creditcard.payment;

import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.adjustment.InvoiceAdjustmentRepository;
import com.finora.api.creditcard.installment.CardInstallmentRepository;
import com.finora.api.creditcard.invoice.CardInvoice;
import com.finora.api.creditcard.invoice.CardInvoiceRepository;
import com.finora.api.creditcard.payment.PaymentDtos.PaymentRequest;
import com.finora.api.creditcard.payment.PaymentDtos.PaymentResponse;
import com.finora.api.identity.CurrentUserProvider;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoice payments: cash settlement of a card statement from one of the
 * user's accounts. A payment reduces the account balance and the invoice's
 * outstanding amount and restores card limit — it is never a new expense,
 * because the installments it settles were already recognized as expenses in
 * their invoice months. History is immutable: wrong payments are reversed,
 * never deleted.
 */
@Service
@Transactional
public class InvoicePaymentService {

    private final InvoicePaymentRepository payments;
    private final CardInvoiceRepository invoices;
    private final CardInstallmentRepository installments;
    private final InvoiceAdjustmentRepository adjustments;
    private final AccountRepository accounts;
    private final CurrentUserProvider currentUser;

    public InvoicePaymentService(InvoicePaymentRepository payments,
                                 CardInvoiceRepository invoices,
                                 CardInstallmentRepository installments,
                                 InvoiceAdjustmentRepository adjustments,
                                 AccountRepository accounts,
                                 CurrentUserProvider currentUser) {
        this.payments = payments;
        this.invoices = invoices;
        this.installments = installments;
        this.adjustments = adjustments;
        this.accounts = accounts;
        this.currentUser = currentUser;
    }

    /**
     * Registers a full or partial payment. The invoice row is locked so two
     * concurrent payments cannot both pass the overpayment check.
     */
    public PaymentResponse pay(Long cardId, Long invoiceId, PaymentRequest request) {
        Long userId = currentUser.currentUserId();
        CardInvoice invoice = invoices.findByIdAndCardIdAndUserIdForUpdate(invoiceId, cardId, userId)
                .orElseThrow(() -> new NotFoundException("Fatura", invoiceId));
        Account account = accounts.findByIdAndUserId(request.accountId(), userId)
                .orElseThrow(() -> new NotFoundException("Conta", request.accountId()));
        if (account.isArchived()) {
            throw new BusinessRuleException("ACCOUNT_ARCHIVED",
                    "Uma conta arquivada não pode pagar faturas.");
        }
        BigDecimal amount = MoneyRules.normalize(request.amount());
        BigDecimal outstanding = outstandingOf(invoice);
        if (amount.compareTo(outstanding) > 0) {
            throw new BusinessRuleException("PAYMENT_EXCEEDS_OUTSTANDING",
                    "O pagamento de %s excede o valor em aberto da fatura (%s)."
                            .formatted(MoneyRules.formatBrl(amount), MoneyRules.formatBrl(outstanding)));
        }
        InvoicePayment payment = new InvoicePayment(userId, invoice, account, amount, request.paidOn());
        payment.setNotes(request.notes() != null && !request.notes().isBlank()
                ? request.notes().trim()
                : null);
        payments.save(payment);
        return toResponse(payment, outstanding.subtract(amount));
    }

    /**
     * Reverses a completed payment: the record is kept, its financial effects
     * are undone (account balance and invoice outstanding are restored, card
     * limit is consumed again). Reversing twice is rejected explicitly.
     */
    public PaymentResponse reverse(Long cardId, Long invoiceId, Long paymentId) {
        Long userId = currentUser.currentUserId();
        // Same lock as pay(): reversal changes the outstanding amount too.
        CardInvoice invoice = invoices.findByIdAndCardIdAndUserIdForUpdate(invoiceId, cardId, userId)
                .orElseThrow(() -> new NotFoundException("Fatura", invoiceId));
        InvoicePayment payment = payments.findByIdAndInvoiceIdAndUserId(paymentId, invoiceId, userId)
                .orElseThrow(() -> new NotFoundException("Pagamento", paymentId));
        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            throw new BusinessRuleException("PAYMENT_ALREADY_REVERSED",
                    "Este pagamento já foi estornado.");
        }
        payment.reverse(Instant.now());
        return toResponse(payment, outstandingOf(invoice));
    }

    private BigDecimal outstandingOf(CardInvoice invoice) {
        Long userId = invoice.getUserId();
        return installments.sumActiveByInvoice(invoice.getId(), userId)
                .add(adjustments.sumActiveNetByInvoice(invoice.getId(), userId))
                .subtract(payments.sumCompletedByInvoice(invoice.getId(), userId));
    }

    private PaymentResponse toResponse(InvoicePayment payment, BigDecimal outstandingAfter) {
        return new PaymentResponse(
                payment.getId(),
                payment.getInvoice().getId(),
                payment.getAccount().getId(),
                payment.getAccount().getName(),
                MoneyRules.normalize(payment.getAmount()),
                payment.getPaidOn(),
                payment.getStatus(),
                payment.getNotes(),
                payment.getReversedAt(),
                MoneyRules.normalize(outstandingAfter));
    }
}
