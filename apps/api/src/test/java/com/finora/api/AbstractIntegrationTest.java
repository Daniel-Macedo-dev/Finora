package com.finora.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.category.CategoryRepository;
import com.finora.api.category.CategoryType;
import com.finora.api.identity.AuthenticatedUser;
import com.finora.api.identity.User;
import com.finora.api.identity.UserRepository;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Base for API integration tests: full Spring context, MockMvc and a real
 * PostgreSQL via Testcontainers. Each test method rolls back its transaction.
 *
 * <p>Every financial endpoint now requires authentication, so tests create
 * real users through the public registration endpoint and keep the resulting
 * server-side session. Cheap BCrypt keeps registration fast in tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "finora.security.bcrypt-strength=4",
        // Deterministic tests drive recurring processing explicitly.
        "finora.recurring.auto-processing.enabled=false",
})
@Transactional
public abstract class AbstractIntegrationTest {

    private static final AtomicLong EMAIL_SEQUENCE = new AtomicLong();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected CategoryRepository categoryRepository;

    @Autowired
    protected UserRepository userRepository;

    /**
     * An authenticated user with their own server-side session (represented by
     * the FINORA_SESSION cookie, exactly as a browser would hold it) and their
     * own default categories.
     */
    public record TestUser(Long id, String email, String password, Cookie session) {
    }

    private Cookie xsrf;

    /**
     * Bootstraps a real CSRF token through the public endpoint — tests use the
     * genuine cookie + header double-submit flow, never the spring-security-test
     * csrf() post-processor (which swaps the shared CsrfFilter repository and
     * contaminates cookie-based CSRF behavior for the whole context).
     */
    @BeforeEach
    void bootstrapCsrfToken() throws Exception {
        MvcResult bootstrap = mockMvc.perform(get("/api/auth/csrf")).andReturn();
        for (String header : bootstrap.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith("XSRF-TOKEN=")) {
                String pair = header.split(";", 2)[0];
                int eq = pair.indexOf('=');
                xsrf = new Cookie(pair.substring(0, eq), pair.substring(eq + 1));
                return;
            }
        }
        throw new IllegalStateException("bootstrap não emitiu o cookie XSRF-TOKEN");
    }

    /** Attaches the real CSRF cookie + header pair to an unsafe request. */
    protected RequestPostProcessor csrf() {
        return request -> {
            Cookie[] existing = request.getCookies();
            if (existing == null) {
                request.setCookies(xsrf);
            } else {
                Cookie[] merged = new Cookie[existing.length + 1];
                System.arraycopy(existing, 0, merged, 0, existing.length);
                merged[existing.length] = xsrf;
                request.setCookies(merged);
            }
            request.addHeader("X-XSRF-TOKEN", xsrf.getValue());
            return request;
        };
    }

    protected Cookie currentXsrfCookie() {
        return xsrf;
    }

    protected TestUser registerUser() throws Exception {
        return registerUser("Usuário Teste");
    }

    protected TestUser registerUser(String displayName) throws Exception {
        String email = "user%d@finora.test".formatted(EMAIL_SEQUENCE.incrementAndGet());
        String password = "senha-secreta-123";
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "%s", "email": "%s", "password": "%s"}
                                """.formatted(displayName, email, password)))
                .andExpect(status().isCreated())
                .andReturn();
        long id = objectMapper
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
        return new TestUser(id, email, password, sessionCookieFrom(result));
    }

    /**
     * Spring Session writes the session cookie as a raw Set-Cookie header, so
     * it must be parsed from headers rather than read via getCookie().
     */
    protected Cookie sessionCookieFrom(MvcResult result) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith("FINORA_SESSION=") || header.startsWith("SESSION=")) {
                String pair = header.split(";", 2)[0];
                int eq = pair.indexOf('=');
                return new Cookie(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        throw new IllegalStateException(
                "resposta não emitiu o cookie de sessão; headers: "
                        + result.getResponse().getHeaders("Set-Cookie"));
    }

    /** Resolves one of the given user's own categories by name and type. */
    protected Long categoryId(TestUser user, String name, CategoryType type) {
        return categoryRepository.findByUserIdAndNameIgnoreCaseAndType(user.id(), name, type)
                .orElseThrow()
                .getId();
    }

    /**
     * Puts the user into the thread's SecurityContext for tests that call
     * services directly instead of going through MockMvc.
     */
    protected void actAs(TestUser user) {
        User entity = userRepository.findById(user.id()).orElseThrow();
        AuthenticatedUser principal = new AuthenticatedUser(entity);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }
}
