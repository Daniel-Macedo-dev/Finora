package com.finora.api.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findAllByUserIdOrderByDisplayOrderAscNameAsc(Long userId);

    Optional<Account> findByUserIdAndNameIgnoreCase(Long userId, String name);

    Optional<Account> findByIdAndUserId(Long id, Long userId);

    /**
     * Net movement of one of the user's accounts: incomes minus expenses.
     * The user predicate is defense in depth — the account id is always
     * owner-verified before this runs. Returns null without transactions.
     * Financially inactive rows (sources of an active legacy-credit
     * conversion) no longer move cash: the generated invoices settle through
     * payments instead, so counting both would move the money twice.
     */
    @Query("""
            select sum(case when t.type = com.finora.api.transaction.TransactionType.INCOME
                            then t.amount else -t.amount end)
            from Transaction t
            where t.account.id = :accountId
              and t.userId = :userId
              and t.financiallyActive = true
            """)
    BigDecimal netMovement(@Param("accountId") Long accountId, @Param("userId") Long userId);

    /**
     * Net movement of every account of one user in a single query:
     * {@code [accountId, net]}.
     *
     * <p>The overview needs a balance for each row, and asking per account is a
     * query per account. Accounts without movement are simply absent from the
     * result — the caller already holds the opening balance.
     */
    @Query("""
            select t.account.id,
                   sum(case when t.type = com.finora.api.transaction.TransactionType.INCOME
                            then t.amount else -t.amount end)
            from Transaction t
            where t.userId = :userId
              and t.account.id is not null
              and t.financiallyActive = true
            group by t.account.id
            """)
    List<Object[]> netMovementGroupedByAccount(@Param("userId") Long userId);

    /** Net movement recognized up to a reference date (forecast opening balance). */
    @Query("""
            select sum(case when t.type = com.finora.api.transaction.TransactionType.INCOME
                            then t.amount else -t.amount end)
            from Transaction t
            where t.account.id = :accountId
              and t.userId = :userId
              and t.financiallyActive = true
              and t.occurredOn <= :through
            """)
    BigDecimal netMovementThrough(@Param("accountId") Long accountId,
                                  @Param("userId") Long userId,
                                  @Param("through") LocalDate through);
}
