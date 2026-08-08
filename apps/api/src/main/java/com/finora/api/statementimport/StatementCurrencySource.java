package com.finora.api.statementimport;

/**
 * Where a statement batch's denomination came from.
 *
 * <p>Every batch has exactly one effective currency — the destination
 * account's — and no import ever converts a value. What differs between these
 * sources is the <em>evidence</em> behind that denomination, and that evidence
 * decides whether the user has to confirm an assumption before money is
 * created. Collapsing them would either invent a declaration the file never
 * made or discard a declaration it did make.
 */
public enum StatementCurrencySource {

    /**
     * The destination account is the source contract. CSV has no Finora-level
     * currency column, so choosing the account <em>is</em> the explicit
     * statement of denomination — nothing further needs confirming.
     */
    ACCOUNT(false),

    /**
     * The OFX file declared its currency in {@code CURDEF} and that
     * declaration matched the destination account. {@code declaredCurrency} is
     * always present for this source, and mismatches never reach persistence.
     */
    FILE(false),

    /**
     * The OFX file carried no {@code CURDEF} at all. The account currency will
     * be used, but that is Finora's assumption rather than the file's
     * statement, so the user must acknowledge it before anything is
     * materialized.
     */
    ACCOUNT_ASSUMED(true),

    /**
     * An OFX batch created before V16, when the parser did not record whether
     * {@code CURDEF} existed. The file may well have declared a currency — the
     * evidence was simply discarded, so this must never be reported as
     * "the file declared no currency". Completed history stays readable;
     * anything still pending needs the same explicit acknowledgement before a
     * new item is materialized.
     */
    LEGACY_UNKNOWN(true);

    private final boolean requiresAccountCurrencyAcknowledgement;

    StatementCurrencySource(boolean requiresAccountCurrencyAcknowledgement) {
        this.requiresAccountCurrencyAcknowledgement = requiresAccountCurrencyAcknowledgement;
    }

    /**
     * Whether materializing a new item from this batch needs the user to
     * confirm that the destination account currency is the right reading of
     * the file's amounts. True exactly when the denomination is an assumption
     * Finora made rather than one the source stated.
     */
    public boolean requiresAccountCurrencyAcknowledgement() {
        return requiresAccountCurrencyAcknowledgement;
    }

    /** Whether this source must carry a file-declared currency code. */
    public boolean declaresFileCurrency() {
        return this == FILE;
    }
}
