package com.finora.api.category;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByOrderByTypeAscNameAsc();

    List<Category> findAllByTypeOrderByNameAsc(CategoryType type);

    Optional<Category> findByNameIgnoreCaseAndType(String name, CategoryType type);
}
