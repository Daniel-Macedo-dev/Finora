package com.finora.api.statementimport.parser;

import java.util.List;

/**
 * Outcome of parsing one uploaded statement.
 *
 * <p>The parser reports what the file said and nothing more: it never selects
 * a destination account, never compares the declared currency against one and
 * never converts a value. Turning {@code declaredCurrency} into user/account
 * semantics — supported-catalogue membership, account agreement, the
 * acknowledgement a missing declaration requires — belongs to the service
 * layer, which is the only layer that knows who is importing and into what.
 *
 * @param entries          normalized rows in source order
 * @param accountHint      bank/branch/account metadata found in the file,
 *                         already masked for display — a preview hint only,
 *                         never used to pick the destination account
 * @param declaredCurrency the currency code the file itself declared
 *                         (OFX {@code CURDEF}), normalized to canonical
 *                         uppercase; {@code null} when the file declared none.
 *                         A raw code, deliberately not yet validated against
 *                         the application's closed currency catalogue.
 */
public record StatementParseResult(List<StatementEntry> entries, String accountHint,
                                   String declaredCurrency) {
}
