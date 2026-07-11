package com.finora.api.creditcard.adjustment;

import com.finora.api.creditcard.adjustment.AdjustmentDtos.AdjustmentRequest;
import com.finora.api.creditcard.adjustment.AdjustmentDtos.AdjustmentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/credit-cards/{cardId}/invoices/{invoiceId}/adjustments")
public class InvoiceAdjustmentController {

    private final InvoiceAdjustmentService service;

    public InvoiceAdjustmentController(InvoiceAdjustmentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdjustmentResponse create(@PathVariable Long cardId,
                                     @PathVariable Long invoiceId,
                                     @Valid @RequestBody AdjustmentRequest request) {
        return service.create(cardId, invoiceId, request);
    }

    @PostMapping("/{adjustmentId}/reverse")
    public AdjustmentResponse reverse(@PathVariable Long cardId,
                                      @PathVariable Long invoiceId,
                                      @PathVariable Long adjustmentId) {
        return service.reverse(cardId, invoiceId, adjustmentId);
    }
}
