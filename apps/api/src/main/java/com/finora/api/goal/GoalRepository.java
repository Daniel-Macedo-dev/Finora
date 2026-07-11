package com.finora.api.goal;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalRepository extends JpaRepository<Goal, Long> {

    List<Goal> findAllByUserIdOrderByArchivedAscNameAsc(Long userId);

    Optional<Goal> findByIdAndUserId(Long id, Long userId);
}
