package com.finora.api.creditcard.invoice;

import com.finora.api.creditcard.invoice.InvoiceDtos.InvoiceDetailResponse;
import com.finora.api.creditcard.invoice.InvoiceDtos.InvoiceSummaryResponse;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/credit-cards/{cardId}/invoices")
public class CardInvoiceController {

    private final InvoiceService service;

    public CardInvoiceController(InvoiceService service) {
        this.service = service;
    }

    @GetMapping
    public List<InvoiceSummaryResponse> list(@PathVariable Long cardId) {
        return service.listForCard(cardId, LocalDate.now());
    }

    @GetMapping("/{invoiceId}")
    public InvoiceDetailResponse get(@PathVariable Long cardId, @PathVariable Long invoiceId) {
        return service.detail(cardId, invoiceId, LocalDate.now());
    }
}
