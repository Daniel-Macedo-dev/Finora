package com.finora.api.statementimport;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StatementImportItemRepository extends JpaRepository<StatementImportItem, Long> {

    Optional<StatementImportItem> findByIdAndUserId(Long id, Long userId);

    List<StatementImportItem> findAllByBatchIdAndUserIdOrderBySourceIndexAsc(Long batchId,
                                                                             Long userId);

    List<StatementImportItem> findAllByBatchIdAndUserIdAndIdInOrderBySourceIndexAsc(
            Long batchId, Long userId, Collection<Long> ids);

    /**
     * Claims one item for confirmation or undo: the row lock serializes
     * concurrent attempts so the second one re-reads the final state instead
     * of double-materializing (the partial unique index on
     * transactions.statement_import_item_id remains the database backstop).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from StatementImportItem i where i.id = :id and i.userId = :userId")
    Optional<StatementImportItem> lockByIdAndUserId(@Param("id") Long id,
                                                    @Param("userId") Long userId);

    /** Already-imported external ids among the given ones (strong identity). */
    @Query("""
            select i.externalId from StatementImportItem i
            where i.userId = :userId
              and i.accountId = :accountId
              and i.status = com.finora.api.statementimport.StatementImportItemStatus.IMPORTED
              and i.externalId in :externalIds
            """)
    List<String> findImportedExternalIds(@Param("userId") Long userId,
                                         @Param("accountId") Long accountId,
                                         @Param("externalIds") Collection<String> externalIds);

    /** Already-imported content fingerprints among the given ones. */
    @Query("""
            select i.fingerprint from StatementImportItem i
            where i.userId = :userId
              and i.accountId = :accountId
              and i.status = com.finora.api.statementimport.StatementImportItemStatus.IMPORTED
              and i.fingerprint in :fingerprints
            """)
    List<String> findImportedFingerprints(@Param("userId") Long userId,
                                          @Param("accountId") Long accountId,
                                          @Param("fingerprints") Collection<String> fingerprints);

    long countByBatchIdAndStatus(Long batchId, StatementImportItemStatus status);

    /** Bulk lookup for transaction responses' import audit metadata. */
    List<StatementImportItem> findAllByUserIdAndIdIn(Long userId, Collection<Long> ids);

    /** Item state histogram of one batch, for status recomputation and totals. */
    @Query("""
            select i.status as status, count(i) as total from StatementImportItem i
            where i.batchId = :batchId group by i.status
            """)
    List<StatusCount> countByStatus(@Param("batchId") Long batchId);

    interface StatusCount {
        StatementImportItemStatus getStatus();

        long getTotal();
    }
}
