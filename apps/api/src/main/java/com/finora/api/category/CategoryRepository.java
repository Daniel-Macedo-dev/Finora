package com.finora.api.category;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByUserIdOrderByTypeAscNameAsc(Long userId);

    List<Category> findAllByUserIdAndTypeOrderByNameAsc(Long userId, CategoryType type);

    Optional<Category> findByUserIdAndNameIgnoreCaseAndType(Long userId, String name, CategoryType type);

    Optional<Category> findByIdAndUserId(Long id, Long userId);
}
