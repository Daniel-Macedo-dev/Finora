package com.finora.api.wishlist;

import com.finora.api.category.CategoryType;
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
import java.util.List;

public final class WishlistDtos {

    private WishlistDtos() {
    }

    public record WishlistItemRequest(
            @NotBlank(message = "Informe o nome do item.")
            @Size(max = 150, message = "O nome pode ter no máximo 150 caracteres.")
            String name,

            @Size(max = 2000, message = "As observações podem ter no máximo 2000 caracteres.")
            String notes,

            Long categoryId,

            @PositiveOrZero(message = "O preço de referência não pode ser negativo.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal referencePrice,

            @PositiveOrZero(message = "O preço alvo não pode ser negativo.")
            @Digits(integer = 12, fraction = 2, message = "Use no máximo 2 casas decimais.")
            BigDecimal targetPrice,

            @NotNull(message = "Informe a prioridade.")
            WishlistPriority priority,

            LocalDate desiredDate,

            WishlistStatus status) {
    }

    public record PurchaseOptionRequest(
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

            /** Card an INSTALLMENT option would be charged on; must belong to the owner. */
            Long creditCardId,

            @Size(max = 2000, message = "As observações podem ter no máximo 2000 caracteres.")
            String notes) {
    }

    public record WishlistCategory(Long id, String name, CategoryType type) {
    }

    public record PurchaseOptionResponse(
            Long id,
            String merchant,
            PurchaseOptionKind kind,
            BigDecimal basePrice,
            BigDecimal shipping,
            BigDecimal fees,
            BigDecimal nominalCost,
            Integer installmentCount,
            BigDecimal installmentAmount,
            Long creditCardId,
            String creditCardName,
            String notes) {

        public static PurchaseOptionResponse from(PurchaseOption option) {
            return new PurchaseOptionResponse(
                    option.getId(),
                    option.getMerchant(),
                    option.getKind(),
                    option.getBasePrice(),
                    option.getShipping(),
                    option.getFees(),
                    option.nominalCost(),
                    option.getInstallmentCount(),
                    option.getInstallmentAmount(),
                    option.getCreditCard() != null ? option.getCreditCard().getId() : null,
                    option.getCreditCard() != null ? option.getCreditCard().getName() : null,
                    option.getNotes());
        }
    }

    public record WishlistItemResponse(
            Long id,
            String name,
            String notes,
            WishlistCategory category,
            BigDecimal referencePrice,
            BigDecimal targetPrice,
            WishlistPriority priority,
            LocalDate desiredDate,
            WishlistStatus status,
            int optionCount,
            BigDecimal bestNominalCost,
            long priceObservationCount,
            BigDecimal latestObservedPrice,
            LocalDate latestObservedOn,
            BigDecimal historicalMinimum,
            Boolean targetReached) {
    }

    public record WishlistItemDetailResponse(
            Long id,
            String name,
            String notes,
            WishlistCategory category,
            BigDecimal referencePrice,
            BigDecimal targetPrice,
            WishlistPriority priority,
            LocalDate desiredDate,
            WishlistStatus status,
            List<PurchaseOptionResponse> options) {
    }
}
