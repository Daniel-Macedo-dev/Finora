package com.finora.api.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository
        extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByIdAndUserId(Long id, Long userId);

    /** Owner-scoped: another user's client identity resolves to empty. */
    Optional<Transaction> findByUserIdAndClientResourceId(Long userId, UUID clientResourceId);

    /**
     * Owner-scoped lookup with a pessimistic write lock. The legacy-conversion
     * engine claims the source row with this before re-checking eligibility,
     * so two concurrent conversions (or a conversion racing a reversal)
     * serialize instead of double-writing.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select t from Transaction t
            where t.id = :id and t.userId = :userId
            """)
    Optional<Transaction> findByIdAndUserIdForUpdate(@Param("id") Long id,
                                                     @Param("userId") Long userId);

    /** Guards for delete protection; the parent id is always owner-verified first. */
    boolean existsByAccountId(Long accountId);

    boolean existsByCategoryId(Long categoryId);

    // Every financial aggregate below excludes financially inactive rows: a
    // transaction replaced by an active legacy-credit conversion is an audit
    // record — the generated card installments carry its expense instead.

    @Query("""
            select coalesce(sum(t.amount), 0)
            from Transaction t
            where t.userId = :userId
              and t.type = :type
              and t.financiallyActive = true
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
              and t.financiallyActive = true
              and t.category.id = :categoryId
              and t.occurredOn >= :from
              and t.occurredOn <= :to
            """)
    BigDecimal sumExpensesByCategoryAndPeriod(@Param("userId") Long userId,
                                              @Param("categoryId") Long categoryId,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to);

    /**
     * Monthly totals of a window split by type and currency:
     * {@code [year, month, type, currency, total]}.
     *
     * <p>One query answers the whole dashboard trend as well as the current and
     * previous month's income and expenses. Grouping by currency in the database
     * is what keeps a USD row from being added to a BRL one on the way out.
     */
    @Query("""
            select year(t.occurredOn), month(t.occurredOn), t.type, t.currency, sum(t.amount)
            from Transaction t
            where t.userId = :userId
              and t.financiallyActive = true
              and t.occurredOn >= :from
              and t.occurredOn <= :to
            group by year(t.occurredOn), month(t.occurredOn), t.type, t.currency
            """)
    List<Object[]> sumGroupedByMonthTypeAndCurrency(@Param("userId") Long userId,
                                                    @Param("from") LocalDate from,
                                                    @Param("to") LocalDate to);

    /**
     * Expense totals of one category in a period, split by currency:
     * {@code [currency, total]}. A budget consumes only what is denominated in
     * the budget's own currency; the rest has to be reported separately.
     */
    @Query("""
            select t.currency, sum(t.amount)
            from Transaction t
            where t.userId = :userId
              and t.type = com.finora.api.transaction.TransactionType.EXPENSE
              and t.financiallyActive = true
              and t.category.id = :categoryId
              and t.occurredOn >= :from
              and t.occurredOn <= :to
            group by t.currency
            """)
    List<Object[]> sumExpensesByCategoryAndPeriodGroupedByCurrency(
            @Param("userId") Long userId,
            @Param("categoryId") Long categoryId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * Expense totals of every category in a period, split by currency:
     * {@code [categoryId, categoryName, currency, total]}. One query for the
     * whole month's budget consumption, so a summary never costs a query per
     * budget.
     */
    @Query("""
            select t.category.id, t.category.name, t.currency, sum(t.amount)
            from Transaction t
            where t.userId = :userId
              and t.type = com.finora.api.transaction.TransactionType.EXPENSE
              and t.financiallyActive = true
              and t.occurredOn >= :from
              and t.occurredOn <= :to
            group by t.category.id, t.category.name, t.currency
            """)
    List<Object[]> sumExpensesGroupedByCategoryAndCurrency(@Param("userId") Long userId,
                                                           @Param("from") LocalDate from,
                                                           @Param("to") LocalDate to);

    /** The user's expense totals grouped by category: [categoryId, categoryName, total]. */
    @Query("""
            select t.category.id, t.category.name, sum(t.amount)
            from Transaction t
            where t.userId = :userId
              and t.type = com.finora.api.transaction.TransactionType.EXPENSE
              and t.financiallyActive = true
              and t.occurredOn >= :from
              and t.occurredOn <= :to
            group by t.category.id, t.category.name
            order by sum(t.amount) desc
            """)
    List<Object[]> sumExpensesGroupedByCategory(@Param("userId") Long userId,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);

    /**
     * How many of the user's transactions are denominated outside one currency.
     *
     * <p>Used to decide whether a single running balance can honestly be
     * projected: an accountless foreign transaction is enough to make one
     * meaningless, and no account or card would reveal it.
     */
    long countByUserIdAndCurrencyNot(Long userId, com.finora.api.common.money.CurrencyCode currency);

    List<Transaction> findTop10ByUserIdOrderByOccurredOnDescIdDesc(Long userId);

    /** Future-dated real transactions inside the forecast window (active cash only). */
    @EntityGraph(attributePaths = {"category", "account"})
    @Query("""
            select t from Transaction t
            where t.userId = :userId
              and t.financiallyActive = true
              and t.occurredOn > :after
              and t.occurredOn <= :through
            order by t.occurredOn asc
            """)
    List<Transaction> findActiveInForecastWindow(@Param("userId") Long userId,
                                                 @Param("after") LocalDate after,
                                                 @Param("through") LocalDate through);

    // ── Statement import ────────────────────────────────────────────────────

    /**
     * Candidate pool for possible-duplicate detection: the account's live
     * transactions inside the statement's (padded) date window, loaded once
     * per batch — never one query per row.
     */
    List<Transaction> findAllByUserIdAndAccountIdAndFinanciallyActiveTrueAndOccurredOnBetween(
            Long userId, Long accountId, LocalDate from, LocalDate to);

    /** The live transaction materialized from one import item, if any. */
    Optional<Transaction> findByUserIdAndStatementImportItemId(Long userId,
                                                               Long statementImportItemId);

    /** Bulk lookup of generated transactions for batch detail responses. */
    List<Transaction> findAllByUserIdAndStatementImportItemIdIn(
            Long userId, java.util.Collection<Long> statementImportItemIds);

    /** Bulk owner-scoped lookup (duplicate-match summaries in batch detail). */
    List<Transaction> findAllByUserIdAndIdIn(Long userId, java.util.Collection<Long> ids);
}
