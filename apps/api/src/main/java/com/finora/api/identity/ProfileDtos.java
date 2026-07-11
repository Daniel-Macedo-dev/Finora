package com.finora.api.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ProfileDtos {

    private ProfileDtos() {
    }

    public record UpdateProfileRequest(
            @NotBlank(message = "Informe seu nome.")
            @Size(max = 100, message = "O nome pode ter no máximo 100 caracteres.")
            String displayName) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "Informe a senha atual.")
            String currentPassword,

            @NotBlank(message = "Informe a nova senha.")
            @Size(min = 8, max = 72, message = "A nova senha deve ter entre 8 e 72 caracteres.")
            String newPassword) {
    }
}
