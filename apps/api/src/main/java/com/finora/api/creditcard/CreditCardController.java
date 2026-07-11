package com.finora.api.creditcard;

import com.finora.api.creditcard.CreditCardDtos.CreditCardRequest;
import com.finora.api.creditcard.CreditCardDtos.CreditCardResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
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
@RequestMapping("/api/credit-cards")
public class CreditCardController {

    private final CreditCardService service;

    public CreditCardController(CreditCardService service) {
        this.service = service;
    }

    @GetMapping
    public List<CreditCardResponse> list() {
        return service.list(LocalDate.now());
    }

    @GetMapping("/{id}")
    public CreditCardResponse get(@PathVariable Long id) {
        return service.get(id, LocalDate.now());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreditCardResponse create(@Valid @RequestBody CreditCardRequest request) {
        return service.create(request, LocalDate.now());
    }

    @PutMapping("/{id}")
    public CreditCardResponse update(@PathVariable Long id,
                                     @Valid @RequestBody CreditCardRequest request) {
        return service.update(id, request, LocalDate.now());
    }

    @PostMapping("/{id}/archive")
    public CreditCardResponse archive(@PathVariable Long id) {
        return service.archive(id, LocalDate.now());
    }

    @PostMapping("/{id}/unarchive")
    public CreditCardResponse unarchive(@PathVariable Long id) {
        return service.unarchive(id, LocalDate.now());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
