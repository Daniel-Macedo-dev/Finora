package com.finora.api.goal;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalRepository extends JpaRepository<Goal, Long> {

    List<Goal> findAllByOrderByArchivedAscNameAsc();

    List<Goal> findAllByArchivedFalse();
}
