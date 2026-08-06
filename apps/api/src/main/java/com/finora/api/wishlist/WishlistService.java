package com.finora.api.wishlist;

import com.finora.api.category.Category;
import com.finora.api.category.CategoryRepository;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.CreditCard;
import com.finora.api.creditcard.CreditCardRepository;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.settings.SettingsService;
import com.finora.api.wishlist.WishlistDtos.PurchaseOptionRequest;
import com.finora.api.wishlist.WishlistDtos.PurchaseOptionResponse;
import com.finora.api.wishlist.WishlistDtos.WishlistCategory;
import com.finora.api.wishlist.WishlistDtos.WishlistItemDetailResponse;
import com.finora.api.wishlist.WishlistDtos.WishlistItemRequest;
import com.finora.api.wishlist.WishlistDtos.WishlistItemResponse;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private final PriceSnapshotRepository priceSnapshots;
    private final CurrentUserProvider currentUser;
    private final SettingsService settings;

    public WishlistService(WishlistItemRepository items,
                           PurchaseOptionRepository options,
                           CategoryRepository categories,
                           CreditCardRepository creditCards,
                           PriceSnapshotRepository priceSnapshots,
                           CurrentUserProvider currentUser,
                           SettingsService settings) {
        this.items = items;
        this.options = options;
        this.categories = categories;
        this.creditCards = creditCards;
        this.priceSnapshots = priceSnapshots;
        this.currentUser = currentUser;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public List<WishlistItemResponse> list() {
        Long userId = currentUser.currentUserId();
        Map<Long, PriceSnapshotRepository.ItemHistoryView> history = priceSnapshots.itemHistory(userId)
                .stream().collect(Collectors.toMap(
                        PriceSnapshotRepository.ItemHistoryView::getItemId, Function.identity()));
        return items.findAllByUserIdOrderByStatusAscPriorityDescNameAsc(userId)
                .stream()
                .map(item -> toSummary(item, history.get(item.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public WishlistItemDetailResponse get(Long id) {
        return toDetail(find(id));
    }

    public WishlistItemDetailResponse create(WishlistItemRequest request) {
        return create(request, null);
    }

    /**
     * Same rules as {@link #create(WishlistItemRequest)}, with the stable
     * identity an item created offline already carries. Offline-created
     * purchase options and price observations reference their parent through
     * that identity until the item receives a server id.
     *
     * @param clientResourceId null for anything created online
     */
    public WishlistItemDetailResponse create(WishlistItemRequest request,
                                             java.util.UUID clientResourceId) {
        Long userId = currentUser.currentUserId();
        WishlistItem item = new WishlistItem(userId, request.name().trim(), request.priority());
        CurrencyCode currency = CurrencyCode.parseOrNull(request.currency());
        item.setCurrency(currency != null ? currency : settings.forUser(userId).getBaseCurrency());
        item.setClientResourceId(clientResourceId);
        apply(item, request);
        return toDetail(items.save(item));
    }

    public WishlistItemDetailResponse update(Long id, WishlistItemRequest request) {
        WishlistItem item = find(id);
        assertCurrencyUnchanged(item, request.currency());
        item.setName(request.name().trim());
        item.setPriority(request.priority());
        apply(item, request);
        return toDetail(item);
    }

    public void delete(Long id) {
        items.delete(find(id));
    }

    public PurchaseOptionResponse addOption(Long itemId, PurchaseOptionRequest request) {
        return addOption(itemId, request, null);
    }

    /**
     * Same rules as {@link #addOption(Long, PurchaseOptionRequest)}, with the
     * stable identity an option created offline already carries.
     *
     * @param clientResourceId null for anything created online
     */
    public PurchaseOptionResponse addOption(Long itemId, PurchaseOptionRequest request,
                                            java.util.UUID clientResourceId) {
        WishlistItem item = find(itemId);
        validateOption(request);
        // Options are priced in the item's currency: they are competing offers
        // for the same thing, so a foreign one could not be compared to it.
        CurrencyCode currency = item.getCurrency();
        MoneyRules.validateScale(request.basePrice(), currency);
        MoneyRules.validateScale(request.shipping(), currency);
        MoneyRules.validateScale(request.fees(), currency);
        PurchaseOption option = new PurchaseOption(
                item,
                request.merchant().trim(),
                request.kind(),
                MoneyRules.normalize(request.basePrice(), currency),
                MoneyRules.normalize(orZero(request.shipping()), currency),
                MoneyRules.normalize(orZero(request.fees()), currency));
        applyInstallments(option, request);
        option.setNotes(trimmedOrNull(request.notes()));
        option.setClientResourceId(clientResourceId);
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
        priceSnapshots.clearOptionLink(optionId);
        options.delete(option);
        options.flush();
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
                // The card will be charged this option's installments, and
                // Finora cannot convert, so the denominations must agree.
                if (card.getCurrency() != option.getItem().getCurrency()) {
                    throw new BusinessRuleException("WISHLIST_CURRENCY_MISMATCH",
                            ("Este item é em %s e não pode ser parcelado em um cartão "
                                    + "em %s. Não há conversão de moeda.")
                                    .formatted(option.getItem().getCurrency().name(),
                                            card.getCurrency().name()));
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

    /**
     * A wishlist item's currency is immutable: its options and its whole price
     * history are denominated in it, so a change would reinterpret the series
     * rather than convert it.
     */
    private void assertCurrencyUnchanged(WishlistItem item, String requested) {
        CurrencyCode explicit = CurrencyCode.parseOrNull(requested);
        if (explicit != null && explicit != item.getCurrency()) {
            throw new BusinessRuleException("CURRENCY_IMMUTABLE",
                    ("A moeda de um item não pode ser alterada (%s). Alterá-la "
                            + "reinterpretaria as opções e o histórico de preços.")
                            .formatted(item.getCurrency().name()));
        }
    }

    private WishlistItem find(Long id) {
        return items.findByIdAndUserId(id, currentUser.currentUserId())
                .orElseThrow(() -> new NotFoundException("Item da lista de desejos", id));
    }

    private WishlistItemResponse toSummary(WishlistItem item,
                                            PriceSnapshotRepository.ItemHistoryView history) {
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
                item.getOptions().size(), bestNominal,
                history == null ? 0 : history.getObservationCount(),
                history == null ? null : history.getLatestObservedPrice(),
                history == null ? null : history.getLatestObservedOn(),
                history == null ? null : history.getHistoricalMinimum(),
                history == null || item.getTargetPrice() == null ? null
                        : history.getLatestObservedPrice().compareTo(item.getTargetPrice()) <= 0,
                item.getCurrency().name(),
                item.getVersion());
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
                item.getOptions().stream().map(PurchaseOptionResponse::from).toList(),
                item.getCurrency().name(),
                item.getVersion());
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
