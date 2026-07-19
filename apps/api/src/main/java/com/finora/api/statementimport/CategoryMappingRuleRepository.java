package com.finora.api.statementimport;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryMappingRuleRepository extends JpaRepository<CategoryMappingRule, Long> {

    Optional<CategoryMappingRule> findByIdAndUserId(Long id, Long userId);

    List<CategoryMappingRule> findAllByUserIdOrderByPriorityDescIdAsc(Long userId);

    List<CategoryMappingRule> findAllByUserIdAndActiveTrue(Long userId);
}
