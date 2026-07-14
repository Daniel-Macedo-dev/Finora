package com.finora.api.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository
        extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByIdAndUserId(Long id, Long userId);

    /** Guards for delete protection; the parent id is always owner-verified first. */
    boolean existsByAccountId(Long accountId);

    boolean existsByCategoryId(Long categoryId);

    @Query("""
            select coalesce(sum(t.amount), 0)
            from Transaction t
            where t.userId = :userId
              and t.type = :type
              and t.occurredOn >= :from
              and t.occurredOn <= :to
            """)
    BigDecimal sumAmountByTypeAndPeriod(@Param("userId") Long userId,
                                        @Param("type") TransactionType type,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to);

    @Query("""
            select coalesce(sum(t.amount), 0)
            from Transaction t
            where t.userId = :userId
              and t.type = com.finora.api.transaction.TransactionType.EXPENSE
              and t.category.id = :categoryId
              and t.occurredOn >= :from
              and t.occurredOn <= :to
            """)
    BigDecimal sumExpensesByCategoryAndPeriod(@Param("userId") Long userId,
                                              @Param("categoryId") Long categoryId,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to);

    /** The user's expense totals grouped by category: [categoryId, categoryName, total]. */
    @Query("""
            select t.category.id, t.category.name, sum(t.amount)
            from Transaction t
            where t.userId = :userId
              and t.type = com.finora.api.transaction.TransactionType.EXPENSE
              and t.occurredOn >= :from
              and t.occurredOn <= :to
            group by t.category.id, t.category.name
            order by sum(t.amount) desc
            """)
    List<Object[]> sumExpensesGroupedByCategory(@Param("userId") Long userId,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);

    List<Transaction> findTop10ByUserIdOrderByOccurredOnDescIdDesc(Long userId);

    /** Future-dated real transactions inside the forecast window. */
    @EntityGraph(attributePaths = {"category", "account"})
    List<Transaction> findAllByUserIdAndOccurredOnGreaterThanAndOccurredOnLessThanEqualOrderByOccurredOnAsc(
            Long userId, LocalDate after, LocalDate through);
}
