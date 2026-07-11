package com.finora.api.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "Informe seu nome.")
            @Size(max = 100, message = "O nome pode ter no máximo 100 caracteres.")
            String displayName,

            @NotBlank(message = "Informe o e-mail.")
            @Email(message = "Informe um e-mail válido.")
            @Size(max = 255, message = "O e-mail pode ter no máximo 255 caracteres.")
            String email,

            // 72 bytes is the BCrypt input limit.
            @NotBlank(message = "Informe a senha.")
            @Size(min = 8, max = 72, message = "A senha deve ter entre 8 e 72 caracteres.")
            String password) {

        public RegisterRequest {
            // Surrounding whitespace is user noise, not identity — trim before validation.
            email = email != null ? email.trim() : null;
        }
    }

    public record LoginRequest(
            @NotBlank(message = "Informe o e-mail.")
            String email,

            @NotBlank(message = "Informe a senha.")
            String password) {
    }

    public record ClaimLegacyRequest(
            @NotBlank(message = "Informe o código de migração.")
            String token,

            @NotBlank(message = "Informe seu nome.")
            @Size(max = 100, message = "O nome pode ter no máximo 100 caracteres.")
            String displayName,

            @NotBlank(message = "Informe o e-mail.")
            @Email(message = "Informe um e-mail válido.")
            @Size(max = 255, message = "O e-mail pode ter no máximo 255 caracteres.")
            String email,

            @NotBlank(message = "Informe a senha.")
            @Size(min = 8, max = 72, message = "A senha deve ter entre 8 e 72 caracteres.")
            String password) {

        public ClaimLegacyRequest {
            email = email != null ? email.trim() : null;
        }
    }

    public record UserResponse(
            Long id,
            String displayName,
            String email,
            Instant createdAt) {

        public static UserResponse from(User user) {
            return new UserResponse(
                    user.getId(),
                    user.getDisplayName(),
                    user.getEmail(),
                    user.getCreatedAt());
        }
    }
}
