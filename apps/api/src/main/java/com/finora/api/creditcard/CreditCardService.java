package com.finora.api.creditcard;

import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.common.money.MoneyRules;
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
import com.finora.api.settings.SettingsService;
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
    private final SettingsService settings;

    public CreditCardService(CreditCardRepository cards,
                             CardInvoiceRepository invoices,
                             CardPurchaseRepository purchases,
                             AccountRepository accounts,
                             CardLimitService limits,
                             InvoiceService invoiceService,
                             CurrentUserProvider currentUser,
                             SettingsService settings) {
        this.cards = cards;
        this.invoices = invoices;
        this.purchases = purchases;
        this.accounts = accounts;
        this.limits = limits;
        this.invoiceService = invoiceService;
        this.currentUser = currentUser;
        this.settings = settings;
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
        CurrencyCode currency = resolveCurrency(request.currency(), userId);
        MoneyRules.validateScale(request.creditLimit(), currency);
        CreditCard card = new CreditCard(
                userId,
                request.name().trim(),
                request.brand(),
                MoneyRules.normalize(request.creditLimit(), currency),
                request.closingDay(),
                request.dueDay(),
                currency);
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
        assertCurrencyUnchanged(card, request.currency());
        MoneyRules.validateScale(request.creditLimit(), card.getCurrency());
        card.setName(request.name().trim());
        card.setBrand(request.brand());
        card.setCreditLimit(MoneyRules.normalize(request.creditLimit(), card.getCurrency()));
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
            // An invoice is settled from this account, and Finora does not
            // convert: a differently denominated account could never pay it.
            if (account.getCurrency() != card.getCurrency()) {
                throw new BusinessRuleException("CARD_CURRENCY_MISMATCH",
                        ("Este cartão é em %s e não pode ter uma conta padrão de "
                                + "pagamento em %s. Não há conversão de moeda.")
                                .formatted(card.getCurrency().name(),
                                        account.getCurrency().name()));
            }
            card.setDefaultPaymentAccount(account);
        } else {
            card.setDefaultPaymentAccount(null);
        }
    }

    /**
     * An omitted currency means the user's base currency; a foreign one is
     * never inferred.
     */
    private CurrencyCode resolveCurrency(String requested, Long userId) {
        CurrencyCode explicit = CurrencyCode.parseOrNull(requested);
        return explicit != null ? explicit : settings.forUser(userId).getBaseCurrency();
    }

    /**
     * A card's currency is immutable: its purchases, installments and invoices
     * all inherit it, so changing it would reinterpret the whole billing
     * history. Omitting the field keeps the current currency.
     */
    private void assertCurrencyUnchanged(CreditCard card, String requested) {
        CurrencyCode explicit = CurrencyCode.parseOrNull(requested);
        if (explicit != null && explicit != card.getCurrency()) {
            throw new BusinessRuleException("CURRENCY_IMMUTABLE",
                    ("A moeda de um cartão não pode ser alterada (%s). Alterá-la "
                            + "reinterpretaria compras, parcelas e faturas já registradas.")
                            .formatted(card.getCurrency().name()));
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
                card.getCurrency().name(),
                card.isArchived(),
                new CardLimitResponse(
                        limit.creditLimit(), limit.usedLimit(),
                        limit.availableLimit(), limit.utilizationPercent(),
                        card.getCurrency().name()),
                new CurrentCycleResponse(
                        currentInvoiceId, cycle.referenceMonth(), cycle.closingDate(), cycle.dueDate()),
                nextDue);
    }

    private static String trimmedOrNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
