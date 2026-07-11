package com.finora.api.identity;

import com.finora.api.identity.AuthDtos.ClaimLegacyRequest;
import com.finora.api.identity.AuthDtos.LoginRequest;
import com.finora.api.identity.AuthDtos.RegisterRequest;
import com.finora.api.identity.AuthDtos.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final RegistrationService registrationService;
    private final LegacyClaimService legacyClaimService;
    private final UserRepository users;
    private final CurrentUserProvider currentUser;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          RegistrationService registrationService,
                          LegacyClaimService legacyClaimService,
                          UserRepository users,
                          CurrentUserProvider currentUser) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.registrationService = registrationService;
        this.legacyClaimService = legacyClaimService;
        this.users = users;
        this.currentUser = currentUser;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest body,
                                                 HttpServletRequest request,
                                                 HttpServletResponse response) {
        User user = registrationService.register(body.displayName(), body.email(), body.password());
        establishSession(user, request, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @PostMapping("/login")
    public UserResponse login(@Valid @RequestBody LoginRequest body,
                              HttpServletRequest request,
                              HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        User.normalizeEmail(body.email()), body.password()));
        persistAuthentication(authentication, request, response);
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        User user = users.findById(principal.getId()).orElseThrow();
        return UserResponse.from(user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserResponse me() {
        return users.findById(currentUser.currentUserId())
                .map(UserResponse::from)
                .orElseThrow();
    }

    /**
     * CSRF bootstrap: forcing token resolution makes the security filter set
     * the XSRF-TOKEN cookie on this response.
     */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(CsrfToken token) {
        token.getToken();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/claim-legacy")
    public UserResponse claimLegacy(@Valid @RequestBody ClaimLegacyRequest body,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        User user = legacyClaimService.claim(
                body.token(), body.displayName(), body.email(), body.password());
        establishSession(user, request, response);
        return UserResponse.from(user);
    }

    /** Generic 401 for any credential failure: no user-existence disclosure. */
    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleAuthenticationFailure(AuthenticationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos.");
        problem.setTitle("Falha de autenticação");
        problem.setType(URI.create("https://finora.app/errors/invalid-credentials"));
        problem.setProperty("code", "AUTH_INVALID_CREDENTIALS");
        return problem;
    }

    private void establishSession(User user, HttpServletRequest request, HttpServletResponse response) {
        AuthenticatedUser principal = new AuthenticatedUser(user);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        persistAuthentication(authentication, request, response);
    }

    private void persistAuthentication(Authentication authentication,
                                       HttpServletRequest request,
                                       HttpServletResponse response) {
        // Session fixation protection for the programmatic login path.
        request.getSession(true);
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
