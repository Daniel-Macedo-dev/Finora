package com.finora.api.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CategoryDtos {

    private CategoryDtos() {
    }

    public record CategoryRequest(
            @NotBlank(message = "Informe o nome da categoria.")
            @Size(max = 60, message = "O nome pode ter no máximo 60 caracteres.")
            String name,

            @NotNull(message = "Informe o tipo da categoria.")
            CategoryType type,

            Boolean active) {
    }

    public record CategoryResponse(
            Long id,
            String name,
            CategoryType type,
            boolean active,
            boolean isDefault) {

        public static CategoryResponse from(Category category) {
            return new CategoryResponse(
                    category.getId(),
                    category.getName(),
                    category.getType(),
                    category.isActive(),
                    category.isDefault());
        }
    }
}
