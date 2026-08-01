package com.finora.api.offlinesync;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.api.offlinesync.OfflineSyncDtos.MutationEnvelope;
import com.finora.api.offlinesync.OfflineSyncDtos.ResourceTarget;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The identity behind "the same key twice".
 *
 * <p>Every idempotency decision the sync endpoint makes reduces to comparing
 * this hash with the one on the stored receipt: equal means return the recorded
 * result, different means refuse the reused key. The endpoint suites prove the
 * decision end to end; these prove the function it rests on, where a wrong
 * answer would either duplicate a financial effect or reject a legitimate retry.
 *
 * <p>Deliberately free of Spring, Mockito and a database — the whole point of
 * the class is to be a pure function of its inputs.
 */
class RequestFingerprintTest {

    private ObjectMapper mapper;

    /** A payload with the shapes that actually travel: money, dates, text, nesting. */
    private record Payload(String description, BigDecimal amount, LocalDate date,
                           Long categoryId, List<String> tags, Map<String, Object> extra) {
    }

    @BeforeEach
    void setUp() {
        mapper = JsonMapper.builder().build();
    }

    private Payload payload() {
        return new Payload("Mercado", new BigDecimal("42.00"), LocalDate.of(2026, 7, 20), 7L,
                List.of("a", "b"), Map.of("alpha", 1, "beta", 2));
    }

    private MutationEnvelope envelope(SyncResourceType type, SyncOperation operation,
                                      ResourceTarget target, Long baseVersion, JsonNode body) {
        return new MutationEnvelope(UUID.randomUUID(), type, operation, target, baseVersion, body);
    }

    private MutationEnvelope envelope() {
        return envelope(SyncResourceType.TRANSACTION, SyncOperation.UPDATE,
                new ResourceTarget(11L, null), 3L, mapper.valueToTree(payload()));
    }

