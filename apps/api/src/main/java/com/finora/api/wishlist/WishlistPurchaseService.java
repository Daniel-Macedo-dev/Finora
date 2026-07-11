package com.finora.api.wishlist;

import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.category.Category;
import com.finora.api.category.CategoryType;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.purchase.CardPurchase;
import com.finora.api.creditcard.purchase.CardPurchaseService;
import com.finora.api.creditcard.purchase.PurchaseDtos.PurchaseRequest;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts a selected wishlist option into a real financial event, exactly
 * once. The item row is locked for the whole execution, so a retry or a
 * double-submit finds the PURCHASED status and is rejected instead of buying
 * twice; the partial unique indexes on the wishlist link columns are the
 * database-level backstop for the same invariant.
 *
 * <p>A CASH option becomes a regular expense transaction (optionally settled
 * from an account). An INSTALLMENT option becomes a credit-card purchase with
 * its full invoice/installment schedule — the card must belong to the user
 * and have enough available limit.
 */
@Service
@Transactional
public class WishlistPurchaseService {

    public record ExecutePurchaseRequest(
            @NotNull Long optionId,
            /** CASH: account that pays (optional). */
            Long accountId,
            /** INSTALLMENT: card to charge; defaults to the option's card. */
            Long creditCardId,
            /** Purchase date; defaults to today. */
            LocalDate purchasedOn) {
    }

    public record ExecutePurchaseResponse(
            Long itemId,
            WishlistStatus status,
            /** Filled for CASH executions. */
            Long transactionId,
            /** Filled for INSTALLMENT executions. */
            Long cardPurchaseId) {
    }

    private final WishlistItemRepository items;
    private final PurchaseOptionRepository options;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final CardPurchaseService cardPurchases;
    private final CurrentUserProvider currentUser;

    public WishlistPurchaseService(WishlistItemRepository items,
                                   PurchaseOptionRepository options,
                                   AccountRepository accounts,
                                   TransactionRepository transactions,
                                   CardPurchaseService cardPurchases,
                                   CurrentUserProvider currentUser) {
        this.items = items;
        this.options = options;
        this.accounts = accounts;
        this.transactions = transactions;
        this.cardPurchases = cardPurchases;
        this.currentUser = currentUser;
    }

    public ExecutePurchaseResponse execute(Long itemId, ExecutePurchaseRequest request,
                                           LocalDate today) {
        Long userId = currentUser.currentUserId();
        // The lock is the idempotency anchor: concurrent executions serialize
        // here and every one after the first sees PURCHASED.
        WishlistItem item = items.findByIdAndUserIdForUpdate(itemId, userId)
                .orElseThrow(() -> new NotFoundException("Item da lista de desejos", itemId));
        if (item.getStatus() == WishlistStatus.PURCHASED) {
            throw new BusinessRuleException("WISHLIST_ALREADY_PURCHASED",
                    "Este item já foi comprado.");
        }
        PurchaseOption option = options
                .findByIdAndItemIdAndItemUserId(request.optionId(), itemId, userId)
                .orElseThrow(() -> new NotFoundException("Opção de compra", request.optionId()));

        Category category = item.getCategory();
        if (category == null || category.getType() != CategoryType.EXPENSE) {
            throw new BusinessRuleException("WISHLIST_CATEGORY_REQUIRED",
                    "Defina uma categoria de despesa para o item antes de registrar a compra.");
        }

        LocalDate purchasedOn = request.purchasedOn() != null ? request.purchasedOn() : today;
        ExecutePurchaseResponse response;
        if (option.getKind() == PurchaseOptionKind.CASH) {
            response = executeCash(userId, item, option, request, purchasedOn);
        } else {
            response = executeInstallment(userId, item, option, request, purchasedOn);
        }
        item.setStatus(WishlistStatus.PURCHASED);
        return response;
    }

    private ExecutePurchaseResponse executeCash(Long userId, WishlistItem item,
                                                PurchaseOption option,
                                                ExecutePurchaseRequest request,
                                                LocalDate purchasedOn) {
        Transaction transaction = new Transaction(
                userId,
                TransactionType.EXPENSE,
                MoneyRules.normalize(option.nominalCost()),
                item.getName(),
                purchasedOn,
                item.getCategory());
        if (request.accountId() != null) {
            // Owner-scoped: another user's account id behaves as absent.
            Account account = accounts.findByIdAndUserId(request.accountId(), userId)
                    .orElseThrow(() -> new NotFoundException("Conta", request.accountId()));
            if (account.isArchived()) {
                throw new BusinessRuleException("ACCOUNT_ARCHIVED",
                        "Uma conta arquivada não pode pagar esta compra.");
            }
            transaction.setAccount(account);
        }
        transaction.setNotes("Comprado em %s (lista de desejos).".formatted(option.getMerchant()));
        transaction.setWishlistItemId(item.getId());
        return new ExecutePurchaseResponse(
                item.getId(), WishlistStatus.PURCHASED,
                transactions.save(transaction).getId(), null);
    }

    private ExecutePurchaseResponse executeInstallment(Long userId, WishlistItem item,
                                                       PurchaseOption option,
                                                       ExecutePurchaseRequest request,
                                                       LocalDate purchasedOn) {
        Long cardId = request.creditCardId() != null
                ? request.creditCardId()
                : option.getCreditCard() != null ? option.getCreditCard().getId() : null;
        if (cardId == null) {
            throw new BusinessRuleException("WISHLIST_CARD_REQUIRED",
                    "Escolha um cartão de crédito para executar esta opção parcelada.");
        }
        // The card purchase carries the exact option total; the deterministic
        // allocator splits it, so the sum always matches what was advertised.
        CardPurchase purchase = cardPurchases.createForWishlistItem(
                userId,
                cardId,
                new PurchaseRequest(
                        item.getName(),
                        option.getMerchant(),
                        item.getCategory().getId(),
                        purchasedOn,
                        MoneyRules.normalize(option.nominalCost()),
                        option.getInstallmentCount(),
                        "Compra executada a partir da lista de desejos."),
                item.getId());
        return new ExecutePurchaseResponse(
                item.getId(), WishlistStatus.PURCHASED, null, purchase.getId());
    }
}
