package com.finora.api.offlinesync;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Shared fixture helpers for the offline synchronization suites.
 *
 * <p>Everything here goes through the real endpoint with a real session cookie
 * and a real CSRF pair — there is no test-only backdoor into the sync path, and
 * no way for a test to construct a mutation the browser could not.
 *
 * <p>These suites run <em>outside</em> a test transaction on purpose. Each
 * mutation opens its own {@code REQUIRES_NEW} transaction, which is exactly the
 * property under test: wrapping the batch in a rolled-back test transaction
 * would hide the real boundary and make the fixture invisible to the code being
 * exercised. Every test registers its own user, so committed rows stay scoped
 * to that owner and cannot leak into another test's assertions.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class OfflineSyncTestSupport extends AbstractIntegrationTest {

    static final String SYNC_PATH = "/api/offline-sync/mutations";

    /** Wraps one envelope in a batch and posts it as the given user. */
    protected ResultActions sync(TestUser user, String... envelopes) throws Exception {
        return mockMvc.perform(post(SYNC_PATH)
                .cookie(user.session()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mutations\":[%s]}".formatted(String.join(",", envelopes))));
    }

    /** Posts the batch, asserts HTTP success and returns the parsed results array. */
    protected JsonNode syncResults(TestUser user, String... envelopes) throws Exception {
        String body = sync(user, envelopes)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("results");
    }

    protected static String create(UUID mutationId, String resourceType, UUID clientResourceId,
                                   String payload) {
        return """
                {"clientMutationId":"%s","resourceType":"%s","operation":"CREATE",
                 "target":{"clientResourceId":"%s"},"payload":%s}
                """.formatted(mutationId, resourceType, clientResourceId, payload);
    }

    protected static String update(UUID mutationId, String resourceType, long serverId,
                                   long baseVersion, String payload) {
        return """
                {"clientMutationId":"%s","resourceType":"%s","operation":"UPDATE",
                 "target":{"serverId":%d},"baseVersion":%d,"payload":%s}
                """.formatted(mutationId, resourceType, serverId, baseVersion, payload);
    }

    protected static String deleteMutation(UUID mutationId, String resourceType, long serverId,
                                   long baseVersion) {
        return """
                {"clientMutationId":"%s","resourceType":"%s","operation":"DELETE",
                 "target":{"serverId":%d},"baseVersion":%d,"payload":{}}
                """.formatted(mutationId, resourceType, serverId, baseVersion);
    }

    protected static String transactionPayload(long categoryId, String amount, String description) {
        return """
                {"type":"EXPENSE","amount":%s,"description":"%s","date":"2026-07-15",
                 "categoryId":%d}
                """.formatted(amount, description, categoryId);
    }

    /** Creates a transaction online and returns its server id. */
    protected long createTransactionOnline(TestUser user, long categoryId, String amount,
                                           String description) throws Exception {
        String body = mockMvc.perform(post("/api/transactions")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionPayload(categoryId, amount, description)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("id").asLong();
    }

    protected long createWishlistItemOnline(TestUser user, String name) throws Exception {
        String body = mockMvc.perform(post("/api/wishlist")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"priority\":\"HIGH\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("id").asLong();
    }
}
