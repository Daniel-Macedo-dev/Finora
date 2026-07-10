package com.finora.api.budget;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    List<Budget> findAllByMonthRefOrderByIdAsc(LocalDate monthRef);

    Optional<Budget> findByMonthRefAndCategoryId(LocalDate monthRef, Long categoryId);
}
