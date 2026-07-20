package com.finora.api.statementimport.parser.csv;

import com.finora.api.statementimport.parser.StatementParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * User-confirmed CSV interpretation: encoding, delimiter, header presence,
 * column roles and regional formats. Nothing here is guessed silently — the
 * backend suggests defaults, the user confirms, and contradictory choices
 * are rejected before any row is interpreted.
 *
 * <p>Exactly one amount shape is allowed: a single signed column, or a
 * separate debit/credit pair.
 *
 * @param decimalSeparator   {@code COMMA} (Brazilian) or {@code DOT}
 * @param thousandsSeparator {@code DOT}, {@code COMMA} or {@code NONE};
 *                           must differ from the decimal separator
 */
public record CsvMappingConfig(
        CsvEncoding encoding,
        CsvDelimiter delimiter,
        boolean hasHeader,
        String datePattern,
        Separator decimalSeparator,
        Separator thousandsSeparator,
        int dateColumn,
        int descriptionColumn,
        Integer amountColumn,
        Integer debitColumn,
        Integer creditColumn,
        Integer externalIdColumn,
        Integer memoColumn) {

    public enum Separator { COMMA, DOT, NONE }

    /**
     * Accepted date patterns in suggestion order — the Brazilian default
     * first, so clients that pre-select the first option stay deterministic.
     */
    public static final List<String> DATE_PATTERN_OPTIONS = List.of(
            "dd/MM/yyyy", "dd/MM/yy", "yyyy-MM-dd", "dd-MM-yyyy", "dd.MM.yyyy", "MM/dd/yyyy");

    /** Accepted date patterns (validation form). */
    public static final Set<String> DATE_PATTERNS = Set.copyOf(DATE_PATTERN_OPTIONS);

    /** Validates internal coherence; throws a safe error when contradictory. */
    public CsvMappingConfig {
        if (encoding == null || delimiter == null) {
            throw invalid("Informe a codificação e o delimitador do arquivo.");
        }
        if (datePattern == null || !DATE_PATTERNS.contains(datePattern)) {
            throw invalid("Padrão de data não suportado.");
        }
        if (decimalSeparator == null || decimalSeparator == Separator.NONE) {
            throw invalid("Informe o separador decimal (vírgula ou ponto).");
        }
        if (thousandsSeparator == null) {
            throw invalid("Informe o separador de milhar (ou nenhum).");
        }
        if (thousandsSeparator == decimalSeparator) {
            throw invalid("O separador de milhar não pode ser igual ao separador decimal.");
        }
        boolean signed = amountColumn != null;
        boolean split = debitColumn != null || creditColumn != null;
        if (signed == split || (split && (debitColumn == null || creditColumn == null))) {
            throw invalid("Escolha uma coluna única de valor ou o par débito/crédito.");
        }
        if (dateColumn < 0 || descriptionColumn < 0
                || isNegative(amountColumn) || isNegative(debitColumn)
                || isNegative(creditColumn) || isNegative(externalIdColumn)
                || isNegative(memoColumn)) {
            throw invalid("As colunas selecionadas são inválidas.");
        }
        Set<Integer> used = new HashSet<>();
        for (Integer column : new Integer[] {
                dateColumn, descriptionColumn, amountColumn, debitColumn, creditColumn,
                externalIdColumn, memoColumn}) {
            if (column != null && !used.add(column)) {
                throw invalid("Cada coluna do arquivo só pode ter um papel no mapeamento.");
            }
        }
    }

    private static boolean isNegative(Integer column) {
        return column != null && column < 0;
    }

    private static StatementParseException invalid(String message) {
        return new StatementParseException("STATEMENT_MAPPING_INVALID", message);
    }
}
