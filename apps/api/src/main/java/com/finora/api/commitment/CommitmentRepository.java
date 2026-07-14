package com.finora.api.commitment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommitmentRepository extends JpaRepository<Commitment, Long> {

    List<Commitment> findAllByUserIdOrderByActiveDescDescriptionAsc(Long userId);

    List<Commitment> findAllByUserIdAndActiveTrue(Long userId);

    Optional<Commitment> findByIdAndUserId(Long id, Long userId);

    /** Definitions the due processor executes for one user. */
    @Query("""
            select c from Commitment c
            where c.userId = :userId
              and c.active = true
              and c.executionMode = com.finora.api.commitment.ExecutionMode.AUTOMATIC
              and c.targetKind <> com.finora.api.commitment.RecurrenceTarget.PROJECTION_ONLY
            """)
    List<Commitment> findAllAutomaticForUser(@Param("userId") Long userId);

    /** Users that currently have automatic recurring work to scan. */
    @Query("""
            select distinct c.userId from Commitment c
            where c.active = true
              and c.executionMode = com.finora.api.commitment.ExecutionMode.AUTOMATIC
              and c.targetKind <> com.finora.api.commitment.RecurrenceTarget.PROJECTION_ONLY
            """)
    List<Long> findUserIdsWithAutomaticDefinitions();
}
