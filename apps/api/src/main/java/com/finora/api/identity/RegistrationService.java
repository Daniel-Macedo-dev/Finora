package com.finora.api.identity;

import com.finora.api.category.Category;
import com.finora.api.category.CategoryRepository;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.settings.AppSettings;
import com.finora.api.settings.SettingsRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a fully initialized user atomically: identity row, per-user default
 * categories and per-user settings. If any step fails the whole registration
 * rolls back — no partially initialized user can exist.
 */
@Service
public class RegistrationService {

    private final UserRepository users;
    private final CategoryRepository categories;
    private final SettingsRepository settings;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(UserRepository users,
                               CategoryRepository categories,
                               SettingsRepository settings,
                               PasswordEncoder passwordEncoder) {
        this.users = users;
        this.categories = categories;
        this.settings = settings;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(String displayName, String email, String rawPassword) {
        String normalizedEmail = User.normalizeEmail(email);
        if (users.existsByEmail(normalizedEmail)) {
            throw new BusinessRuleException("EMAIL_ALREADY_REGISTERED",
                    "Este e-mail já possui uma conta.");
        }
        User user = users.save(new User(
                displayName.trim(),
                normalizedEmail,
                passwordEncoder.encode(rawPassword),
                UserStatus.ACTIVE));
        initializeUserData(user.getId());
        return user;
    }

    /** Also used by the legacy claim flow when the pending owner lacks defaults. */
    void initializeUserData(Long userId) {
        for (DefaultCategoryCatalog.CategoryDefinition definition : DefaultCategoryCatalog.DEFAULTS) {
            categories.save(new Category(userId, definition.name(), definition.type(), true));
        }
        settings.save(AppSettings.withDefaults(userId));
    }
}
