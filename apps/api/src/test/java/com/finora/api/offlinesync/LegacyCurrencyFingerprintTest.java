package com.finora.api.offlinesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.api.category.CategoryType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A mutation queued before multi-currency carries no currency field at all.
 *
 * <p>Its receipt was fingerprinted over a payload shape without that field. If
 * upgrading the server changed the hash, retrying an already-applied mutation
 * whose response was lost would be reported as the same idempotency key reused
 * with different content -- the user would be told a payment or expense they
 * genuinely made was refused, and the queued entry would be stuck forever.
 *
 * <p>These tests pin both halves of the contract: the legacy shape still
 * matches, and an explicit currency never borrows it.
 */
class LegacyCurrencyFingerprintTest extends OfflineSyncTestSupport {

    @Test
    void aLegacyMutationWithoutCurrencyAppliesAsBrl() throws Exception {
        TestUser user = registerUser();
        UUID mutation = UUID.randomUUID();

        JsonNode results = syncResults(user,
                create(mutation, "TRANSACTION", UUID.randomUUID(), legacyPayload(user, "50.00")));

        assertThat(results.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        assertThat(results.get(0).get("result").get("currency").stringValue()).isEqualTo("BRL");
    }

    @Test
    void retryingALegacyMutationReturnsTheStoredResultRatherThanAKeyReuseConflict()
            throws Exception {
        TestUser user = registerUser();
        UUID mutation = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        String envelope = create(mutation, "TRANSACTION", resource, legacyPayload(user, "50.00"));

        JsonNode first = syncResults(user, envelope);
        assertThat(first.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        long createdId = first.get(0).get("resourceId").asLong();

        // The client never saw the response and sends the very same entry again.
        JsonNode retry = syncResults(user, envelope);
        assertThat(retry.get(0).get("status").stringValue()).isEqualTo("ALREADY_APPLIED");
        assertThat(retry.get(0).get("resourceId").asLong()).isEqualTo(createdId);
    }

    @Test
    void anExplicitBrlPayloadIsNotTreatedAsTheLegacyShape() throws Exception {
        TestUser user = registerUser();
        UUID mutation = UUID.randomUUID();
        UUID resource = UUID.randomUUID();

        syncResults(user, create(mutation, "TRANSACTION", resource, legacyPayload(user, "50.00")));

        // Same key, now stating BRL explicitly: different content, so the
        // server must refuse rather than silently answer from the receipt.
        JsonNode conflict = syncResults(user,
                create(mutation, "TRANSACTION", resource, payloadWithCurrency(user, "50.00", "BRL")));
        assertThat(conflict.get(0).get("status").stringValue()).isEqualTo("CONFLICT");
    }

    @Test
    void anExplicitForeignPayloadNeverCollidesWithAnImplicitBrlOne() throws Exception {
        TestUser user = registerUser();
        UUID mutation = UUID.randomUUID();
        UUID resource = UUID.randomUUID();

        syncResults(user, create(mutation, "TRANSACTION", resource, legacyPayload(user, "50.00")));

        JsonNode conflict = syncResults(user,
                create(mutation, "TRANSACTION", resource, payloadWithCurrency(user, "50.00", "USD")));
        assertThat(conflict.get(0).get("status").stringValue()).isEqualTo("CONFLICT");
    }

    @Test
    void aChangedLegacyPayloadUnderTheSameKeyIsStillRefused() throws Exception {
        TestUser user = registerUser();
        UUID mutation = UUID.randomUUID();
        UUID resource = UUID.randomUUID();

        syncResults(user, create(mutation, "TRANSACTION", resource, legacyPayload(user, "50.00")));

        JsonNode conflict = syncResults(user,
                create(mutation, "TRANSACTION", resource, legacyPayload(user, "999.00")));
        assertThat(conflict.get(0).get("status").stringValue()).isEqualTo("CONFLICT");
    }

    @Test
    void aNewMutationCarryingAnExplicitCurrencyPreservesIt() throws Exception {
        TestUser user = registerUser();

        JsonNode results = syncResults(user, create(UUID.randomUUID(), "TRANSACTION",
                UUID.randomUUID(), payloadWithCurrency(user, "300.00", "EUR")));

        assertThat(results.get(0).get("status").stringValue()).isEqualTo("APPLIED");
        assertThat(results.get(0).get("result").get("currency").stringValue()).isEqualTo("EUR");
    }

    @Test
    void retryingAnExplicitCurrencyMutationAlsoReturnsTheStoredResult() throws Exception {
        TestUser user = registerUser();
        String envelope = create(UUID.randomUUID(), "TRANSACTION", UUID.randomUUID(),
                payloadWithCurrency(user, "300.00", "EUR"));

        JsonNode first = syncResults(user, envelope);
        assertThat(first.get(0).get("status").stringValue()).isEqualTo("APPLIED");

        JsonNode retry = syncResults(user, envelope);
        assertThat(retry.get(0).get("status").stringValue()).isEqualTo("ALREADY_APPLIED");
        assertThat(retry.get(0).get("result").get("currency").stringValue()).isEqualTo("EUR");
    }

    @Test
    void oneUsersMutationIdDoesNotAnswerAnotherUsersRetry() throws Exception {
        TestUser owner = registerUser("Dono");
        UUID mutation = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        syncResults(owner, create(mutation, "TRANSACTION", resource, legacyPayload(owner, "50.00")));

        // The same identifiers from a different owner must do their own work,
        // not read the first user's receipt.
        TestUser other = registerUser("Outro");
        JsonNode results = syncResults(other,
                create(mutation, "TRANSACTION", resource, legacyPayload(other, "50.00")));
        assertThat(results.get(0).get("status").stringValue()).isEqualTo("APPLIED");
    }

    /** Exactly what a pre-multi-currency client queued: no currency key at all. */
    private String legacyPayload(TestUser user, String amount) {
        return """
                {"type":"EXPENSE","amount":%s,"description":"Compra offline",
                 "date":"2026-07-01","categoryId":%d,"paymentMethod":"PIX"}
                """.formatted(amount, categoryId(user, "Moradia", CategoryType.EXPENSE));
    }

    private String payloadWithCurrency(TestUser user, String amount, String currency) {
        return """
                {"type":"EXPENSE","amount":%s,"description":"Compra offline",
                 "date":"2026-07-01","categoryId":%d,"paymentMethod":"PIX","currency":"%s"}
                """.formatted(amount, categoryId(user, "Moradia", CategoryType.EXPENSE), currency);
    }
}
