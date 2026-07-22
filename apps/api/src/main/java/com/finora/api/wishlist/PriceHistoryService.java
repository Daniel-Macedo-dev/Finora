package com.finora.api.wishlist;

import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.common.web.PageResponse;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.wishlist.PriceHistoryDtos.ChartPoint;
import com.finora.api.wishlist.PriceHistoryDtos.ChartResponse;
import com.finora.api.wishlist.PriceHistoryDtos.ObservationMetadataRequest;
import com.finora.api.wishlist.PriceHistoryDtos.SnapshotRequest;
import com.finora.api.wishlist.PriceHistoryDtos.SnapshotResponse;
import com.finora.api.wishlist.PriceHistoryDtos.SnapshotUpdateRequest;
import com.finora.api.wishlist.PriceHistoryDtos.SummaryResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PriceHistoryService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final long MAX_CHART_DAYS = 730;

    private final PriceSnapshotRepository snapshots;
    private final WishlistItemRepository items;
    private final PurchaseOptionRepository options;
    private final WishlistService wishlist;
    private final CurrentUserProvider currentUser;
    private final Clock clock;

    public PriceHistoryService(PriceSnapshotRepository snapshots, WishlistItemRepository items,
                               PurchaseOptionRepository options, WishlistService wishlist,
                               CurrentUserProvider currentUser, Clock clock) {
        this.snapshots = snapshots;
        this.items = items;
        this.options = options;
        this.wishlist = wishlist;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public SnapshotResponse capture(Long itemId, Long optionId, ObservationMetadataRequest request) {
        Long userId = currentUser.currentUserId();
        snapshots.lockOwner(userId);
        WishlistItem item = findItem(itemId, userId);
        PurchaseOption option = findOption(itemId, optionId, userId);
        SnapshotRequest snapshot = new SnapshotRequest(request.clientRequestId(), optionId,
                option.getMerchant(), option.getKind(), option.getBasePrice(), option.getShipping(),
                option.getFees(), option.getInstallmentCount(), option.getInstallmentAmount(),
                request.observedOn(), request.offerUrl(), request.notes(), false);
        return createLocked(item, option, snapshot);
    }

    public SnapshotResponse create(Long itemId, SnapshotRequest request) {
        Long userId = currentUser.currentUserId();
        snapshots.lockOwner(userId);
        WishlistItem item = findItem(itemId, userId);
        PurchaseOption option = request.purchaseOptionId() == null ? null
                : findOption(itemId, request.purchaseOptionId(), userId);
        return createLocked(item, option, request);
    }

    private SnapshotResponse createLocked(WishlistItem item, PurchaseOption option,
                                          SnapshotRequest request) {
        requireRequestId(request.clientRequestId());
        validateDate(request.observedOn());
        String offerUrl = validateUrl(request.offerUrl());
        SnapshotValues values = normalize(request.merchant(), request.paymentKind(),
                request.basePrice(), request.shipping(), request.fees(),
                request.installmentCount(), request.installmentAmount());
        String seriesKey = option == null
                ? "MANUAL:" + normalizeMerchant(values.merchant()) + ":" + values.kind()
                : "OPTION:" + option.getId();

        var existing = snapshots.findByUserIdAndClientRequestId(
                currentUser.currentUserId(), request.clientRequestId());
        if (existing.isPresent()) {
            PriceSnapshot found = existing.get();
            if (!samePayload(found, item.getId(), option, values, request.observedOn(),
                    offerUrl, trimmedOrNull(request.notes()))) {
                throw new BusinessRuleException("PRICE_SNAPSHOT_IDEMPOTENCY_CONFLICT",
                        "Esta identificação de envio já foi usada com dados diferentes.");
            }
            return SnapshotResponse.from(found);
        }

        PriceSnapshot entity = new PriceSnapshot(currentUser.currentUserId(), item,
                request.clientRequestId());
        apply(entity, option, seriesKey, values, request.observedOn(), offerUrl, request.notes());
        snapshots.saveAndFlush(entity);

        if (request.updateLinkedOption()) {
            if (option == null) {
                throw new BusinessRuleException("PRICE_SNAPSHOT_OPTION_REQUIRED",
                        "Selecione uma opção atual para atualizá-la.");
            }
            wishlist.updateOption(item.getId(), option.getId(), new WishlistDtos.PurchaseOptionRequest(
                    values.merchant(), values.kind(), values.basePrice(), values.shipping(),
                    values.fees(), values.installmentCount(), values.installmentAmount(),
                    option.getCreditCard() == null ? null : option.getCreditCard().getId(),
                    trimmedOrNull(request.notes())));
        }
        return SnapshotResponse.from(entity);
    }

    public SnapshotResponse update(Long itemId, Long snapshotId, SnapshotUpdateRequest request) {
        Long userId = currentUser.currentUserId();
        findItem(itemId, userId);
        PriceSnapshot entity = snapshots.findByIdAndItemIdAndUserId(snapshotId, itemId, userId)
                .orElseThrow(() -> new NotFoundException("Observação de preço", snapshotId));
        PurchaseOption option = request.purchaseOptionId() == null ? null
                : findOption(itemId, request.purchaseOptionId(), userId);
        validateDate(request.observedOn());
        SnapshotValues values = normalize(request.merchant(), request.paymentKind(),
                request.basePrice(), request.shipping(), request.fees(), request.installmentCount(),
                request.installmentAmount());
        String seriesKey = option == null
                ? "MANUAL:" + normalizeMerchant(values.merchant()) + ":" + values.kind()
                : "OPTION:" + option.getId();
        apply(entity, option, seriesKey, values, request.observedOn(),
                validateUrl(request.offerUrl()), request.notes());
        return SnapshotResponse.from(entity);
    }

    public void delete(Long itemId, Long snapshotId) {
        Long userId = currentUser.currentUserId();
        findItem(itemId, userId);
        PriceSnapshot snapshot = snapshots.findByIdAndItemIdAndUserId(snapshotId, itemId, userId)
                .orElseThrow(() -> new NotFoundException("Observação de preço", snapshotId));
        snapshots.delete(snapshot);
    }

    @Transactional(readOnly = true)
    public PageResponse<SnapshotResponse> history(Long itemId, int page, int size,
            LocalDate from, LocalDate to, String merchant, Long optionId,
            PurchaseOptionKind kind, PriceHistorySort order) {
        Long userId = currentUser.currentUserId();
        findItem(itemId, userId);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessRuleException("PRICE_HISTORY_PAGE_INVALID",
                    "Use página a partir de zero e tamanho entre 1 e 100.");
        }
        validateRange(from, to, false);
        Sort sort = switch (order == null ? PriceHistorySort.NEWEST : order) {
            case NEWEST -> Sort.by(Sort.Order.desc("observedOn"), Sort.Order.desc("id"));
            case OLDEST -> Sort.by(Sort.Order.asc("observedOn"), Sort.Order.asc("id"));
            case LOWEST -> Sort.by(Sort.Order.asc("nominalCost"), Sort.Order.asc("id"));
            case HIGHEST -> Sort.by(Sort.Order.desc("nominalCost"), Sort.Order.desc("id"));
        };
        Specification<PriceSnapshot> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("userId"), userId), cb.equal(root.get("item").get("id"), itemId));
        if (from != null) spec = spec.and((r, q, cb) -> cb.greaterThanOrEqualTo(r.get("observedOn"), from));
        if (to != null) spec = spec.and((r, q, cb) -> cb.lessThanOrEqualTo(r.get("observedOn"), to));
        if (merchant != null && !merchant.isBlank()) {
            String normalized = normalizeMerchant(merchant);
            spec = spec.and((r, q, cb) -> cb.equal(r.get("merchantNormalized"), normalized));
        }
        if (optionId != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("purchaseOptionId"), optionId));
        if (kind != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("kind"), kind));
        Page<SnapshotResponse> result = snapshots.findAll(spec, PageRequest.of(page, size, sort))
                .map(SnapshotResponse::from);
        return PageResponse.from(result);
    }

    @Transactional(readOnly = true)
    public SummaryResponse summary(Long itemId) {
        Long userId = currentUser.currentUserId();
        WishlistItem item = findItem(itemId, userId);
        var aggregate = snapshots.aggregate(userId, itemId);
        List<PriceSnapshot> latest = snapshots.latestPerSeries(userId, itemId);
        PriceSnapshot winner = latest.isEmpty() ? null : latest.getFirst();
        PriceSnapshot previous = winner == null ? null : snapshots
                .findFirstByUserIdAndItemIdAndSeriesKeyAndIdNotOrderByObservedOnDescIdDesc(
                        userId, itemId, winner.getSeriesKey(), winner.getId()).orElse(null);
        BigDecimal latestCost = winner == null ? null : winner.getNominalCost();
        BigDecimal previousCost = previous == null ? null : previous.getNominalCost();
        BigDecimal change = latestCost == null || previousCost == null ? null
                : MoneyRules.normalize(latestCost.subtract(previousCost));
        BigDecimal percentage = percentage(change, previousCost);
        BigDecimal bestOption = item.getOptions().stream().map(PurchaseOption::nominalCost)
                .min(BigDecimal::compareTo).map(MoneyRules::normalize).orElse(null);
        BigDecimal benchmark = latestCost != null ? latestCost : bestOption;
        BigDecimal target = item.getTargetPrice();
        BigDecimal distance = benchmark == null || target == null ? null
                : MoneyRules.normalize(benchmark.subtract(target));
        BigDecimal distancePercentage = percentage(distance, target);
        return new SummaryResponse(aggregate.getObservationCount(), aggregate.getSeriesCount(),
                aggregate.getFirstObservedOn(), aggregate.getLastObservedOn(), bestOption, latestCost,
                previousCost, change, percentage, aggregate.getHistoricalMinimum(),
                aggregate.getHistoricalMaximum(), aggregate.getHistoricalAverage(), target,
                distance, distancePercentage,
                benchmark == null || target == null ? null : benchmark.compareTo(target) <= 0,
                winner == null ? null : winner.getMerchant(), winner == null ? null : winner.getKind(),
                winner == null ? null : winner.getSeriesKey(), winner == null ? null
                        : ChronoUnit.DAYS.between(winner.getObservedOn(), LocalDate.now(clock)));
    }

    @Transactional(readOnly = true)
    public ChartResponse chart(Long itemId, LocalDate from, LocalDate to, String seriesKey,
                               Long optionId, String merchant, PurchaseOptionKind kind) {
        Long userId = currentUser.currentUserId();
        findItem(itemId, userId);
        LocalDate end = to == null ? LocalDate.now(clock) : to;
        LocalDate start = from == null ? end.minusYears(2) : from;
        validateRange(start, end, true);
        List<ChartPoint> points = snapshots.chart(userId, itemId, start, end,
                blankToNull(seriesKey), optionId,
                merchant == null || merchant.isBlank() ? null : normalizeMerchant(merchant),
                kind == null ? null : kind.name()).stream()
                .map(p -> new ChartPoint(p.getObservedOn(), p.getNominalCost(),
                        p.getObservationCount(), p.getSnapshotId())).toList();
        return new ChartResponse(start, end, points);
    }

    private WishlistItem findItem(Long itemId, Long userId) {
        return items.findByIdAndUserId(itemId, userId)
                .orElseThrow(() -> new NotFoundException("Item da lista de desejos", itemId));
    }

    private PurchaseOption findOption(Long itemId, Long optionId, Long userId) {
        return options.findByIdAndItemIdAndItemUserId(optionId, itemId, userId)
                .orElseThrow(() -> new NotFoundException("Opção de compra", optionId));
    }

    private void validateDate(LocalDate date) {
        if (date.isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleException("PRICE_SNAPSHOT_FUTURE_DATE",
                    "A data da observação não pode estar no futuro.");
        }
    }

    private static void validateRange(LocalDate from, LocalDate to, boolean bounded) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new BusinessRuleException("PRICE_HISTORY_DATE_RANGE_INVALID",
                    "A data final deve ser igual ou posterior à inicial.");
        }
        if (bounded && ChronoUnit.DAYS.between(from, to) > MAX_CHART_DAYS) {
            throw new BusinessRuleException("PRICE_HISTORY_CHART_RANGE_TOO_LARGE",
                    "O gráfico aceita no máximo dois anos por consulta.");
        }
    }

    private static SnapshotValues normalize(String merchant, PurchaseOptionKind kind,
            BigDecimal basePrice, BigDecimal shipping, BigDecimal fees,
            Integer installmentCount, BigDecimal installmentAmount) {
        BigDecimal normalizedBase = MoneyRules.normalize(basePrice);
        BigDecimal normalizedShipping = MoneyRules.normalize(shipping == null ? BigDecimal.ZERO : shipping);
        BigDecimal normalizedFees = MoneyRules.normalize(fees == null ? BigDecimal.ZERO : fees);
        BigDecimal normalizedInstallment = installmentAmount == null ? null
                : MoneyRules.normalize(installmentAmount);
        if (kind == PurchaseOptionKind.CASH
                && (installmentCount != null || normalizedInstallment != null)) {
            throw new BusinessRuleException("OPTION_CASH_WITH_INSTALLMENTS",
                    "Uma observação à vista não pode ter parcelas.");
        }
        if (kind == PurchaseOptionKind.INSTALLMENT
                && (installmentCount == null || normalizedInstallment == null)) {
            throw new BusinessRuleException("OPTION_INSTALLMENT_DATA_REQUIRED",
                    "Informe o número e o valor das parcelas.");
        }
        if (kind == PurchaseOptionKind.INSTALLMENT) {
            BigDecimal difference = normalizedInstallment.multiply(BigDecimal.valueOf(installmentCount))
                    .subtract(normalizedBase).abs();
            BigDecimal tolerance = WishlistService.RECONCILIATION_TOLERANCE_PER_INSTALLMENT
                    .multiply(BigDecimal.valueOf(installmentCount));
            if (difference.compareTo(tolerance) > 0) {
                throw new BusinessRuleException("OPTION_INSTALLMENTS_DONT_RECONCILE",
                        "As parcelas não correspondem ao preço total informado.");
            }
        }
        return new SnapshotValues(merchant.trim(), kind, normalizedBase, normalizedShipping,
                normalizedFees, normalizedBase.add(normalizedShipping).add(normalizedFees),
                installmentCount, normalizedInstallment);
    }

    private static void apply(PriceSnapshot target, PurchaseOption option, String seriesKey,
            SnapshotValues values, LocalDate observedOn, String offerUrl, String notes) {
        target.setPurchaseOptionId(option == null ? null : option.getId());
        target.setSeriesKey(seriesKey);
        target.setMerchant(values.merchant());
        target.setMerchantNormalized(normalizeMerchant(values.merchant()));
        target.setKind(values.kind());
        target.setBasePrice(values.basePrice());
        target.setShipping(values.shipping());
        target.setFees(values.fees());
        target.setNominalCost(values.nominalCost());
        target.setInstallmentCount(values.installmentCount());
        target.setInstallmentAmount(values.installmentAmount());
        target.setObservedOn(observedOn);
        target.setOfferUrl(offerUrl);
        target.setNotes(trimmedOrNull(notes));
    }

    private static boolean samePayload(PriceSnapshot existing, Long itemId, PurchaseOption option,
            SnapshotValues values, LocalDate observedOn, String offerUrl, String notes) {
        return Objects.equals(existing.getItem().getId(), itemId)
                && Objects.equals(existing.getPurchaseOptionId(), option == null ? null : option.getId())
                && existing.getMerchant().equals(values.merchant()) && existing.getKind() == values.kind()
                && existing.getBasePrice().compareTo(values.basePrice()) == 0
                && existing.getShipping().compareTo(values.shipping()) == 0
                && existing.getFees().compareTo(values.fees()) == 0
                && Objects.equals(existing.getInstallmentCount(), values.installmentCount())
                && moneyEquals(existing.getInstallmentAmount(), values.installmentAmount())
                && existing.getObservedOn().equals(observedOn)
                && Objects.equals(existing.getOfferUrl(), offerUrl)
                && Objects.equals(existing.getNotes(), notes);
    }

    private static String validateUrl(String raw) {
        String value = trimmedOrNull(raw);
        if (value == null) return null;
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw invalidUrl();
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) throw invalidUrl();
        } catch (URISyntaxException ex) {
            throw invalidUrl();
        }
        return value;
    }

    private static BusinessRuleException invalidUrl() {
        return new BusinessRuleException("PRICE_SNAPSHOT_URL_INVALID",
                "Use uma URL completa iniciada por http:// ou https://.");
    }

    static String normalizeMerchant(String value) {
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private static BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) return null;
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private static boolean moneyEquals(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static void requireRequestId(java.util.UUID id) {
        if (id == null) throw new BusinessRuleException("PRICE_SNAPSHOT_REQUEST_ID_REQUIRED",
                "Informe a identificação única do envio.");
    }

    private static String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record SnapshotValues(String merchant, PurchaseOptionKind kind,
            BigDecimal basePrice, BigDecimal shipping, BigDecimal fees, BigDecimal nominalCost,
            Integer installmentCount, BigDecimal installmentAmount) { }
}
