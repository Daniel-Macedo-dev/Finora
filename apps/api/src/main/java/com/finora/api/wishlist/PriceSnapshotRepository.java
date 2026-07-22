package com.finora.api.wishlist;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

public interface PriceSnapshotRepository
        extends JpaRepository<PriceSnapshot, Long>, JpaSpecificationExecutor<PriceSnapshot> {

    Optional<PriceSnapshot> findByUserIdAndClientRequestId(Long userId, UUID clientRequestId);

    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:userId)", nativeQuery = true)
    int lockOwner(@Param("userId") Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PriceSnapshot s set s.purchaseOptionId = null where s.purchaseOptionId = :optionId")
    int clearOptionLink(@Param("optionId") Long optionId);

    Optional<PriceSnapshot> findByIdAndItemIdAndUserId(Long id, Long itemId, Long userId);

    interface AggregateView {
        long getObservationCount();
        long getSeriesCount();
        LocalDate getFirstObservedOn();
        LocalDate getLastObservedOn();
        BigDecimal getHistoricalMinimum();
        BigDecimal getHistoricalMaximum();
        BigDecimal getHistoricalAverage();
    }

    @Query(value = """
            SELECT count(*) AS observationCount,
                   count(DISTINCT series_key) AS seriesCount,
                   min(observed_on) AS firstObservedOn,
                   max(observed_on) AS lastObservedOn,
                   min(nominal_cost) AS historicalMinimum,
                   max(nominal_cost) AS historicalMaximum,
                   round(avg(nominal_cost), 2) AS historicalAverage
            FROM wishlist_price_snapshots
            WHERE user_id = :userId AND wishlist_item_id = :itemId
            """, nativeQuery = true)
    AggregateView aggregate(@Param("userId") Long userId, @Param("itemId") Long itemId);

    interface ItemHistoryView {
        Long getItemId();
        long getObservationCount();
        BigDecimal getLatestObservedPrice();
        LocalDate getLatestObservedOn();
        BigDecimal getHistoricalMinimum();
    }

    @Query(value = """
            SELECT item_id AS itemId, observation_count AS observationCount,
                   latest_price AS latestObservedPrice, latest_on AS latestObservedOn,
                   historical_minimum AS historicalMinimum
            FROM (
                SELECT wishlist_item_id AS item_id,
                       count(*) OVER (PARTITION BY wishlist_item_id) AS observation_count,
                       min(nominal_cost) OVER (PARTITION BY wishlist_item_id) AS historical_minimum,
                       observed_on AS latest_on, nominal_cost AS latest_price,
                       row_number() OVER (PARTITION BY wishlist_item_id
                           ORDER BY observed_on DESC, id DESC) AS latest_rank
                FROM wishlist_price_snapshots WHERE user_id = :userId
            ) ranked WHERE latest_rank = 1
            """, nativeQuery = true)
    List<ItemHistoryView> itemHistory(@Param("userId") Long userId);

    @Query("""
            select s from PriceSnapshot s
            where s.userId = :userId and s.item.id = :itemId
              and not exists (
                select newer.id from PriceSnapshot newer
                where newer.userId = s.userId and newer.item.id = s.item.id
                  and newer.seriesKey = s.seriesKey
                  and (newer.observedOn > s.observedOn
                       or (newer.observedOn = s.observedOn and newer.id > s.id)))
            order by s.nominalCost asc, s.observedOn desc, s.id desc
            """)
    List<PriceSnapshot> latestPerSeries(@Param("userId") Long userId,
                                        @Param("itemId") Long itemId);

    Optional<PriceSnapshot> findFirstByUserIdAndItemIdAndSeriesKeyAndIdNotOrderByObservedOnDescIdDesc(
            Long userId, Long itemId, String seriesKey, Long excludedId);

    interface ChartPointView {
        LocalDate getObservedOn();
        BigDecimal getNominalCost();
        long getObservationCount();
        Long getSnapshotId();
    }

    @Query(value = """
            SELECT observed_on AS observedOn, nominal_cost AS nominalCost,
                   day_count AS observationCount, id AS snapshotId
            FROM (
                SELECT s.*,
                       count(*) OVER (PARTITION BY observed_on) AS day_count,
                       row_number() OVER (
                         PARTITION BY observed_on
                         ORDER BY nominal_cost ASC, id ASC) AS day_rank
                FROM wishlist_price_snapshots s
                WHERE user_id = :userId AND wishlist_item_id = :itemId
                  AND observed_on BETWEEN :fromDate AND :toDate
                  AND (:seriesKey IS NULL OR series_key = :seriesKey)
                  AND (:optionId IS NULL OR purchase_option_id = :optionId)
                  AND (:merchant IS NULL OR merchant_normalized = :merchant)
                  AND (:kind IS NULL OR payment_kind = :kind)
            ) ranked
            WHERE day_rank = 1
            ORDER BY observed_on ASC, id ASC
            LIMIT 731
            """, nativeQuery = true)
    List<ChartPointView> chart(@Param("userId") Long userId,
                               @Param("itemId") Long itemId,
                               @Param("fromDate") LocalDate from,
                               @Param("toDate") LocalDate to,
                               @Param("seriesKey") String seriesKey,
                               @Param("optionId") Long optionId,
                               @Param("merchant") String merchant,
                               @Param("kind") String kind);
}
