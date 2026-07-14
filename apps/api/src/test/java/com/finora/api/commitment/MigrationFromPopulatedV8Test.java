package com.finora.api.commitment;

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
 * Real upgrade path of the persistent development database from V8 (credit
 * cards) to the recurring-automation schema: existing commitments must
 * survive with safe defaults (MANUAL + PROJECTION_ONLY — never auto-executed),
 * a legacy CREDIT commitment stays projection-only, and the new occurrence
 * constraints (identity uniqueness, cross-owner protection) hold.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationFromPopulatedV8Test {

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
    void migratesToV8AndAcceptsRealisticData() throws Exception {
        flywayTo("8").migrate();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO users (display_name, email, password_hash, status)
                    VALUES ('Usuária A', 'a@finora.test', 'hash-a', 'ACTIVE'),
                           ('Usuário B', 'b@finora.test', 'hash-b', 'ACTIVE')
                    """);
            statement.execute("""
                    INSERT INTO accounts (name, type, opening_balance, user_id)
                    VALUES ('Conta A', 'CHECKING', 1000.00, 1),
                           ('Conta B', 'CHECKING', 500.00, 2)
                    """);
            statement.execute("""
                    INSERT INTO categories (name, type, user_id)
                    VALUES ('Assinaturas', 'EXPENSE', 1), ('Assinaturas', 'EXPENSE', 2)
                    """);
            statement.execute("""
                    INSERT INTO commitments
                        (description, amount, category_id, cadence, due_day, start_date,
                         active, payment_method, user_id)
                    VALUES ('Streaming', 39.90,
                            (SELECT id FROM categories WHERE user_id = 1), 'MONTHLY', 8,
                            '2025-01-08', TRUE, 'CREDIT', 1),
                           ('Aluguel', 1800.00,
                            (SELECT id FROM categories WHERE user_id = 1), 'MONTHLY', 5,
                            '2025-01-05', TRUE, 'PIX', 1),
                           ('Seguro de B', 900.00,
                            (SELECT id FROM categories WHERE user_id = 2), 'YEARLY', NULL,
                            '2025-03-20', TRUE, NULL, 2)
                    """);
            statement.execute("""
                    INSERT INTO transactions
                        (type, amount, description, occurred_on, category_id, account_id, user_id)
                    VALUES ('EXPENSE', 39.90, 'Streaming de maio', '2026-05-08',
                            (SELECT id FROM categories WHERE user_id = 1),
                            (SELECT id FROM accounts WHERE user_id = 1), 1)
                    """);
        }
    }

    @Test
    @Order(2)
    void migratesToLatestWithSafeDefaultsForLegacyCommitments() throws Exception {
        flywayTo("latest").migrate();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("""
                    SELECT description, execution_mode, target_kind, installment_count,
                           payment_method
                    FROM commitments ORDER BY id
                    """)) {
                int rows = 0;
                while (rs.next()) {
                    rows++;
                    assertThat(rs.getString("execution_mode")).isEqualTo("MANUAL");
                    assertThat(rs.getString("target_kind")).isEqualTo("PROJECTION_ONLY");
                    assertThat(rs.getInt("installment_count")).isEqualTo(1);
                }
                assertThat(rows).isEqualTo(3);
            }
            // No occurrence was fabricated for legacy rows.
            try (ResultSet rs = statement.executeQuery(
                    "SELECT count(*) FROM commitment_occurrences")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
            // Historical transactions survive untouched.
            try (ResultSet rs = statement.executeQuery(
                    "SELECT count(*), sum(amount) FROM transactions")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(1);
                assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("39.90");
            }
        }
    }

    @Test
    @Order(3)
    void newCadenceAndConstraintsHold() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // WEEKLY is now a valid cadence.
            statement.execute("""
                    INSERT INTO commitments
                        (description, amount, category_id, cadence, start_date, active,
                         execution_mode, target_kind, account_id, installment_count, user_id)
                    VALUES ('Feira semanal', 120.00,
                            (SELECT id FROM categories WHERE user_id = 1), 'WEEKLY',
                            '2026-07-01', TRUE, 'AUTOMATIC', 'ACCOUNT_TRANSACTION',
                            (SELECT id FROM accounts WHERE user_id = 1), 1, 1)
                    """);
            // AUTOMATIC without a target is rejected by the check constraint.
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO commitments
                        (description, amount, category_id, cadence, due_day, start_date, active,
                         execution_mode, target_kind, installment_count, user_id)
                    VALUES ('Inválido', 10.00,
                            (SELECT id FROM categories WHERE user_id = 1), 'MONTHLY', 1,
                            '2026-01-01', TRUE, 'AUTOMATIC', 'PROJECTION_ONLY', 1, 1)
                    """)).hasMessageContaining("ck_commitments_automatic_has_target");
            // Cross-owner target references are impossible.
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO commitments
                        (description, amount, category_id, cadence, due_day, start_date, active,
                         execution_mode, target_kind, account_id, installment_count, user_id)
                    VALUES ('Roubo de conta', 10.00,
                            (SELECT id FROM categories WHERE user_id = 2), 'MONTHLY', 1,
                            '2026-01-01', TRUE, 'MANUAL', 'ACCOUNT_TRANSACTION',
                            (SELECT id FROM accounts WHERE user_id = 1), 1, 2)
                    """)).hasMessageContaining("fk_commitments_account_owner");
        }
    }

    @Test
    @Order(4)
    void occurrenceIdentityAndOwnershipConstraintsHold() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO commitment_occurrences
                        (user_id, commitment_id, scheduled_date, effective_date, status)
                    VALUES (1, (SELECT id FROM commitments WHERE description = 'Aluguel'),
                            '2026-08-05', '2026-08-05', 'SCHEDULED')
                    """);
            // Same definition + scheduled date cannot exist twice.
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO commitment_occurrences
                        (user_id, commitment_id, scheduled_date, effective_date, status)
                    VALUES (1, (SELECT id FROM commitments WHERE description = 'Aluguel'),
                            '2026-08-05', '2026-08-20', 'SCHEDULED')
                    """)).hasMessageContaining("uq_commitment_occurrences_identity");
            // User B cannot own an occurrence of A's definition.
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO commitment_occurrences
                        (user_id, commitment_id, scheduled_date, effective_date, status)
                    VALUES (2, (SELECT id FROM commitments WHERE description = 'Aluguel'),
                            '2026-09-05', '2026-09-05', 'SCHEDULED')
                    """)).hasMessageContaining("fk_commitment_occurrences_commitment_owner");
        }
    }
}
