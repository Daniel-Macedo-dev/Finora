package com.finora.api.creditcard.purchase;

import com.finora.api.category.Category;
import com.finora.api.category.CategoryRepository;
import com.finora.api.category.CategoryType;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.common.web.PageResponse;
import com.finora.api.creditcard.CardLimitService;
import com.finora.api.creditcard.CreditCard;
import com.finora.api.creditcard.CreditCardRepository;
import com.finora.api.creditcard.InvoiceCycleCalculator;
import com.finora.api.creditcard.InvoiceCycleCalculator.InvoiceCycle;
import com.finora.api.creditcard.installment.CardInstallment;
import com.finora.api.creditcard.installment.CardInstallmentRepository;
import com.finora.api.creditcard.installment.InstallmentAllocator;
import com.finora.api.creditcard.installment.InstallmentStatus;
import com.finora.api.creditcard.invoice.CardInvoice;
import com.finora.api.creditcard.invoice.InvoiceService;
import com.finora.api.creditcard.payment.InvoicePaymentRepository;
import com.finora.api.creditcard.purchase.PurchaseDtos.PurchaseCategory;
import com.finora.api.creditcard.purchase.PurchaseDtos.PurchaseInstallmentResponse;
import com.finora.api.creditcard.purchase.PurchaseDtos.PurchaseRequest;
import com.finora.api.creditcard.purchase.PurchaseDtos.PurchaseResponse;
import com.finora.api.identity.CurrentUserProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Card purchases and their installment schedules. Creation is atomic: the
 * purchase, its N installments and any missing invoice rows are persisted in
 * one transaction, under a pessimistic lock on the card so concurrent
 * purchases cannot both pass the available-limit check.
 */
@Service
@Transactional
public class CardPurchaseService {

    static final int MAX_PAGE_SIZE = 100;

    private final CardPurchaseRepository purchases;
    private final CardInstallmentRepository installments;
    private final InvoicePaymentRepository payments;
    private final CreditCardRepository cards;
    private final CategoryRepository categories;
    private final CardLimitService limits;
    private final InvoiceService invoiceService;
    private final CurrentUserProvider currentUser;

    public CardPurchaseService(CardPurchaseRepository purchases,
                               CardInstallmentRepository installments,
                               InvoicePaymentRepository payments,
                               CreditCardRepository cards,
                               CategoryRepository categories,
                               CardLimitService limits,
                               InvoiceService invoiceService,
                               CurrentUserProvider currentUser) {
        this.purchases = purchases;
        this.installments = installments;
        this.payments = payments;
        this.cards = cards;
        this.categories = categories;
        this.limits = limits;
        this.invoiceService = invoiceService;
        this.currentUser = currentUser;
    }

    public PurchaseResponse create(Long cardId, PurchaseRequest request) {
        Long userId = currentUser.currentUserId();
        // The write lock serializes every limit-consuming operation on this card.
        CreditCard card = cards.findByIdAndUserIdForUpdate(cardId, userId)
                .orElseThrow(() -> new NotFoundException("Cartão", cardId));
        CardPurchase purchase = buildPurchase(userId, card, request, null);
        generateInstallments(purchase);
        return toResponse(purchase);
    }

    /**
     * Creates a purchase already linked to a wishlist item (idempotency anchor).
     * The caller must hold the card lock context — this method locks the card
     * itself, so it is safe from any entry point.
     */
    public CardPurchase createForWishlistItem(Long userId, Long cardId, PurchaseRequest request,
                                              Long wishlistItemId) {
        CreditCard card = cards.findByIdAndUserIdForUpdate(cardId, userId)
                .orElseThrow(() -> new NotFoundException("Cartão", cardId));
        CardPurchase purchase = buildPurchase(userId, card, request, wishlistItemId);
        generateInstallments(purchase);
        return purchase;
    }

    @Transactional(readOnly = true)
    public PageResponse<PurchaseResponse> list(Long cardId, int page, int size) {
        Long userId = currentUser.currentUserId();
        cards.findByIdAndUserId(cardId, userId)
                .orElseThrow(() -> new NotFoundException("Cartão", cardId));
        return PageResponse.from(purchases
                .findAllByCardIdAndUserIdOrderByPurchaseDateDescIdDesc(
                        cardId, userId,
                        PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE)))
                .map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public PurchaseResponse get(Long cardId, Long purchaseId) {
        return toResponse(find(cardId, purchaseId));
    }

