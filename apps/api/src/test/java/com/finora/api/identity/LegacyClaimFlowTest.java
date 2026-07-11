package com.finora.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.account.AccountType;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "finora.legacy-claim.token=token-de-teste-9f2a")
class LegacyClaimFlowTest extends AbstractIntegrationTest {

    @Autowired
    private AccountRepository accounts;

    private User pending;

    @BeforeEach
    void createPendingLegacyOwnerWithData() {
        // Simulates the post-V4 state of a database that had v1 data.
        pending = userRepository.save(new User(
                "Dados anteriores do Finora", "legacy@finora.local", "unclaimable",
                UserStatus.PENDING_LEGACY_CLAIM));
        accounts.save(new Account(pending.getId(), "Conta antiga", AccountType.CHECKING,
                new BigDecimal("2500.00"), 0));
    }

    @Test
    void pendingLegacyUserCannotAuthenticate() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "legacy@finora.local", "password": "unclaimable"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void claimWithValidTokenTransfersIdentityAndKeepsData() throws Exception {
        var claim = mockMvc.perform(post("/api/auth/claim-legacy")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "token-de-teste-9f2a", "displayName": "Dono Real",
                                 "email": "dono@email.com", "password": "senha-segura-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Dono Real"))
                .andExpect(jsonPath("$.email").value("dono@email.com"))
                .andReturn();

        // Same identity id: financial rows keep pointing at it with no rewrites.
        User claimed = userRepository.findById(pending.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(accounts.findAllByUserIdOrderByDisplayOrderAscNameAsc(pending.getId()))
                .extracting(Account::getName)
                .containsExactly("Conta antiga");

        // Claim establishes an authenticated session that sees the old data.
        var session = sessionCookieFrom(claim);
        mockMvc.perform(get("/api/accounts").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Conta antiga"));

        // The flow is one-time: no pending user remains.
        mockMvc.perform(post("/api/auth/claim-legacy")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "token-de-teste-9f2a", "displayName": "Outro",
                                 "email": "outro@email.com", "password": "senha-segura-1"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LEGACY_CLAIM_UNAVAILABLE"));
    }

    @Test
    void claimWithWrongTokenIsRejectedAndTransfersNothing() throws Exception {
        mockMvc.perform(post("/api/auth/claim-legacy")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "token-errado", "displayName": "Invasor",
                                 "email": "invasor@email.com", "password": "senha-segura-1"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LEGACY_CLAIM_INVALID"));

        assertThat(userRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.PENDING_LEGACY_CLAIM);
    }

    @Test
    void ordinaryRegistrationDoesNotInheritLegacyData() throws Exception {
        // "First to register wins the old data" must NOT be the behavior.
        TestUser newcomer = registerUser("Registrante Comum");
        mockMvc.perform(get("/api/accounts").cookie(newcomer.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
