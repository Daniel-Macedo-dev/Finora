package com.finora.api.purchaseanalysis;

import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.purchaseanalysis.PurchaseAnalysisDtos.AnalysisAssumptions;
import com.finora.api.purchaseanalysis.PurchaseAnalysisDtos.AnalysisResponse;
import com.finora.api.purchaseanalysis.PurchaseAnalysisDtos.OptionAnalysis;
import com.finora.api.purchaseanalysis.PurchaseAnalysisDtos.OptionIssue;
import com.finora.api.purchaseanalysis.PurchaseAnalysisDtos.Recommendation;
import com.finora.api.purchaseanalysis.PurchaseAnalysisDtos.RecommendationType;
import com.finora.api.settings.AppSettings;
import com.finora.api.settings.SettingsService;
import com.finora.api.wishlist.PurchaseOption;
import com.finora.api.wishlist.PurchaseOptionKind;
import com.finora.api.wishlist.WishlistItem;
import com.finora.api.wishlist.WishlistItemRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic comparison of the purchase options of a wishlist item.
 *
 * <p>Every rule is arithmetic over Finora data plus the configured settings —
 * there is no scoring model and no external service. The full method is
 * documented in docs/purchase-analysis.md; in short:
 *
 * <ul>
 *   <li>an option's present value discounts each installment by the monthly
 *       opportunity rate (rate 0 degrades to the nominal comparison);</li>
 *   <li>a CASH option is unsafe when paying it would leave available cash
 *       below the configured minimum buffer;</li>
 *   <li>an INSTALLMENT option is unsafe when its monthly installment exceeds
 *       the average monthly surplus, or when installment + recurring
 *       commitments exceed the configured share of average income;</li>
 *   <li>among safe options the lowest present value wins (ties: lowest nominal
 *       cost, then CASH before INSTALLMENT);</li>
 *   <li>with no safe option the engine recommends waiting and, when surplus
 *       history exists, estimates how many months of average surplus close
 *       the gap.</li>
 * </ul>
 */
@Service
public class PurchaseAnalysisService {

    private final WishlistItemRepository items;
    private final SettingsService settings;
    private final FinancialContextService contextService;

    public PurchaseAnalysisService(WishlistItemRepository items,
                                   SettingsService settings,
                                   FinancialContextService contextService) {
        this.items = items;
        this.settings = settings;
        this.contextService = contextService;
    }

    @Transactional(readOnly = true)
    public AnalysisResponse analyze(Long itemId, LocalDate referenceDate) {
        WishlistItem item = items.findById(itemId)
                .orElseThrow(() -> new NotFoundException("Item da lista de desejos", itemId));
        AppSettings config = settings.current();
        FinancialContext context = contextService.build(referenceDate);

        List<OptionAnalysis> analyses = item.getOptions().stream()
                .map(option -> analyzeOption(option, config, context))
                .toList();

        return new AnalysisResponse(
                item.getId(),
                item.getName(),
                new AnalysisAssumptions(
                        context.availableCash(),
                        config.getMinimumCashBuffer(),
                        config.getMonthlyOpportunityRate(),
                        config.getMaxInstallmentCommitmentRatio(),
                        context.avgMonthlyIncome(),
                        context.avgMonthlyExpense(),
                        context.avgMonthlySurplus(),
                        context.monthlyCommitments(),
                        context.historyMonthsUsed()),
                analyses,
                recommend(analyses, config, context));
    }

    private OptionAnalysis analyzeOption(PurchaseOption option, AppSettings config, FinancialContext context) {
        BigDecimal nominal = MoneyRules.normalize(option.nominalCost());
        BigDecimal presentValue = presentValue(option, config.getMonthlyOpportunityRate());
        List<OptionIssue> issues = new ArrayList<>();

        BigDecimal upfront;
        BigDecimal monthlyBurden = null;
        if (option.getKind() == PurchaseOptionKind.CASH) {
            upfront = nominal;
        } else {
            upfront = MoneyRules.normalize(option.getShipping().add(option.getFees()));
            monthlyBurden = option.getInstallmentAmount();
            checkInstallmentPressure(option, config, context, issues);
        }

        BigDecimal cashAfter = MoneyRules.normalize(context.availableCash().subtract(upfront));
        if (cashAfter.compareTo(config.getMinimumCashBuffer()) < 0) {
            issues.add(new OptionIssue(
                    "BUFFER_VIOLATION",
                    "Pagar %s deixaria o caixa em %s, abaixo da reserva mínima de %s."
                            .formatted(brl(upfront), brl(cashAfter), brl(config.getMinimumCashBuffer())),
                    true));
        }

        boolean safe = issues.stream().noneMatch(OptionIssue::blocking);
        return new OptionAnalysis(
                option.getId(),
                option.getMerchant(),
                option.getKind(),
                nominal,
                presentValue,
                upfront,
                monthlyBurden,
                option.getInstallmentCount(),
                cashAfter,
                safe,
                issues);
    }