    /**
     * Metadata (description, merchant, category, notes) is always editable.
     * Financial fields (amount, date, installment count) may only change while
     * every affected invoice is still open and unpaid — the schedule is then
     * regenerated atomically under the card lock. Otherwise: cancel and
     * re-create the purchase.
     */
    public PurchaseResponse update(Long cardId, Long purchaseId, PurchaseRequest request,
                                   LocalDate today) {
        Long userId = currentUser.currentUserId();
        CardPurchase purchase = find(cardId, purchaseId);
        if (purchase.getStatus() != PurchaseStatus.ACTIVE) {
            throw new BusinessRuleException("PURCHASE_NOT_ACTIVE",
                    "Uma compra cancelada não pode ser alterada.");
        }
        purchase.setDescription(request.description().trim());
        purchase.setMerchant(trimmedOrNull(request.merchant()));
        purchase.setNotes(trimmedOrNull(request.notes()));
        purchase.setCategory(resolveExpenseCategory(userId, request.categoryId()));

        boolean financialChange = purchase.getTotalAmount().compareTo(
                        MoneyRules.normalize(request.totalAmount())) != 0
                || !purchase.getPurchaseDate().equals(request.purchaseDate())
                || purchase.getInstallmentCount() != request.installmentCount();
        if (financialChange) {
            // Lock the card before touching the schedule or rechecking the limit.
            CreditCard card = cards.findByIdAndUserIdForUpdate(cardId, userId).orElseThrow();
            requireUnsettledSchedule(purchase, "alterada");
            if (installments.existsActiveWithClosedInvoice(purchase.getId(), today)) {
                throw new BusinessRuleException("PURCHASE_INVOICE_CLOSED",
                        "Uma fatura desta compra já fechou; os valores não podem ser alterados. "
                                + "Cancele a compra e registre uma nova, ou use um ajuste na fatura.");
            }
            List<CardInstallment> old =
                    installments.findAllByPurchaseIdAndUserIdOrderBySequenceNumberAsc(purchaseId, userId);
            installments.deleteAll(old);
            installments.flush();
            purchase.setPurchaseDate(request.purchaseDate());
            purchase.setTotalAmount(MoneyRules.normalize(request.totalAmount()));
            purchase.setInstallmentCount(request.installmentCount());
            requireAvailableLimit(card, purchase.getTotalAmount());
            generateInstallments(purchase);
        }
        return toResponse(purchase);
    }

    /**
     * Cancels an unsettled purchase: the record and its installments remain in
     * history as CANCELLED, stop counting toward invoices and budgets, and the
     * consumed limit is released. Blocked once any affected invoice has a
     * completed payment — money already moved; use adjustments instead.
     */
    public PurchaseResponse cancel(Long cardId, Long purchaseId) {
        Long userId = currentUser.currentUserId();
        // Lock keeps the released limit consistent with concurrent purchases.
        cards.findByIdAndUserIdForUpdate(cardId, userId)
                .orElseThrow(() -> new NotFoundException("Cartão", cardId));
        CardPurchase purchase = find(cardId, purchaseId);
        if (purchase.getStatus() != PurchaseStatus.ACTIVE) {
            throw new BusinessRuleException("PURCHASE_NOT_ACTIVE",
                    "Esta compra já foi cancelada.");
        }
        requireUnsettledSchedule(purchase, "cancelada");
        purchase.setStatus(PurchaseStatus.CANCELLED);
        installments.findAllByPurchaseIdAndUserIdOrderBySequenceNumberAsc(purchaseId, userId)
                .forEach(installment -> installment.setStatus(InstallmentStatus.CANCELLED));
        return toResponse(purchase);
    }

    private CardPurchase buildPurchase(Long userId, CreditCard card, PurchaseRequest request,
                                       Long wishlistItemId) {
        if (card.isArchived()) {
            throw new BusinessRuleException("CARD_ARCHIVED",
                    "Um cartão arquivado não pode receber novas compras.");
        }
        BigDecimal total = MoneyRules.normalize(request.totalAmount());
        requireAvailableLimit(card, total);
        CardPurchase purchase = new CardPurchase(
                userId,
                card,
                resolveExpenseCategory(userId, request.categoryId()),
                request.description().trim(),
                request.purchaseDate(),
                total,
                request.installmentCount());
        purchase.setMerchant(trimmedOrNull(request.merchant()));
        purchase.setNotes(trimmedOrNull(request.notes()));
        purchase.setWishlistItemId(wishlistItemId);
        return purchases.save(purchase);
    }

