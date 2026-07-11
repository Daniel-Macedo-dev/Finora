package com.finora.api.identity;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinoraUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public FinoraUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        return users.findByEmail(User.normalizeEmail(email))
                .map(AuthenticatedUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("Credenciais inválidas."));
    }
}
