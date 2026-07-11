package com.finora.api.purchaseanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.api.AbstractIntegrationTest;
import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.account.AccountType;
import com.finora.api.category.Category;
import com.finora.api.category.CategoryType;
import com.finora.api.purchaseanalysis.PurchaseAnalysisDtos.AnalysisResponse;
import com.finora.api.purchaseanalysis.PurchaseAnalysisDtos.OptionAnalysis;
import com.finora.api.purchaseanalysis.PurchaseAnalysisDtos.RecommendationType;
import com.finora.api.settings.AppSettings;
import com.finora.api.settings.SettingsRepository;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import com.finora.api.transaction.TransactionType;
import com.finora.api.wishlist.PurchaseOption;
import com.finora.api.wishlist.PurchaseOptionKind;
import com.finora.api.wishlist.WishlistItem;
import com.finora.api.wishlist.WishlistItemRepository;
import com.finora.api.wishlist.WishlistPriority;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Deterministic scenarios for the purchase analysis engine, all running as an
 * authenticated user with a fixed reference date so month math never depends
 * on the wall clock.
 */
class PurchaseAnalysisEngineTest extends AbstractIntegrationTest {

    private static final LocalDate REFERENCE = LocalDate.of(2026, 7, 15);

    @Autowired
    private PurchaseAnalysisService service;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private WishlistItemRepository wishlist;

    @Autowired
    private SettingsRepository settingsRepository;

