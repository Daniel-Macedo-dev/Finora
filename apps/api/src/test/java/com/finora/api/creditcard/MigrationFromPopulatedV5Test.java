package com.finora.api.creditcard;

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
 * Simulates the real upgrade path of the persistent development database:
 * migrate to V5, insert live-looking data (users, accounts, categories,
 * transactions — including a legacy CREDIT one), then migrate to the latest
 * version and prove that history survived, the legacy flag landed exactly on
 * the CREDIT rows, and the new cross-owner constraints hold.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationFromPopulatedV5Test {

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
    void migratesToV5AndAcceptsRealisticData() throws Exception {
        flywayTo("5").migrate();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO users (display_name, email, password_hash, status)
                    VALUES ('Usuária A', 'a@finora.test', 'hash-a', 'ACTIVE'),
                           ('Usuário B', 'b@finora.test', 'hash-b', 'ACTIVE')
                    """);
            statement.execute("""
                    INSERT INTO accounts (name, type, opening_balance, user_id)
                    VALUES ('Conta Corrente', 'CHECKING', 1000.00, 1),
                           ('Conta B', 'CHECKING', 500.00, 2)
                    """);
            statement.execute("""
                    INSERT INTO categories (name, type, user_id)
                    VALUES ('Compras', 'EXPENSE', 1), ('Compras', 'EXPENSE', 2)
                    """);
            // Ids are resolved by owner: the V1 seed categories are removed by
            // V4 on a fresh database, so hardcoded ids would not exist.
            statement.execute("""
                    INSERT INTO transactions
                        (type, amount, description, occurred_on, category_id, account_id,
                         payment_method, user_id)
                    VALUES ('EXPENSE', 150.00, 'Compra antiga no crédito', '2026-05-10',
                            (SELECT id FROM categories WHERE user_id = 1),
                            (SELECT id FROM accounts WHERE user_id = 1), 'CREDIT', 1),
                           ('EXPENSE', 80.00, 'Mercado no débito', '2026-05-11',
                            (SELECT id FROM categories WHERE user_id = 1),
                            (SELECT id FROM accounts WHERE user_id = 1), 'DEBIT', 1),
                           ('EXPENSE', 42.00, 'Crédito antigo de B', '2026-05-12',
                            (SELECT id FROM categories WHERE user_id = 2),
                            (SELECT id FROM accounts WHERE user_id = 2), 'CREDIT', 2)
                    """);
        }
    }

    @Test
    @Order(2)
    void migratesToLatestPreservingDataAndFlaggingLegacyCredit() throws Exception {
        flywayTo("latest").migrate();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT count(*), sum(amount) FROM transactions")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(3);
                assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("272.00");
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT count(*) FROM transactions WHERE legacy_credit")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT legacy_credit FROM transactions WHERE payment_method = 'DEBIT'")) {
                rs.next();
                assertThat(rs.getBoolean(1)).isFalse();
            }
        }
    }

    @Test
    @Order(3)
    void rejectsNewGenericCreditTransactionsAtDatabaseLevel() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO transactions
                        (type, amount, description, occurred_on, category_id, payment_method, user_id)
                    VALUES ('EXPENSE', 10.00, 'Novo crédito genérico', '2026-07-01',
                            (SELECT id FROM categories WHERE user_id = 1), 'CREDIT', 1)
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_transactions_credit_is_legacy");
        }
    }

    @Test
    @Order(4)
    void rejectsCrossOwnerCreditCardReferences() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO credit_cards
                        (user_id, name, brand, credit_limit, closing_day, due_day)
                    VALUES (1, 'Cartão de A', 'VISA', 5000.00, 10, 17)
                    """);
            // User B cannot own a purchase pointing at user A's card.
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO credit_card_purchases
                        (user_id, card_id, category_id, description, purchase_date,
                         total_amount, installment_count)
                    VALUES (2, 1, (SELECT id FROM categories WHERE user_id = 2),
                            'Compra maliciosa', '2026-07-01', 100.00, 1)
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_credit_card_purchases_card_owner");
            // User B cannot pay from user A's account either.
            statement.execute("""
                    INSERT INTO credit_card_invoices
                        (user_id, card_id, reference_month, closing_date, due_date)
                    VALUES (1, 1, '2026-08-01', '2026-08-10', '2026-08-17')
                    """);
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO credit_card_payments
                        (user_id, invoice_id, account_id, amount, paid_on)
                    VALUES (1, 1, 2, 50.00, '2026-08-17')
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_credit_card_payments_account_owner");
        }
    }

    @Test
    @Order(5)
    void enforcesOneInvoicePerCardAndMonth() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO credit_card_invoices
                        (user_id, card_id, reference_month, closing_date, due_date)
                    VALUES (1, 1, '2026-08-01', '2026-08-10', '2026-08-17')
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_credit_card_invoices_card_month");
        }
    }
}
