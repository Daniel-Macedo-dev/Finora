package com.finora.api.statementimport.parser;

import java.util.List;

/**
 * Outcome of parsing one uploaded statement.
 *
 * @param entries         normalized rows in source order
 * @param accountHint     bank/branch/account metadata found in the file,
 *                        already masked for display — a preview hint only,
 *                        never used to pick the destination account
 */
public record StatementParseResult(List<StatementEntry> entries, String accountHint) {
}