    private TestUser user;
    private Category income;
    private Category expense;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser();
        actAs(user);
        income = categoryRepository
                .findByUserIdAndNameIgnoreCaseAndType(user.id(), "Salário", CategoryType.INCOME)
                .orElseThrow();
        expense = categoryRepository
                .findByUserIdAndNameIgnoreCaseAndType(user.id(), "Outros", CategoryType.EXPENSE)
                .orElseThrow();
    }

    private void givenCash(String amount) {
        accounts.save(new Account(user.id(), "Conta Teste", AccountType.CHECKING,
                new BigDecimal(amount), 0));
    }

    private void givenMonthlyHistory(String incomeAmount, String expenseAmount) {
        for (int i = 1; i <= 3; i++) {
            YearMonth month = YearMonth.from(REFERENCE).minusMonths(i);
            transactions.save(new Transaction(user.id(), TransactionType.INCOME,
                    new BigDecimal(incomeAmount), "Salário", month.atDay(5), income));
            transactions.save(new Transaction(user.id(), TransactionType.EXPENSE,
                    new BigDecimal(expenseAmount), "Gastos do mês", month.atDay(20), expense));
        }
    }

    private void givenSettings(String buffer, String maxRatio, String rate) {
        AppSettings settings = settingsRepository.findByUserId(user.id()).orElseThrow();
        settings.setMinimumCashBuffer(new BigDecimal(buffer));
        settings.setMaxInstallmentCommitmentRatio(new BigDecimal(maxRatio));
        settings.setMonthlyOpportunityRate(new BigDecimal(rate));
    }

    private WishlistItem item() {
        return wishlist.save(new WishlistItem(user.id(), "Notebook", WishlistPriority.HIGH));
    }

    private PurchaseOption cashOption(WishlistItem item, String price, String shipping) {
        PurchaseOption option = new PurchaseOption(item, "Loja à vista", PurchaseOptionKind.CASH,
                new BigDecimal(price), new BigDecimal(shipping), BigDecimal.ZERO);
        item.getOptions().add(option);
        return option;
    }

    private PurchaseOption installmentOption(WishlistItem item, String total, int count, String each) {
        PurchaseOption option = new PurchaseOption(item, "Loja parcelada", PurchaseOptionKind.INSTALLMENT,
                new BigDecimal(total), BigDecimal.ZERO, BigDecimal.ZERO);
        option.setInstallmentCount(count);
        option.setInstallmentAmount(new BigDecimal(each));
        item.getOptions().add(option);
        return option;
    }

    private AnalysisResponse analyze(WishlistItem item) {
        wishlist.flush();
        return service.analyze(item.getId(), REFERENCE);
    }

    @Test
    void recommendsCashWhenCheaperAndSafe() {
        givenCash("10000.00");
        givenMonthlyHistory("6000.00", "4000.00");
        givenSettings("2000.00", "0.30", "0");

        WishlistItem item = item();
        cashOption(item, "4500.00", "0");
        installmentOption(item, "5000.00", 10, "500.00");

        AnalysisResponse analysis = analyze(item);
        assertThat(analysis.recommendation().type()).isEqualTo(RecommendationType.BUY_CASH);
        assertThat(analysis.recommendation().reasonCodes())
                .contains("LOWEST_PRESENT_VALUE", "CASH_DISCOUNT_WORTH_IT", "NOMINAL_COMPARISON");
        OptionAnalysis cash = analysis.options().stream()
                .filter(o -> o.kind() == PurchaseOptionKind.CASH).findFirst().orElseThrow();
        assertThat(cash.cashAfterPurchase()).isEqualByComparingTo("5500.00");
        assertThat(cash.safe()).isTrue();
    }

    @Test
    void recommendsInstallmentWhenCashViolatesBuffer() {
        givenCash("5000.00");
        givenMonthlyHistory("6000.00", "4000.00");
        givenSettings("2000.00", "0.50", "0");

        WishlistItem item = item();
        cashOption(item, "4500.00", "0");
        installmentOption(item, "5000.00", 10, "500.00");

        AnalysisResponse analysis = analyze(item);
        assertThat(analysis.recommendation().type()).isEqualTo(RecommendationType.BUY_INSTALLMENT);
        assertThat(analysis.recommendation().reasonCodes()).contains("PRESERVES_LIQUIDITY");

        OptionAnalysis cash = analysis.options().stream()
                .filter(o -> o.kind() == PurchaseOptionKind.CASH).findFirst().orElseThrow();
        assertThat(cash.safe()).isFalse();
        assertThat(cash.issues()).anyMatch(issue -> issue.code().equals("BUFFER_VIOLATION"));
    }

    @Test
    void interestFreeInstallmentsBeatCashAtPositiveRate() {
        givenCash("20000.00");
        givenMonthlyHistory("10000.00", "5000.00");
        givenSettings("1000.00", "0.80", "0.01");

        WishlistItem item = item();
        cashOption(item, "1200.00", "0");
        installmentOption(item, "1200.00", 12, "100.00");

        AnalysisResponse analysis = analyze(item);
        assertThat(analysis.recommendation().type()).isEqualTo(RecommendationType.BUY_INSTALLMENT);
        assertThat(analysis.recommendation().reasonCodes()).contains("INSTALLMENTS_BEAT_CASH_AT_RATE");

        OptionAnalysis installment = analysis.options().stream()
                .filter(o -> o.kind() == PurchaseOptionKind.INSTALLMENT).findFirst().orElseThrow();
        assertThat(installment.presentValue()).isEqualByComparingTo("1125.51");
    }

    @Test
    void blocksInstallmentThatExceedsMonthlySurplus() {
        givenCash("500.00");
        givenMonthlyHistory("5000.00", "4500.00");
        givenSettings("400.00", "0.90", "0");

        WishlistItem item = item();
        installmentOption(item, "6000.00", 10, "600.00");

        AnalysisResponse analysis = analyze(item);
        OptionAnalysis option = analysis.options().getFirst();
        assertThat(option.safe()).isFalse();
        assertThat(option.issues()).anyMatch(issue -> issue.code().equals("INSTALLMENT_EXCEEDS_SURPLUS"));
        assertThat(analysis.recommendation().type()).isEqualTo(RecommendationType.WAIT);
    }

    @Test
    void blocksInstallmentThatOvercommitsIncome() {
        givenCash("10000.00");
        givenMonthlyHistory("3000.00", "1000.00");
        givenSettings("0.00", "0.30", "0");

        WishlistItem item = item();
        installmentOption(item, "10000.00", 10, "1000.00");

        AnalysisResponse analysis = analyze(item);
        OptionAnalysis option = analysis.options().getFirst();
        assertThat(option.safe()).isFalse();
        assertThat(option.issues()).anyMatch(issue -> issue.code().equals("INSTALLMENT_PRESSURE_HIGH"));
    }

    @Test
    void waitEstimatesMonthsFromAverageSurplus() {
        givenCash("1000.00");
        givenMonthlyHistory("5000.00", "4000.00");
        givenSettings("500.00", "0.30", "0");

        WishlistItem item = item();
        cashOption(item, "3500.00", "0");

        AnalysisResponse analysis = analyze(item);
        assertThat(analysis.recommendation().type()).isEqualTo(RecommendationType.WAIT);
        assertThat(analysis.recommendation().requiredAdditionalCash()).isEqualByComparingTo("3000.00");
        assertThat(analysis.recommendation().estimatedMonthsToAfford()).isEqualTo(3);
    }

    @Test
    void waitWithoutHistoryCannotEstimateMonths() {
        givenCash("100.00");
        givenSettings("500.00", "0.30", "0");

        WishlistItem item = item();
        cashOption(item, "2000.00", "0");

        AnalysisResponse analysis = analyze(item);
        assertThat(analysis.recommendation().type()).isEqualTo(RecommendationType.WAIT);
        assertThat(analysis.recommendation().estimatedMonthsToAfford()).isNull();
        assertThat(analysis.recommendation().warnings()).isNotEmpty();
        assertThat(analysis.assumptions().avgMonthlySurplus()).isNull();
        assertThat(analysis.assumptions().historyMonthsUsed()).isZero();
    }

    @Test
    void equalCostOptionsPreferCash() {
        givenCash("10000.00");
        givenMonthlyHistory("8000.00", "3000.00");
        givenSettings("1000.00", "0.80", "0");

        WishlistItem item = item();
        cashOption(item, "1000.00", "0");
        installmentOption(item, "1000.00", 10, "100.00");

        AnalysisResponse analysis = analyze(item);
        assertThat(analysis.recommendation().type()).isEqualTo(RecommendationType.BUY_CASH);
    }

    @Test
    void shippingFlipsThePreferredOption() {
        givenCash("10000.00");
        givenMonthlyHistory("8000.00", "3000.00");
        givenSettings("1000.00", "0.80", "0");

        WishlistItem item = item();
        cashOption(item, "1000.00", "80.00");
        installmentOption(item, "1050.00", 10, "105.00");

        AnalysisResponse analysis = analyze(item);
        assertThat(analysis.recommendation().type()).isEqualTo(RecommendationType.BUY_INSTALLMENT);
    }

    @Test
    void noOptionsYieldsNoOptionsRecommendation() {
        givenCash("10000.00");
        WishlistItem item = item();

        AnalysisResponse analysis = analyze(item);
        assertThat(analysis.recommendation().type()).isEqualTo(RecommendationType.NO_OPTIONS);
        assertThat(analysis.options()).isEmpty();
    }

    @Test
    void anotherUsersFinancesCannotChangeTheRecommendation() throws Exception {
        // User under test: modest cash, no history, expensive item -> WAIT.
        givenCash("1000.00");
        givenSettings("500.00", "0.30", "0");
        WishlistItem item = item();
        cashOption(item, "3500.00", "0");

        // A second, wealthy user with huge surplus and cash.
        TestUser rich = registerUser("Usuária Rica");
        accounts.save(new Account(rich.id(), "Conta Rica", AccountType.CHECKING,
                new BigDecimal("100000.00"), 0));
        Category richIncome = categoryRepository
                .findByUserIdAndNameIgnoreCaseAndType(rich.id(), "Salário", CategoryType.INCOME)
                .orElseThrow();
        for (int i = 1; i <= 3; i++) {
            YearMonth month = YearMonth.from(REFERENCE).minusMonths(i);
            transactions.save(new Transaction(rich.id(), TransactionType.INCOME,
                    new BigDecimal("50000.00"), "Salário", month.atDay(5), richIncome));
        }

        actAs(user);
        AnalysisResponse analysis = analyze(item);
        // The rich user's income must not make this purchase look affordable.
        assertThat(analysis.recommendation().type()).isEqualTo(RecommendationType.WAIT);
        assertThat(analysis.assumptions().availableCash()).isEqualByComparingTo("1000.00");
        assertThat(analysis.assumptions().avgMonthlyIncome()).isNull();
    }
}
