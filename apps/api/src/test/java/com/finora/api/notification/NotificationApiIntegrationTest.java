package com.finora.api.notification;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationApiIntegrationTest extends AbstractIntegrationTest {

    @Test
    void synchronizesAndAppliesOwnerScopedLifecycleActionsIdempotently() throws Exception {
        TestUser owner = registerUser("Dona");
        long categoryId = categoryId(owner, "Assinaturas", CategoryType.EXPENSE);
        LocalDate today = LocalDate.now();
        var account = mockMvc.perform(post("/api/accounts").cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Conta","type":"CHECKING","openingBalance":1000}
                                """))
                .andExpect(status().isCreated()).andReturn();
        long accountId = objectMapper.readTree(account.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("id").asLong();
        mockMvc.perform(post("/api/commitments").cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Internet","amount":120,"categoryId":%d,
                                 "cadence":"MONTHLY","dueDay":%d,"startDate":"%s",
                                 "targetKind":"ACCOUNT_TRANSACTION","accountId":%d}
                                """.formatted(categoryId, today.minusDays(2).getDayOfMonth(),
                                today.minusMonths(2), accountId)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/notifications/sync").cookie(owner.session()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/notifications/sync").cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));
        mockMvc.perform(post("/api/notifications/sync").cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0));

        var listing = mockMvc.perform(get("/api/notifications?filter=ACTIVE")
                        .cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sourceKey").value(
                        org.hamcrest.Matchers.startsWith("COMMITMENT:")))
                .andReturn();
        long id = objectMapper.readTree(listing.getResponse()
                        .getContentAsString(StandardCharsets.UTF_8))
                .get("content").get(0).get("id").asLong();

        mockMvc.perform(get("/api/notifications/unread-count").cookie(owner.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
        mockMvc.perform(post("/api/notifications/{id}/read", id)
                        .cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(false));
        mockMvc.perform(post("/api/notifications/{id}/read", id)
                        .cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(false));
        mockMvc.perform(post("/api/notifications/{id}/dismiss", id)
                        .cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dismissed").value(true));
        mockMvc.perform(post("/api/notifications/{id}/restore", id)
                        .cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dismissed").value(false));

        TestUser attacker = registerUser("Outra");
        mockMvc.perform(post("/api/notifications/{id}/read", id)
                        .cookie(attacker.session()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void preferencesValidateAndBrowserClaimsRespectPrivacyAndBaseline() throws Exception {
        TestUser user = registerUser();
        mockMvc.perform(get("/api/notification-preferences").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.browserEnabled").value(false))
                .andExpect(jsonPath("$.browserShowAmounts").value(false));
        mockMvc.perform(put("/api/notification-preferences").cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"enabled":true,"upcomingLeadDays":30,
                                 "recurringDueEnabled":true,"invoiceDueEnabled":true,
                                 "executionFailureEnabled":true,"cashRiskEnabled":true,
                                 "browserEnabled":false,"browserMinimumSeverity":"WARNING",
                                 "browserShowAmounts":false}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/notifications/browser-claims")
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
}
