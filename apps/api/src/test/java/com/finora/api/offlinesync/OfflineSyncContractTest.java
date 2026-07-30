package com.finora.api.offlinesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.category.CategoryType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** The shape of the endpoint itself: what it accepts, refuses and bounds. */
class OfflineSyncContractTest extends OfflineSyncTestSupport {

    private TestUser user;
    private Long foodCategory;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        foodCategory = categoryId(user, "Alimentação", CategoryType.EXPENSE);
    }

    @Test
    void rejectsUnauthenticatedReplay() throws Exception {
        mockMvc.perform(post(SYNC_PATH).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mutations\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsReplayWithoutCsrf() throws Exception {
        mockMvc.perform(post(SYNC_PATH).cookie(user.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mutations\":[]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsEmptyAndOversizedBatchesBeforeProcessingAnything() throws Exception {
        sync(user).andExpect(status().isBadRequest());

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= OfflineSyncDtos.MAX_BATCH_SIZE; i++) {
            tooMany.add(create(UUID.randomUUID(), "TRANSACTION", UUID.randomUUID(),
                    transactionPayload(foodCategory, "10.00", "Item " + i)));
        }
        sync(user, tooMany.toArray(String[]::new))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAPayloadLargerThanTheAllowedCeiling() throws Exception {
        String huge = "x".repeat(OfflineSyncDtos.MAX_PAYLOAD_BYTES + 1);
        sync(user, create(UUID.randomUUID(), "TRANSACTION", UUID.randomUUID(),
                """
                {"type":"EXPENSE","amount":10.00,"description":"Grande","date":"2026-07-15",
                 "categoryId":%d,"notes":"%s"}
                """.formatted(foodCategory, huge)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SYNC_PAYLOAD_TOO_LARGE"));
    }

    @Test
    void refusesUnknownResourceTypesAndOperationsAtTheWire() throws Exception {
        // A resource outside the allowlist is not a handler that happens to be
        // missing — the enum simply cannot deserialize it.
        sync(user, """
                {"clientMutationId":"%s","resourceType":"CREDIT_CARD","operation":"CREATE",
                 "target":{"clientResourceId":"%s"},"payload":{}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isBadRequest());

        sync(user, """
                {"clientMutationId":"%s","resourceType":"TRANSACTION","operation":"UPSERT",
                 "target":{"serverId":1},"baseVersion":0,"payload":{}}
                """.formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest());

        sync(user, """
                {"clientMutationId":"not-a-uuid","resourceType":"TRANSACTION",
                 "operation":"CREATE","target":{"clientResourceId":"%s"},"payload":{}}
                """.formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesAmbiguousMissingAndContradictoryTargets() throws Exception {
        long existing = createTransactionOnline(user, foodCategory, "10.00", "Base");

        JsonNode both = syncResults(user, """
                {"clientMutationId":"%s","resourceType":"TRANSACTION","operation":"UPDATE",
                 "target":{"serverId":%d,"clientResourceId":"%s"},"baseVersion":0,"payload":%s}
                """.formatted(UUID.randomUUID(), existing, UUID.randomUUID(),
                transactionPayload(foodCategory, "11.00", "Ambígua")));
        assertThat(both.get(0).get("status").stringValue()).isEqualTo("REJECTED");
        assertThat(both.get(0).get("error").get("code").stringValue())
                .isEqualTo("SYNC_TARGET_AMBIGUOUS");

        JsonNode neither = syncResults(user, """
                {"clientMutationId":"%s","resourceType":"TRANSACTION","operation":"UPDATE",
                 "target":{},"baseVersion":0,"payload":%s}
                """.formatted(UUID.randomUUID(),
                transactionPayload(foodCategory, "11.00", "Sem alvo")));
        assertThat(neither.get(0).get("error").get("code").stringValue())
                .isEqualTo("SYNC_TARGET_REQUIRED");

        JsonNode createWithServerId = syncResults(user, """
                {"clientMutationId":"%s","resourceType":"TRANSACTION","operation":"CREATE",
                 "target":{"serverId":%d},"payload":%s}
                """.formatted(UUID.randomUUID(), existing,
                transactionPayload(foodCategory, "11.00", "Criação com id")));
        assertThat(createWithServerId.get(0).get("error").get("code").stringValue())
                .isEqualTo("SYNC_CREATE_REQUIRES_CLIENT_ID");

        JsonNode createWithVersion = syncResults(user, """
                {"clientMutationId":"%s","resourceType":"TRANSACTION","operation":"CREATE",
                 "target":{"clientResourceId":"%s"},"baseVersion":3,"payload":%s}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(),
                transactionPayload(foodCategory, "11.00", "Criação versionada")));
        assertThat(createWithVersion.get(0).get("error").get("code").stringValue())
                .isEqualTo("SYNC_CREATE_WITH_BASE_VERSION");
    }

    @Test
    void requiresABaseVersionOnUpdateAndDelete() throws Exception {
        long existing = createTransactionOnline(user, foodCategory, "10.00", "Base");

        JsonNode update = syncResults(user, """
                {"clientMutationId":"%s","resourceType":"TRANSACTION","operation":"UPDATE",
                 "target":{"serverId":%d},"payload":%s}
                """.formatted(UUID.randomUUID(), existing,
                transactionPayload(foodCategory, "11.00", "Sem versão")));
        assertThat(update.get(0).get("error").get("code").stringValue())
                .isEqualTo("SYNC_BASE_VERSION_REQUIRED");

        JsonNode remove = syncResults(user, """
                {"clientMutationId":"%s","resourceType":"TRANSACTION","operation":"DELETE",
                 "target":{"serverId":%d},"payload":{}}
                """.formatted(UUID.randomUUID(), existing));
        assertThat(remove.get(0).get("error").get("code").stringValue())
                .isEqualTo("SYNC_BASE_VERSION_REQUIRED");
    }

    @Test
    void rejectsADeleteThatCarriesData() throws Exception {
        long existing = createTransactionOnline(user, foodCategory, "10.00", "Base");
        JsonNode results = syncResults(user, """
                {"clientMutationId":"%s","resourceType":"TRANSACTION","operation":"DELETE",
                 "target":{"serverId":%d},"baseVersion":0,"payload":{"amount":1}}
                """.formatted(UUID.randomUUID(), existing));
        assertThat(results.get(0).get("error").get("code").stringValue())
                .isEqualTo("SYNC_PAYLOAD_INVALID");
    }

    @Test
    void returnsOneResultPerInputInSubmissionOrderEvenWhenSomeFail() throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID badId = UUID.randomUUID();
        UUID lastId = UUID.randomUUID();

        JsonNode results = syncResults(user,
                create(firstId, "TRANSACTION", UUID.randomUUID(),
                        transactionPayload(foodCategory, "10.00", "Primeira")),
                // Negative amount: permanently invalid, must not stop its neighbours.
                create(badId, "TRANSACTION", UUID.randomUUID(),
                        transactionPayload(foodCategory, "-5.00", "Inválida")),
                create(lastId, "TRANSACTION", UUID.randomUUID(),
                        transactionPayload(foodCategory, "12.00", "Última")));

        assertThat(results).hasSize(3);
        List<String> ids = results.valueStream()
                .map(node -> node.get("clientMutationId").stringValue())
                .collect(Collectors.toList());
        assertThat(ids).containsExactly(firstId.toString(), badId.toString(), lastId.toString());

        assertThat(results.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        assertThat(results.get(1).get("status").stringValue()).isEqualTo("REJECTED");
        assertThat(results.get(2).get("status").stringValue()).isEqualTo("APPLIED");

        // The independent successes are really committed, not rolled back with
        // their failing neighbour.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/transactions").cookie(user.session())
                        .param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void reportsFieldLevelDetailForAnInvalidPayload() throws Exception {
        JsonNode results = syncResults(user, create(UUID.randomUUID(), "TRANSACTION",
                UUID.randomUUID(), """
                {"type":"EXPENSE","amount":-1,"description":"","date":"2026-07-15",
                 "categoryId":%d}
                """.formatted(foodCategory)));

        JsonNode error = results.get(0).get("error");
        assertThat(error.get("code").stringValue()).isEqualTo("SYNC_PAYLOAD_INVALID");
        assertThat(error.get("fieldErrors").valueStream()
                .map(node -> node.get("field").stringValue()).toList())
                .contains("amount", "description");
    }
}
