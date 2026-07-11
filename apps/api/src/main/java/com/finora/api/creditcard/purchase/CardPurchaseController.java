package com.finora.api.creditcard.purchase;

import com.finora.api.common.web.PageResponse;
import com.finora.api.creditcard.purchase.PurchaseDtos.PurchaseRequest;
import com.finora.api.creditcard.purchase.PurchaseDtos.PurchaseResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/credit-cards/{cardId}/purchases")
public class CardPurchaseController {

    private final CardPurchaseService service;

    public CardPurchaseController(CardPurchaseService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<PurchaseResponse> list(@PathVariable Long cardId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return service.list(cardId, page, size);
    }

    @GetMapping("/{purchaseId}")
    public PurchaseResponse get(@PathVariable Long cardId, @PathVariable Long purchaseId) {
        return service.get(cardId, purchaseId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseResponse create(@PathVariable Long cardId,
                                   @Valid @RequestBody PurchaseRequest request) {
        return service.create(cardId, request);
    }

    @PutMapping("/{purchaseId}")
    public PurchaseResponse update(@PathVariable Long cardId,
                                   @PathVariable Long purchaseId,
                                   @Valid @RequestBody PurchaseRequest request) {
        return service.update(cardId, purchaseId, request, LocalDate.now());
    }

    @PostMapping("/{purchaseId}/cancel")
    public PurchaseResponse cancel(@PathVariable Long cardId, @PathVariable Long purchaseId) {
        return service.cancel(cardId, purchaseId);
    }
}