    private void checkInstallmentPressure(PurchaseOption option, AppSettings config,
                                          FinancialContext context, List<OptionIssue> issues) {
        BigDecimal installment = option.getInstallmentAmount();

        if (context.avgMonthlySurplus() != null) {
            if (installment.compareTo(context.avgMonthlySurplus()) > 0) {
                issues.add(new OptionIssue(
                        "INSTALLMENT_EXCEEDS_SURPLUS",
                        ("A parcela de %s é maior que a sobra média mensal de %s — os próximos %d meses "
                                + "tendem a fechar no vermelho.")
                                .formatted(brl(installment), brl(context.avgMonthlySurplus()),
                                        option.getInstallmentCount()),
                        true));
            }
        } else {
            issues.add(new OptionIssue(
                    "INSUFFICIENT_SURPLUS_HISTORY",
                    "Sem histórico de meses anteriores não é possível verificar se a parcela cabe na sobra mensal.",
                    false));
        }

        if (context.avgMonthlyIncome() != null && context.avgMonthlyIncome().signum() > 0) {
            BigDecimal committed = installment.add(context.monthlyCommitments());
            BigDecimal ratio = committed.divide(context.avgMonthlyIncome(),
                    MoneyRules.RATE_SCALE, RoundingMode.HALF_UP);
            if (ratio.compareTo(config.getMaxInstallmentCommitmentRatio()) > 0) {
                issues.add(new OptionIssue(
                        "INSTALLMENT_PRESSURE_HIGH",
                        ("Parcela + compromissos recorrentes (%s) comprometeriam %s%% da renda média, acima do "
                                + "limite configurado de %s%%.")
                                .formatted(brl(committed),
                                        toPercent(ratio),
                                        toPercent(config.getMaxInstallmentCommitmentRatio())),
                        true));
            }
        } else {
            issues.add(new OptionIssue(
                    "INSUFFICIENT_INCOME_HISTORY",
                    "Sem histórico de renda não é possível verificar o comprometimento da renda mensal.",
                    false));
        }
    }

    /**
     * Present value of the option at the monthly opportunity rate: upfront
     * extras stay at face value, each installment k (1-based) is divided by
     * (1 + rate)^k. A zero rate degrades to the nominal comparison.
     */
    static BigDecimal presentValue(PurchaseOption option, BigDecimal monthlyRate) {
        if (option.getKind() == PurchaseOptionKind.CASH || monthlyRate.signum() == 0) {
            return MoneyRules.normalize(option.nominalCost());
        }
        BigDecimal factor = BigDecimal.ONE;
        BigDecimal onePlusRate = BigDecimal.ONE.add(monthlyRate);
        BigDecimal discounted = BigDecimal.ZERO;
        for (int k = 1; k <= option.getInstallmentCount(); k++) {
            factor = factor.divide(onePlusRate, MoneyRules.RATE_SCALE, RoundingMode.HALF_UP);
            discounted = discounted.add(option.getInstallmentAmount().multiply(factor));
        }
        return MoneyRules.normalize(discounted.add(option.getShipping()).add(option.getFees()));
    }

    private Recommendation recommend(List<OptionAnalysis> analyses, AppSettings config,
                                     FinancialContext context) {
        if (analyses.isEmpty()) {
            return new Recommendation(RecommendationType.NO_OPTIONS, null, List.of(),
                    "Cadastre opções de compra para gerar a análise.", List.of(), null, null);
        }

        List<String> warnings = new ArrayList<>();
        if (context.historyMonthsUsed() == 0) {
            warnings.add("A análise usa apenas o caixa atual: ainda não há meses anteriores com "
                    + "transações para estimar renda e sobra mensal.");
        }

        List<OptionAnalysis> safeOptions = analyses.stream().filter(OptionAnalysis::safe).toList();
        if (!safeOptions.isEmpty()) {
            OptionAnalysis best = safeOptions.stream()
                    .min(Comparator.comparing(OptionAnalysis::presentValue)
                            .thenComparing(OptionAnalysis::nominalCost)
                            .thenComparing(a -> a.kind() == PurchaseOptionKind.CASH ? 0 : 1)
                            .thenComparing(OptionAnalysis::optionId))
                    .orElseThrow();
            return buildBuyRecommendation(best, analyses, config, warnings);
        }
        return buildWaitRecommendation(analyses, config, context, warnings);
    }

