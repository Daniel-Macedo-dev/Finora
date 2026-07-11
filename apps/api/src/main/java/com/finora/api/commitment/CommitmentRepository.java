package com.finora.api.commitment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommitmentRepository extends JpaRepository<Commitment, Long> {

    List<Commitment> findAllByUserIdOrderByActiveDescDescriptionAsc(Long userId);

    List<Commitment> findAllByUserIdAndActiveTrue(Long userId);

    Optional<Commitment> findByIdAndUserId(Long id, Long userId);
}