    @Test
    void of_quandoMesmaOperacaoEMesmoPayload_produzOMesmoHash() {
        String first = RequestFingerprint.of(envelope(), payload(), mapper);
        String second = RequestFingerprint.of(envelope(), payload(), mapper);

        assertThat(first).isEqualTo(second);
        // The mutation id itself is not part of the identity: the receipt is
        // keyed by it, so hashing it too would make every comparison trivially
        // equal and the "same key, different content" refusal impossible.
        assertThat(first).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void of_quandoOrdemDasPropriedadesMuda_produzOMesmoHash() {
        JsonNode ordered = mapper.readTree(
                "{\"alpha\":1,\"beta\":2,\"gamma\":{\"x\":1,\"y\":2}}");
        JsonNode shuffled = mapper.readTree(
                "{\"gamma\":{\"y\":2,\"x\":1},\"beta\":2,\"alpha\":1}");

        assertThat(RequestFingerprint.of(envelope(), ordered, mapper))
                .isEqualTo(RequestFingerprint.of(envelope(), shuffled, mapper));
    }

    @Test
    void of_quandoNumeroTemOutraRepresentacao_produzOMesmoHash() {
        // 10, 10.00 and 1E+1 are one monetary value. A retry that re-serialized
        // the same amount differently must not look like a different request.
        String plain = RequestFingerprint.of(envelope(), mapper.readTree("{\"amount\":10}"), mapper);
        String scaled = RequestFingerprint.of(envelope(), mapper.readTree("{\"amount\":10.00}"), mapper);
        String exponent = RequestFingerprint.of(envelope(), mapper.readTree("{\"amount\":1E+1}"), mapper);

        assertThat(plain).isEqualTo(scaled).isEqualTo(exponent);
    }

    @Test
    void of_quandoValorMonetarioDifere_produzHashDiferente() {
        String original = RequestFingerprint.of(envelope(), mapper.readTree("{\"amount\":42.00}"), mapper);
        String cents = RequestFingerprint.of(envelope(), mapper.readTree("{\"amount\":42.01}"), mapper);

        assertThat(original).isNotEqualTo(cents);
    }

    @Test
    void of_quandoOrdemDoArrayMuda_produzHashDiferente() {
        // Arrays are ordered data; reordering them is a different request.
        String ascending = RequestFingerprint.of(envelope(), mapper.readTree("{\"tags\":[\"a\",\"b\"]}"), mapper);
        String descending = RequestFingerprint.of(envelope(), mapper.readTree("{\"tags\":[\"b\",\"a\"]}"), mapper);

        assertThat(ascending).isNotEqualTo(descending);
    }

    @Test
    void of_quandoOperacaoOuRecursoDifere_produzHashDiferente() {
        JsonNode body = mapper.valueToTree(payload());
        ResourceTarget target = new ResourceTarget(11L, null);

        String update = RequestFingerprint.of(
                envelope(SyncResourceType.TRANSACTION, SyncOperation.UPDATE, target, 3L, body),
                payload(), mapper);
        String delete = RequestFingerprint.of(
                envelope(SyncResourceType.TRANSACTION, SyncOperation.DELETE, target, 3L, body),
                payload(), mapper);
        String otherResource = RequestFingerprint.of(
                envelope(SyncResourceType.BUDGET, SyncOperation.UPDATE, target, 3L, body),
                payload(), mapper);

        assertThat(update).isNotEqualTo(delete).isNotEqualTo(otherResource);
        assertThat(delete).isNotEqualTo(otherResource);
    }

    @Test
    void of_quandoVersaoBaseDifere_produzHashDiferente() {
        JsonNode body = mapper.valueToTree(payload());
        ResourceTarget target = new ResourceTarget(11L, null);

        String third = RequestFingerprint.of(
                envelope(SyncResourceType.TRANSACTION, SyncOperation.UPDATE, target, 3L, body),
                payload(), mapper);
        String fourth = RequestFingerprint.of(
                envelope(SyncResourceType.TRANSACTION, SyncOperation.UPDATE, target, 4L, body),
                payload(), mapper);
        String absent = RequestFingerprint.of(
                envelope(SyncResourceType.TRANSACTION, SyncOperation.UPDATE, target, null, body),
                payload(), mapper);

        assertThat(third).isNotEqualTo(fourth).isNotEqualTo(absent);
    }

    @Test
    void of_quandoAlvoDifere_produzHashDiferente() {
        JsonNode body = mapper.valueToTree(payload());
        UUID clientResource = UUID.randomUUID();

        String byServerId = RequestFingerprint.of(
                envelope(SyncResourceType.TRANSACTION, SyncOperation.UPDATE,
                        new ResourceTarget(11L, null), 3L, body), payload(), mapper);
        String otherServerId = RequestFingerprint.of(
                envelope(SyncResourceType.TRANSACTION, SyncOperation.UPDATE,
                        new ResourceTarget(12L, null), 3L, body), payload(), mapper);
        String byClientId = RequestFingerprint.of(
                envelope(SyncResourceType.TRANSACTION, SyncOperation.UPDATE,
                        new ResourceTarget(null, clientResource), 3L, body), payload(), mapper);

        assertThat(byServerId).isNotEqualTo(otherServerId).isNotEqualTo(byClientId);
    }

    @Test
    void of_quandoTextoContemAsPontuacoesDoFormatoCanonico_naoColideEntreCampos() {
        // The canonical form is textual, so a description carrying quotes,
        // braces or a separator must not be able to imitate the surrounding
        // structure and make two different requests hash alike.
        String injected = RequestFingerprint.of(envelope(),
                mapper.readTree("{\"description\":\"a\\\":1,\\\"b\",\"b\":\"x\"}"), mapper);
        String honest = RequestFingerprint.of(envelope(),
                mapper.readTree("{\"description\":\"a\",\"b\":\"1,\\\"b\\\":\\\"x\"}"), mapper);

        assertThat(injected).isNotEqualTo(honest);
    }

    @Test
    void of_quandoPayloadEhNuloOuVazio_permaneceEstavelEDistinto() {
        String nullPayload = RequestFingerprint.of(envelope(), null, mapper);
        String emptyPayload = RequestFingerprint.of(envelope(), Map.of(), mapper);

        assertThat(nullPayload).isEqualTo(RequestFingerprint.of(envelope(), null, mapper));
        assertThat(nullPayload).isNotEqualTo(emptyPayload);
    }
}
