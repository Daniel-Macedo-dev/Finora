package com.finora.api.identity;

import com.finora.api.category.CategoryType;
import java.util.List;

/**
 * Single source of truth for the default categories every new user receives.
 * (The category list inside migration V1 is historical: it seeded the
 * pre-multiuser database and only survives for legacy data; fresh databases
 * get their defaults exclusively from this catalog at registration time.)
 */
public final class DefaultCategoryCatalog {

    public record CategoryDefinition(String name, CategoryType type) {
    }

    public static final List<CategoryDefinition> DEFAULTS = List.of(
            new CategoryDefinition("Moradia", CategoryType.EXPENSE),
            new CategoryDefinition("Alimentação", CategoryType.EXPENSE),
            new CategoryDefinition("Transporte", CategoryType.EXPENSE),
            new CategoryDefinition("Saúde", CategoryType.EXPENSE),
            new CategoryDefinition("Educação", CategoryType.EXPENSE),
            new CategoryDefinition("Lazer", CategoryType.EXPENSE),
            new CategoryDefinition("Compras", CategoryType.EXPENSE),
            new CategoryDefinition("Assinaturas", CategoryType.EXPENSE),
            new CategoryDefinition("Outros", CategoryType.EXPENSE),
            new CategoryDefinition("Salário", CategoryType.INCOME),
            new CategoryDefinition("Freelance", CategoryType.INCOME),
            new CategoryDefinition("Outros", CategoryType.INCOME));

    private DefaultCategoryCatalog() {
    }
}
