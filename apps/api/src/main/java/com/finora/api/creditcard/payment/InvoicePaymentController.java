package com.finora.api.creditcard.payment;

import com.finora.api.creditcard.payment.PaymentDtos.PaymentRequest;
import com.finora.api.creditcard.payment.PaymentDtos.PaymentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/credit-cards/{cardId}/invoices/{invoiceId}/payments")
public class InvoicePaymentController {

    private final InvoicePaymentService service;

    public InvoicePaymentController(InvoicePaymentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse pay(@PathVariable Long cardId,
                               @PathVariable Long invoiceId,
                               @Valid @RequestBody PaymentRequest request) {
        return service.pay(cardId, invoiceId, request);
    }

    @PostMapping("/{paymentId}/reverse")
    public PaymentResponse reverse(@PathVariable Long cardId,
                                   @PathVariable Long invoiceId,
                                   @PathVariable Long paymentId) {
        return service.reverse(cardId, invoiceId, paymentId);
    }
}
