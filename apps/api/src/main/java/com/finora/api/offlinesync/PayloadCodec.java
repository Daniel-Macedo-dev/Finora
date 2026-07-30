package com.finora.api.offlinesync;

import com.finora.api.offlinesync.OfflineSyncDtos.FieldError;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a queued mutation's raw JSON into a validated domain request record.
 *
 * <p>The record itself is the allowlist. A field that is not declared on it
 * simply does not exist as far as the mutation is concerned, so no flag can be
 * smuggled in through generic JSON — that is why, for example, the offline
 * price-snapshot payload has no {@code updateLinkedOption} property at all
 * rather than a property that is checked and refused.
 *
 * <p>Validation runs the same Bean Validation constraints the online endpoints
 * run, because the records are the same ones the online endpoints use.
 */
@Component
public class PayloadCodec {

    private final ObjectMapper mapper;
    private final Validator validator;

    public PayloadCodec(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    /** Parses and validates; never returns a partially valid object. */
    public <T> T parse(JsonNode payload, Class<T> type) {
        if (payload == null || !payload.isObject()) {
            throw new SyncRejectedException("SYNC_PAYLOAD_INVALID",
                    "Os dados da operação estão em formato inválido.");
        }
        T value;
        try {
            value = mapper.treeToValue(payload, type);
        } catch (RuntimeException e) {
            // The parse failure text can quote raw input; keep it out of the response.
            throw new SyncRejectedException("SYNC_PAYLOAD_INVALID",
                    "Os dados da operação estão em formato inválido.");
        }
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            List<FieldError> errors = violations.stream()
                    .map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage()))
                    .sorted((a, b) -> a.field().compareTo(b.field()))
                    .toList();
            throw new SyncRejectedException("SYNC_PAYLOAD_INVALID",
                    "Um ou mais campos estão inválidos.", errors);
        }
        return value;
    }

    /**
     * A DELETE carries no data. Accepting one would let a payload ride along
     * that nothing validates and nothing reads, but that still changes the
     * request fingerprint.
     */
    public void requireEmpty(JsonNode payload) {
        if (payload == null || !payload.isObject() || !payload.isEmpty()) {
            throw new SyncRejectedException("SYNC_PAYLOAD_INVALID",
                    "Uma exclusão não deve enviar dados.");
        }
    }
}
