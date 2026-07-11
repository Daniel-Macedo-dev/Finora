package com.finora.api.budget;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    List<Budget> findAllByUserIdAndMonthRefOrderByIdAsc(Long userId, LocalDate monthRef);

    Optional<Budget> findByUserIdAndMonthRefAndCategoryId(Long userId, LocalDate monthRef, Long categoryId);

    Optional<Budget> findByIdAndUserId(Long id, Long userId);
}
