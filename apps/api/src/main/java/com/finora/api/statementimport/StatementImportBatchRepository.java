package com.finora.api.statementimport;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementImportBatchRepository extends JpaRepository<StatementImportBatch, Long> {

    Optional<StatementImportBatch> findByIdAndUserId(Long id, Long userId);

    Page<StatementImportBatch> findAllByUserId(Long userId, Pageable pageable);

    Page<StatementImportBatch> findAllByUserIdAndAccountId(Long userId, Long accountId,
                                                           Pageable pageable);

    /** Exact file reupload detection (same owner, account and byte hash). */
    boolean existsByUserIdAndAccountIdAndFileSha256AndIdNot(Long userId, Long accountId,
                                                            String fileSha256, Long excludedId);
}
