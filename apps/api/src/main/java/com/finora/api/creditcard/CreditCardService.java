package com.finora.api.creditcard;

import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.creditcard.CardLimitService.CardLimit;
import com.finora.api.creditcard.CreditCardDtos.CardLimitResponse;
import com.finora.api.creditcard.CreditCardDtos.CreditCardRequest;
import com.finora.api.creditcard.CreditCardDtos.CreditCardResponse;
import com.finora.api.creditcard.CreditCardDtos.CurrentCycleResponse;
import com.finora.api.creditcard.InvoiceCycleCalculator.InvoiceCycle;
import com.finora.api.creditcard.invoice.CardInvoiceRepository;
import com.finora.api.creditcard.invoice.InvoiceDtos.InvoiceSummaryResponse;
import com.finora.api.creditcard.invoice.InvoiceService;
import com.finora.api.creditcard.invoice.InvoiceStatus;
import com.finora.api.creditcard.purchase.CardPurchaseRepository;
import com.finora.api.identity.CurrentUserProvider;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credit-card CRUD and archival. Cards that ever charged anything are never
 * hard-deleted — they are archived, keeping invoices, payments and history
 * intact. A card with outstanding balance cannot be archived.
 */
@Service
@Transactional
public class CreditCardService {

    private final CreditCardRepository cards;
    private final CardInvoiceRepository invoices;
    private final CardPurchaseRepository purchases;
    private final AccountRepository accounts;
    private final CardLimitService limits;
    private final InvoiceService invoiceService;
    private final CurrentUserProvider currentUser;

