package com.finora.api.offlinesync.handler;

import com.finora.api.offlinesync.OfflineSyncDtos.ResourceTarget;
import com.finora.api.wishlist.PurchaseOptionKind;
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

/**
 * Payload shapes that exist only for offline replay, because their online
 * counterparts carry fields a queued mutation must not be able to set.
 *
 * <p>The records themselves are the allowlist: a property that is not declared
 * here cannot arrive through generic JSON no matter what the client sends. That
 * is why the offline snapshot payload has no {@code updateLinkedOption} and no
 * way to request an authoritative capture — those flows read or write current
 * server state and are deliberately online-only, so the safest way to refuse
 * them is to give them no wire representation at all.
 */
public final class OfflinePayloads {

    private OfflinePayloads() {
    }

    /**
     * A purchase option queued offline. The parent item may be one that already
     * exists on the server or one created in the same outbox.
     */
    public record OptionPayload(
            /** Required on CREATE; ignored on UPDATE, where the option knows its parent. */
            ResourceTarget item,

            @NotBlank(message = "Informe a loja ou vendedor.")
            @Size(max = 150, message = "O nome da loja pode ter no máximo 150 caracteres.")
            String merchant,

            @NotNull(message = "Informe a forma de pagamento.")
            PurchaseOptionKind kind,

            @NotNull(message = "Informe o preço.")
            @Positive(message = "O preço deve ser maior que zero.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal basePrice,

            @PositiveOrZero(message = "O frete não pode ser negativo.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal shipping,

            @PositiveOrZero(message = "As taxas não podem ser negativas.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal fees,

            @Min(value = 1, message = "O número de parcelas deve ser pelo menos 1.")
            @Max(value = 120, message = "O número de parcelas deve ser no máximo 120.")
            Integer installmentCount,

            @Positive(message = "O valor da parcela deve ser maior que zero.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal installmentAmount,

            /** Must resolve to one of the owner's non-archived cards; cards are never created offline. */
            Long creditCardId,

            @Size(max = 2000, message = "As observações podem ter no máximo 2000 caracteres.")
            String notes) {
    }

    /**
     * A manual price observation queued offline — history only.
     *
     * <p>There is no way to express "capture the current option" or "update the
     * linked option" here. Both depend on server state at replay time, which is
     * not the state the user was looking at when they typed the observation.
     */
    public record SnapshotPayload(
            /** Required on CREATE; the item the observation belongs to. */
            ResourceTarget item,

            /** Optional: ties the observation to one of the item's options. */
            ResourceTarget purchaseOption,

            @NotBlank(message = "Informe a loja ou vendedor.")
            @Size(max = 150, message = "O nome da loja pode ter no máximo 150 caracteres.")
            String merchant,

            @NotNull(message = "Informe a forma de pagamento.")
            PurchaseOptionKind paymentKind,

            @NotNull(message = "Informe o preço.")
            @Positive(message = "O preço deve ser maior que zero.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal basePrice,

            @PositiveOrZero(message = "O frete não pode ser negativo.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal shipping,

            @PositiveOrZero(message = "As taxas não podem ser negativas.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal fees,

            @Min(value = 1, message = "O número de parcelas deve ser pelo menos 1.")
            @Max(value = 120, message = "O número de parcelas deve ser no máximo 120.")
            Integer installmentCount,

            @Positive(message = "O valor da parcela deve ser maior que zero.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal installmentAmount,

            @NotNull(message = "Informe a data da observação.")
            LocalDate observedOn,

            @Size(max = 2000, message = "O endereço pode ter no máximo 2000 caracteres.")
            String offerUrl,

            @Size(max = 2000, message = "As observações podem ter no máximo 2000 caracteres.")
            String notes) {
    }
}
