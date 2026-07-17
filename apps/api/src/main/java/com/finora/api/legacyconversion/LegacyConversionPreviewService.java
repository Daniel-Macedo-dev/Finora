package com.finora.api.legacyconversion;

import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.creditcard.CardLimitService;
import com.finora.api.creditcard.CreditCard;
import com.finora.api.creditcard.CreditCardRepository;
import com.finora.api.creditcard.InvoiceCycleCalculator;
import com.finora.api.creditcard.InvoiceCycleCalculator.InvoiceCycle;
import com.finora.api.creditcard.adjustment.InvoiceAdjustmentRepository;
import com.finora.api.creditcard.installment.CardInstallmentRepository;
import com.finora.api.creditcard.installment.InstallmentAllocator;
import com.finora.api.creditcard.invoice.CardInvoice;
import com.finora.api.creditcard.invoice.CardInvoiceRepository;
import com.finora.api.creditcard.invoice.InvoiceService;
import com.finora.api.creditcard.invoice.InvoiceStatus;
import com.finora.api.creditcard.payment.InvoicePaymentRepository;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.legacyconversion.ConversionPreviewDtos.CashFlowExplanation;
import com.finora.api.legacyconversion.ConversionPreviewDtos.ConversionPreviewResponse;
import com.finora.api.legacyconversion.ConversionPreviewDtos.MonthlyExpenseShift;
import com.finora.api.legacyconversion.ConversionPreviewDtos.PreviewCard;
import com.finora.api.legacyconversion.ConversionPreviewDtos.PreviewInstallment;
import com.finora.api.legacyconversion.ConversionPreviewDtos.PreviewLimit;
import com.finora.api.legacyconversion.ConversionPreviewDtos.PreviewMessage;
import com.finora.api.legacyconversion.ConversionPreviewDtos.PreviewSource;
import com.finora.api.transaction.Transaction;
import com.finora.api.transaction.TransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic conversion preview. All financial mathematics is delegated to
 * the existing single authorities — {@link InstallmentAllocator} for the
 * cent-exact split, {@link InvoiceCycleCalculator} for cycle dates,
 * {@link CardLimitService} for the limit formula and
 * {@link InvoiceService#deriveStatus} for invoice status — so the preview can
 * never drift from what the conversion engine will actually persist.
 */
@Service
@Transactional(readOnly = true)
public class LegacyConversionPreviewService {

    /**
     * User-confirmed conversion parameters. {@code firstInvoiceMonth} is the
     * explicit allocation confirmation: when present it must match the cycle
     * the calculator derives from the effective purchase date.
     */
    public record PreviewInput(Long transactionId,
                               Long cardId,
                               LocalDate effectivePurchaseDate,
                               int installmentCount,
                               YearMonth firstInvoiceMonth) {
    }

    private final TransactionRepository transactions;
    private final CreditCardRepository cards;
    private final CardInvoiceRepository invoices;
    private final CardInstallmentRepository installments;
    private final InvoiceAdjustmentRepository adjustments;
    private final InvoicePaymentRepository payments;
    private final CardLimitService limits;
    private final LegacyConversionEligibilityService eligibility;
    private final CurrentUserProvider currentUser;
    private final Clock clock;

    public LegacyConversionPreviewService(TransactionRepository transactions,
                                          CreditCardRepository cards,
                                          CardInvoiceRepository invoices,
                                          CardInstallmentRepository installments,
                                          InvoiceAdjustmentRepository adjustments,
                                          InvoicePaymentRepository payments,
                                          CardLimitService limits,
                                          LegacyConversionEligibilityService eligibility,
                                          CurrentUserProvider currentUser,
                                          Clock clock) {
        this.transactions = transactions;
        this.cards = cards;
        this.invoices = invoices;
        this.installments = installments;
        this.adjustments = adjustments;
        this.payments = payments;
        this.limits = limits;
        this.eligibility = eligibility;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public ConversionPreviewResponse preview(PreviewInput input) {
        return previewForUser(currentUser.currentUserId(), input);
    }

    /** Owner-explicit variant, reused by the conversion engine inside its transaction. */
    public ConversionPreviewResponse previewForUser(Long userId, PreviewInput input) {
        Transaction source = transactions.findByIdAndUserId(input.transactionId(), userId)
                .orElseThrow(() -> new NotFoundException("Transação", input.transactionId()));
        CreditCard card = cards.findByIdAndUserId(input.cardId(), userId)
                .orElseThrow(() -> new NotFoundException("Cartão", input.cardId()));
        LocalDate today = LocalDate.now(clock);

        List<PreviewMessage> blockers = new ArrayList<>();
        List<PreviewMessage> warnings = new ArrayList<>();

        var verdict = eligibility.evaluate(source);
        if (!verdict.convertible()) {
            blockers.add(new PreviewMessage(verdict.reasonCode(), verdict.message()));
        }
        if (card.isArchived()) {
            blockers.add(new PreviewMessage("CARD_ARCHIVED",
                    "Um cartão arquivado não pode receber a compra convertida."));
        }
        if (input.effectivePurchaseDate().isAfter(today)) {
            blockers.add(new PreviewMessage("EFFECTIVE_DATE_IN_FUTURE",
                    "A data efetiva da compra histórica não pode estar no futuro."));
        }

        BigDecimal total = MoneyRules.normalize(source.getAmount());
        List<BigDecimal> amounts = List.of();
        try {
            amounts = InstallmentAllocator.allocate(total, input.installmentCount());
        } catch (IllegalArgumentException tooSmall) {
            blockers.add(new PreviewMessage("PURCHASE_INSTALLMENTS_TOO_SMALL",
                    "O valor da transação não permite parcelas de pelo menos R$ 0,01."));
        }

        InvoiceCycle first = InvoiceCycleCalculator.cycleForPurchase(
                card.getClosingDay(), card.getDueDay(), input.effectivePurchaseDate());
        List<PreviewInstallment> schedule =
                buildSchedule(userId, card, first, amounts, today);
        validateAllocation(input, first, schedule, source, blockers, warnings);

        CardLimitService.CardLimit limit = limits.limitOf(card);
        boolean sufficient = total.compareTo(limit.availableLimit()) <= 0;
        if (!sufficient) {
            blockers.add(new PreviewMessage("INSUFFICIENT_CARD_LIMIT",
                    "Limite disponível insuficiente: a conversão de %s excede o limite livre de %s."
                            .formatted(MoneyRules.formatBrl(total),
                                    MoneyRules.formatBrl(limit.availableLimit()))));
        }

        boolean sourceAffectsBalance = source.getAccount() != null;
        if (sourceAffectsBalance) {
            warnings.add(new PreviewMessage("SOURCE_AFFECTS_ACCOUNT_CASH",
                    "A transação original reduz o saldo da conta %s. Após a conversão esse efeito "
                            + "é removido: o dinheiro passa a sair apenas no pagamento das faturas."
                            .formatted(source.getAccount().getName())));
        }
        boolean paymentAccountAssigned = card.getDefaultPaymentAccount() != null
                && !card.getDefaultPaymentAccount().isArchived();
        if (!paymentAccountAssigned) {
            warnings.add(new PreviewMessage("NO_DEFAULT_PAYMENT_ACCOUNT",
                    "O cartão não tem conta de pagamento padrão: as faturas geradas aparecerão "
                            + "como fluxo não atribuído na previsão de caixa."));
        }

        return new ConversionPreviewResponse(
                new PreviewSource(
                        source.getId(),
                        source.getDescription(),
                        total,
                        source.getOccurredOn(),
                        new LegacyConversionDtos.CategorySummary(
                                source.getCategory().getId(), source.getCategory().getName()),
                        source.getAccount() != null ? source.getAccount().getName() : null,
                        sourceAffectsBalance),
                new PreviewCard(card.getId(), card.getName(),
                        card.getClosingDay(), card.getDueDay(), card.isArchived()),
                total,
                input.installmentCount(),
                first.referenceMonth(),
                schedule,
                new PreviewLimit(
                        limit.creditLimit(),
                        limit.availableLimit(),
                        MoneyRules.normalize(limit.availableLimit().subtract(total)),
                        sufficient),
                expenseShift(source, total, schedule),
                new CashFlowExplanation(
                        sourceAffectsBalance,
                        sourceAffectsBalance,
                        paymentAccountAssigned,
                        cashExplanation(sourceAffectsBalance, schedule, today)),
                forecastExplanation(schedule, today),
                List.copyOf(warnings),
                List.copyOf(blockers),
                blockers.isEmpty());
    }

    /**
     * The exact installment schedule the engine would persist: consecutive
     * invoice months starting at the cycle covering the effective date, with
     * the real status of every invoice that already exists. Existing invoices
     * keep their snapshot dates — a card reconfiguration never rewrites them.
     */
    private List<PreviewInstallment> buildSchedule(Long userId, CreditCard card,
                                                   InvoiceCycle first, List<BigDecimal> amounts,
                                                   LocalDate today) {
        // Three grouped queries cover every existing invoice's totals — the
        // schedule never issues one query per installment.
        Map<Long, BigDecimal> charges = new HashMap<>();
        for (Object[] row : installments.sumActiveGroupedByInvoice(card.getId(), userId)) {
            charges.merge((Long) row[0], (BigDecimal) row[1], BigDecimal::add);
        }
        for (Object[] row : adjustments.sumActiveNetGroupedByInvoice(card.getId(), userId)) {
            charges.merge((Long) row[0], (BigDecimal) row[1], BigDecimal::add);
        }
        Map<Long, BigDecimal> paid = new HashMap<>();
        for (Object[] row : payments.sumCompletedGroupedByInvoice(card.getId(), userId)) {
            paid.put((Long) row[0], (BigDecimal) row[1]);
        }

        List<PreviewInstallment> schedule = new ArrayList<>(amounts.size());
        for (int i = 0; i < amounts.size(); i++) {
            InvoiceCycle cycle = i == 0
                    ? first
                    : InvoiceCycleCalculator.cycleFor(card.getClosingDay(), card.getDueDay(),
                            first.referenceMonth().plusMonths(i));
            Optional<CardInvoice> existing = invoices.findByUserIdAndCardIdAndReferenceMonth(
                    userId, card.getId(), cycle.referenceMonth().atDay(1));
            LocalDate closingDate = existing.map(CardInvoice::getClosingDate)
                    .orElse(cycle.closingDate());
            LocalDate dueDate = existing.map(CardInvoice::getDueDate).orElse(cycle.dueDate());
            InvoiceStatus status = existing.map(invoice -> InvoiceService.deriveStatus(
                            today,
                            invoice.getClosingDate(),
                            invoice.getDueDate(),
                            charges.getOrDefault(invoice.getId(), BigDecimal.ZERO),
                            paid.getOrDefault(invoice.getId(), BigDecimal.ZERO)))
                    .orElse(null);
            BigDecimal amountPaid = existing
                    .map(invoice -> paid.getOrDefault(invoice.getId(), BigDecimal.ZERO))
                    .orElse(BigDecimal.ZERO);
            schedule.add(new PreviewInstallment(
                    i + 1,
                    amounts.size(),
                    amounts.get(i),
                    cycle.referenceMonth(),
                    closingDate,
                    dueDate,
                    existing.isPresent(),
                    status,
                    MoneyRules.normalize(amountPaid)));
        }
        return schedule;
    }

    /**
     * Historical allocation guards. The policy is: effective purchase date +
     * the normal invoice-cycle calculation, explicitly confirmed by the user
     * through {@code firstInvoiceMonth}. Invoices that already received a
     * completed payment are settled history — adding charges to them is
     * blocked; merely closed invoices are allowed with an explicit warning.
     */
    private void validateAllocation(PreviewInput input, InvoiceCycle first,
                                    List<PreviewInstallment> schedule, Transaction source,
                                    List<PreviewMessage> blockers, List<PreviewMessage> warnings) {
        if (input.firstInvoiceMonth() != null
                && !input.firstInvoiceMonth().equals(first.referenceMonth())) {
            blockers.add(new PreviewMessage("FIRST_INVOICE_MISMATCH",
                    "A primeira fatura confirmada (%s) não corresponde ao ciclo calculado (%s). "
                            .formatted(input.firstInvoiceMonth(), first.referenceMonth())
                            + "Ajuste a data efetiva da compra para mudar a alocação."));
        }
        boolean anyPaid = false;
        boolean anyClosed = false;
        for (PreviewInstallment installment : schedule) {
            if (installment.invoiceStatus() == InvoiceStatus.PAID
                    || installment.invoiceAmountPaid().signum() > 0) {
                anyPaid = true;
            } else if (installment.invoiceStatus() == InvoiceStatus.CLOSED
                    || installment.invoiceStatus() == InvoiceStatus.OVERDUE) {
                anyClosed = true;
            }
        }
        if (anyPaid) {
            blockers.add(new PreviewMessage("INVOICE_ALREADY_PAID",
                    "Uma das faturas de destino já recebeu pagamento e é história liquidada. "
                            + "Escolha outra data efetiva ou outro número de parcelas."));
        }
        if (anyClosed) {
            warnings.add(new PreviewMessage("INVOICE_CLOSED",
                    "Parcelas serão lançadas em faturas já fechadas — o valor em aberto dessas "
                            + "faturas aumentará imediatamente."));
        }
        boolean redistributes = schedule.stream()
                .anyMatch(i -> !i.invoiceMonth().equals(YearMonth.from(source.getOccurredOn())));
        if (redistributes) {
            warnings.add(new PreviewMessage("MONTHLY_REDISTRIBUTION",
                    "A despesa muda de mês: hoje ela conta em %s; após a conversão contará nos "
                            .formatted(YearMonth.from(source.getOccurredOn()))
                            + "meses das faturas mostradas acima."));
        }
    }

    /** Net expense-recognition movement per month (source month loses, invoice months gain). */
    private static List<MonthlyExpenseShift> expenseShift(Transaction source, BigDecimal total,
                                                          List<PreviewInstallment> schedule) {
        Map<YearMonth, BigDecimal> deltas = new LinkedHashMap<>();
        deltas.merge(YearMonth.from(source.getOccurredOn()), total.negate(), BigDecimal::add);
        for (PreviewInstallment installment : schedule) {
            deltas.merge(installment.invoiceMonth(), installment.amount(), BigDecimal::add);
        }
        return deltas.entrySet().stream()
                .filter(entry -> entry.getValue().signum() != 0)
                .map(entry -> new MonthlyExpenseShift(
                        entry.getKey(), MoneyRules.normalize(entry.getValue())))
                .toList();
    }

    private static String cashExplanation(boolean sourceAffectsBalance,
                                          List<PreviewInstallment> schedule, LocalDate today) {
        StringBuilder text = new StringBuilder();
        if (sourceAffectsBalance) {
            text.append("O débito original na conta deixa de valer e o caixa volta a refletir "
                    + "apenas o pagamento das faturas. ");
        } else {
            text.append("A transação original não movimenta nenhuma conta; o caixa só é "
                    + "afetado pelo pagamento das faturas geradas. ");
        }
        long dueInPast = schedule.stream().filter(i -> i.dueDate().isBefore(today)).count();
        if (dueInPast > 0) {
            text.append(("%d parcela(s) caem em faturas com vencimento no passado: o valor em "
                    + "aberto delas é exigível imediatamente.").formatted(dueInPast));
        } else {
            text.append("Cada fatura gerada sai do caixa na sua data de vencimento.");
        }
        return text.toString();
    }

    private static String forecastExplanation(List<PreviewInstallment> schedule, LocalDate today) {
        long futureInvoices = schedule.stream().filter(i -> i.dueDate().isAfter(today)).count();
        if (futureInvoices == 0) {
            return "Nenhuma fatura futura: a conversão afeta apenas meses passados; a previsão "
                    + "de caixa muda somente se houver valor em aberto exigível hoje.";
        }
        return ("%d fatura(s) com vencimento futuro entram na previsão de caixa pelo valor em "
                + "aberto na data de vencimento. A transação original deixa de contar como "
                + "despesa ativa.").formatted(futureInvoices);
    }
}
