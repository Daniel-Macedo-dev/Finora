package com.finora.api.commitment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.category.CategoryType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The legacy-credit mapping endpoint: a pre-automation CREDIT definition gains
 * a real card target with an automation horizon set to the mapping day — so
 * automatic processing never backfills historical occurrences — while
 * non-legacy definitions, archived cards and foreign resources are rejected.
 */
class LegacyCardMappingIntegrationTest extends AbstractIntegrationTest {

    private TestUser user;
    private Long expenseCategoryId;
    private long cardId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("Recorrente");
        expenseCategoryId = categoryId(user, "Assinaturas", CategoryType.EXPENSE);
        cardId = createCard(user, "Cartão Novo");
    }

    private long createCard(TestUser owner, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/credit-cards")
                        .cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "brand": "VISA", "creditLimit": 5000,
                                 "closingDay": 10, "dueDay": 17}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).get("id").asLong();
    }

    /**
     * A legacy CREDIT definition: paymentMethod CREDIT with the default
     * PROJECTION_ONLY target, started long before the mapping. The due day
     * avoids today so no occurrence is due exactly on the automation horizon.
     */
    private long createLegacyCreditCommitment() throws Exception {
        int dueDay = LocalDate.now().getDayOfMonth() == 1 ? 2 : 1;
        MvcResult result = mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Streaming antigo", "amount": 39.90,
                                 "categoryId": %d, "cadence": "MONTHLY", "dueDay": %d,
                                 "startDate": "2025-01-%02d", "paymentMethod": "CREDIT"}
                                """.formatted(expenseCategoryId, dueDay, dueDay)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.legacyProjectionOnly").value(true))
                .andReturn();
        return json(result).get("id").asLong();
    }

    private MappingRequest mapping(long commitmentId, long targetCardId) {
        return new MappingRequest(commitmentId, targetCardId);
    }

    /** Small builder so each test states only what varies. */
    private final class MappingRequest {
        private final long commitmentId;
        private final long targetCardId;

        private MappingRequest(long commitmentId, long targetCardId) {
            this.commitmentId = commitmentId;
            this.targetCardId = targetCardId;
        }

        org.springframework.test.web.servlet.ResultActions performAs(TestUser caller)
                throws Exception {
            return mockMvc.perform(
                    post("/api/commitments/%d/legacy-card-mapping".formatted(commitmentId))
                            .cookie(caller.session()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"creditCardId": %d, "installmentCount": 1,
                                     "executionMode": "AUTOMATIC"}
                                    """.formatted(targetCardId)));
        }
    }

    private tools.jackson.databind.JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void mapsLegacyDefinitionToRealCardWithoutHistoricalBackfill() throws Exception {
        long commitmentId = createLegacyCreditCommitment();

        mapping(commitmentId, cardId).performAs(user)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetKind").value("CREDIT_CARD_PURCHASE"))
                .andExpect(jsonPath("$.creditCardId").value(cardId))
                .andExpect(jsonPath("$.creditCardName").value("Cartão Novo"))
                .andExpect(jsonPath("$.executionMode").value("AUTOMATIC"))
                .andExpect(jsonPath("$.installmentCount").value(1))
                .andExpect(jsonPath("$.legacyProjectionOnly").value(false));

        // The automation horizon forbids backfill: a year of past occurrences
        // exists, yet automatic catch-up materializes none of them.
        mockMvc.perform(post("/api/commitments/process-due")
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materialized").value(0))
                .andExpect(jsonPath("$.failed").value(0));

        // No purchase was created on the card and no expense was recorded.
        mockMvc.perform(get("/api/credit-cards/%d/purchases".formatted(cardId))
                        .cookie(user.session()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void rejectsNonLegacyDefinitionsAndArchivedCards() throws Exception {
        // An ordinary PIX definition is not a legacy credit awaiting migration.
        MvcResult ordinary = mockMvc.perform(post("/api/commitments")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Assinatura comum", "amount": 29.90,
                                 "categoryId": %d, "cadence": "MONTHLY", "dueDay": 5,
                                 "startDate": "2025-01-05", "paymentMethod": "PIX"}
                                """.formatted(expenseCategoryId)))
                .andExpect(status().isCreated())
                .andReturn();
        mapping(json(ordinary).get("id").asLong(), cardId).performAs(user)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COMMITMENT_NOT_LEGACY_CREDIT"));

        long commitmentId = createLegacyCreditCommitment();
        long archivedCard = createCard(user, "Cartão Arquivado");
        mockMvc.perform(post("/api/credit-cards/%d/archive".formatted(archivedCard))
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk());
        mapping(commitmentId, archivedCard).performAs(user)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_ARCHIVED"));

        // Rejections change nothing: the definition still awaits migration.
        mockMvc.perform(get("/api/commitments").cookie(user.session()))
                .andExpect(jsonPath(
                        "$[?(@.id == %d)].legacyProjectionOnly".formatted(commitmentId))
                        .value(true));
    }

    @Test
    void foreignCommitmentsAndForeignCardsBehaveAsAbsent() throws Exception {
        long commitmentId = createLegacyCreditCommitment();
        TestUser other = registerUser("Vizinho");
        long foreignCard = createCard(other, "Cartão Alheio");

        // Another user cannot map my definition — even to their own card.
        mapping(commitmentId, foreignCard).performAs(other)
                .andExpect(status().isNotFound());

        // I cannot target another user's card.
        mapping(commitmentId, foreignCard).performAs(user)
                .andExpect(status().isNotFound());

        // The definition is untouched and still mappable by its owner.
        mapping(commitmentId, cardId).performAs(user)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetKind").value("CREDIT_CARD_PURCHASE"));
    }
}
