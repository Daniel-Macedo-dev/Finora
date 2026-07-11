package com.finora.api.identity;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Single seam between Spring Security and the financial domains. Every
 * service resolves data ownership exclusively through this provider — never
 * from request parameters or bodies. Fails closed when no authenticated user
 * is present.
 */
@Component
public class CurrentUserProvider {

    public Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser principal) {
            return principal.getId();
        }
        throw new AuthenticationCredentialsNotFoundException("Nenhum usuário autenticado.");
    }
}