    public CreditCardService(CreditCardRepository cards,
                             CardInvoiceRepository invoices,
                             CardPurchaseRepository purchases,
                             AccountRepository accounts,
                             CardLimitService limits,
                             InvoiceService invoiceService,
                             CurrentUserProvider currentUser) {
        this.cards = cards;
        this.invoices = invoices;
        this.purchases = purchases;
        this.accounts = accounts;
        this.limits = limits;
        this.invoiceService = invoiceService;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<CreditCardResponse> list(LocalDate today) {
        return cards.findAllByUserIdOrderByArchivedAscNameAsc(currentUser.currentUserId()).stream()
                .map(card -> toResponse(card, today))
                .toList();
    }

    @Transactional(readOnly = true)
    public CreditCardResponse get(Long id, LocalDate today) {
        return toResponse(find(id), today);
    }

    public CreditCardResponse create(CreditCardRequest request, LocalDate today) {
        Long userId = currentUser.currentUserId();
        cards.findByUserIdAndNameIgnoreCase(userId, request.name().trim()).ifPresent(existing -> {
            throw new BusinessRuleException("CARD_NAME_TAKEN", "Já existe um cartão com esse nome.");
        });
        CreditCard card = new CreditCard(
                userId,
                request.name().trim(),
                request.brand(),
                request.creditLimit(),
                request.closingDay(),
                request.dueDay());
        applyOptionalFields(userId, card, request);
        return toResponse(cards.save(card), today);
    }

    public CreditCardResponse update(Long id, CreditCardRequest request, LocalDate today) {
        Long userId = currentUser.currentUserId();
        CreditCard card = find(id);
        cards.findByUserIdAndNameIgnoreCase(userId, request.name().trim()).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new BusinessRuleException("CARD_NAME_TAKEN", "Já existe um cartão com esse nome.");
            }
        });
        card.setName(request.name().trim());
        card.setBrand(request.brand());
        card.setCreditLimit(request.creditLimit());
        // New closing/due days shape only invoices created from now on;
        // existing invoices keep their snapshot dates.
        card.setClosingDay(request.closingDay());
        card.setDueDay(request.dueDay());
        applyOptionalFields(userId, card, request);
        return toResponse(card, today);
    }

    public CreditCardResponse archive(Long id, LocalDate today) {
        CreditCard card = find(id);
        if (limits.limitOf(card).usedLimit().signum() > 0) {
            throw new BusinessRuleException("CARD_HAS_OUTSTANDING_BALANCE",
                    "Este cartão possui saldo em aberto e não pode ser arquivado. "
                            + "Quite as faturas pendentes antes de arquivar.");
        }
        card.setArchived(true);
        return toResponse(card, today);
    }

    public CreditCardResponse unarchive(Long id, LocalDate today) {
        CreditCard card = find(id);
        card.setArchived(false);
        return toResponse(card, today);
    }

    /** Hard delete is only possible for a card that never charged anything. */
    public void delete(Long id) {
        CreditCard card = find(id);
        if (purchases.existsByCardId(card.getId())
                || !invoices.findAllByCardIdAndUserIdOrderByReferenceMonthAsc(
                        card.getId(), card.getUserId()).isEmpty()) {
            throw new BusinessRuleException("CARD_HAS_HISTORY",
                    "Este cartão possui compras ou faturas e não pode ser excluído. "
                            + "Arquive o cartão para preservá-lo no histórico.");
        }
        cards.delete(card);
    }

    private void applyOptionalFields(Long userId, CreditCard card, CreditCardRequest request) {
        card.setIssuer(trimmedOrNull(request.issuer()));
        card.setLastFourDigits(trimmedOrNull(request.lastFourDigits()));
        if (request.defaultPaymentAccountId() != null) {
            // Owner-scoped: another user's account id behaves as absent.
            Account account = accounts.findByIdAndUserId(request.defaultPaymentAccountId(), userId)
                    .orElseThrow(() -> new NotFoundException("Conta", request.defaultPaymentAccountId()));
            if (account.isArchived()) {
                throw new BusinessRuleException("ACCOUNT_ARCHIVED",
                        "Uma conta arquivada não pode ser a conta padrão de pagamento.");
            }
            card.setDefaultPaymentAccount(account);
        } else {
            card.setDefaultPaymentAccount(null);
        }
    }

    private CreditCard find(Long id) {
        return cards.findByIdAndUserId(id, currentUser.currentUserId())
                .orElseThrow(() -> new NotFoundException("Cartão", id));
    }

    private CreditCardResponse toResponse(CreditCard card, LocalDate today) {
        CardLimit limit = limits.limitOf(card);
        InvoiceCycle cycle = InvoiceService.currentCycle(card, today);
        Long currentInvoiceId = invoices.findByUserIdAndCardIdAndReferenceMonth(
                        card.getUserId(), card.getId(), cycle.referenceMonth().atDay(1))
                .map(invoice -> invoice.getId())
                .orElse(null);

        List<InvoiceSummaryResponse> cardInvoices = invoiceService.listForCard(card.getId(), today);
        InvoiceSummaryResponse nextDue = cardInvoices.stream()
                .filter(invoice -> invoice.status() != InvoiceStatus.PAID
                        && invoice.outstandingAmount().signum() > 0)
                .min(Comparator.comparing(InvoiceSummaryResponse::dueDate))
                .orElse(null);

        return new CreditCardResponse(
                card.getId(),
                card.getName(),
                card.getIssuer(),
                card.getBrand(),
                card.getLastFourDigits(),
                card.getClosingDay(),
                card.getDueDay(),
                Optional.ofNullable(card.getDefaultPaymentAccount()).map(Account::getId).orElse(null),
                Optional.ofNullable(card.getDefaultPaymentAccount()).map(Account::getName).orElse(null),
                card.isArchived(),
                new CardLimitResponse(
                        limit.creditLimit(), limit.usedLimit(),
                        limit.availableLimit(), limit.utilizationPercent()),
                new CurrentCycleResponse(
                        currentInvoiceId, cycle.referenceMonth(), cycle.closingDate(), cycle.dueDate()),
                nextDue);
    }

    private static String trimmedOrNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
