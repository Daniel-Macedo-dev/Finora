package com.finora.api.statementimport.parser.csv;

import com.finora.api.common.money.MoneyRules;
import com.finora.api.statementimport.parser.StatementEntry;
import com.finora.api.statementimport.parser.StatementLimits;
import com.finora.api.statementimport.parser.StatementParseResult;
import com.finora.api.statementimport.parser.TextNormalizer;
import com.finora.api.statementimport.parser.ValidationIssue;
import com.finora.api.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic CSV → {@link StatementEntry} parser driven entirely by the
 * user-confirmed {@link CsvMappingConfig}. Row-level problems become
 * validation issues on the row (never a whole-file failure); file-level
 * problems (encoding, malformed quoting, limits) fail fast with safe codes.
 */
public final class CsvStatementParser {

    /** After thousands separators are removed and the decimal becomes '.'. */
    private static final Pattern CANONICAL_AMOUNT = Pattern.compile("[+-]?\\d{1,12}(\\.\\d{1,2})?");

    private CsvStatementParser() {
    }

    /** Full parse of a CSV statement with a validated mapping. */
    public static StatementParseResult parse(byte[] content, CsvMappingConfig config) {
        List<List<String>> rows = rows(content, config.encoding(), config.delimiter());
        DateTimeFormatter dateFormat = formatter(config.datePattern());
        List<StatementEntry> entries = new ArrayList<>();
        int start = config.hasHeader() && !rows.isEmpty() ? 1 : 0;
        for (int i = start; i < rows.size(); i++) {
            entries.add(toEntry(rows.get(i), entries.size() + 1, config, dateFormat));
        }
        return new StatementParseResult(entries, null);
    }

    /**
     * Bounded raw-cell preview for the mapping step: the first rows exactly
     * as tokenized, so the user can point each column to its role.
     */
    public static List<List<String>> rawPreview(byte[] content, CsvEncoding encoding,
                                                CsvDelimiter delimiter, int maxRows) {
        List<List<String>> rows = rows(content, encoding, delimiter);
        return rows.subList(0, Math.min(rows.size(), maxRows));
    }

    private static List<List<String>> rows(byte[] content, CsvEncoding encoding,
                                           CsvDelimiter delimiter) {
        CsvDecoder.rejectBinary(content);
        String text = CsvDecoder.decode(content, encoding);
        return CsvDecoder.tokenize(text, delimiter);
    }

