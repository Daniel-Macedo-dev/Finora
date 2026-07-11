package com.finora.api.identity;

import com.finora.api.common.error.BusinessRuleException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time, environment-gated transfer of pre-multiuser (v1) data.
 *
 * <p>Migration V4 assigns any pre-existing financial data to a
 * {@link UserStatus#PENDING_LEGACY_CLAIM} placeholder that cannot log in.
 * Claiming converts that placeholder into a real account — the financial rows
 * already point at its id, so the transfer is a single transactional identity
 * update with no per-row rewrites and no partial states.
 *
 * <p>The flow only works while BOTH conditions hold: a pending legacy user
 * exists AND the operator configured {@code finora.legacy-claim.token}
 * (never committed; see .env.example). After a successful claim the pending
 * user no longer exists, so the endpoint permanently reports unavailability
 * even if the token stays configured.
 */
@Service
public class LegacyClaimService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String configuredToken;

    public LegacyClaimService(UserRepository users,
                              PasswordEncoder passwordEncoder,
                              @Value("${finora.legacy-claim.token:}") String configuredToken) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.configuredToken = configuredToken;
    }

    @Transactional(readOnly = true)
    public boolean claimAvailable() {
        return !configuredToken.isBlank()
                && users.findFirstByStatus(UserStatus.PENDING_LEGACY_CLAIM).isPresent();
    }

    @Transactional
    public User claim(String token, String displayName, String email, String rawPassword) {
        if (configuredToken.isBlank()) {
            throw new BusinessRuleException("LEGACY_CLAIM_UNAVAILABLE",
                    "A migração de dados anteriores não está habilitada.");
        }
        User pending = users.findFirstByStatus(UserStatus.PENDING_LEGACY_CLAIM)
                .orElseThrow(() -> new BusinessRuleException("LEGACY_CLAIM_UNAVAILABLE",
                        "Não há dados anteriores aguardando migração."));
        if (!MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessRuleException("LEGACY_CLAIM_INVALID",
                    "Código de migração inválido.");
        }
        String normalizedEmail = User.normalizeEmail(email);
        if (users.existsByEmail(normalizedEmail)) {
            throw new BusinessRuleException("EMAIL_ALREADY_REGISTERED",
                    "Este e-mail já possui uma conta.");
        }
        pending.setDisplayName(displayName.trim());
        pending.setEmail(normalizedEmail);
        pending.setPasswordHash(passwordEncoder.encode(rawPassword));
        pending.setStatus(UserStatus.ACTIVE);
        return pending;
    }
}
