package com.finora.api.wishlist;

import com.finora.api.common.web.PageResponse;
import com.finora.api.wishlist.PriceHistoryDtos.ChartResponse;
import com.finora.api.wishlist.PriceHistoryDtos.ObservationMetadataRequest;
import com.finora.api.wishlist.PriceHistoryDtos.SnapshotRequest;
import com.finora.api.wishlist.PriceHistoryDtos.SnapshotResponse;
import com.finora.api.wishlist.PriceHistoryDtos.SnapshotUpdateRequest;
import com.finora.api.wishlist.PriceHistoryDtos.SummaryResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wishlist/{itemId}")
public class PriceHistoryController {
    private final PriceHistoryService service;

    public PriceHistoryController(PriceHistoryService service) {
        this.service = service;
    }

    @PostMapping("/price-snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public SnapshotResponse create(@PathVariable Long itemId,
                                   @Valid @RequestBody SnapshotRequest request) {
        return service.create(itemId, request);
    }

    @PostMapping("/options/{optionId}/price-snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public SnapshotResponse capture(@PathVariable Long itemId, @PathVariable Long optionId,
                                    @Valid @RequestBody ObservationMetadataRequest request) {
        return service.capture(itemId, optionId, request);
    }

    @PutMapping("/price-snapshots/{snapshotId}")
    public SnapshotResponse update(@PathVariable Long itemId, @PathVariable Long snapshotId,
                                   @Valid @RequestBody SnapshotUpdateRequest request) {
        return service.update(itemId, snapshotId, request);
    }

    @DeleteMapping("/price-snapshots/{snapshotId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long itemId, @PathVariable Long snapshotId) {
        service.delete(itemId, snapshotId);
    }

    @GetMapping("/price-snapshots")
    public PageResponse<SnapshotResponse> history(@PathVariable Long itemId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String merchant,
            @RequestParam(required = false) Long purchaseOptionId,
            @RequestParam(required = false) PurchaseOptionKind paymentKind,
            @RequestParam(defaultValue = "NEWEST") PriceHistorySort sort) {
        return service.history(itemId, page, size, from, to, merchant,
                purchaseOptionId, paymentKind, sort);
    }

    @GetMapping("/price-history-summary")
    public SummaryResponse summary(@PathVariable Long itemId) {
        return service.summary(itemId);
    }

    @GetMapping("/price-history-series")
    public ChartResponse chart(@PathVariable Long itemId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String seriesKey,
            @RequestParam(required = false) Long purchaseOptionId,
            @RequestParam(required = false) String merchant,
            @RequestParam(required = false) PurchaseOptionKind paymentKind) {
        return service.chart(itemId, from, to, seriesKey, purchaseOptionId, merchant, paymentKind);
    }
}