    private static StatementEntry toEntry(List<String> row, int sourceIndex,
                                          CsvMappingConfig config, DateTimeFormatter dateFormat) {
        List<ValidationIssue> issues = new ArrayList<>();

        String rawDate = cell(row, config.dateColumn());
        String rawDescription = cell(row, config.descriptionColumn());
        String externalId = config.externalIdColumn() != null
                ? emptyToNull(TextNormalizer.truncate(
                        TextNormalizer.clean(cell(row, config.externalIdColumn())), 255))
                : null;
        String memo = config.memoColumn() != null
                ? emptyToNull(TextNormalizer.truncate(
                        TextNormalizer.clean(cell(row, config.memoColumn())), 500))
                : null;

        LocalDate date = null;
        if (rawDate == null || rawDate.isBlank()) {
            issues.add(new ValidationIssue("STATEMENT_ROW_MISSING_DATE",
                    "A linha não possui data."));
        } else {
            try {
                date = LocalDate.parse(rawDate.strip(), dateFormat);
            } catch (DateTimeParseException e) {
                issues.add(new ValidationIssue("STATEMENT_ROW_INVALID_DATE",
                        "A data não corresponde ao padrão selecionado."));
            }
        }

        String description = TextNormalizer.truncate(TextNormalizer.clean(rawDescription),
                StatementLimits.MAX_DESCRIPTION_LENGTH);
        if (description == null || description.isBlank()) {
            issues.add(new ValidationIssue("STATEMENT_ROW_MISSING_DESCRIPTION",
                    "A linha não possui descrição."));
            description = null;
        }

        BigDecimal signed = null;
        TransactionType type = null;
        if (config.amountColumn() != null) {
            signed = parseAmount(cell(row, config.amountColumn()), config, issues);
        } else {
            String debit = cell(row, config.debitColumn());
            String credit = cell(row, config.creditColumn());
            boolean hasDebit = debit != null && !debit.isBlank();
            boolean hasCredit = credit != null && !credit.isBlank();
            if (hasDebit && hasCredit) {
                issues.add(new ValidationIssue("STATEMENT_ROW_AMBIGUOUS_AMOUNT",
                        "A linha possui valor nas colunas de débito e de crédito ao mesmo tempo."));
            } else if (!hasDebit && !hasCredit) {
                issues.add(new ValidationIssue("STATEMENT_ROW_MISSING_AMOUNT",
                        "A linha não possui valor."));
            } else {
                BigDecimal value = parseAmount(hasDebit ? debit : credit, config, issues);
                if (value != null) {
                    // Debit/credit columns carry direction by column, not by
                    // sign — the magnitude is what matters.
                    signed = hasDebit ? value.abs().negate() : value.abs();
                }
            }
        }
        if (signed != null) {
            if (signed.signum() == 0) {
                issues.add(new ValidationIssue("STATEMENT_ROW_ZERO_AMOUNT",
                        "O valor da linha é zero."));
            } else {
                type = signed.signum() > 0 ? TransactionType.INCOME : TransactionType.EXPENSE;
            }
        }

        return new StatementEntry(
                sourceIndex,
                externalId,
                date,
                type,
                type == null ? null : MoneyRules.normalize(signed.abs()),
                description,
                description == null ? null
                        : TextNormalizer.truncate(TextNormalizer.canonical(description),
                                StatementLimits.MAX_DESCRIPTION_LENGTH),
                memo,
                "CSV",
                List.copyOf(issues));
    }

    /**
     * Parses one monetary cell under the configured separators. Characters
     * inconsistent with the configuration (e.g. a dot when the decimal is a
     * comma and no thousands separator was declared) are a row issue — never
     * a silent guess.
     */
    private static BigDecimal parseAmount(String raw, CsvMappingConfig config,
                                          List<ValidationIssue> issues) {
        if (raw == null || raw.isBlank()) {
            issues.add(new ValidationIssue("STATEMENT_ROW_MISSING_AMOUNT",
                    "A linha não possui valor."));
            return null;
        }
        String value = raw.strip()
                .replace("R$", "")
                .replace(" ", "")
                .replace(" ", "");
        // Some bank exports place the sign after the number ("123,45-").
        if (value.endsWith("-")) {
            value = "-" + value.substring(0, value.length() - 1);
        }
        if (config.thousandsSeparator() == CsvMappingConfig.Separator.DOT) {
            value = value.replace(".", "");
        } else if (config.thousandsSeparator() == CsvMappingConfig.Separator.COMMA) {
            value = value.replace(",", "");
        }
        if (config.decimalSeparator() == CsvMappingConfig.Separator.COMMA) {
            value = value.replace(',', '.');
        }
        if (!CANONICAL_AMOUNT.matcher(value).matches()) {
            issues.add(new ValidationIssue("STATEMENT_ROW_INVALID_AMOUNT",
                    "O valor da linha não corresponde ao formato selecionado."));
            return null;
        }
        return new BigDecimal(value);
    }

    private static DateTimeFormatter formatter(String userPattern) {
        // Strict resolution needs era-free 'u' years; the user-facing pattern
        // keeps the familiar 'y'.
        String strictPattern = userPattern.replace('y', 'u');
        return DateTimeFormatter.ofPattern(strictPattern).withResolverStyle(ResolverStyle.STRICT);
    }

    private static String cell(List<String> row, Integer index) {
        if (index == null || index >= row.size()) {
            return null;
        }
        return row.get(index);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
