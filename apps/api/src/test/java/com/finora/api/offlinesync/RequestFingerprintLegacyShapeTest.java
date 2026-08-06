package com.finora.api.offlinesync;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.api.offlinesync.OfflineSyncDtos.MutationEnvelope;
import com.finora.api.offlinesync.OfflineSyncDtos.ResourceTarget;
import com.finora.api.transaction.PaymentMethod;
import com.finora.api.transaction.TransactionDtos.TransactionRequest;
import com.finora.api.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fingerprint contract across the multi-currency upgrade.
 *
 * <p>An end-to-end retry cannot prove this: both halves of such a test run on
 * the same server build and would agree with each other whatever the rule is.
 * The receipt that matters was written by the <em>previous</em> build, over a
 * payload shape that had no currency field. So the property is pinned directly:
 * hashing today's record with no currency must equal hashing the old field set.
 *
 * <p>If this test fails, every offline mutation queued before multi-currency
 * whose response was lost would come back as IDEMPOTENCY_KEY_REUSED.
 */
class RequestFingerprintLegacyShapeTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final UUID MUTATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RESOURCE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static MutationEnvelope envelope() {
        return new MutationEnvelope(MUTATION, SyncResourceType.TRANSACTION, SyncOperation.CREATE,
                new ResourceTarget(null, RESOURCE), null, null);
    }

    /** Today's canonical record, with no currency supplied. */
    private static TransactionRequest withoutCurrency() {
        return new TransactionRequest(TransactionType.EXPENSE, new BigDecimal("50.00"),
                "Compra offline", LocalDate.of(2026, 7, 1), 7L, null, null,
                PaymentMethod.PIX, null);
    }

    private static TransactionRequest withCurrency(String currency) {
        return new TransactionRequest(TransactionType.EXPENSE, new BigDecimal("50.00"),
                "Compra offline", LocalDate.of(2026, 7, 1), 7L, null, currency,
                PaymentMethod.PIX, null);
    }

    /**
     * The exact field set a pre-multi-currency build serialized: same values,
     * no currency key at all.
     */
    private static Map<String, Object> legacyFieldSet() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("type", "EXPENSE");
        legacy.put("amount", new BigDecimal("50.00"));
        legacy.put("description", "Compra offline");
        legacy.put("date", "2026-07-01");
        legacy.put("categoryId", 7L);
        legacy.put("accountId", null);
        legacy.put("paymentMethod", "PIX");
        legacy.put("notes", null);
        return legacy;
    }

    @Test
    void absentCurrencyHashesExactlyLikeThePreMultiCurrencyShape() {
        String today = RequestFingerprint.of(envelope(), withoutCurrency(), MAPPER);
        String legacy = RequestFingerprint.of(envelope(), legacyFieldSet(), MAPPER);

        assertThat(today)
                .as("a legacy receipt must still match a legacy retry")
                .isEqualTo(legacy);
    }

    @Test
    void anExplicitCurrencyChangesTheFingerprint() {
        String legacy = RequestFingerprint.of(envelope(), withoutCurrency(), MAPPER);

        assertThat(RequestFingerprint.of(envelope(), withCurrency("BRL"), MAPPER))
                .as("explicit BRL is a different request from an implicit one")
                .isNotEqualTo(legacy);
        assertThat(RequestFingerprint.of(envelope(), withCurrency("USD"), MAPPER))
                .as("an explicit foreign currency must never borrow the legacy shape")
                .isNotEqualTo(legacy);
    }

    @Test
    void differentCurrenciesNeverShareAFingerprint() {
        assertThat(RequestFingerprint.of(envelope(), withCurrency("USD"), MAPPER))
                .isNotEqualTo(RequestFingerprint.of(envelope(), withCurrency("EUR"), MAPPER));
    }

    @Test
    void otherNullableFieldsStayInsideTheHash() {
        // Only fields added after the format froze are omitted when absent.
        // Blanket null-skipping would silently rewrite every existing receipt.
        Map<String, Object> withNotes = legacyFieldSet();
        withNotes.put("notes", "algo");

        assertThat(RequestFingerprint.of(envelope(), legacyFieldSet(), MAPPER))
                .isNotEqualTo(RequestFingerprint.of(envelope(), withNotes, MAPPER));
    }

    @Test
    void theSamePayloadAlwaysHashesTheSame() {
        assertThat(RequestFingerprint.of(envelope(), withoutCurrency(), MAPPER))
                .isEqualTo(RequestFingerprint.of(envelope(), withoutCurrency(), MAPPER));
    }
}
