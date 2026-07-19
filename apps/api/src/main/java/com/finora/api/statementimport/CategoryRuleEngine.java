package com.finora.api.statementimport;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Deterministic rule matcher — no statistics, no AI, no regex. Given one
 * item and the owner's active rules, the winning rule is chosen by a fixed
 * precedence so the same input always maps to the same category:
 *
 * <ol>
 *   <li>account-specific rule over global rule;</li>
 *   <li>transaction-type compatibility (hard filter);</li>
 *   <li>highest explicit priority;</li>
 *   <li>most specific operation (EXACT &gt; STARTS_WITH &gt; CONTAINS);</li>
 *   <li>longest normalized pattern;</li>
 *   <li>smallest id (stable tie-break).</li>
 * </ol>
 */
@Component
public class CategoryRuleEngine {

    /** Deterministic confidence class derived from rule strength alone. */
    public enum RuleConfidence { HIGH, MEDIUM, LOW }

    public record Match(CategoryMappingRule rule, RuleConfidence confidence) {
    }

    /**
     * Best matching rule for one item, or {@code null}. {@code rules} must
     * already be the owner's active rules — loaded once per batch, never per
     * row.
     */
    public Match bestMatch(StatementImportItem item, List<CategoryMappingRule> rules) {
        if (item.getType() == null) {
            return null;
        }
        CategoryMappingRule best = rules.stream()
                .filter(rule -> rule.getTransactionType() == item.getType())
                .filter(rule -> rule.getAccountId() == null
                        || rule.getAccountId().equals(item.getAccountId()))
                .filter(rule -> matches(rule, item))
                .min(Comparator
                        .comparing((CategoryMappingRule rule) -> rule.getAccountId() == null ? 1 : 0)
                        .thenComparing(rule -> -rule.getPriority())
                        .thenComparing(rule -> rule.getOperation().ordinal())
                        .thenComparing(rule -> -rule.getPattern().length())
                        .thenComparing(CategoryMappingRule::getId))
                .orElse(null);
        return best == null ? null : new Match(best, confidence(best));
    }

    private static boolean matches(CategoryMappingRule rule, StatementImportItem item) {
        String text = rule.getMatchField() == CategoryRuleField.MEMO
                ? canonicalMemo(item)
                : item.getNormalizedDescription();
        if (text == null || text.isBlank()) {
            return false;
        }
        return switch (rule.getOperation()) {
            case EXACT -> text.equals(rule.getPattern());
            case STARTS_WITH -> text.startsWith(rule.getPattern());
            case CONTAINS -> text.contains(rule.getPattern());
        };
    }

    private static String canonicalMemo(StatementImportItem item) {
        return com.finora.api.statementimport.parser.TextNormalizer.canonical(item.getMemo());
    }

    private static RuleConfidence confidence(CategoryMappingRule rule) {
        return switch (rule.getOperation()) {
            case EXACT -> RuleConfidence.HIGH;
            case STARTS_WITH -> RuleConfidence.MEDIUM;
            case CONTAINS -> RuleConfidence.LOW;
        };
    }
}
