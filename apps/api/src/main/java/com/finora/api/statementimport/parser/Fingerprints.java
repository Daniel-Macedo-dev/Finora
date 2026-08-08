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

    /**
     * Bumped only when the fingerprint <em>composition</em> changes — the list
     * of values hashed below, or how they are canonicalized.
     *
     * <p>This is financial identity: it decides whether two rows are the same
     * row, and therefore whether an import is a duplicate. It must not move
     * because unrelated parser output grew, and it must not move because of a
     * user's consent or safety confirmation. Bumping it needlessly would make
     * every stored fingerprint incomparable and re-import already-imported
     * money.
     */
    public static final int VERSION = 1;

    /**
     * Bumped when the parser's observable output changes — new fields, or
     * different normalization of existing ones.
     *
     * <p>Deliberately independent from {@link #VERSION}: this records which
     * parser produced a batch, not what a row's identity is made of. Version 2
     * adds the OFX {@code CURDEF} declaration to the parse result, which is new
     * observable output and therefore a parser change — but it contributes
     * nothing to {@link #contentFingerprint}, so row identity is untouched and
     * {@code VERSION} stays 1.
     */
    public static final int PARSER_VERSION = 2;

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
