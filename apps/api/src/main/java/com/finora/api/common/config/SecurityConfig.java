package com.finora.api.common.config;

import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Session-based security for the Finora SPA.
 *
 * <ul>
 *   <li>Server-side sessions (persisted by Spring Session JDBC) identified by
 *       an HttpOnly cookie — no token ever reaches JavaScript storage.</li>
 *   <li>CSRF double-submit cookie: the token travels in a non-HttpOnly
 *       {@code XSRF-TOKEN} cookie and must come back in the
 *       {@code X-XSRF-TOKEN} header for unsafe methods (the supported Spring
 *       Security SPA integration: BREACH-protected rendering with plain
 *       header comparison).</li>
 *   <li>Public surface: register, login, legacy claim and CSRF bootstrap.
 *       Everything else under /api requires authentication and returns a 401
 *       Problem Details body instead of a redirect.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .cors(cors -> {
                })
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/claim-legacy").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(SecurityConfig::writeUnauthenticatedProblem)
                        .accessDeniedHandler(SecurityConfig::writeAccessDeniedProblem))
                .sessionManagement(sessions -> sessions
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .logout(logout -> logout.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .requestCache(cache -> cache.disable());
        return http.build();
    }

    private static void writeUnauthenticatedProblem(HttpServletRequest request,
                                                    HttpServletResponse response,
                                                    org.springframework.security.core.AuthenticationException exception)
            throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {"type":"https://finora.app/errors/unauthenticated",\
                "title":"Não autenticado","status":401,\
                "detail":"Faça login para continuar.","code":"AUTH_UNAUTHENTICATED"}""");
    }

    private static void writeAccessDeniedProblem(HttpServletRequest request,
                                                 HttpServletResponse response,
                                                 org.springframework.security.access.AccessDeniedException exception)
            throws java.io.IOException {
        boolean csrf = exception instanceof org.springframework.security.web.csrf.CsrfException;
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(csrf
                ? """
                {"type":"https://finora.app/errors/csrf","title":"Requisição rejeitada",\
                "status":403,"detail":"Token de proteção CSRF ausente ou inválido.",\
                "code":"CSRF_INVALID"}"""
                : """
                {"type":"https://finora.app/errors/forbidden","title":"Acesso negado",\
                "status":403,"detail":"Você não tem permissão para esta operação.",\
                "code":"ACCESS_DENIED"}""");
    }

    /**
     * Supported SPA handler: renders the token with BREACH protection (Xor)
     * while accepting the plain cookie value from the {@code X-XSRF-TOKEN}
     * header, and resolves the token eagerly so the cookie is always issued.
     */
    static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           Supplier<CsrfToken> csrfToken) {
            this.xor.handle(request, response, csrfToken);
            // Force generation so the XSRF-TOKEN cookie is written on the response.
            csrfToken.get();
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            String headerValue = request.getHeader(csrfToken.getHeaderName());
            return (org.springframework.util.StringUtils.hasText(headerValue) ? this.plain : this.xor)
                    .resolveCsrfTokenValue(request, csrfToken);
        }
    }

    @Bean
    PasswordEncoder passwordEncoder(
            @Value("${finora.security.bcrypt-strength:10}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * Deliberate session-cookie configuration for Spring Session (Boot's
     * server.servlet.session.cookie.* applies to container sessions, not to
     * Spring Session's serializer, so we configure it explicitly).
     */
    @Bean
    org.springframework.session.web.http.CookieSerializer cookieSerializer(
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        var serializer = new org.springframework.session.web.http.DefaultCookieSerializer();
        serializer.setCookieName("FINORA_SESSION");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseSecureCookie(secure);
        serializer.setCookiePath("/");
        return serializer;
    }
}