    /**
     * Creates the purchase's installments, one per consecutive invoice month
     * starting at the invoice whose closing date covers the purchase date.
     * Amounts come from the deterministic cent-exact allocator.
     */
    private void generateInstallments(CardPurchase purchase) {
        CreditCard card = purchase.getCard();
        List<BigDecimal> amounts;
        try {
            amounts = InstallmentAllocator.allocate(
                    purchase.getTotalAmount(), purchase.getInstallmentCount());
        } catch (IllegalArgumentException ex) {
            throw new BusinessRuleException("PURCHASE_INSTALLMENTS_TOO_SMALL",
                    "O valor da compra não permite parcelas de pelo menos R$ 0,01.");
        }
        InvoiceCycle first = InvoiceCycleCalculator.cycleForPurchase(
                card.getClosingDay(), card.getDueDay(), purchase.getPurchaseDate());
        List<CardInstallment> schedule = new ArrayList<>(amounts.size());
        for (int i = 0; i < amounts.size(); i++) {
            YearMonth month = first.referenceMonth().plusMonths(i);
            InvoiceCycle cycle = i == 0
                    ? first
                    : InvoiceCycleCalculator.cycleFor(card.getClosingDay(), card.getDueDay(), month);
            CardInvoice invoice = invoiceService.ensureInvoice(card, cycle);
            schedule.add(new CardInstallment(
                    purchase.getUserId(), purchase, invoice,
                    i + 1, amounts.size(), amounts.get(i)));
        }
        installments.saveAll(schedule);
    }

    private void requireAvailableLimit(CreditCard card, BigDecimal amount) {
        BigDecimal available = limits.availableLimit(card);
        if (amount.compareTo(available) > 0) {
            throw new BusinessRuleException("INSUFFICIENT_CARD_LIMIT",
                    "Limite disponível insuficiente: a compra de %s excede o limite livre de %s."
                            .formatted(MoneyRules.formatBrl(amount), MoneyRules.formatBrl(available)));
        }
    }

    /** Rejects changes to a schedule whose invoices already closed or received payment. */
    private void requireUnsettledSchedule(CardPurchase purchase, String action) {
        if (payments.existsCompletedForPurchaseInvoices(purchase.getId())) {
            throw new BusinessRuleException("PURCHASE_INVOICE_ALREADY_PAID",
                    "Uma fatura desta compra já recebeu pagamento; a compra não pode ser %s. "
                            .formatted(action)
                            + "Utilize um ajuste de crédito na fatura correspondente.");
        }
    }

    private Category resolveExpenseCategory(Long userId, Long categoryId) {
        Category category = categories.findByIdAndUserId(categoryId, userId)
                .orElseThrow(() -> new NotFoundException("Categoria", categoryId));
        if (category.getType() != CategoryType.EXPENSE) {
            throw new BusinessRuleException("CATEGORY_NOT_EXPENSE",
                    "Compras no cartão exigem uma categoria de despesa.");
        }
        return category;
    }

    private CardPurchase find(Long cardId, Long purchaseId) {
        return purchases.findByIdAndCardIdAndUserId(purchaseId, cardId, currentUser.currentUserId())
                .orElseThrow(() -> new NotFoundException("Compra", purchaseId));
    }

    PurchaseResponse toResponse(CardPurchase purchase) {
        List<PurchaseInstallmentResponse> schedule = installments
                .findAllByPurchaseIdAndUserIdOrderBySequenceNumberAsc(
                        purchase.getId(), purchase.getUserId())
                .stream()
                .map(i -> new PurchaseInstallmentResponse(
                        i.getId(),
                        i.getSequenceNumber(),
                        i.getTotalInstallments(),
                        MoneyRules.normalize(i.getAmount()),
                        i.getInvoice().getId(),
                        i.getInvoice().getReferenceMonth(),
                        i.getInvoice().getDueDate(),
                        i.getStatus()))
                .toList();
        return new PurchaseResponse(
                purchase.getId(),
                purchase.getCard().getId(),
                purchase.getDescription(),
                purchase.getMerchant(),
                new PurchaseCategory(purchase.getCategory().getId(), purchase.getCategory().getName()),
                purchase.getPurchaseDate(),
                MoneyRules.normalize(purchase.getTotalAmount()),
                purchase.getInstallmentCount(),
                purchase.getStatus(),
                purchase.getWishlistItemId(),
                purchase.getNotes(),
                schedule);
    }

    private static String trimmedOrNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
