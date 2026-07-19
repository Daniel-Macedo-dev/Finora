package com.finora.api.statementimport;

import com.finora.api.statementimport.CategoryRuleDtos.CategoryRuleRequest;
import com.finora.api.statementimport.CategoryRuleDtos.CategoryRuleResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Owner-scoped CRUD for deterministic category-mapping rules. */
@RestController
@RequestMapping("/api/category-mapping-rules")
public class CategoryRuleController {

    private final CategoryRuleService service;

    public CategoryRuleController(CategoryRuleService service) {
        this.service = service;
    }

    @GetMapping
    public List<CategoryRuleResponse> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryRuleResponse create(@Valid @RequestBody CategoryRuleRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public CategoryRuleResponse update(@PathVariable Long id,
                                       @Valid @RequestBody CategoryRuleRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
