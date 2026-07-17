package com.finora.api.legacyconversion;

import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.legacyconversion.ConversionPreviewDtos.ConversionPreviewResponse;
import com.finora.api.legacyconversion.LegacyConversionDtos.BatchConversionResponse;
import com.finora.api.legacyconversion.LegacyConversionDtos.ConversionInventoryResponse;
import com.finora.api.legacyconversion.LegacyConversionDtos.ConversionResponse;
import com.finora.api.legacyconversion.LegacyConversionDtos.EligibilityResponse;
import com.finora.api.legacyconversion.LegacyConversionInventoryService.InventoryFilters;
import com.finora.api.legacyconversion.LegacyConversionPreviewService.PreviewInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner-scoped legacy-credit conversion API. Session authentication, CSRF and
 * RFC 9457 error bodies come from the global security configuration; every
 * lookup is owner-scoped, so another user's ids behave as absent.
 */
@RestController
@Validated
@RequestMapping("/api/legacy-conversions")
public class LegacyConversionController {

    /** Confirmation of one conversion; the first invoice must be explicit. */
    public record ConvertRequest(
            @NotNull(message = "Informe a transação de origem.")
            Long transactionId,

            @NotNull(message = "Escolha o cartão real da compra.")
            Long cardId,

            @NotNull(message = "Confirme a data efetiva da compra.")
            LocalDate effectivePurchaseDate,

            @NotNull(message = "Informe o número de parcelas.")
            @Min(value = 1, message = "O número de parcelas deve estar entre 1 e 120.")
            @Max(value = 120, message = "O número de parcelas deve estar entre 1 e 120.")
            Integer installmentCount,

            @NotNull(message = "Confirme a primeira fatura da conversão.")
            YearMonth firstInvoiceMonth) {

        PreviewInput toInput() {
            return new PreviewInput(transactionId, cardId, effectivePurchaseDate,
                    installmentCount, firstInvoiceMonth);
        }
    }

    /** Preview parameters; the first invoice is computed when not yet confirmed. */
    public record PreviewRequest(
            @NotNull(message = "Informe a transação de origem.")
            Long transactionId,

            @NotNull(message = "Escolha o cartão real da compra.")
            Long cardId,

            @NotNull(message = "Informe a data efetiva da compra.")
            LocalDate effectivePurchaseDate,

            @NotNull(message = "Informe o número de parcelas.")
            @Min(value = 1, message = "O número de parcelas deve estar entre 1 e 120.")
            @Max(value = 120, message = "O número de parcelas deve estar entre 1 e 120.")
            Integer installmentCount,

            YearMonth firstInvoiceMonth) {

        PreviewInput toInput() {
            return new PreviewInput(transactionId, cardId, effectivePurchaseDate,
                    installmentCount, firstInvoiceMonth);
        }
    }

    public record ReverseRequest(
            @Size(max = 300, message = "O motivo pode ter no máximo 300 caracteres.")
            String reason) {
    }

    public record BatchConvertRequest(
            @NotEmpty(message = "Informe ao menos uma conversão.")
            @Size(max = 50, message = "Converta no máximo 50 transações por lote.")
            List<@Valid ConvertRequest> items) {
    }

    private final LegacyConversionInventoryService inventory;
    private final LegacyConversionEligibilityService eligibility;
    private final LegacyConversionPreviewService previews;
    private final LegacyConversionService conversions;
    private final LegacyConversionReversalService reversals;
    private final LegacyConversionBatchService batches;
    private final CurrentUserProvider currentUser;

    public LegacyConversionController(LegacyConversionInventoryService inventory,
                                      LegacyConversionEligibilityService eligibility,
                                      LegacyConversionPreviewService previews,
                                      LegacyConversionService conversions,
                                      LegacyConversionReversalService reversals,
                                      LegacyConversionBatchService batches,
                                      CurrentUserProvider currentUser) {
        this.inventory = inventory;
        this.eligibility = eligibility;
        this.previews = previews;
        this.conversions = conversions;
        this.reversals = reversals;
        this.batches = batches;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ConversionInventoryResponse inventory(
            @RequestParam(required = false) YearMonth month,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) @Positive @Digits(integer = 12, fraction = 2) BigDecimal minAmount,
            @RequestParam(required = false) @Positive @Digits(integer = 12, fraction = 2) BigDecimal maxAmount,
            @RequestParam(required = false) ConversionInventoryState state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return inventory.inventory(
                new InventoryFilters(month, from, to, categoryId, minAmount, maxAmount, state),
                page, size);
    }

    @GetMapping("/eligibility/{transactionId}")
    public EligibilityResponse eligibility(@PathVariable Long transactionId) {
        var verdict = eligibility.evaluate(currentUser.currentUserId(), transactionId);
        return new EligibilityResponse(transactionId, verdict.status(), verdict.convertible(),
                verdict.reasonCode(), verdict.message());
    }

    @PostMapping("/preview")
    public ConversionPreviewResponse preview(@Valid @RequestBody PreviewRequest request) {
        return previews.preview(request.toInput());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversionResponse convert(@Valid @RequestBody ConvertRequest request) {
        return conversions.convert(request.toInput());
    }

    @GetMapping("/{id}")
    public ConversionResponse get(@PathVariable Long id) {
        return conversions.get(id);
    }

    @PostMapping("/{id}/reverse")
    public ConversionResponse reverse(@PathVariable Long id,
                                      @RequestBody(required = false) @Valid ReverseRequest request) {
        return conversions.toResponse(
                reversals.reverse(id, request != null ? request.reason() : null));
    }

    @PostMapping("/batch")
    public BatchConversionResponse batch(@Valid @RequestBody BatchConvertRequest request) {
        return batches.convertAll(request.items().stream()
                .map(ConvertRequest::toInput)
                .toList());
    }
}
