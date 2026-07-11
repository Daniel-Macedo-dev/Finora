package com.finora.api.identity;

import com.finora.api.common.error.BusinessRuleException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProfileService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserProvider currentUser;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public ProfileService(UserRepository users,
                          PasswordEncoder passwordEncoder,
                          CurrentUserProvider currentUser,
                          FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
        this.sessions = sessions;
    }

    public User updateDisplayName(String displayName) {
        User user = current();
        user.setDisplayName(displayName.trim());
        return user;
    }

    /**
     * Changes the password after verifying the current one. Every other
     * session of this user is invalidated through the JDBC session store; only
     * the session performing the change stays authenticated.
     */
    public User changePassword(String currentPassword, String newPassword, String currentSessionId) {
        User user = current();
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessRuleException("CURRENT_PASSWORD_INVALID", "Senha atual incorreta.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        sessions.findByPrincipalName(user.getEmail()).keySet().stream()
                .filter(sessionId -> !sessionId.equals(currentSessionId))
                .forEach(sessions::deleteById);
        return user;
    }

    private User current() {
        return users.findById(currentUser.currentUserId()).orElseThrow();
    }
}
