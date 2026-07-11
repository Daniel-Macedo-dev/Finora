package com.finora.api.identity;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Principal stored in the security context: carries the user id so services
 * never need to re-query identity by email. Serializable because it lives in
 * the JDBC-backed session.
 */
public class AuthenticatedUser implements UserDetails {

    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final boolean enabled;

    public AuthenticatedUser(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.enabled = user.getStatus() == UserStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
