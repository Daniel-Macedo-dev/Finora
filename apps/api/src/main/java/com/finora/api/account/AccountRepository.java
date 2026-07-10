package com.finora.api.account;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findAllByOrderByDisplayOrderAscNameAsc();

    Optional<Account> findByNameIgnoreCase(String name);

    /**
     * Net movement of an account: sum of incomes minus sum of expenses.
     * Returns null when the account has no transactions.
     */
    @Query("""
            select sum(case when t.type = com.finora.api.transaction.TransactionType.INCOME
                            then t.amount else -t.amount end)
            from Transaction t
            where t.account.id = :accountId
            """)
    BigDecimal netMovement(@Param("accountId") Long accountId);
}
