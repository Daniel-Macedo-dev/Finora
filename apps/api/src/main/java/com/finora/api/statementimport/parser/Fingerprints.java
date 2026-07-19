package com.finora.api.statementimport.parser;

import com.finora.api.transaction.TransactionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * Versioned, deterministic SHA-256 fingerprints. All inputs are canonical
 * values (ISO dates, scale-2 plain amounts, canonical descriptions) so an
 * equivalent row always produces the same fingerprint across uploads, JVMs
 * and machines — never Java hash codes, never import-time timestamps.
 */
public final class Fingerprints {

    /** Bumped only when the fingerprint composition changes. */
    public static final int VERSION = 1;

    /** Bumped only when parser normalization changes observable output. */
    public static final int PARSER_VERSION = 1;

    private Fingerprints() {
    }

    /** SHA-256 of the uploaded bytes: identifies an exact file reupload. */
    public static String fileSha256(byte[] content) {
        return HexFormat.of().formatHex(sha256().digest(content));
    }

    /**
     * Content identity of one statement row for a given owner and
     * destination account. Used when no strong external id exists.
     */
    public static String contentFingerprint(Long userId, Long accountId, LocalDate postedDate,
                                            TransactionType type, BigDecimal absoluteAmount,
                                            String canonicalDescription) {
        String material = String.join("\n",
                "v" + VERSION,
                String.valueOf(userId),
                String.valueOf(accountId),
                postedDate.toString(),
                type.name(),
                absoluteAmount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                canonicalDescription == null ? "" : canonicalDescription);
        return HexFormat.of().formatHex(sha256().digest(material.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
