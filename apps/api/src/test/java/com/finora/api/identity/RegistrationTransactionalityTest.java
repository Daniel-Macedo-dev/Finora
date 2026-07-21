package com.finora.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.finora.api.TestcontainersConfiguration;
import com.finora.api.category.CategoryRepository;
import com.finora.api.settings.AppSettings;
import com.finora.api.settings.SettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Registration must be atomic: if per-user initialization (default categories
 * or settings) fails, no partially initialized user may remain.
 *
 * <p>Unlike the other integration tests this one is NOT wrapped in a
 * rollback-only test transaction — proving rollback requires registration's
 * own transaction to actually commit or roll back, then a fresh query.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "finora.security.bcrypt-strength=4",
        "finora.notifications.auto-sync.enabled=false"
})
class RegistrationTransactionalityTest {

    private static final String EMAIL = "rollback@email.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private CategoryRepository categories;

    @MockitoSpyBean
    private SettingsRepository settingsRepository;

    @AfterEach
    void cleanup() {
        users.findByEmail(EMAIL).ifPresent(user -> {
            categories.deleteAll(categories.findAllByUserIdOrderByTypeAscNameAsc(user.getId()));
            users.delete(user);
        });
    }

    @Test
    void failedSettingsInitializationRollsBackTheWholeRegistration() {
        // Force the last step of initialization to fail.
        doThrow(new RuntimeException("falha simulada ao criar settings"))
                .when(settingsRepository).save(any(AppSettings.class));

        // MockMvc rethrows the unhandled RuntimeException; the point is what
        // persists afterwards.
        assertThatThrownBy(() -> mockMvc.perform(post("/api/auth/register")
                .with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"displayName": "Vai Falhar", "email": "%s", "password": "senha-segura-1"}
                        """.formatted(EMAIL))))
                .hasRootCauseMessage("falha simulada ao criar settings");

        // The whole registration transaction rolled back: no user, no categories.
        assertThat(users.existsByEmail(EMAIL)).isFalse();
    }
}