    private Recommendation buildBuyRecommendation(OptionAnalysis best, List<OptionAnalysis> all,
                                                  AppSettings config, List<String> warnings) {
        List<String> reasons = new ArrayList<>();
        reasons.add("LOWEST_PRESENT_VALUE");
        if (config.getMonthlyOpportunityRate().signum() == 0) {
            reasons.add("NOMINAL_COMPARISON");
        }
        boolean isCash = best.kind() == PurchaseOptionKind.CASH;
        if (isCash && all.stream().anyMatch(o -> o.kind() == PurchaseOptionKind.INSTALLMENT
                && o.presentValue().compareTo(best.presentValue()) > 0)) {
            reasons.add("CASH_DISCOUNT_WORTH_IT");
        }
        if (!isCash && all.stream().anyMatch(o -> o.kind() == PurchaseOptionKind.CASH && !o.safe())) {
            reasons.add("PRESERVES_LIQUIDITY");
        }
        if (!isCash && all.stream().anyMatch(o -> o.kind() == PurchaseOptionKind.CASH && o.safe()
                && o.presentValue().compareTo(best.presentValue()) > 0)) {
            reasons.add("INSTALLMENTS_BEAT_CASH_AT_RATE");
        }

        best.issues().stream().filter(issue -> !issue.blocking())
                .forEach(issue -> warnings.add(issue.message()));

        String explanation = isCash
                ? ("Comprar à vista em %s custa %s, o menor valor presente entre as opções, e mantém o caixa "
                        + "acima da reserva mínima (%s após a compra).")
                        .formatted(best.merchant(), brl(best.presentValue()), brl(best.cashAfterPurchase()))
                : ("Parcelar em %s (%d× de %s) tem o menor valor presente (%s) considerando a taxa de "
                        + "oportunidade configurada, e a parcela cabe nas margens definidas.")
                        .formatted(best.merchant(), best.installmentCount(), brl(best.monthlyBurden()),
                                brl(best.presentValue()));

        return new Recommendation(
                isCash ? RecommendationType.BUY_CASH : RecommendationType.BUY_INSTALLMENT,
                best.optionId(), reasons, explanation, warnings, null, null);
    }

    private Recommendation buildWaitRecommendation(List<OptionAnalysis> analyses, AppSettings config,
                                                   FinancialContext context, List<String> warnings) {
        // The gap to close: smallest shortfall between required cash (upfront +
        // buffer) and available cash across the options.
        BigDecimal requiredAdditionalCash = analyses.stream()
                .map(option -> option.upfrontCost()
                        .add(config.getMinimumCashBuffer())
                        .subtract(context.availableCash()))
                .filter(gap -> gap.signum() > 0)
                .min(Comparator.naturalOrder())
                .map(MoneyRules::normalize)
                .orElse(null);

        Integer months = null;
        if (requiredAdditionalCash != null
                && context.avgMonthlySurplus() != null
                && context.avgMonthlySurplus().signum() > 0) {
            months = requiredAdditionalCash
                    .divide(context.avgMonthlySurplus(), 0, RoundingMode.CEILING)
                    .intValueExact();
        } else if (requiredAdditionalCash != null) {
            warnings.add("Não há sobra mensal média positiva para estimar em quantos meses a compra ficaria segura.");
        }

        String explanation = months != null
                ? ("Nenhuma opção é segura agora. Guardando a sobra média mensal, a projeção é de que faltem "
                        + "cerca de %d mês(es) para acumular os %s que separam o caixa atual de uma compra segura.")
                        .formatted(months, brl(requiredAdditionalCash))
                : "Nenhuma opção é segura agora: os valores violariam a reserva mínima de caixa ou as margens "
                        + "mensais configuradas.";

        return new Recommendation(RecommendationType.WAIT, null, List.of("NO_SAFE_OPTION"),
                explanation, warnings, requiredAdditionalCash, months);
    }

    private static String brl(BigDecimal value) {
        return "R$ " + MoneyRules.normalize(value).toPlainString().replace('.', ',');
    }

    private static String toPercent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP)
                .toPlainString()
                .replace('.', ',');
    }
}
