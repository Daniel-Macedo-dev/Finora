package com.finora.api.wishlist;

import com.finora.api.category.Category;
import com.finora.api.category.CategoryRepository;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.CreditCard;
import com.finora.api.creditcard.CreditCardRepository;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.wishlist.WishlistDtos.PurchaseOptionRequest;
import com.finora.api.wishlist.WishlistDtos.PurchaseOptionResponse;
import com.finora.api.wishlist.WishlistDtos.WishlistCategory;
import com.finora.api.wishlist.WishlistDtos.WishlistItemDetailResponse;
import com.finora.api.wishlist.WishlistDtos.WishlistItemRequest;
import com.finora.api.wishlist.WishlistDtos.WishlistItemResponse;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WishlistService {

    /**
     * Tolerance for reconciling installmentCount * installmentAmount with the
     * advertised total: one cent per installment (rounding of the advertised
     * per-installment value).
     */
    static final BigDecimal RECONCILIATION_TOLERANCE_PER_INSTALLMENT = new BigDecimal("0.01");

    private final WishlistItemRepository items;
    private final PurchaseOptionRepository options;
    private final CategoryRepository categories;
    private final CreditCardRepository creditCards;
    private final CurrentUserProvider currentUser;

    public WishlistService(WishlistItemRepository items,
                           PurchaseOptionRepository options,
                           CategoryRepository categories,
                           CreditCardRepository creditCards,
                           CurrentUserProvider currentUser) {
        this.items = items;
        this.options = options;
        this.categories = categories;
        this.creditCards = creditCards;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<WishlistItemResponse> list() {
        return items.findAllByUserIdOrderByStatusAscPriorityDescNameAsc(currentUser.currentUserId())
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public WishlistItemDetailResponse get(Long id) {
        return toDetail(find(id));
    }

    public WishlistItemDetailResponse create(WishlistItemRequest request) {
        WishlistItem item = new WishlistItem(
                currentUser.currentUserId(), request.name().trim(), request.priority());
        apply(item, request);
        return toDetail(items.save(item));
    }

    public WishlistItemDetailResponse update(Long id, WishlistItemRequest request) {
        WishlistItem item = find(id);
        item.setName(request.name().trim());
        item.setPriority(request.priority());
        apply(item, request);
        return toDetail(item);
    }

    public void delete(Long id) {
        items.delete(find(id));
    }

    public PurchaseOptionResponse addOption(Long itemId, PurchaseOptionRequest request) {
        WishlistItem item = find(itemId);
        validateOption(request);
        PurchaseOption option = new PurchaseOption(
                item,
                request.merchant().trim(),
                request.kind(),
                MoneyRules.normalize(request.basePrice()),
                MoneyRules.normalize(orZero(request.shipping())),
                MoneyRules.normalize(orZero(request.fees())));
        applyInstallments(option, request);
        option.setNotes(trimmedOrNull(request.notes()));
        item.getOptions().add(option);
        items.flush();
        return PurchaseOptionResponse.from(option);
    }

    public PurchaseOptionResponse updateOption(Long itemId, Long optionId, PurchaseOptionRequest request) {
        PurchaseOption option = options
                .findByIdAndItemIdAndItemUserId(optionId, itemId, currentUser.currentUserId())
                .orElseThrow(() -> new NotFoundException("Opção de compra", optionId));
        validateOption(request);
        option.setMerchant(request.merchant().trim());
        option.setKind(request.kind());
        option.setBasePrice(MoneyRules.normalize(request.basePrice()));
        option.setShipping(MoneyRules.normalize(orZero(request.shipping())));
        option.setFees(MoneyRules.normalize(orZero(request.fees())));
        applyInstallments(option, request);
        option.setNotes(trimmedOrNull(request.notes()));
        return PurchaseOptionResponse.from(option);
    }

    public void deleteOption(Long itemId, Long optionId) {
        PurchaseOption option = options
                .findByIdAndItemIdAndItemUserId(optionId, itemId, currentUser.currentUserId())
                .orElseThrow(() -> new NotFoundException("Opção de compra", optionId));
        option.getItem().getOptions().remove(option);
    }

    /**
     * Rejects contradictory purchase option combinations before they enter the
     * system (also enforced by database check constraints).
     */
    private void validateOption(PurchaseOptionRequest request) {
        if (request.kind() == PurchaseOptionKind.CASH) {
            if (request.installmentCount() != null || request.installmentAmount() != null) {
                throw new BusinessRuleException("OPTION_CASH_WITH_INSTALLMENTS",
                        "Uma opção à vista não pode ter parcelas.");
            }
            return;
        }
        if (request.installmentCount() == null || request.installmentAmount() == null) {
            throw new BusinessRuleException("OPTION_INSTALLMENT_DATA_REQUIRED",
                    "Informe o número de parcelas e o valor da parcela.");
        }
        BigDecimal computedTotal = request.installmentAmount()
                .multiply(BigDecimal.valueOf(request.installmentCount()));
        BigDecimal tolerance = RECONCILIATION_TOLERANCE_PER_INSTALLMENT
                .multiply(BigDecimal.valueOf(request.installmentCount()));
        if (computedTotal.subtract(request.basePrice()).abs().compareTo(tolerance) > 0) {
            throw new BusinessRuleException("OPTION_INSTALLMENTS_DONT_RECONCILE",
                    "As parcelas (%d × %s) não correspondem ao preço total informado."
                            .formatted(request.installmentCount(), request.installmentAmount()));
        }
    }

    private void applyInstallments(PurchaseOption option, PurchaseOptionRequest request) {
        if (request.kind() == PurchaseOptionKind.INSTALLMENT) {
            option.setInstallmentCount(request.installmentCount());
            option.setInstallmentAmount(MoneyRules.normalize(request.installmentAmount()));
            if (request.creditCardId() != null) {
                // Owner-scoped: another user's card id behaves as absent.
                CreditCard card = creditCards
                        .findByIdAndUserId(request.creditCardId(), currentUser.currentUserId())
                        .orElseThrow(() -> new NotFoundException("Cartão", request.creditCardId()));
                if (card.isArchived()) {
                    throw new BusinessRuleException("CARD_ARCHIVED",
                            "Um cartão arquivado não pode ser vinculado a uma opção de compra.");
                }
                option.setCreditCard(card);
            } else {
                option.setCreditCard(null);
            }
        } else {
            if (request.creditCardId() != null) {
                throw new BusinessRuleException("OPTION_CASH_WITH_CARD",
                        "Uma opção à vista não usa cartão de crédito.");
            }
            option.setInstallmentCount(null);
            option.setInstallmentAmount(null);
            option.setCreditCard(null);
        }
    }

    private void apply(WishlistItem item, WishlistItemRequest request) {
        item.setNotes(trimmedOrNull(request.notes()));
        if (request.categoryId() != null) {
            // Owner-scoped: another user's category id behaves as absent.
            Category category = categories
                    .findByIdAndUserId(request.categoryId(), currentUser.currentUserId())
                    .orElseThrow(() -> new NotFoundException("Categoria", request.categoryId()));
            item.setCategory(category);
        } else {
            item.setCategory(null);
        }
        item.setReferencePrice(normalizeOrNull(request.referencePrice()));
        item.setTargetPrice(normalizeOrNull(request.targetPrice()));
        item.setDesiredDate(request.desiredDate());
        if (request.status() != null) {
            item.setStatus(request.status());
        }
    }

    private WishlistItem find(Long id) {
        return items.findByIdAndUserId(id, currentUser.currentUserId())
                .orElseThrow(() -> new NotFoundException("Item da lista de desejos", id));
    }

    private WishlistItemResponse toSummary(WishlistItem item) {
        BigDecimal bestNominal = item.getOptions().stream()
                .map(PurchaseOption::nominalCost)
                .min(Comparator.naturalOrder())
                .orElse(null);
        return new WishlistItemResponse(
                item.getId(),
                item.getName(),
                item.getNotes(),
                toCategory(item),
                item.getReferencePrice(),
                item.getTargetPrice(),
                item.getPriority(),
                item.getDesiredDate(),
                item.getStatus(),
                item.getOptions().size(),
                bestNominal);
    }

    private WishlistItemDetailResponse toDetail(WishlistItem item) {
        return new WishlistItemDetailResponse(
                item.getId(),
                item.getName(),
                item.getNotes(),
                toCategory(item),
                item.getReferencePrice(),
                item.getTargetPrice(),
                item.getPriority(),
                item.getDesiredDate(),
                item.getStatus(),
                item.getOptions().stream().map(PurchaseOptionResponse::from).toList());
    }

    private static WishlistCategory toCategory(WishlistItem item) {
        if (item.getCategory() == null) {
            return null;
        }
        return new WishlistCategory(
                item.getCategory().getId(),
                item.getCategory().getName(),
                item.getCategory().getType());
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static BigDecimal normalizeOrNull(BigDecimal value) {
        return value != null ? MoneyRules.normalize(value) : null;
    }

    private static String trimmedOrNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
