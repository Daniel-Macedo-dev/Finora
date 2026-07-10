package com.finora.api.category;

import com.finora.api.category.CategoryDtos.CategoryRequest;
import com.finora.api.category.CategoryDtos.CategoryResponse;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.transaction.TransactionRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CategoryService {

    private final CategoryRepository categories;
    private final TransactionRepository transactions;

    public CategoryService(CategoryRepository categories, TransactionRepository transactions) {
        this.categories = categories;
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> list(CategoryType type) {
        List<Category> result = type != null
                ? categories.findAllByTypeOrderByNameAsc(type)
                : categories.findAllByOrderByTypeAscNameAsc();
        return result.stream().map(CategoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponse get(Long id) {
        return CategoryResponse.from(find(id));
    }

    public CategoryResponse create(CategoryRequest request) {
        String name = request.name().trim();
        categories.findByNameIgnoreCaseAndType(name, request.type()).ifPresent(existing -> {
            throw new BusinessRuleException("CATEGORY_NAME_TAKEN",
                    "Já existe uma categoria com esse nome para esse tipo.");
        });
        Category category = new Category(name, request.type());
        if (request.active() != null) {
            category.setActive(request.active());
        }
        return CategoryResponse.from(categories.save(category));
    }

    public CategoryResponse update(Long id, CategoryRequest request) {
        Category category = find(id);
        if (request.type() != category.getType()) {
            throw new BusinessRuleException("CATEGORY_TYPE_IMMUTABLE",
                    "O tipo de uma categoria não pode ser alterado.");
        }
        String name = request.name().trim();
        categories.findByNameIgnoreCaseAndType(name, request.type()).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new BusinessRuleException("CATEGORY_NAME_TAKEN",
                        "Já existe uma categoria com esse nome para esse tipo.");
            }
        });
        category.setName(name);
        if (request.active() != null) {
            category.setActive(request.active());
        }
        return CategoryResponse.from(category);
    }

    public void delete(Long id) {
        Category category = find(id);
        if (transactions.existsByCategoryId(id)) {
            throw new BusinessRuleException(
                    "CATEGORY_HAS_TRANSACTIONS",
                    "Esta categoria possui transações e não pode ser excluída. Desative a categoria para preservá-la no histórico.");
        }
        categories.delete(category);
    }

    private Category find(Long id) {
        return categories.findById(id).orElseThrow(() -> new NotFoundException("Categoria", id));
    }
}
