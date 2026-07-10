package com.finora.api.commitment;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommitmentRepository extends JpaRepository<Commitment, Long> {

    List<Commitment> findAllByOrderByActiveDescDescriptionAsc();

    List<Commitment> findAllByActiveTrue();
}
