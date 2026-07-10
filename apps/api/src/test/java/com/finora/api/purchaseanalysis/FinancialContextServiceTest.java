package com.finora.api.purchaseanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.account.AccountType;
import com.finora.api.category.Category;
import com.finora.api.category.CategoryRepository;
import com.finora.api.category.CategoryType;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FinancialContextServiceTest extends AbstractIntegrationTest {

    private static final LocalDate REFERENCE = LocalDate.of(2026, 7, 15);

    @Autowired
    private FinancialContextService service;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private AccountRepository accounts;

    private Category incomeCategory() {
        return categories.findByNameIgnoreCaseAndType("Salário", CategoryType.INCOME).orElseThrow();
    }

    @Test
    void averagesDivideByMonthsWithDataNotByWindowSize() {
        // Only one of the three inspected months has data.
        YearMonth may = YearMonth.of(2026, 5);
        transactions.save(new Transaction(TransactionType.INCOME, new BigDecimal("3000.00"),
                "Salário", may.atDay(5), incomeCategory()));

        FinancialContext context = service.build(REFERENCE);

        assertThat(context.historyMonthsUsed()).isEqualTo(1);
        // 3000 / 1 month with data, not 3000 / 3.
        assertThat(context.avgMonthlyIncome()).isEqualByComparingTo("3000.00");
        assertThat(context.avgMonthlySurplus()).isEqualByComparingTo("3000.00");
    }

    @Test
    void currentPartialMonthIsExcludedFromAverages() {
        // Data only inside the reference month itself must not count as history.
        transactions.save(new Transaction(TransactionType.INCOME, new BigDecimal("9999.00"),
                "Salário", REFERENCE.withDayOfMonth(2), incomeCategory()));

        FinancialContext context = service.build(REFERENCE);

        assertThat(context.historyMonthsUsed()).isZero();
        assertThat(context.avgMonthlyIncome()).isNull();
        assertThat(context.avgMonthlySurplus()).isNull();
    }

    @Test
    void archivedAccountsDoNotCountTowardsAvailableCash() {
        accounts.save(new Account("Ativa", AccountType.CHECKING, new BigDecimal("1000.00"), 0));
        Account archived = new Account("Arquivada", AccountType.SAVINGS, new BigDecimal("500.00"), 1);
        archived.setArchived(true);
        accounts.save(archived);

        FinancialContext context = service.build(REFERENCE);

        assertThat(context.availableCash()).isEqualByComparingTo("1000.00");
    }
}
