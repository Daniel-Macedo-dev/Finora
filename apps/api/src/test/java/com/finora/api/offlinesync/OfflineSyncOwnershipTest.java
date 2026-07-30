package com.finora.api.offlinesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.category.CategoryType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * One owner must never be able to reach, change or even detect another's data
 * through the replay endpoint.
 *
 * <p>"Not yours" and "not there" are deliberately indistinguishable. Any
 * difference between the two answers would turn this endpoint into an existence
 * oracle over other people's finances.
 */
class OfflineSyncOwnershipTest extends OfflineSyncTestSupport {

    private TestUser owner;
    private TestUser intruder;
    private Long ownerCategory;
    private Long intruderCategory;

    @BeforeEach
    void setUp() throws Exception {
        owner = registerUser("Dono");
        intruder = registerUser("Intruso");
        ownerCategory = categoryId(owner, "Alimentação", CategoryType.EXPENSE);
        intruderCategory = categoryId(intruder, "Alimentação", CategoryType.EXPENSE);
    }

    @Test
    void aForeignServerIdIsIndistinguishableFromADeletedResource() throws Exception {
        long theirs = createTransactionOnline(owner, ownerCategory, "10.00", "Do dono");

        JsonNode attempt = syncResults(intruder, update(UUID.randomUUID(), "TRANSACTION",
                theirs, 0, transactionPayload(intruderCategory, "1.00", "Roubada")));

        assertThat(attempt.get(0).get("status").stringValue()).isEqualTo("CONFLICT");
        assertThat(attempt.get(0).get("conflict").get("conflictType").stringValue())
                .isEqualTo("REMOTE_DELETED");
        // Nothing about the real resource leaks — no snapshot, no version.
        assertThat(attempt.get(0).get("conflict").get("serverSnapshot").isNull()).isTrue();
        assertThat(attempt.get(0).get("conflict").get("serverVersion").isNull()).isTrue();

        mockMvc.perform(get("/api/transactions/{id}", theirs).cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(10.00))
                .andExpect(jsonPath("$.description").value("Do dono"));
    }

    @Test
    void aForeignClientIdentityResolvesToNothing() throws Exception {
        UUID clientResourceId = UUID.randomUUID();
        syncResults(owner, create(UUID.randomUUID(), "TRANSACTION", clientResourceId,
                transactionPayload(ownerCategory, "10.00", "Do dono")));

        JsonNode attempt = syncResults(intruder, """
                {"clientMutationId":"%s","resourceType":"TRANSACTION","operation":"UPDATE",
                 "target":{"clientResourceId":"%s"},"baseVersion":0,"payload":%s}
                """.formatted(UUID.randomUUID(), clientResourceId,
                transactionPayload(intruderCategory, "1.00", "Roubada")));

        // Resolved as absent, not as someone else's — the intruder learns nothing.
        assertThat(attempt.get(0).get("status").stringValue()).isEqualTo("CONFLICT");
        assertThat(attempt.get(0).get("conflict").get("conflictType").stringValue())
                .isEqualTo("REMOTE_DELETED");

        mockMvc.perform(get("/api/transactions").cookie(intruder.session())
                        .param("month", "2026-07"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void aChildCannotBeAttachedToAnotherOwnersParent() throws Exception {
        long theirItem = createWishlistItemOnline(owner, "Notebook do dono");

        JsonNode attempt = syncResults(intruder, create(UUID.randomUUID(), "PURCHASE_OPTION",
                UUID.randomUUID(), """
                {"item":{"serverId":%d},"merchant":"Loja","kind":"CASH","basePrice":100.00}
                """.formatted(theirItem)));

        assertThat(attempt.get(0).get("status").stringValue()).isEqualTo("REJECTED");

        mockMvc.perform(get("/api/wishlist/{id}", theirItem).cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options.length()").value(0));
    }

    @Test
    void anIntruderCannotReadAnotherOwnersStoredResultByReusingTheirKey() throws Exception {
        UUID mutationId = UUID.randomUUID();
        UUID clientResourceId = UUID.randomUUID();
        syncResults(owner, create(mutationId, "TRANSACTION", clientResourceId,
                transactionPayload(ownerCategory, "1234.56", "Segredo do dono")));

        // The same key under a different session is simply a new mutation for a
        // different owner: it can never surface the first owner's stored result.
        JsonNode attempt = syncResults(intruder, create(mutationId, "TRANSACTION",
                clientResourceId, transactionPayload(intruderCategory, "1.00", "Minha")));

        assertThat(attempt.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        assertThat(attempt.get(0).get("result").get("description").stringValue())
                .isEqualTo("Minha");
        assertThat(attempt.toString()).doesNotContain("Segredo do dono");
    }

    @Test
    void aForeignCategoryOrAccountBehavesAsAbsentRatherThanForbidden() throws Exception {
        JsonNode attempt = syncResults(intruder, create(UUID.randomUUID(), "TRANSACTION",
                UUID.randomUUID(), transactionPayload(ownerCategory, "10.00", "Categoria alheia")));

        assertThat(attempt.get(0).get("status").stringValue()).isEqualTo("REJECTED");
        assertThat(attempt.get(0).get("error").get("code").stringValue())
                .isEqualTo("SYNC_REFERENCE_NOT_FOUND");
        // The message names the category the caller supplied, never its owner.
        assertThat(attempt.get(0).get("error").get("detail").stringValue())
                .doesNotContain(owner.email());
    }
}
