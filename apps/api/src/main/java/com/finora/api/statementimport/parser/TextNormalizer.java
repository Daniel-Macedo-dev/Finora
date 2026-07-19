package com.finora.api.statementimport.parser;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Deterministic text handling for statement content.
 *
 * <ul>
 *   <li>{@link #clean} produces the display form: control characters
 *       removed, whitespace collapsed, meaningful accents preserved.</li>
 *   <li>{@link #canonical} produces the matching form used by fingerprints
 *       and category rules: cleaned, lower-cased and accent-folded, so
 *       {@code "PADARIA  São João"} and {@code "padaria sao joao"} are the
 *       same identity.</li>
 * </ul>
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /** Removes control characters and collapses runs of whitespace. */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
            } else {
                out.append(c);
            }
        }
        return out.toString().strip();
    }

    /** Canonical matching form: cleaned, lower-cased, accents folded away. */
    public static String canonical(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            return null;
        }
        String decomposed = Normalizer.normalize(cleaned, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                out.append(c);
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** Truncates to {@code max} characters (used after cleaning). */
    public static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
