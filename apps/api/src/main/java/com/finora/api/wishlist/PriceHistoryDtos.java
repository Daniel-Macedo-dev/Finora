package com.finora.api.wishlist;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Validated API contracts for manual wishlist price history. */
public final class PriceHistoryDtos {
    private PriceHistoryDtos() { }

    public record ObservationMetadataRequest(
            @NotNull UUID clientRequestId,
            @NotNull LocalDate observedOn,
            @Size(max = 2000) String offerUrl,
            @Size(max = 2000) String notes) { }

    public record SnapshotRequest(
            @NotNull UUID clientRequestId,
            Long purchaseOptionId,
            @NotBlank @Size(max = 150) String merchant,
            @NotNull PurchaseOptionKind paymentKind,
            @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal basePrice,
            @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal shipping,
            @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal fees,
            @Min(1) @Max(120) Integer installmentCount,
            @Positive @Digits(integer = 12, fraction = 2) BigDecimal installmentAmount,
            @NotNull LocalDate observedOn,
            @Size(max = 2000) String offerUrl,
            @Size(max = 2000) String notes,
            boolean updateLinkedOption) { }

    public record SnapshotUpdateRequest(
            Long purchaseOptionId,
            @NotBlank @Size(max = 150) String merchant,
            @NotNull PurchaseOptionKind paymentKind,
            @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal basePrice,
            @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal shipping,
            @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal fees,
            @Min(1) @Max(120) Integer installmentCount,
            @Positive @Digits(integer = 12, fraction = 2) BigDecimal installmentAmount,
            @NotNull LocalDate observedOn,
            @Size(max = 2000) String offerUrl,
            @Size(max = 2000) String notes) { }

    public record SnapshotResponse(
            Long id, Long purchaseOptionId, boolean linkedOptionAvailable,
            String seriesKey, String merchant, PurchaseOptionKind paymentKind,
            BigDecimal basePrice, BigDecimal shipping, BigDecimal fees,
            BigDecimal nominalCost, Integer installmentCount,
            BigDecimal installmentAmount, LocalDate observedOn,
            String offerUrl, String notes) {
        static SnapshotResponse from(PriceSnapshot snapshot) {
            return new SnapshotResponse(snapshot.getId(), snapshot.getPurchaseOptionId(),
                    snapshot.getPurchaseOptionId() != null, snapshot.getSeriesKey(),
                    snapshot.getMerchant(), snapshot.getKind(), snapshot.getBasePrice(),
                    snapshot.getShipping(), snapshot.getFees(), snapshot.getNominalCost(),
                    snapshot.getInstallmentCount(), snapshot.getInstallmentAmount(),
                    snapshot.getObservedOn(), snapshot.getOfferUrl(), snapshot.getNotes());
        }
    }

    public record SummaryResponse(
            long observationCount, long seriesCount, LocalDate firstObservedOn,
            LocalDate lastObservedOn, BigDecimal bestCurrentOptionCost,
            BigDecimal latestObservedBestCost, BigDecimal previousComparableCost,
            BigDecimal absoluteChange, BigDecimal percentageChange,
            BigDecimal historicalMinimum, BigDecimal historicalMaximum,
            BigDecimal historicalAverage, BigDecimal targetPrice,
            BigDecimal distanceToTarget, BigDecimal distanceToTargetPercentage,
            Boolean targetReached, String latestMerchant,
            PurchaseOptionKind latestPaymentKind, String latestSeriesKey,
            Long daysSinceLatestObservation) { }

    public record ChartPoint(LocalDate observedOn, BigDecimal nominalCost,
                             long observationCount, Long snapshotId) { }

    public record ChartResponse(LocalDate from, LocalDate to, List<ChartPoint> points) { }
}
