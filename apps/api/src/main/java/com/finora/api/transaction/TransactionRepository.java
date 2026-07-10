package com.finora.api.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository
        extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    boolean existsByAccountId(Long accountId);

    boolean existsByCategoryId(Long categoryId);

    @Query("""
            select coalesce(sum(t.amount), 0)
            from Transaction t
            where t.type = :type
              and t.occurredOn >= :from
              and t.occurredOn <= :to
            """)
    BigDecimal sumAmountByTypeAndPeriod(@Param("type") TransactionType type,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to);

    @Query("""
            select coalesce(sum(t.amount), 0)
            from Transaction t
            where t.type = com.finora.api.transaction.TransactionType.EXPENSE
              and t.category.id = :categoryId
              and t.occurredOn >= :from
              and t.occurredOn <= :to
            """)
    BigDecimal sumExpensesByCategoryAndPeriod(@Param("categoryId") Long categoryId,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to);

    /** Expense totals grouped by category for a period: [categoryId, categoryName, total]. */
    @Query("""
            select t.category.id, t.category.name, sum(t.amount)
            from Transaction t
            where t.type = com.finora.api.transaction.TransactionType.EXPENSE
              and t.occurredOn >= :from
              and t.occurredOn <= :to
            group by t.category.id, t.category.name
            order by sum(t.amount) desc
            """)
    List<Object[]> sumExpensesGroupedByCategory(@Param("from") LocalDate from,
                                                @Param("to") LocalDate to);

    List<Transaction> findTop10ByOrderByOccurredOnDescIdDesc();
}
