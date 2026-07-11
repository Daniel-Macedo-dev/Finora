package com.finora.api.creditcard.adjustment;

import com.finora.api.category.Category;
import com.finora.api.category.CategoryRepository;
import com.finora.api.category.CategoryType;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.adjustment.AdjustmentDtos.AdjustmentRequest;
import com.finora.api.creditcard.adjustment.AdjustmentDtos.AdjustmentResponse;
import com.finora.api.creditcard.installment.CardInstallmentRepository;
import com.finora.api.creditcard.invoice.CardInvoice;
import com.finora.api.creditcard.invoice.CardInvoiceRepository;
import com.finora.api.creditcard.payment.InvoicePaymentRepository;
import com.finora.api.identity.CurrentUserProvider;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoice adjustments: fees, interest and other debits increase the invoice;
 * credits and refunds reduce it. Debit kinds are expenses — they require an
 * expense category and count toward budgets in the invoice month. A credit
 * never exceeds the invoice's outstanding amount (it cannot fabricate cash),
 * and reversing a debit is rejected if payments already cover the remainder.
 */
@Service
@Transactional
public class InvoiceAdjustmentService {

    private final InvoiceAdjustmentRepository adjustments;
    private final CardInvoiceRepository invoices;
    private final CardInstallmentRepository installments;
    private final InvoicePaymentRepository payments;
    private final CategoryRepository categories;
    private final CurrentUserProvider currentUser;

    public InvoiceAdjustmentService(InvoiceAdjustmentRepository adjustments,
                                    CardInvoiceRepository invoices,
                                    CardInstallmentRepository installments,
                                    InvoicePaymentRepository payments,
                                    CategoryRepository categories,
                                    CurrentUserProvider currentUser) {
        this.adjustments = adjustments;
        this.invoices = invoices;
        this.installments = installments;
        this.payments = payments;
        this.categories = categories;
        this.currentUser = currentUser;
    }

    public AdjustmentResponse create(Long cardId, Long invoiceId, AdjustmentRequest request) {
        Long userId = currentUser.currentUserId();
        // Locked: adjustments shift the outstanding amount that payments check.
        CardInvoice invoice = invoices.findByIdAndCardIdAndUserIdForUpdate(invoiceId, cardId, userId)
                .orElseThrow(() -> new NotFoundException("Fatura", invoiceId));
        BigDecimal amount = MoneyRules.normalize(request.amount());

        Category category = null;
        if (request.kind().isDebit()) {
            if (request.categoryId() == null) {
                throw new BusinessRuleException("ADJUSTMENT_CATEGORY_REQUIRED",
                        "Tarifas, juros e outros débitos exigem uma categoria de despesa.");
            }
            category = categories.findByIdAndUserId(request.categoryId(), userId)
                    .orElseThrow(() -> new NotFoundException("Categoria", request.categoryId()));
            if (category.getType() != CategoryType.EXPENSE) {
                throw new BusinessRuleException("CATEGORY_NOT_EXPENSE",
                        "Ajustes de débito exigem uma categoria de despesa.");
            }
        } else if (request.categoryId() != null) {
            category = categories.findByIdAndUserId(request.categoryId(), userId)
                    .orElseThrow(() -> new NotFoundException("Categoria", request.categoryId()));
        }

        if (!request.kind().isDebit()) {
            BigDecimal outstanding = outstandingOf(invoice);
            if (amount.compareTo(outstanding) > 0) {
                throw new BusinessRuleException("CREDIT_EXCEEDS_OUTSTANDING",
                        "O crédito de %s excede o valor em aberto da fatura (%s)."
                                .formatted(MoneyRules.formatBrl(amount),
                                        MoneyRules.formatBrl(outstanding)));
            }
        }

        InvoiceAdjustment adjustment = new InvoiceAdjustment(
                userId, invoice, category, request.kind(), request.description().trim(), amount);
        return toResponse(adjustments.save(adjustment));
    }

    public AdjustmentResponse reverse(Long cardId, Long invoiceId, Long adjustmentId) {
        Long userId = currentUser.currentUserId();
        CardInvoice invoice = invoices.findByIdAndCardIdAndUserIdForUpdate(invoiceId, cardId, userId)
                .orElseThrow(() -> new NotFoundException("Fatura", invoiceId));
        InvoiceAdjustment adjustment = adjustments
                .findByIdAndInvoiceIdAndUserId(adjustmentId, invoiceId, userId)
                .orElseThrow(() -> new NotFoundException("Ajuste", adjustmentId));
        if (adjustment.getStatus() != AdjustmentStatus.ACTIVE) {
            throw new BusinessRuleException("ADJUSTMENT_ALREADY_REVERSED",
                    "Este ajuste já foi estornado.");
        }
        // Undoing a debit reduces the invoice total; payments already made must
        // never exceed the new total, or paid cash would become unaccounted.
        if (adjustment.getKind().isDebit()
                && adjustment.getAmount().compareTo(outstandingOf(invoice)) > 0) {
            throw new BusinessRuleException("ADJUSTMENT_REVERSAL_EXCEEDS_OUTSTANDING",
                    "A fatura já recebeu pagamentos que cobrem este débito; "
                            + "estorne o pagamento antes de estornar o ajuste.");
        }
        adjustment.reverse(Instant.now());
        return toResponse(adjustment);
    }

    private BigDecimal outstandingOf(CardInvoice invoice) {
        Long userId = invoice.getUserId();
        return installments.sumActiveByInvoice(invoice.getId(), userId)
                .add(adjustments.sumActiveNetByInvoice(invoice.getId(), userId))
                .subtract(payments.sumCompletedByInvoice(invoice.getId(), userId));
    }

    private static AdjustmentResponse toResponse(InvoiceAdjustment adjustment) {
        return new AdjustmentResponse(
                adjustment.getId(),
                adjustment.getInvoice().getId(),
                adjustment.getKind(),
                adjustment.getDescription(),
                adjustment.getCategory() != null ? adjustment.getCategory().getId() : null,
                adjustment.getCategory() != null ? adjustment.getCategory().getName() : null,
                MoneyRules.normalize(adjustment.getAmount()),
                adjustment.getStatus(),
                adjustment.getReversedAt());
    }
}
