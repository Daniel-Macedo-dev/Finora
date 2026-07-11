package com.finora.api.purchaseanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.account.AccountType;
import com.finora.api.category.Category;
import com.finora.api.category.CategoryType;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FinancialContextServiceTest extends AbstractIntegrationTest {

    private static final LocalDate REFERENCE = LocalDate.of(2026, 7, 15);

    @Autowired
    private FinancialContextService service;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    private TestUser user;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
    }

    private Category incomeCategory(TestUser owner) {
        return categoryRepository
                .findByUserIdAndNameIgnoreCaseAndType(owner.id(), "Salário", CategoryType.INCOME)
                .orElseThrow();
    }

    @Test
    void averagesDivideByMonthsWithDataNotByWindowSize() {
        YearMonth may = YearMonth.of(2026, 5);
        transactions.save(new Transaction(user.id(), TransactionType.INCOME,
                new BigDecimal("3000.00"), "Salário", may.atDay(5), incomeCategory(user)));

        FinancialContext context = service.build(user.id(), REFERENCE);

        assertThat(context.historyMonthsUsed()).isEqualTo(1);
        assertThat(context.avgMonthlyIncome()).isEqualByComparingTo("3000.00");
        assertThat(context.avgMonthlySurplus()).isEqualByComparingTo("3000.00");
    }

    @Test
    void currentPartialMonthIsExcludedFromAverages() {
        transactions.save(new Transaction(user.id(), TransactionType.INCOME,
                new BigDecimal("9999.00"), "Salário", REFERENCE.withDayOfMonth(2),
                incomeCategory(user)));

        FinancialContext context = service.build(user.id(), REFERENCE);

        assertThat(context.historyMonthsUsed()).isZero();
        assertThat(context.avgMonthlyIncome()).isNull();
        assertThat(context.avgMonthlySurplus()).isNull();
    }

    @Test
    void archivedAccountsDoNotCountTowardsAvailableCash() {
        accounts.save(new Account(user.id(), "Ativa", AccountType.CHECKING,
                new BigDecimal("1000.00"), 0));
        Account archived = new Account(user.id(), "Arquivada", AccountType.SAVINGS,
                new BigDecimal("500.00"), 1);
        archived.setArchived(true);
        accounts.save(archived);

        FinancialContext context = service.build(user.id(), REFERENCE);

        assertThat(context.availableCash()).isEqualByComparingTo("1000.00");
    }

    @Test
    void contextIsFullyIsolatedBetweenUsers() throws Exception {
        // User with modest finances.
        accounts.save(new Account(user.id(), "Conta A", AccountType.CHECKING,
                new BigDecimal("100.00"), 0));

        // A much richer second user must not leak into the first user's context.
        TestUser rich = registerUser("Usuária Rica");
        accounts.save(new Account(rich.id(), "Conta B", AccountType.CHECKING,
                new BigDecimal("50000.00"), 0));
        YearMonth june = YearMonth.of(2026, 6);
        transactions.save(new Transaction(rich.id(), TransactionType.INCOME,
                new BigDecimal("30000.00"), "Salário alto", june.atDay(5), incomeCategory(rich)));

        FinancialContext context = service.build(user.id(), REFERENCE);

        assertThat(context.availableCash()).isEqualByComparingTo("100.00");
        assertThat(context.avgMonthlyIncome()).isNull();
        assertThat(context.historyMonthsUsed()).isZero();
    }
}
