package com.finora.api.legacyconversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real upgrade path of the persistent development database from V9 (recurring
 * automation) to the legacy-credit conversion schema: every existing row must
 * survive, legacy CREDIT transactions stay financially active by default, no
 * conversion is fabricated by migration, and the new constraints (one active
 * conversion per source, cross-owner protection, deactivation only for legacy
 * rows) hold at the database level.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationFromPopulatedV9Test {

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startContainer() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.6-alpine"));
        postgres.start();
    }

    @AfterAll
    static void stopContainer() {
        postgres.stop();
    }

    private static Flyway flywayTo(String targetVersion) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(targetVersion)
                .load();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    @Order(1)
    void migratesToV9AndAcceptsRealisticData() throws Exception {
        flywayTo("9").migrate();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO users (display_name, email, password_hash, status)
                    VALUES ('Usuária A', 'a@finora.test', 'hash-a', 'ACTIVE'),
                           ('Usuário B', 'b@finora.test', 'hash-b', 'ACTIVE')
                    """);
            statement.execute("""
                    INSERT INTO accounts (name, type, opening_balance, user_id)
                    VALUES ('Conta A', 'CHECKING', 2000.00, 1),
                           ('Conta B', 'CHECKING', 800.00, 2)
                    """);
            statement.execute("""
                    INSERT INTO categories (name, type, user_id)
                    VALUES ('Mercado', 'EXPENSE', 1), ('Salário', 'INCOME', 1),
                           ('Mercado', 'EXPENSE', 2)
                    """);
            // Ordinary cash history plus pre-card-era CREDIT rows (legacy).
            statement.execute("""
                    INSERT INTO transactions
                        (type, amount, description, occurred_on, category_id, account_id,
                         payment_method, legacy_credit, user_id)
                    VALUES ('EXPENSE', 250.00, 'Feira do mês', '2026-04-10',
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'EXPENSE'),
                            (SELECT id FROM accounts WHERE user_id = 1), 'PIX', FALSE, 1),
                           ('INCOME', 5000.00, 'Salário de abril', '2026-04-05',
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'INCOME'),
                            (SELECT id FROM accounts WHERE user_id = 1), 'PIX', FALSE, 1),
                           ('EXPENSE', 899.90, 'Notebook no crédito antigo', '2025-11-20',
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'EXPENSE'),
                            NULL, 'CREDIT', TRUE, 1),
                           ('EXPENSE', 120.00, 'Assinatura anual no crédito antigo', '2025-08-02',
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'EXPENSE'),
                            (SELECT id FROM accounts WHERE user_id = 1), 'CREDIT', TRUE, 1),
                           ('EXPENSE', 75.50, 'Crédito antigo de B', '2025-10-01',
                            (SELECT id FROM categories WHERE user_id = 2),
                            NULL, 'CREDIT', TRUE, 2)
                    """);
            // A real card with an invoice, a purchase, its installment and a payment.
            statement.execute("""
                    INSERT INTO credit_cards
                        (user_id, name, brand, credit_limit, closing_day, due_day)
                    VALUES (1, 'Cartão Roxo', 'VISA', 5000.00, 28, 10)
                    """);
            statement.execute("""
                    INSERT INTO credit_card_invoices
                        (user_id, card_id, reference_month, closing_date, due_date)
                    VALUES (1, 1, '2026-05-01', '2026-04-28', '2026-05-10')
                    """);
            statement.execute("""
                    INSERT INTO credit_card_purchases
                        (user_id, card_id, category_id, description, purchase_date,
                         total_amount, installment_count)
                    VALUES (1, 1,
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'EXPENSE'),
                            'Compra real no cartão', '2026-04-15', 300.00, 1)
                    """);
            statement.execute("""
                    INSERT INTO credit_card_installments
                        (user_id, purchase_id, invoice_id, sequence_number,
                         total_installments, amount)
                    VALUES (1, 1, 1, 1, 1, 300.00)
                    """);
            statement.execute("""
                    INSERT INTO credit_card_payments
                        (user_id, invoice_id, account_id, amount, paid_on)
                    VALUES (1, 1, (SELECT id FROM accounts WHERE user_id = 1),
                            300.00, '2026-05-09')
                    """);
            // A legacy CREDIT recurring definition (projection-only) with history.
            statement.execute("""
                    INSERT INTO commitments
                        (description, amount, category_id, cadence, due_day, start_date,
                         active, payment_method, execution_mode, target_kind,
                         installment_count, user_id)
                    VALUES ('Streaming no crédito antigo', 39.90,
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'EXPENSE'),
                            'MONTHLY', 8, '2025-01-08', TRUE, 'CREDIT', 'MANUAL',
                            'PROJECTION_ONLY', 1, 1)
                    """);
            statement.execute("""
                    INSERT INTO commitment_occurrences
                        (user_id, commitment_id, scheduled_date, effective_date, status)
                    VALUES (1, 1, '2026-06-08', '2026-06-08', 'SCHEDULED')
                    """);
        }
    }

    @Test
    @Order(2)
    void migratesToLatestPreservingEveryRowAndDefaults() throws Exception {
        flywayTo("latest").migrate();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // Every transaction survives, financially active by default.
            try (ResultSet rs = statement.executeQuery("""
                    SELECT count(*) AS total,
                           count(*) FILTER (WHERE financially_active) AS active,
                           count(*) FILTER (WHERE legacy_credit) AS legacy,
                           sum(amount) AS total_amount
                    FROM transactions
                    """)) {
                rs.next();
                assertThat(rs.getLong("total")).isEqualTo(5);
                assertThat(rs.getLong("active")).isEqualTo(5);
                assertThat(rs.getLong("legacy")).isEqualTo(3);
                assertThat(rs.getBigDecimal("total_amount")).isEqualByComparingTo("6345.40");
            }
            // Migration never fabricates a conversion.
            try (ResultSet rs = statement.executeQuery(
                    "SELECT count(*) FROM legacy_credit_conversions")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
            // Card history is intact and no purchase gained a legacy origin.
            try (ResultSet rs = statement.executeQuery("""
                    SELECT count(*) AS purchases,
                           count(legacy_transaction_id) AS linked
                    FROM credit_card_purchases
                    """)) {
                rs.next();
                assertThat(rs.getLong("purchases")).isEqualTo(1);
                assertThat(rs.getLong("linked")).isZero();
            }
            // The legacy recurring definition stays projection-only, untouched.
            try (ResultSet rs = statement.executeQuery("""
                    SELECT target_kind, payment_method FROM commitments WHERE id = 1
                    """)) {
                rs.next();
                assertThat(rs.getString("target_kind")).isEqualTo("PROJECTION_ONLY");
                assertThat(rs.getString("payment_method")).isEqualTo("CREDIT");
            }
            // New indexes exist.
            try (ResultSet rs = statement.executeQuery("""
                    SELECT indexname FROM pg_indexes
                    WHERE tablename IN ('legacy_credit_conversions', 'transactions',
                                        'credit_card_purchases')
                    """)) {
                var names = new java.util.ArrayList<String>();
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
                assertThat(names).contains(
                        "uq_legacy_conversions_active_source",
                        "uq_legacy_conversions_purchase",
                        "ix_legacy_conversions_user_status",
                        "ix_transactions_user_legacy_credit",
                        "uq_credit_card_purchases_legacy_tx");
            }
        }
    }

    @Test
    @Order(3)
    void deactivationIsRestrictedToLegacyRows() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // An ordinary transaction can never be financially deactivated.
            assertThatThrownBy(() -> statement.execute("""
                    UPDATE transactions SET financially_active = FALSE
                    WHERE description = 'Feira do mês'
                    """)).hasMessageContaining("ck_transactions_inactive_is_legacy");
            // A legacy CREDIT transaction can.
            statement.execute("""
                    UPDATE transactions SET financially_active = FALSE
                    WHERE description = 'Notebook no crédito antigo'
                    """);
            statement.execute("""
                    UPDATE transactions SET financially_active = TRUE
                    WHERE description = 'Notebook no crédito antigo'
                    """);
        }
    }

    @Test
    @Order(4)
    void conversionConstraintsHold() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // A second purchase to link conversions to (the first is unrelated).
            statement.execute("""
                    INSERT INTO credit_card_purchases
                        (user_id, card_id, category_id, description, purchase_date,
                         total_amount, installment_count, legacy_transaction_id)
                    VALUES (1, 1,
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'EXPENSE'),
                            'Notebook convertido', '2025-11-20', 899.90, 1,
                            (SELECT id FROM transactions
                             WHERE description = 'Notebook no crédito antigo'))
                    """);
            statement.execute("""
                    INSERT INTO legacy_credit_conversions
                        (user_id, source_transaction_id, card_purchase_id, card_id,
                         original_transaction_date, effective_purchase_date,
                         installment_count, first_invoice_month, status, converted_at)
                    VALUES (1,
                            (SELECT id FROM transactions
                             WHERE description = 'Notebook no crédito antigo'),
                            2, 1, '2025-11-20', '2025-11-20', 1, '2025-12-01',
                            'ACTIVE', now())
                    """);
            // A second ACTIVE conversion of the same source is impossible.
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO legacy_credit_conversions
                        (user_id, source_transaction_id, card_purchase_id, card_id,
                         original_transaction_date, effective_purchase_date,
                         installment_count, first_invoice_month, status, converted_at)
                    VALUES (1,
                            (SELECT id FROM transactions
                             WHERE description = 'Notebook no crédito antigo'),
                            1, 1, '2025-11-20', '2025-11-20', 1, '2025-12-01',
                            'ACTIVE', now())
                    """)).hasMessageContaining("uq_legacy_conversions_active_source");
            // User B cannot own a conversion of A's transaction.
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO legacy_credit_conversions
                        (user_id, source_transaction_id, card_purchase_id, card_id,
                         original_transaction_date, effective_purchase_date,
                         installment_count, first_invoice_month, status, converted_at)
                    VALUES (2,
                            (SELECT id FROM transactions
                             WHERE description = 'Assinatura anual no crédito antigo'),
                            1, 1, '2025-08-02', '2025-08-02', 1, '2025-09-01',
                            'ACTIVE', now())
                    """)).hasMessageContaining("fk_legacy_conversions");
            // A REVERSED status requires its timestamp (and vice versa).
            assertThatThrownBy(() -> statement.execute("""
                    UPDATE legacy_credit_conversions SET status = 'REVERSED'
                    WHERE card_purchase_id = 2
                    """)).hasMessageContaining("ck_legacy_conversions_reversal");
            // Clean up the probe conversion so later tests see a pristine ledger.
            statement.execute("DELETE FROM legacy_credit_conversions");
            statement.execute("DELETE FROM credit_card_purchases WHERE id = 2");
        }
    }
}
