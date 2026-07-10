package com.finora.api.wishlist;

import com.finora.api.wishlist.WishlistDtos.PurchaseOptionRequest;
import com.finora.api.wishlist.WishlistDtos.PurchaseOptionResponse;
import com.finora.api.wishlist.WishlistDtos.WishlistItemDetailResponse;
import com.finora.api.wishlist.WishlistDtos.WishlistItemRequest;
import com.finora.api.wishlist.WishlistDtos.WishlistItemResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wishlist")
public class WishlistController {

    private final WishlistService service;

    public WishlistController(WishlistService service) {
        this.service = service;
    }

    @GetMapping
    public List<WishlistItemResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public WishlistItemDetailResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WishlistItemDetailResponse create(@Valid @RequestBody WishlistItemRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public WishlistItemDetailResponse update(@PathVariable Long id,
                                             @Valid @RequestBody WishlistItemRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping("/{id}/options")
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseOptionResponse addOption(@PathVariable Long id,
                                            @Valid @RequestBody PurchaseOptionRequest request) {
        return service.addOption(id, request);
    }

    @PutMapping("/{id}/options/{optionId}")
    public PurchaseOptionResponse updateOption(@PathVariable Long id,
                                               @PathVariable Long optionId,
                                               @Valid @RequestBody PurchaseOptionRequest request) {
        return service.updateOption(id, optionId, request);
    }

    @DeleteMapping("/{id}/options/{optionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOption(@PathVariable Long id, @PathVariable Long optionId) {
        service.deleteOption(id, optionId);
    }
}
