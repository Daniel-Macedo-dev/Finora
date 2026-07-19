package com.finora.api.statementimport.parser;

import com.finora.api.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One normalized statement row — the single output shape shared by the CSV
 * and OFX parsers.
 *
 * <p>Direction rules: a positive source amount is {@code INCOME}, a negative
 * one is {@code EXPENSE}; the persisted {@code absoluteAmount} is always
 * positive (the Finora storage invariant) and a zero amount is a blocking
 * validation issue. Rows with issues carry {@code null} in the fields that
 * could not be normalized.
 *
 * @param sourceIndex           1-based CSV row number or OFX STMTTRN sequence
 * @param externalId            OFX FITID or mapped CSV external id (nullable)
 * @param postedDate            normalized business date (no timezone drift)
 * @param type                  financial direction derived from the sign
 * @param absoluteAmount        positive monetary value, scale 2
 * @param description           display description (meaningful accents kept)
 * @param normalizedDescription canonical matching form (case/accent-folded)
 * @param memo                  optional secondary text (OFX MEMO / CSV column)
 * @param sourceType            OFX TRNTYPE or {@code CSV}; preview info only
 * @param issues                blocking problems; non-empty means invalid row
 */
public record StatementEntry(
        int sourceIndex,
        String externalId,
        LocalDate postedDate,
        TransactionType type,
        BigDecimal absoluteAmount,
        String description,
        String normalizedDescription,
        String memo,
        String sourceType,
        List<ValidationIssue> issues) {

    public boolean valid() {
        return issues.isEmpty();
    }
}
