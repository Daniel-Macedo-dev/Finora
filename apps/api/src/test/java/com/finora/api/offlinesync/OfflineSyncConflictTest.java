package com.finora.api.offlinesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.category.CategoryType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * What happens when the world moved while the user was offline.
 *
 * <p>The rule under test is always the same: never merge, never last-write-wins,
 * never overwrite because the client's clock says its edit is newer. Show both
 * sides and let the person decide.
 */
class OfflineSyncConflictTest extends OfflineSyncTestSupport {

    private TestUser user;
    private Long foodCategory;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        foodCategory = categoryId(user, "Alimentação", CategoryType.EXPENSE);
    }

    @Test
    void anEditBasedOnAStaleVersionConflictsAndShowsTheServerValue() throws Exception {
        long id = createTransactionOnline(user, foodCategory, "10.00", "Original");

        // Another device edits it while this one is offline.
        mockMvc.perform(put("/api/transactions/{id}", id)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionPayload(foodCategory, "80.00", "Do outro aparelho")))
                .andExpect(status().isOk());

        JsonNode results = syncResults(user, update(UUID.randomUUID(), "TRANSACTION", id, 0,
                transactionPayload(foodCategory, "25.00", "Edição offline")));

        JsonNode conflict = results.get(0).get("conflict");
        assertThat(results.get(0).get("status").stringValue()).isEqualTo("CONFLICT");
        assertThat(conflict.get("conflictType").stringValue()).isEqualTo("VERSION_MISMATCH");
        assertThat(conflict.get("localBaseVersion").asLong()).isZero();
        assertThat(conflict.get("serverVersion").asLong()).isEqualTo(1);
        assertThat(conflict.get("serverSnapshot").get("description").stringValue())
                .isEqualTo("Do outro aparelho");
        assertThat(conflict.get("resolutionOptions").valueStream()
                .map(JsonNode::stringValue).toList())
                .containsExactlyInAnyOrder("KEEP_SERVER", "APPLY_LOCAL", "EDIT_AND_RETRY",
                        "DISCARD_LOCAL");

        // The server value survived: the offline edit changed nothing.
        mockMvc.perform(get("/api/transactions/{id}", id).cookie(user.session()))
                .andExpect(jsonPath("$.amount").value(80.00));
    }

    @Test
    void theServerSnapshotCarriesNoOwnerOrInternalIdentifiers() throws Exception {
        long id = createTransactionOnline(user, foodCategory, "10.00", "Original");
        mockMvc.perform(put("/api/transactions/{id}", id)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionPayload(foodCategory, "80.00", "Servidor")))
                .andExpect(status().isOk());

        JsonNode snapshot = syncResults(user, update(UUID.randomUUID(), "TRANSACTION", id, 0,
                transactionPayload(foodCategory, "25.00", "Local")))
                .get(0).get("conflict").get("serverSnapshot");

        List<String> properties = snapshot.propertyStream().map(java.util.Map.Entry::getKey).toList();
        assertThat(properties).doesNotContain("userId", "user", "clientResourceId", "requestHash");
        assertThat(snapshot.toString()).doesNotContain(user.email());
    }

    @Test
    void anEditOfSomethingDeletedElsewhereNeverRecreatesIt() throws Exception {
        long id = createTransactionOnline(user, foodCategory, "10.00", "Some");
        mockMvc.perform(delete("/api/transactions/{id}", id)
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isNoContent());

        JsonNode results = syncResults(user, update(UUID.randomUUID(), "TRANSACTION", id, 0,
                transactionPayload(foodCategory, "25.00", "Ressuscitada?")));

        assertThat(results.get(0).get("status").stringValue()).isEqualTo("CONFLICT");
        assertThat(results.get(0).get("conflict").get("conflictType").stringValue())
                .isEqualTo("REMOTE_DELETED");
        assertThat(results.get(0).get("conflict").get("resolutionOptions").valueStream()
                .map(JsonNode::stringValue).toList())
                .containsExactlyInAnyOrder("KEEP_SERVER", "DISCARD_LOCAL");

        mockMvc.perform(get("/api/transactions").cookie(user.session()).param("month", "2026-07"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void aDeleteOfSomethingAlreadyDeletedIsAConflictNotASilentSuccess() throws Exception {
        long id = createTransactionOnline(user, foodCategory, "10.00", "Some");
        mockMvc.perform(delete("/api/transactions/{id}", id)
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isNoContent());

        JsonNode results = syncResults(user,
                deleteMutation(UUID.randomUUID(), "TRANSACTION", id, 0));
        assertThat(results.get(0).get("conflict").get("conflictType").stringValue())
                .isEqualTo("REMOTE_DELETED");
    }

    @Test
    void aBudgetForAMonthAndCategoryThatAlreadyExistsIsASemanticConflict() throws Exception {
        // Created on another device while this one was offline.
        mockMvc.perform(post("/api/budgets").cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"month":"2026-07","categoryId":%d,"limitAmount":800.00}
                                """.formatted(foodCategory)))
                .andExpect(status().isCreated());

        JsonNode results = syncResults(user, create(UUID.randomUUID(), "BUDGET",
                UUID.randomUUID(), """
                {"month":"2026-07","categoryId":%d,"limitAmount":500.00}
                """.formatted(foodCategory)));

        JsonNode conflict = results.get(0).get("conflict");
        assertThat(results.get(0).get("status").stringValue()).isEqualTo("CONFLICT");
        assertThat(conflict.get("conflictType").stringValue()).isEqualTo("RESOURCE_ALREADY_EXISTS");
        // The competing limit is shown so the two can be compared.
        assertThat(conflict.get("serverSnapshot").get("limitAmount").decimalValue())
                .isEqualByComparingTo("800.00");
        // A create was not quietly promoted to an update.
        assertThat(conflict.get("resolutionOptions").valueStream()
                .map(JsonNode::stringValue).toList())
                .doesNotContain("APPLY_LOCAL");

        mockMvc.perform(get("/api/budgets").cookie(user.session()).param("month", "2026-07"))
                .andExpect(jsonPath("$.budgets.length()").value(1))
                .andExpect(jsonPath("$.budgets[0].limitAmount").value(800.00));
    }

    @Test
    void aChildWhoseOfflineParentHasNotLandedIsHeldNotFabricated() throws Exception {
        UUID unsyncedItem = UUID.randomUUID();

        JsonNode results = syncResults(user, create(UUID.randomUUID(), "PURCHASE_OPTION",
                UUID.randomUUID(), """
                {"item":{"clientResourceId":"%s"},"merchant":"Loja","kind":"CASH",
                 "basePrice":100.00}
                """.formatted(unsyncedItem)));

        assertThat(results.get(0).get("status").stringValue()).isEqualTo("DEPENDENCY_MISSING");
        assertThat(results.get(0).get("error").get("code").stringValue())
                .isEqualTo("SYNC_DEPENDENCY_MISSING");

        // No phantom parent was created to hang the child from.
        mockMvc.perform(get("/api/wishlist").cookie(user.session()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void applyingLocalValuesOnTopOfTheCurrentVersionSucceedsWithANewKey() throws Exception {
        long id = createTransactionOnline(user, foodCategory, "10.00", "Original");
        mockMvc.perform(put("/api/transactions/{id}", id)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionPayload(foodCategory, "80.00", "Servidor")))
                .andExpect(status().isOk());

        JsonNode conflict = syncResults(user, update(UUID.randomUUID(), "TRANSACTION", id, 0,
                transactionPayload(foodCategory, "25.00", "Local")))
                .get(0).get("conflict");
        long serverVersion = conflict.get("serverVersion").asLong();

        // "Apply local" is a brand new mutation based on the version just seen.
        JsonNode applied = syncResults(user, update(UUID.randomUUID(), "TRANSACTION", id,
                serverVersion, transactionPayload(foodCategory, "25.00", "Local")));
        assertThat(applied.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        assertThat(applied.get(0).get("version").asLong()).isEqualTo(serverVersion + 1);

        mockMvc.perform(get("/api/transactions/{id}", id).cookie(user.session()))
                .andExpect(jsonPath("$.amount").value(25.00))
                .andExpect(jsonPath("$.description").value("Local"));
    }
}
