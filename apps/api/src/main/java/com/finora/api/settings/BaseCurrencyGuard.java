package com.finora.api.settings;

import com.finora.api.common.error.BusinessRuleException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides whether a user may still change their base currency.
 *
 * <p>Finora cannot convert between currencies yet, so changing the base
 * currency does not restate the ledger -- it would silently reinterpret it. A
 * budget of 2.500 written as BRL would become 2.500 USD without a single row
 * being edited. The change is therefore allowed only while there is genuinely
 * nothing to reinterpret.
 *
 * <p>The probe is one round trip: emptiness is checked for every financial
 * domain in a single query rather than one count per repository.
 */
@Component
public class BaseCurrencyGuard {

    /**
     * Domains whose presence makes a base-currency change unsafe, paired with
     * the pt-BR wording used to tell the user what is blocking them.
     */
    private static final List<Domain> DOMAINS = List.of(
            new Domain("accounts", "contas"),
            new Domain("transactions", "lançamentos"),
            new Domain("budgets", "orçamentos"),
            new Domain("commitments", "compromissos"),
            new Domain("commitment_occurrences", "ocorrências de compromissos"),
            new Domain("credit_cards", "cartões de crédito"),
            new Domain("credit_card_invoices", "faturas"),
            new Domain("credit_card_purchases", "compras no cartão"),
            new Domain("credit_card_payments", "pagamentos de fatura"),
            new Domain("credit_card_adjustments", "ajustes de fatura"),
            new Domain("goals", "metas"),
            new Domain("wishlist_items", "itens da lista de desejos"),
            new Domain("purchase_options", "opções de compra"),
            new Domain("wishlist_price_snapshots", "histórico de preços"),
            new Domain("statement_import_batches", "importações de extrato"),
            new Domain("notifications", "notificações"),
            new Domain("offline_mutation_receipts", "mutações offline"));

    private record Domain(String table, String label) {
    }

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Rejects the change when the user already owns financial data, or when a
     * base-denominated setting carries a meaningful value.
     *
     * @throws BusinessRuleException with code {@code BASE_CURRENCY_CHANGE_BLOCKED}
     */
    @Transactional(readOnly = true)
    public void assertChangeAllowed(Long userId, AppSettings settings) {
        List<String> blocking = new ArrayList<>(nonEmptyDomains(userId));

        // A non-zero buffer is a real amount in the current base currency, so
        // reinterpreting it would quietly change the user's safety margin.
        if (settings.getMinimumCashBuffer() != null
                && settings.getMinimumCashBuffer().compareTo(BigDecimal.ZERO) != 0) {
            blocking.add("reserva mínima de caixa");
        }

        if (!blocking.isEmpty()) {
            throw new BusinessRuleException(
                    "BASE_CURRENCY_CHANGE_BLOCKED",
                    ("Não é possível alterar a moeda base depois que já existem dados "
                            + "financeiros (%s). A alteração reinterpretaria o histórico: os "
                            + "valores já registrados passariam a ser lidos em outra moeda sem "
                            + "serem convertidos.")
                            .formatted(String.join(", ", blocking)));
        }
    }

    /**
     * Labels of the domains that already hold data for this user, in catalogue
     * order. The query reports table names; the pt-BR wording is attached here
     * so no prose is ever concatenated into SQL.
     */
    private List<String> nonEmptyDomains(Long userId) {
        String probe = DOMAINS.stream()
                .map(domain -> "SELECT '%1$s' AS t WHERE EXISTS (SELECT 1 FROM %1$s WHERE user_id = :userId)"
                        .formatted(domain.table()))
                .collect(Collectors.joining(" UNION ALL "));

        @SuppressWarnings("unchecked")
        List<String> tables = entityManager.createNativeQuery(probe)
                .setParameter("userId", userId)
                .getResultList();

        return DOMAINS.stream()
                .filter(domain -> tables.contains(domain.table()))
                .map(Domain::label)
                .toList();
    }
}
