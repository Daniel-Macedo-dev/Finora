package com.finora.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.settings.SettingsRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

class AuthFlowTest extends AbstractIntegrationTest {

    @Autowired
    private SettingsRepository settingsRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /* ---------- registration ---------- */

    @Test
    void registrationCreatesUserWithDefaultsAndAuthenticatedSession() throws Exception {
        TestUser user = registerUser("Fulana de Tal");

        // Authenticated session established by registration.
        mockMvc.perform(get("/api/auth/me").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Fulana de Tal"))
                .andExpect(jsonPath("$.email").value(user.email()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        // Per-user default categories and settings created atomically.
        assertThat(categoryRepository.findAllByUserIdOrderByTypeAscNameAsc(user.id()))
                .hasSize(DefaultCategoryCatalog.DEFAULTS.size());
        assertThat(settingsRepository.findByUserId(user.id())).isPresent();

        // Password is stored as an adaptive one-way hash, never plaintext.
        User entity = userRepository.findById(user.id()).orElseThrow();
        assertThat(entity.getPasswordHash()).startsWith("$2").doesNotContain(user.password());
    }

    @Test
    void registrationNormalizesEmailAndRejectsCaseVariantDuplicates() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "Alguém", "email": "  Pessoa@Email.COM ",
                                 "password": "senha-segura-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("pessoa@email.com"));

        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "Impostor", "email": "PESSOA@email.com",
                                 "password": "outra-senha-1"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void registrationRejectsShortPassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "Curta", "email": "curta@email.com", "password": "1234567"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    /* ---------- login / logout ---------- */

    @Test
    void loginWithValidCredentialsEstablishesSession() throws Exception {
        TestUser user = registerUser();

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(user.email().toUpperCase(), user.password())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.email()))
                .andReturn();

        Cookie session = sessionCookieFrom(login);
        mockMvc.perform(get("/api/auth/me").cookie(session))
                .andExpect(status().isOk());
    }

    @Test
    void loginFailuresAreGenericForWrongPasswordAndUnknownEmail() throws Exception {
        TestUser user = registerUser();

        String wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "senha-errada-1"}
                                """.formatted(user.email())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        String unknownEmail = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "ninguem@email.com", "password": "qualquer-senha-1"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // No user-existence disclosure: byte-identical bodies.
        assertThat(wrongPassword).isEqualTo(unknownEmail);
    }

    @Test
    void logoutInvalidatesTheSessionForLaterRequests() throws Exception {
        TestUser user = registerUser();

        mockMvc.perform(post("/api/auth/logout").cookie(user.session()).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").cookie(user.session()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    }

    /* ---------- CSRF ---------- */

    @Test
    void unsafeRequestWithoutCsrfTokenIsRejected() throws Exception {
        TestUser user = registerUser();
        mockMvc.perform(post("/api/goals")
                        .cookie(user.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Meta", "targetAmount": 100.00}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    /** CookieCsrfTokenRepository writes via Set-Cookie header, not addCookie(). */
    private Cookie xsrfCookieFrom(MvcResult result) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith("XSRF-TOKEN=")) {
                String pair = header.split(";", 2)[0];
                int eq = pair.indexOf('=');
                return new Cookie(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        throw new IllegalStateException("resposta não emitiu o cookie XSRF-TOKEN; status="
                + result.getResponse().getStatus()
                + " headers=" + result.getResponse().getHeaderNames()
                + " setCookies=" + result.getResponse().getHeaders("Set-Cookie"));
    }

    @Test
    void csrfBootstrapIssuesTokenCookieUsableAsHeader() throws Exception {
        // Real double-submit lifecycle without the csrf() test post-processor.
        MvcResult bootstrap = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn();
        Cookie xsrf = xsrfCookieFrom(bootstrap);
        assertThat(xsrf).isNotNull();

        mockMvc.perform(post("/api/auth/register")
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "Via Cookie", "email": "cookie@email.com",
                                 "password": "senha-segura-1"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void invalidCsrfHeaderIsRejected() throws Exception {
        MvcResult bootstrap = mockMvc.perform(get("/api/auth/csrf")).andReturn();
        Cookie xsrf = xsrfCookieFrom(bootstrap);
        assertThat(xsrf).isNotNull();

        mockMvc.perform(post("/api/auth/register")
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", "token-forjado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "X", "email": "x@email.com", "password": "senha-segura-1"}
                                """))
                .andExpect(status().isForbidden());
    }

    /* ---------- profile / password ---------- */

    @Test
    void updatesDisplayName() throws Exception {
        TestUser user = registerUser();
        mockMvc.perform(put("/api/profile")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "Novo Nome"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Novo Nome"));
    }

    @Test
    void changesPasswordAndInvalidatesOldCredential() throws Exception {
        TestUser user = registerUser();

        mockMvc.perform(post("/api/profile/password")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "%s", "newPassword": "senha-nova-9"}
                                """.formatted(user.password())))
                .andExpect(status().isOk());

        // Old password no longer authenticates; the new one does.
        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(user.email(), user.password())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "senha-nova-9"}
                                """.formatted(user.email())))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsPasswordChangeWithWrongCurrentPassword() throws Exception {
        TestUser user = registerUser();
        mockMvc.perform(post("/api/profile/password")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "senha-errada-1", "newPassword": "senha-nova-9"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"));
    }

    @Test
    void passwordChangeInvalidatesOtherSessionsButKeepsCurrent() throws Exception {
        TestUser user = registerUser();

        // Open a second session by logging in again.
        MvcResult secondLogin = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(user.email(), user.password())))
                .andExpect(status().isOk())
                .andReturn();
        Cookie secondSession = sessionCookieFrom(secondLogin);

        // Change password from the FIRST session.
        mockMvc.perform(post("/api/profile/password")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "%s", "newPassword": "senha-nova-9"}
                                """.formatted(user.password())))
                .andExpect(status().isOk());

        // The other session was invalidated; the current one survives.
        mockMvc.perform(get("/api/auth/me").cookie(secondSession))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").cookie(user.session()))
                .andExpect(status().isOk());
    }

    /* ---------- session persistence ---------- */

    @Test
    void sessionsArePersistedInTheJdbcStore() throws Exception {
        TestUser user = registerUser();
        // The authenticated session must exist as a SPRING_SESSION row indexed
        // by the principal (proves the JDBC store, not in-memory sessions).
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from spring_session where principal_name = ?",
                Integer.class, user.email());
        assertThat(rows).isNotNull().isGreaterThanOrEqualTo(1);
    }
}
