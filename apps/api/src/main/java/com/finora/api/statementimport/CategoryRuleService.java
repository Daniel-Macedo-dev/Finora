package com.finora.api.statementimport;

import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.category.Category;
import com.finora.api.category.CategoryRepository;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.statementimport.CategoryRuleDtos.CategoryRuleRequest;
import com.finora.api.statementimport.CategoryRuleDtos.CategoryRuleResponse;
import com.finora.api.statementimport.parser.TextNormalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-scoped CRUD for deterministic category-mapping rules. Patterns are
 * stored in the same canonical normalization applied to statement text, so
 * matching is a plain string comparison at import time.
 */
@Service
@Transactional
public class CategoryRuleService {

    private final CategoryMappingRuleRepository rules;
    private final CategoryRepository categories;
    private final AccountRepository accounts;
    private final CurrentUserProvider currentUser;

    public CategoryRuleService(CategoryMappingRuleRepository rules,
                               CategoryRepository categories,
                               AccountRepository accounts,
                               CurrentUserProvider currentUser) {
        this.rules = rules;
        this.categories = categories;
        this.accounts = accounts;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<CategoryRuleResponse> list() {
        Long userId = currentUser.currentUserId();
        List<CategoryMappingRule> all = rules.findAllByUserIdOrderByPriorityDescIdAsc(userId);
        Map<Long, String> categoryNames = new HashMap<>();
        for (Category category : categories.findAllByUserIdOrderByTypeAscNameAsc(userId)) {
            categoryNames.put(category.getId(), category.getName());
        }
        Map<Long, String> accountNames = new HashMap<>();
        for (Account account : accounts.findAllByUserIdOrderByDisplayOrderAscNameAsc(userId)) {
            accountNames.put(account.getId(), account.getName());
        }
        return all.stream()
                .map(rule -> toResponse(rule, categoryNames, accountNames))
                .toList();
    }

    public CategoryRuleResponse create(CategoryRuleRequest request) {
        Long userId = currentUser.currentUserId();
        String pattern = normalizePattern(request.pattern());
        Category category = resolveCategory(userId, request);
        Long accountId = resolveAccountScope(userId, request.accountId());
        CategoryMappingRule rule = new CategoryMappingRule(
                userId, request.transactionType(), accountId, request.matchField(),
                request.operation(), pattern, category.getId(), request.priority());
        rule.setActive(request.active());
        return toResponse(rules.save(rule), category.getName(), accountName(userId, accountId));
    }

    public CategoryRuleResponse update(Long id, CategoryRuleRequest request) {
        Long userId = currentUser.currentUserId();
        CategoryMappingRule rule = find(id, userId);
        String pattern = normalizePattern(request.pattern());
        Category category = resolveCategory(userId, request);
        Long accountId = resolveAccountScope(userId, request.accountId());
        rule.setTransactionType(request.transactionType());
        rule.setAccountId(accountId);
        rule.setMatchField(request.matchField());
        rule.setOperation(request.operation());
        rule.setPattern(pattern);
        rule.setCategoryId(category.getId());
        rule.setPriority(request.priority());
        rule.setActive(request.active());
        return toResponse(rule, category.getName(), accountName(userId, accountId));
    }

    public void delete(Long id) {
        Long userId = currentUser.currentUserId();
        rules.delete(find(id, userId));
    }

    private CategoryMappingRule find(Long id, Long userId) {
        return rules.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Regra de categoria", id));
    }

    private String normalizePattern(String raw) {
        String pattern = TextNormalizer.canonical(raw);
        if (pattern == null || pattern.isBlank()) {
            throw new BusinessRuleException("RULE_PATTERN_BLANK",
                    "O texto da regra não pode ficar vazio após a normalização.");
        }
        return TextNormalizer.truncate(pattern, 200);
    }

    private Category resolveCategory(Long userId, CategoryRuleRequest request) {
        Category category = categories.findByIdAndUserId(request.categoryId(), userId)
                .orElseThrow(() -> new NotFoundException("Categoria", request.categoryId()));
        if (!category.isActive()) {
            throw new BusinessRuleException("RULE_CATEGORY_INACTIVE",
                    "A categoria selecionada está desativada.");
        }
        if (!category.getType().name().equals(request.transactionType().name())) {
            throw new BusinessRuleException("CATEGORY_TYPE_MISMATCH",
                    "A categoria selecionada não corresponde ao tipo da regra.");
        }
        return category;
    }

    private Long resolveAccountScope(Long userId, Long accountId) {
        if (accountId == null) {
            return null;
        }
        Account account = accounts.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new NotFoundException("Conta", accountId));
        return account.getId();
    }

    private String accountName(Long userId, Long accountId) {
        if (accountId == null) {
            return null;
        }
        return accounts.findByIdAndUserId(accountId, userId)
                .map(Account::getName)
                .orElse(null);
    }

    private static CategoryRuleResponse toResponse(CategoryMappingRule rule,
                                                   Map<Long, String> categoryNames,
                                                   Map<Long, String> accountNames) {
        return toResponse(rule,
                categoryNames.get(rule.getCategoryId()),
                rule.getAccountId() == null ? null : accountNames.get(rule.getAccountId()));
    }

    private static CategoryRuleResponse toResponse(CategoryMappingRule rule, String categoryName,
                                                   String accountName) {
        return new CategoryRuleResponse(
                rule.getId(),
                rule.isActive(),
                rule.getTransactionType(),
                rule.getAccountId(),
                accountName,
                rule.getMatchField(),
                rule.getOperation(),
                rule.getPattern(),
                rule.getCategoryId(),
                Objects.requireNonNullElse(categoryName, ""),
                rule.getPriority(),
                rule.getMatchCount(),
                rule.getLastUsedAt());
    }
}
