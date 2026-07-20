package com.finora.api.statementimport;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StatementImportBatchRepository extends JpaRepository<StatementImportBatch, Long> {

    Optional<StatementImportBatch> findByIdAndUserId(Long id, Long userId);

    /**
     * Claims the batch for the status recomputation after a confirmation or
     * undo run: concurrent runs serialize on the row instead of racing the
     * optimistic version and surfacing a 500 to one of them.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from StatementImportBatch b where b.id = :id and b.userId = :userId")
    Optional<StatementImportBatch> lockByIdAndUserId(@Param("id") Long id,
                                                     @Param("userId") Long userId);

    Page<StatementImportBatch> findAllByUserId(Long userId, Pageable pageable);

    Page<StatementImportBatch> findAllByUserIdAndAccountId(Long userId, Long accountId,
                                                           Pageable pageable);

    /** Exact file reupload detection (same owner, account and byte hash). */
    boolean existsByUserIdAndAccountIdAndFileSha256AndIdNot(Long userId, Long accountId,
                                                            String fileSha256, Long excludedId);
}
