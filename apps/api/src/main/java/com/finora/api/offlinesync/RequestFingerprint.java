package com.finora.api.offlinesync;

import com.finora.api.offlinesync.OfflineSyncDtos.MutationEnvelope;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Deterministic SHA-256 over what a mutation actually asks the server to do.
 *
 * <p>The fingerprint is what makes "the same key twice" answerable. Replaying
 * an identical mutation must return the stored result; replaying the same key
 * with different content must be refused. Both decisions reduce to comparing
 * this hash against the one recorded on the receipt.
 *
 * <p>Two properties matter more than the choice of algorithm:
 *
 * <ul>
 *   <li><strong>It is computed over the canonical payload</strong> — the typed,
 *       validated, normalized form each handler produces, not the raw request
 *       body. Trimming a description or rescaling an amount therefore cannot
 *       make a retry look like a different request.</li>
 *   <li><strong>It does not depend on encoding accidents</strong> — object keys
 *       are sorted, numbers are written in a single canonical form, and no Java
 *       {@code hashCode} is involved anywhere.</li>
 * </ul>
 */
final class RequestFingerprint {

    /** Unit separator: cannot occur in validated text, so fields cannot bleed. */
    private static final char SEPARATOR = '';

    private RequestFingerprint() {
    }

    /**
     * Hashes resource type, operation, target, base version and the canonical
     * payload. Any difference in any of them yields a different fingerprint.
     */
    static String of(MutationEnvelope envelope, Object canonicalPayload, ObjectMapper mapper) {
        StringBuilder canonical = new StringBuilder(256);
        canonical.append(envelope.resourceType().name()).append(SEPARATOR)
                .append(envelope.operation().name()).append(SEPARATOR)
                .append(envelope.target().serverId() == null
                        ? "-" : envelope.target().serverId().toString()).append(SEPARATOR)
                .append(envelope.target().clientResourceId() == null
                        ? "-" : envelope.target().clientResourceId().toString()).append(SEPARATOR)
                .append(envelope.baseVersion() == null
                        ? "-" : envelope.baseVersion().toString()).append(SEPARATOR);
        writeCanonical(mapper.valueToTree(canonicalPayload), canonical);
        return sha256Hex(canonical.toString());
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every Java platform.
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    /**
     * Writes a JSON tree in one canonical textual form: object keys sorted,
     * numbers normalized to a single representation, no insignificant
     * whitespace. Property order in the wire payload is therefore irrelevant.
     */
    private static void writeCanonical(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
            return;
        }
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.propertyStream().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(entry.getKey(), out);
                out.append(':');
                writeCanonical(entry.getValue(), out);
            }
            out.append('}');
            return;
        }
        if (node.isArray()) {
            // Arrays are ordered data; their order is part of the request.
            List<JsonNode> elements = new ArrayList<>();
            node.forEach(elements::add);
            out.append('[');
            for (int i = 0; i < elements.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeCanonical(elements.get(i), out);
            }
            out.append(']');
            return;
        }
        if (node.isNumber()) {
            // 10, 10.0 and 1E+1 are the same monetary value; they must hash alike.
            BigDecimal value = node.decimalValue().stripTrailingZeros();
            out.append(value.scale() <= 0 ? value.toBigInteger().toString() : value.toPlainString());
            return;
        }
        if (node.isBoolean()) {
            out.append(node.booleanValue());
            return;
        }
        writeString(node.stringValue(), out);
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
