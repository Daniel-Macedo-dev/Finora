package com.finora.api.purchaseanalysis;

import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.CardLimitService;
import com.finora.api.creditcard.CardLimitService.CardLimit;
import com.finora.api.creditcard.CreditCard;
import com.finora.api.creditcard.InvoiceCycleCalculator;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.purchaseanalysis.PurchaseAnalysisDtos.AnalysisAssumptions;
import com.finora.api.common.money.CurrencyCode;
import com.finora.api.purchaseanalysis.PurchaseAnalysisDtos.AnalysisResponse;
import com.finora.api.purchaseanalysis.PurchaseAnalysisDtos.UnavailableReason;
import com.finora.api.purchaseanalysis.PurchaseAnalysisDtos.CardAnalysis;
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
    private final PurchaseFinancialContextService contextService;
    private final CardLimitService cardLimits;
    private final CurrentUserProvider currentUser;

    public PurchaseAnalysisService(WishlistItemRepository items,
                                   SettingsService settings,
                                   PurchaseFinancialContextService contextService,
                                   CardLimitService cardLimits,
                                   CurrentUserProvider currentUser) {
        this.items = items;
        this.settings = settings;
        this.contextService = contextService;
        this.cardLimits = cardLimits;
        this.currentUser = currentUser;
    }

    /**
     * Analyzes one of the authenticated user's wishlist items using only that
     * user's settings and financial context. A foreign item id resolves to 404.
     */
    @Transactional(readOnly = true)
    public AnalysisResponse analyze(Long itemId, LocalDate referenceDate) {
        Long userId = currentUser.currentUserId();
        // Owner scope is settled before any currency is even looked at, so
        // another user's item stays indistinguishable from a missing one.
        WishlistItem item = items.findByIdAndUserId(itemId, userId)
                .orElseThrow(() -> new NotFoundException("Item da lista de desejos", itemId));
        AppSettings config = settings.forUser(userId);
        PurchaseFinancialContext context = contextService.build(userId, referenceDate);
        CurrencyCode base = CurrencyCode.parse(context.baseCurrency());
        CurrencyCode itemCurrency = item.getCurrency();

        // Eligibility is decided before a single subtraction, ratio or
        // present-value comparison runs. Computing mixed figures and hiding
        // them afterwards would leave the wrong numbers one refactor away from
        // being shown.
        List<UnavailableReason> reasons = unavailableReasons(item, itemCurrency, base, context);
        if (!reasons.isEmpty()) {
            return AnalysisResponse.exchangeRateRequired(
                    item.getId(), item.getName(), base.name(), itemCurrency.name(),
                    missingCurrencies(context, itemCurrency, base), reasons);
        }

        // Past this point every operand is denominated in the base currency,
        // which is also the item's, so the arithmetic below is homogeneous and
        // behaves exactly as it did before multi-currency.
        BaseAmounts money = BaseAmounts.of(context);
        List<OptionAnalysis> analyses = item.getOptions().stream()
                .map(option -> analyzeOption(option, config, money, referenceDate))
                .toList();

        return AnalysisResponse.available(
                item.getId(),
                item.getName(),
                base.name(),
                new AnalysisAssumptions(
                        money.availableCash(),
                        config.getMinimumCashBuffer(),
                        config.getMonthlyOpportunityRate(),
                        config.getMaxInstallmentCommitmentRatio(),
                        money.avgMonthlyIncome(),
                        money.avgMonthlyExpense(),
                        money.avgMonthlySurplus(),
                        money.monthlyCommitments(),
                        money.cardOutstandingTotal(),
                        money.nextMonthCardInstallments(),
                        money.historyMonthsUsed()),
                analyses,
                recommend(analyses, config, money));
    }

    /**
     * Every reason a complete recommendation cannot be produced, not just the
     * first: a user whose item is foreign <em>and</em> whose cash is mixed
     * deserves to see both.
     *
     * <p>Card outstanding is deliberately absent from this list. It is reported
     * in the assumptions but no recommendation rule divides or subtracts with
     * it, so an incomplete card-obligation total cannot distort a conclusion and
     * must not suppress an otherwise valid analysis.
     */
    private static List<UnavailableReason> unavailableReasons(WishlistItem item,
            CurrencyCode itemCurrency, CurrencyCode base, PurchaseFinancialContext context) {
        List<UnavailableReason> reasons = new ArrayList<>();
        if (itemCurrency != base) {
            reasons.add(new UnavailableReason("ITEM_CURRENCY_DIFFERS_FROM_BASE",
                    ("%s está em %s e sua análise financeira é feita em %s. Sem cotação não há como "
                            + "comparar os dois sem distorcer o resultado.")
                            .formatted(item.getName(), itemCurrency.name(), base.name())));
        }
        if (!context.availableCash().baseComplete()) {
            reasons.add(new UnavailableReason("AVAILABLE_CASH_INCOMPLETE",
                    "Parte do seu saldo disponível está em %s."
                            .formatted(join(context.availableCash().unconvertedCurrencies()))));
        }
        // No history at all is fine: the engine already falls back to a
        // cash-only analysis with explicit warnings. History that exists in
        // another currency is not the same thing and must not pass as absence.
        if (context.averageIncome().anyHistory() && !context.averageIncome().baseComplete()) {
            reasons.add(new UnavailableReason("INCOME_HISTORY_INCOMPLETE",
                    "Parte da sua renda dos últimos meses está em %s."
                            .formatted(join(context.averageIncome().unconvertedCurrencies()))));
        }
        if (context.averageExpenses().anyHistory() && !context.averageExpenses().baseComplete()) {
            reasons.add(new UnavailableReason("EXPENSE_HISTORY_INCOMPLETE",
                    "Parte das suas despesas dos últimos meses está em %s."
                            .formatted(join(context.averageExpenses().unconvertedCurrencies()))));
        }
        if (!context.monthlyCommitments().baseComplete()) {
            reasons.add(new UnavailableReason("COMMITMENTS_INCOMPLETE",
                    "Parte dos seus compromissos recorrentes está em %s."
                            .formatted(join(context.monthlyCommitments().unconvertedCurrencies()))));
        }
        if (!context.nextMonthCardInstallments().baseComplete()) {
            reasons.add(new UnavailableReason("CARD_INSTALLMENTS_INCOMPLETE",
                    "Parte das parcelas de cartão do próximo mês está em %s."
                            .formatted(join(context.nextMonthCardInstallments()
                                    .unconvertedCurrencies()))));
        }
        // An option billed by a card in another currency cannot be compared with
        // the item's price either.
        boolean incompatibleCard = item.getOptions().stream()
                .map(PurchaseOption::getCreditCard)
                .filter(java.util.Objects::nonNull)
                .anyMatch(card -> card.getCurrency() != itemCurrency);
        if (incompatibleCard) {
            reasons.add(new UnavailableReason("CARD_CONTEXT_INCOMPATIBLE",
                    "Uma das opções está ligada a um cartão que fatura em outra moeda."));
        }
        return List.copyOf(reasons);
    }

    /** Context currencies plus the item's own, deduplicated in catalogue order. */
    private static List<String> missingCurrencies(PurchaseFinancialContext context,
            CurrencyCode itemCurrency, CurrencyCode base) {
        java.util.EnumSet<CurrencyCode> missing = java.util.EnumSet.noneOf(CurrencyCode.class);
        context.missingCurrencies().forEach(code -> missing.add(CurrencyCode.parse(code)));
        if (itemCurrency != base) {
            missing.add(itemCurrency);
        }
        List<String> ordered = new ArrayList<>(missing.size());
        missing.forEach(currency -> ordered.add(currency.name()));
        return List.copyOf(ordered);
    }

    private static String join(List<String> currencies) {
        return String.join(", ", currencies);
    }

    /**
     * The base-denominated projection of a context already proven complete.
     *
     * <p>It exists so the recommendation arithmetic keeps operating on plain
     * scalars exactly as it always has — but it can only be built after every
     * dimension has been shown to be in one currency, so those scalars can no
     * longer be a mix of two.
     */
    private record BaseAmounts(
            /** The single currency every amount below is denominated in. */
            CurrencyCode currency,
            BigDecimal availableCash,
            BigDecimal avgMonthlyIncome,
            BigDecimal avgMonthlyExpense,
            BigDecimal avgMonthlySurplus,
            BigDecimal monthlyCommitments,
            BigDecimal cardOutstandingTotal,
            BigDecimal nextMonthCardInstallments,
            int historyMonthsUsed) {

        static BaseAmounts of(PurchaseFinancialContext context) {
            return new BaseAmounts(
                    CurrencyCode.parse(context.baseCurrency()),
                    context.availableCash().baseTotal(),
                    context.averageIncome().baseAverage(),
                    context.averageExpenses().baseAverage(),
                    context.averageSurplus().baseAverage(),
                    context.monthlyCommitments().baseTotal(),
                    // Informational only; null when the card side is mixed,
                    // which never blocks the analysis.
                    context.cardOutstanding().baseTotal(),
                    context.nextMonthCardInstallments().baseTotal(),
                    context.averageIncome().baseMonthsUsed());
        }
    }

    private OptionAnalysis analyzeOption(PurchaseOption option, AppSettings config,
                                         BaseAmounts context, LocalDate referenceDate) {
        BigDecimal nominal = MoneyRules.normalize(option.nominalCost());
        BigDecimal presentValue = presentValue(option, config.getMonthlyOpportunityRate());
        List<OptionIssue> issues = new ArrayList<>();

        BigDecimal upfront;
        BigDecimal monthlyBurden = null;
        CardAnalysis card = null;
        if (option.getKind() == PurchaseOptionKind.CASH) {
            upfront = nominal;
        } else {
            upfront = MoneyRules.normalize(option.getShipping().add(option.getFees()));
            monthlyBurden = option.getInstallmentAmount();
            checkInstallmentPressure(option, config, context, issues);
            card = analyzeCard(option, nominal, referenceDate, context.currency(), issues);
        }

        BigDecimal cashAfter = MoneyRules.normalize(context.availableCash().subtract(upfront));
        if (cashAfter.compareTo(config.getMinimumCashBuffer()) < 0) {
            issues.add(new OptionIssue(
                    "BUFFER_VIOLATION",
                    "Pagar %s deixaria o caixa em %s, abaixo da reserva mínima de %s."
                            .formatted(money(upfront, context.currency()),
                                    money(cashAfter, context.currency()),
                                    money(config.getMinimumCashBuffer(), context.currency())),
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
                card,
                safe,
                issues);
    }

    /**
     * Projects an INSTALLMENT option onto its linked card: the whole nominal
     * value consumes limit at purchase time, so insufficient available limit
     * blocks the option. Card limit is capacity, never income or cash — it
     * cannot make an option safe, only unavailable.
     */
    private CardAnalysis analyzeCard(PurchaseOption option, BigDecimal nominal,
                                     LocalDate referenceDate, CurrencyCode currency,
                                     List<OptionIssue> issues) {
        CreditCard card = option.getCreditCard();
        if (card == null) {
            return null;
        }
        if (card.isArchived()) {
            issues.add(new OptionIssue(
                    "CARD_ARCHIVED",
                    "O cartão %s está arquivado e não pode receber novas compras.".formatted(card.getName()),
                    true));
        }
        CardLimit limit = cardLimits.limitOf(card);
        boolean sufficient = nominal.compareTo(limit.availableLimit()) <= 0;
        if (!sufficient) {
            issues.add(new OptionIssue(
                    "CARD_LIMIT_INSUFFICIENT",
                    "A compra de %s excede o limite disponível de %s no cartão %s."
                            .formatted(money(nominal, currency),
                                    money(limit.availableLimit(), currency), card.getName()),
                    true));
        }
        BigDecimal usedAfter = limit.usedLimit().add(nominal);
        BigDecimal utilizationAfter = limit.creditLimit().signum() > 0
                ? usedAfter.multiply(BigDecimal.valueOf(100))
                        .divide(limit.creditLimit(), 1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(1);
        return new CardAnalysis(
                card.getId(),
                card.getName(),
                limit.availableLimit(),
                MoneyRules.normalize(limit.availableLimit().subtract(nominal)),
                utilizationAfter,
                InvoiceCycleCalculator.cycleForPurchase(
                        card.getClosingDay(), card.getDueDay(), referenceDate).referenceMonth(),
                sufficient);
    }

    private void checkInstallmentPressure(PurchaseOption option, AppSettings config,
                                          BaseAmounts context, List<OptionIssue> issues) {
        BigDecimal installment = option.getInstallmentAmount();

        if (context.avgMonthlySurplus() != null) {
            if (installment.compareTo(context.avgMonthlySurplus()) > 0) {
                issues.add(new OptionIssue(
                        "INSTALLMENT_EXCEEDS_SURPLUS",
                        ("A parcela de %s é maior que a sobra média mensal de %s — os próximos %d meses "
                                + "tendem a fechar no vermelho.")
                                .formatted(money(installment, context.currency()),
                                        money(context.avgMonthlySurplus(), context.currency()),
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
            // Existing card installments already claim part of the income; the
            // new installment competes with them, not just with commitments.
            BigDecimal committed = installment
                    .add(context.monthlyCommitments())
                    .add(context.nextMonthCardInstallments());
            BigDecimal ratio = committed.divide(context.avgMonthlyIncome(),
                    MoneyRules.RATE_SCALE, RoundingMode.HALF_UP);
            if (ratio.compareTo(config.getMaxInstallmentCommitmentRatio()) > 0) {
                issues.add(new OptionIssue(
                        "INSTALLMENT_PRESSURE_HIGH",
                        ("Parcela + compromissos recorrentes + parcelas de cartão já assumidas (%s) "
                                + "comprometeriam %s%% da renda média, acima do limite configurado de %s%%.")
                                .formatted(money(committed, context.currency()),
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
                                     BaseAmounts context) {
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
            return buildBuyRecommendation(best, analyses, config, context.currency(), warnings);
        }
        return buildWaitRecommendation(analyses, config, context, warnings);
    }

    private Recommendation buildBuyRecommendation(OptionAnalysis best, List<OptionAnalysis> all,
                                                  AppSettings config, CurrencyCode currency,
                                                  List<String> warnings) {
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
                        .formatted(best.merchant(), money(best.presentValue(), currency),
                                money(best.cashAfterPurchase(), currency))
                : ("Parcelar em %s (%d× de %s) tem o menor valor presente (%s) considerando a taxa de "
                        + "oportunidade configurada, e a parcela cabe nas margens definidas.")
                        .formatted(best.merchant(), best.installmentCount(),
                                money(best.monthlyBurden(), currency),
                                money(best.presentValue(), currency));

        return new Recommendation(
                isCash ? RecommendationType.BUY_CASH : RecommendationType.BUY_INSTALLMENT,
                best.optionId(), reasons, explanation, warnings, null, null);
    }

    private Recommendation buildWaitRecommendation(List<OptionAnalysis> analyses, AppSettings config,
                                                   BaseAmounts context, List<String> warnings) {
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
                        .formatted(months, money(requiredAdditionalCash, context.currency()))
                : "Nenhuma opção é segura agora: os valores violariam a reserva mínima de caixa ou as margens "
                        + "mensais configuradas.";

        return new Recommendation(RecommendationType.WAIT, null, List.of("NO_SAFE_OPTION"),
                explanation, warnings, requiredAdditionalCash, months);
    }

    /**
     * Formats an amount in the currency the analysis is actually denominated in.
     *
     * <p>Every message below is produced only after eligibility proved the item,
     * the context and the base currency agree, so that one currency labels all
     * of them. It is not necessarily BRL: a user whose base currency is USD gets
     * an available analysis whose figures are dollars, and printing those with a
     * reais symbol would misstate the amount rather than merely look wrong.
     */
    private static String money(BigDecimal value, CurrencyCode currency) {
        return MoneyRules.format(value, currency);
    }

    private static String toPercent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP)
                .toPlainString()
                .replace('.', ',');
    }
}
