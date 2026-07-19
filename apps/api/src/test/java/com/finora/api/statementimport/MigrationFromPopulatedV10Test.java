package com.finora.api.statementimport;

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
 * Real upgrade path of the persistent development database from V10 (legacy
 * credit conversion) to the statement-import schema: every existing row must
 * survive untouched, no historical transaction is ever marked as imported by
 * migration, the new import tables start empty, and the new constraints
 * (strong external-id identity, one live transaction per import item,
 * cross-owner rejection) hold at the database level.
 *
 * <p>Schema-validation startup on the migrated schema is exercised by every
 * Spring integration test in the suite ({@code ddl-auto=validate}).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationFromPopulatedV10Test {

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
    void migratesToV10AndAcceptsRealisticData() throws Exception {
        flywayTo("10").migrate();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO users (display_name, email, password_hash, status)
                    VALUES ('Usuária A', 'a@finora.test', 'hash-a', 'ACTIVE'),
                           ('Usuário B', 'b@finora.test', 'hash-b', 'ACTIVE')
                    """);
            // A live server-side session for user A (Spring Session JDBC, V5).
            statement.execute("""
                    INSERT INTO spring_session
                        (primary_id, session_id, creation_time, last_access_time,
                         max_inactive_interval, expiry_time, principal_name)
                    VALUES ('11111111-1111-1111-1111-111111111111',
                            '22222222-2222-2222-2222-222222222222',
                            1750000000000, 1750000000000, 43200,
                            1999999999999, 'a@finora.test')
                    """);
            statement.execute("""
                    INSERT INTO accounts (name, type, opening_balance, user_id)
                    VALUES ('Conta Corrente A', 'CHECKING', 2500.00, 1),
                           ('Poupança A', 'SAVINGS', 10000.00, 1),
                           ('Conta B', 'CHECKING', 900.00, 2)
                    """);
            statement.execute("""
                    INSERT INTO categories (name, type, user_id)
                    VALUES ('Mercado', 'EXPENSE', 1), ('Salário', 'INCOME', 1),
                           ('Mercado', 'EXPENSE', 2)
                    """);
            statement.execute("""
                    INSERT INTO transactions
                        (type, amount, description, occurred_on, category_id, account_id,
                         payment_method, legacy_credit, user_id)
                    VALUES ('EXPENSE', 320.45, 'Compra no mercado', '2026-05-12',
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'EXPENSE'),
                            (SELECT id FROM accounts WHERE user_id = 1 AND type = 'CHECKING'),
                            'PIX', FALSE, 1),
                           ('INCOME', 5200.00, 'Salário de maio', '2026-05-05',
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'INCOME'),
                            (SELECT id FROM accounts WHERE user_id = 1 AND type = 'CHECKING'),
                            'PIX', FALSE, 1),
                           ('EXPENSE', 899.90, 'Notebook no crédito antigo', '2025-11-20',
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'EXPENSE'),
                            NULL, 'CREDIT', TRUE, 1),
                           ('EXPENSE', 87.30, 'Compra de B', '2026-05-02',
                            (SELECT id FROM categories WHERE user_id = 2),
                            (SELECT id FROM accounts WHERE user_id = 2), 'DEBIT', FALSE, 2)
                    """);
            statement.execute("""
                    INSERT INTO budgets (month_ref, category_id, limit_amount, user_id)
                    VALUES ('2026-05-01',
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'EXPENSE'),
                            800.00, 1)
                    """);
            statement.execute("""
                    INSERT INTO commitments
                        (description, amount, category_id, cadence, due_day, start_date,
                         active, payment_method, execution_mode, target_kind,
                         installment_count, user_id)
                    VALUES ('Aluguel', 1500.00,
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'EXPENSE'),
                            'MONTHLY', 5, '2025-01-05', TRUE, 'PIX', 'MANUAL',
                            'PROJECTION_ONLY', 1, 1)
                    """);
            statement.execute("""
                    INSERT INTO commitment_occurrences
                        (user_id, commitment_id, scheduled_date, effective_date, status)
                    VALUES (1, 1, '2026-06-05', '2026-06-05', 'SCHEDULED')
                    """);
            // A real card with an invoice, purchase, installment and payment.
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
                         total_amount, installment_count, legacy_transaction_id)
                    VALUES (1, 1,
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'EXPENSE'),
                            'Notebook convertido', '2025-11-20', 899.90, 1,
                            (SELECT id FROM transactions
                             WHERE description = 'Notebook no crédito antigo'))
                    """);
            statement.execute("""
                    INSERT INTO credit_card_installments
                        (user_id, purchase_id, invoice_id, sequence_number,
                         total_installments, amount)
                    VALUES (1, 1, 1, 1, 1, 899.90)
                    """);
            statement.execute("""
                    INSERT INTO credit_card_payments
                        (user_id, invoice_id, account_id, amount, paid_on)
                    VALUES (1, 1,
                            (SELECT id FROM accounts WHERE user_id = 1 AND type = 'CHECKING'),
                            899.90, '2026-05-09')
                    """);
            // An ACTIVE legacy-credit conversion anchoring the purchase above.
            statement.execute("""
                    UPDATE transactions SET financially_active = FALSE
                    WHERE description = 'Notebook no crédito antigo'
                    """);
            statement.execute("""
                    INSERT INTO legacy_credit_conversions
                        (user_id, source_transaction_id, card_purchase_id, card_id,
                         original_transaction_date, effective_purchase_date,
                         installment_count, first_invoice_month, status, converted_at)
                    VALUES (1,
                            (SELECT id FROM transactions
                             WHERE description = 'Notebook no crédito antigo'),
                            1, 1, '2025-11-20', '2025-11-20', 1, '2025-12-01',
                            'ACTIVE', now())
                    """);
        }
    }

    @Test
    @Order(2)
    void migratesToLatestPreservingEveryRow() throws Exception {
        flywayTo("latest").migrate();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // Every transaction survives and none gained an import origin.
            try (ResultSet rs = statement.executeQuery("""
                    SELECT count(*) AS total,
                           count(statement_import_item_id) AS imported,
                           sum(amount) AS total_amount
                    FROM transactions
                    """)) {
                rs.next();
                assertThat(rs.getLong("total")).isEqualTo(4);
                assertThat(rs.getLong("imported")).isZero();
                assertThat(rs.getBigDecimal("total_amount")).isEqualByComparingTo("6507.65");
            }
            // The new import tables start empty.
            for (String table : new String[] {
                    "statement_import_batches", "statement_import_items",
                    "category_mapping_rules"}) {
                try (ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    rs.next();
                    assertThat(rs.getLong(1)).as(table).isZero();
                }
            }
            // Neighbour domains are intact: session, card cycle and conversion.
            for (String[] check : new String[][] {
                    {"spring_session", "1"}, {"accounts", "3"}, {"categories", "3"},
                    {"budgets", "1"}, {"commitments", "1"}, {"commitment_occurrences", "1"},
                    {"credit_cards", "1"}, {"credit_card_invoices", "1"},
                    {"credit_card_purchases", "1"}, {"credit_card_installments", "1"},
                    {"credit_card_payments", "1"}, {"legacy_credit_conversions", "1"}}) {
                try (ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + check[0])) {
                    rs.next();
                    assertThat(rs.getLong(1)).as(check[0]).isEqualTo(Long.parseLong(check[1]));
                }
            }
            // The converted source is still financially inactive — migration
            // never touches conversion accounting.
            try (ResultSet rs = statement.executeQuery("""
                    SELECT financially_active FROM transactions
                    WHERE description = 'Notebook no crédito antigo'
                    """)) {
                rs.next();
                assertThat(rs.getBoolean(1)).isFalse();
            }
            // The new indexes exist.
            try (ResultSet rs = statement.executeQuery("""
                    SELECT indexname FROM pg_indexes
                    WHERE tablename IN ('statement_import_batches', 'statement_import_items',
                                        'category_mapping_rules', 'transactions')
                    """)) {
                var names = new java.util.ArrayList<String>();
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
                assertThat(names).contains(
                        "ix_import_batches_user_created",
                        "ix_import_batches_user_file_hash",
                        "uq_import_items_external_imported",
                        "ix_import_items_fingerprint_imported",
                        "ix_import_items_batch_status",
                        "uq_transactions_import_item",
                        "uq_category_rules_identity",
                        "ix_category_rules_user_active");
            }
        }
    }

    @Test
    @Order(3)
    void importConstraintsHold() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // User B cannot own a batch pointing at A's account.
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO statement_import_batches
                        (user_id, account_id, original_filename, format, file_sha256,
                         file_size_bytes, parser_version, fingerprint_version, status)
                    VALUES (2, (SELECT id FROM accounts WHERE user_id = 1 AND type = 'CHECKING'),
                            'extrato.ofx', 'OFX', repeat('a', 64), 100, 1, 1, 'PREVIEW_READY')
                    """)).hasMessageContaining("fk_import_batches_account_owner");
            // A legitimate batch with two items for user A.
            statement.execute("""
                    INSERT INTO statement_import_batches
                        (user_id, account_id, original_filename, format, file_sha256,
                         file_size_bytes, parser_version, fingerprint_version, status)
                    VALUES (1, (SELECT id FROM accounts WHERE user_id = 1 AND type = 'CHECKING'),
                            'extrato.ofx', 'OFX', repeat('a', 64), 100, 1, 1, 'PREVIEW_READY')
                    """);
            statement.execute("""
                    INSERT INTO statement_import_items
                        (user_id, batch_id, account_id, source_index, external_id,
                         posted_date, amount, type, description, normalized_description,
                         fingerprint, status, imported_at)
                    VALUES (1, (SELECT id FROM statement_import_batches WHERE user_id = 1),
                            (SELECT id FROM accounts WHERE user_id = 1 AND type = 'CHECKING'),
                            1, 'FITID-001', '2026-06-01', 45.90, 'EXPENSE',
                            'Padaria', 'padaria', repeat('b', 64), 'IMPORTED', now())
                    """);
            // Strong identity: the same external id cannot be IMPORTED twice
            // for the same owner and account.
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO statement_import_items
                        (user_id, batch_id, account_id, source_index, external_id,
                         posted_date, amount, type, description, normalized_description,
                         fingerprint, status, imported_at)
                    VALUES (1, (SELECT id FROM statement_import_batches WHERE user_id = 1),
                            (SELECT id FROM accounts WHERE user_id = 1 AND type = 'CHECKING'),
                            2, 'FITID-001', '2026-06-01', 45.90, 'EXPENSE',
                            'Padaria', 'padaria', repeat('b', 64), 'IMPORTED', now())
                    """)).hasMessageContaining("uq_import_items_external_imported");
            // ...but an UNDONE copy may coexist (identity is released by undo).
            statement.execute("""
                    INSERT INTO statement_import_items
                        (user_id, batch_id, account_id, source_index, external_id,
                         posted_date, amount, type, description, normalized_description,
                         fingerprint, status, imported_at, undone_at)
                    VALUES (1, (SELECT id FROM statement_import_batches WHERE user_id = 1),
                            (SELECT id FROM accounts WHERE user_id = 1 AND type = 'CHECKING'),
                            3, 'FITID-001', '2026-06-01', 45.90, 'EXPENSE',
                            'Padaria', 'padaria', repeat('b', 64), 'UNDONE', now(), now())
                    """);
            // One live transaction per import item, ever.
            statement.execute("""
                    INSERT INTO transactions
                        (type, amount, description, occurred_on, category_id, account_id,
                         payment_method, user_id, statement_import_item_id)
                    VALUES ('EXPENSE', 45.90, 'Padaria', '2026-06-01',
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'EXPENSE'),
                            (SELECT id FROM accounts WHERE user_id = 1 AND type = 'CHECKING'),
                            'OTHER', 1,
                            (SELECT id FROM statement_import_items WHERE source_index = 1))
                    """);
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO transactions
                        (type, amount, description, occurred_on, category_id, account_id,
                         payment_method, user_id, statement_import_item_id)
                    VALUES ('EXPENSE', 45.90, 'Padaria de novo', '2026-06-01',
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'EXPENSE'),
                            (SELECT id FROM accounts WHERE user_id = 1 AND type = 'CHECKING'),
                            'OTHER', 1,
                            (SELECT id FROM statement_import_items WHERE source_index = 1))
                    """)).hasMessageContaining("uq_transactions_import_item");
            // User B cannot link a transaction to A's import item.
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO transactions
                        (type, amount, description, occurred_on, category_id, account_id,
                         payment_method, user_id, statement_import_item_id)
                    VALUES ('EXPENSE', 45.90, 'Ataque', '2026-06-01',
                            (SELECT id FROM categories WHERE user_id = 2),
                            (SELECT id FROM accounts WHERE user_id = 2),
                            'OTHER', 2,
                            (SELECT id FROM statement_import_items WHERE source_index = 3))
                    """)).hasMessageContaining("fk_transactions_import_item_owner");
            // Rules: cross-owner category linkage is impossible...
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO category_mapping_rules
                        (user_id, transaction_type, operation, pattern, category_id)
                    VALUES (2, 'EXPENSE', 'CONTAINS', 'padaria',
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'EXPENSE'))
                    """)).hasMessageContaining("fk_category_rules_category_owner");
            // ...a valid rule is accepted, and its exact duplicate is not.
            statement.execute("""
                    INSERT INTO category_mapping_rules
                        (user_id, transaction_type, operation, pattern, category_id)
                    VALUES (1, 'EXPENSE', 'CONTAINS', 'padaria',
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'EXPENSE'))
                    """);
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO category_mapping_rules
                        (user_id, transaction_type, operation, pattern, category_id)
                    VALUES (1, 'EXPENSE', 'CONTAINS', 'padaria',
                            (SELECT id FROM categories WHERE user_id = 1 AND type = 'EXPENSE'))
                    """)).hasMessageContaining("uq_category_rules_identity");
            // Clean up probes so the ledger stays pristine for later tests.
            statement.execute("DELETE FROM category_mapping_rules");
            statement.execute("DELETE FROM transactions WHERE statement_import_item_id IS NOT NULL");
            statement.execute("DELETE FROM statement_import_items");
            statement.execute("DELETE FROM statement_import_batches");
        }
    }
}
