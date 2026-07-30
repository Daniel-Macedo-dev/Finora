package com.finora.api.offlinesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.category.CategoryType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The property the whole stage rests on: a replayed mutation must not produce a
 * second financial side effect.
 *
 * <p>The client cannot tell "never applied" from "applied, response lost" — so
 * it always retries with the same key, and the server has to be the one that
 * knows. These tests exercise that from the outside, through the real endpoint.
 */
class OfflineSyncIdempotencyTest extends OfflineSyncTestSupport {

    @Autowired
    private MutationReceiptRepository receipts;

    private TestUser user;
    private Long foodCategory;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        foodCategory = categoryId(user, "Alimentação", CategoryType.EXPENSE);
    }

    @Test
    void aLostResponseIsSafeToRetryWithTheSameKey() throws Exception {
        UUID mutationId = UUID.randomUUID();
        UUID clientResourceId = UUID.randomUUID();
        String envelope = create(mutationId, "TRANSACTION", clientResourceId,
                transactionPayload(foodCategory, "42.00", "Mercado"));

        JsonNode first = syncResults(user, envelope);
        assertThat(first.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        long resourceId = first.get(0).get("resourceId").asLong();

        // Exactly what a client does when the connection dropped mid-response.
        JsonNode retry = syncResults(user, envelope);
        assertThat(retry.get(0).get("status").stringValue()).isEqualTo("ALREADY_APPLIED");
        assertThat(retry.get(0).get("resourceId").asLong()).isEqualTo(resourceId);
        assertThat(retry.get(0).get("clientResourceId").stringValue())
                .isEqualTo(clientResourceId.toString());
        // The stored body is the original one, replayed verbatim.
        assertThat(retry.get(0).get("result").get("description").stringValue())
                .isEqualTo("Mercado");

        assertThat(receipts.findByUserIdAndClientMutationId(user.id(), mutationId)).isPresent();
        mockMvc.perform(get("/api/transactions").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void theSameKeyWithDifferentContentIsRefusedAndChangesNothing() throws Exception {
        UUID mutationId = UUID.randomUUID();
        UUID clientResourceId = UUID.randomUUID();

        syncResults(user, create(mutationId, "TRANSACTION", clientResourceId,
                transactionPayload(foodCategory, "42.00", "Mercado")));

        JsonNode reused = syncResults(user, create(mutationId, "TRANSACTION", clientResourceId,
                transactionPayload(foodCategory, "999.00", "Outro valor")));
        assertThat(reused.get(0).get("status").stringValue()).isEqualTo("CONFLICT");
        assertThat(reused.get(0).get("conflict").get("conflictType").stringValue())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");

        // Neither a second transaction nor an overwritten first one.
        mockMvc.perform(get("/api/transactions").cookie(user.session()).param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].amount").value(42.00));
        // The receipt itself was not rewritten.
        assertThat(receipts.findByUserIdAndClientMutationId(user.id(), mutationId).orElseThrow()
                .getResultCode()).isEqualTo("APPLIED");
    }

    @Test
    void formattingDifferencesAreNotContentDifferences() throws Exception {
        UUID mutationId = UUID.randomUUID();
        UUID clientResourceId = UUID.randomUUID();

        syncResults(user, create(mutationId, "TRANSACTION", clientResourceId,
                transactionPayload(foodCategory, "42.00", "Mercado")));

        // Same request: a trailing zero, untrimmed text and a different property
        // order are encoding accidents, not a different mutation.
        JsonNode retry = syncResults(user, """
                {"clientMutationId":"%s","resourceType":"TRANSACTION","operation":"CREATE",
                 "target":{"clientResourceId":"%s"},
                 "payload":{"categoryId":%d,"date":"2026-07-15","description":"  Mercado  ",
                            "amount":42.000,"type":"EXPENSE"}}
                """.formatted(mutationId, clientResourceId, foodCategory));
        assertThat(retry.get(0).get("status").stringValue()).isEqualTo("ALREADY_APPLIED");
    }

    @Test
    void twoOwnersMayUseTheSameKeyIndependently() throws Exception {
        TestUser other = registerUser("Outro dono");
        Long otherCategory = categoryId(other, "Alimentação", CategoryType.EXPENSE);
        UUID sharedMutationId = UUID.randomUUID();
        UUID sharedResourceId = UUID.randomUUID();

        JsonNode mine = syncResults(user, create(sharedMutationId, "TRANSACTION", sharedResourceId,
                transactionPayload(foodCategory, "10.00", "Minha")));
        JsonNode theirs = syncResults(other, create(sharedMutationId, "TRANSACTION",
                sharedResourceId, transactionPayload(otherCategory, "20.00", "Deles")));

        assertThat(mine.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        assertThat(theirs.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        assertThat(mine.get(0).get("resourceId").asLong())
                .isNotEqualTo(theirs.get(0).get("resourceId").asLong());

        assertThat(receipts.findByUserIdAndClientMutationId(user.id(), sharedMutationId))
                .isPresent();
        assertThat(receipts.findByUserIdAndClientMutationId(other.id(), sharedMutationId))
                .isPresent();
    }

    @Test
    void retryingADeleteDoesNotTouchAReplacementResource() throws Exception {
        long original = createTransactionOnline(user, foodCategory, "10.00", "Original");
        UUID mutationId = UUID.randomUUID();
        String envelope = deleteMutation(mutationId, "TRANSACTION", original, 0);

        assertThat(syncResults(user, envelope).get(0).get("status").stringValue())
                .isEqualTo("APPLIED");

        long replacement = createTransactionOnline(user, foodCategory, "77.00", "Substituta");

        // The retry answers from the receipt; it never re-resolves the target,
        // so a row that happens to occupy the same conceptual slot is untouched.
        JsonNode retry = syncResults(user, envelope);
        assertThat(retry.get(0).get("status").stringValue()).isEqualTo("ALREADY_APPLIED");

        mockMvc.perform(get("/api/transactions/{id}", replacement).cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Substituta"));
    }

    @Test
    void retryingAnUpdateDoesNotBumpTheVersionASecondTime() throws Exception {
        long id = createTransactionOnline(user, foodCategory, "10.00", "Original");
        UUID mutationId = UUID.randomUUID();
        String envelope = update(mutationId, "TRANSACTION", id, 0,
                transactionPayload(foodCategory, "15.00", "Editada"));

        JsonNode first = syncResults(user, envelope);
        assertThat(first.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        long versionAfterFirst = first.get(0).get("version").asLong();
        assertThat(versionAfterFirst).isEqualTo(1);

        JsonNode retry = syncResults(user, envelope);
        assertThat(retry.get(0).get("status").stringValue()).isEqualTo("ALREADY_APPLIED");
        assertThat(retry.get(0).get("version").asLong()).isEqualTo(versionAfterFirst);

        mockMvc.perform(get("/api/transactions/{id}", id).cookie(user.session()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.amount").value(15.00));
    }

    @Test
    void aRejectedMutationLeavesNoReceiptAndMayBeRetriedOnceTheBlockClears() throws Exception {
        UUID mutationId = UUID.randomUUID();
        UUID clientResourceId = UUID.randomUUID();
        long foreignCategory = categoryId(registerUser("Estranho"), "Alimentação",
                CategoryType.EXPENSE);

        // Another owner's category behaves as absent, so this is a rejection.
        JsonNode rejected = syncResults(user, create(mutationId, "TRANSACTION", clientResourceId,
                transactionPayload(foreignCategory, "10.00", "Categoria alheia")));
        assertThat(rejected.get(0).get("status").stringValue()).isEqualTo("REJECTED");
        assertThat(receipts.findByUserIdAndClientMutationId(user.id(), mutationId)).isEmpty();

        // Nothing happened, so the same key is free to carry a corrected request.
        JsonNode corrected = syncResults(user, create(mutationId, "TRANSACTION", clientResourceId,
                transactionPayload(foodCategory, "10.00", "Categoria própria")));
        assertThat(corrected.get(0).get("status").stringValue()).isEqualTo("APPLIED");
    }

    @Test
    void aReceiptExistsForEveryAppliedMutationAndOnlyForThose() throws Exception {
        UUID applied = UUID.randomUUID();
        UUID invalid = UUID.randomUUID();

        syncResults(user,
                create(applied, "TRANSACTION", UUID.randomUUID(),
                        transactionPayload(foodCategory, "10.00", "Válida")),
                create(invalid, "TRANSACTION", UUID.randomUUID(),
                        transactionPayload(foodCategory, "-1.00", "Inválida")));

        assertThat(receipts.findByUserIdAndClientMutationId(user.id(), applied)).isPresent();
        assertThat(receipts.findByUserIdAndClientMutationId(user.id(), invalid)).isEmpty();
    }
}
