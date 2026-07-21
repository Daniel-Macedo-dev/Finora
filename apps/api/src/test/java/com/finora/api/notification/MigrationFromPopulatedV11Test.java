package com.finora.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL proof that a populated V11 installation migrates safely. */
class MigrationFromPopulatedV11Test {

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

    private static Flyway flywayTo(String version) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(version)
                .load();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void populatedV11MigratesWithoutChangingFinancialOrImportData() throws Exception {
        flywayTo("11").migrate();
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    INSERT INTO users (display_name, email, password_hash, status)
                    VALUES ('A', 'a@finora.test', 'hash', 'ACTIVE'),
                           ('B', 'b@finora.test', 'hash', 'ACTIVE')
                    """);
            sql.execute("""
                    INSERT INTO app_settings
                        (minimum_cash_buffer, max_installment_commitment_ratio,
                         monthly_opportunity_rate, budget_warning_threshold, user_id)
                    VALUES (100, .3, .01, .8, 1), (0, .3, 0, .8, 2)
                    """);
            sql.execute("""
                    INSERT INTO categories (name, type, user_id)
                    VALUES ('Despesa A', 'EXPENSE', 1), ('Despesa B', 'EXPENSE', 2)
                    """);
            sql.execute("""
                    INSERT INTO accounts (name, type, opening_balance, user_id)
                    VALUES ('Conta A', 'CHECKING', 1000, 1), ('Conta B', 'CHECKING', 500, 2)
                    """);
            sql.execute("""
                    INSERT INTO transactions
                        (type, amount, description, occurred_on, category_id, account_id,
                         payment_method, user_id)
                    VALUES ('EXPENSE', 10, 'Café', '2026-07-01',
                            (SELECT id FROM categories WHERE user_id = 1),
                            (SELECT id FROM accounts WHERE user_id = 1), 'PIX', 1)
                    """);
            sql.execute("""
                    INSERT INTO commitments
                        (description, amount, category_id, cadence, due_day, start_date,
                         active, payment_method, execution_mode, target_kind,
                         installment_count, user_id)
                    VALUES ('Aluguel', 500,
                            (SELECT id FROM categories WHERE user_id = 1),
                            'MONTHLY', 10, '2026-01-10', TRUE,
                            'PIX', 'MANUAL', 'PROJECTION_ONLY', 1, 1)
                    """);
            sql.execute("""
                    INSERT INTO commitment_occurrences
                        (user_id, commitment_id, scheduled_date, effective_date, status)
                    VALUES (1, (SELECT id FROM commitments WHERE user_id = 1),
                            '2026-07-10', '2026-07-10', 'SCHEDULED')
                    """);
            sql.execute("""
                    INSERT INTO credit_cards
                        (user_id, name, brand, credit_limit, closing_day, due_day)
                    VALUES (1, 'Cartão', 'VISA', 2000, 5, 12)
                    """);
            sql.execute("""
                    INSERT INTO credit_card_invoices
                        (user_id, card_id, reference_month, closing_date, due_date)
                    VALUES (1, (SELECT id FROM credit_cards WHERE user_id = 1),
                            '2026-07-01', '2026-07-05', '2026-07-12')
                    """);
            sql.execute("""
                    INSERT INTO statement_import_batches
                        (user_id, account_id, original_filename, format, file_sha256,
                         file_size_bytes, parser_version, fingerprint_version, status)
                    VALUES (1, (SELECT id FROM accounts WHERE user_id = 1),
                            'sample.ofx', 'OFX', repeat('a', 64), 10, 1, 1,
                            'PREVIEW_READY')
                    """);
            sql.execute("""
                    INSERT INTO statement_import_items
                        (user_id, batch_id, account_id, source_index, status)
                    VALUES (1, (SELECT id FROM statement_import_batches WHERE user_id = 1),
                            (SELECT id FROM accounts WHERE user_id = 1), 1, 'READY')
                    """);
        }

        flywayTo("latest").migrate();
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            try (ResultSet rows = sql.executeQuery("""
                    SELECT count(*) AS total, count(*) FILTER (WHERE enabled) AS enabled,
                           count(*) FILTER (WHERE upcoming_lead_days = 7) AS lead,
                           count(*) FILTER (WHERE browser_enabled = FALSE
                               AND browser_show_amounts = FALSE) AS private_defaults
                    FROM notification_preferences
                    """)) {
                rows.next();
                assertThat(rows.getInt("total")).isEqualTo(2);
                assertThat(rows.getInt("enabled")).isEqualTo(2);
                assertThat(rows.getInt("lead")).isEqualTo(2);
                assertThat(rows.getInt("private_defaults")).isEqualTo(2);
            }
            assertCount(sql, "notifications", 0);
            assertCount(sql, "users", 2);
            assertCount(sql, "app_settings", 2);
            assertCount(sql, "accounts", 2);
            assertCount(sql, "transactions", 1);
            assertCount(sql, "commitments", 1);
            assertCount(sql, "commitment_occurrences", 1);
            assertCount(sql, "credit_card_invoices", 1);
            assertCount(sql, "statement_import_batches", 1);
            assertCount(sql, "statement_import_items", 1);

            assertThatThrownBy(() -> sql.execute("""
                    INSERT INTO notification_preferences (user_id) VALUES (1)
                    """)).hasMessageContaining("uq_notification_preferences_user");
            assertThatThrownBy(() -> sql.execute("""
                    INSERT INTO notification_preferences (user_id, upcoming_lead_days)
                    VALUES (999, 30)
                    """)).hasMessageContaining("ck_notification_preferences_lead_days");
            assertThatThrownBy(() -> sql.execute("""
                    INSERT INTO notifications
                        (user_id, source_key, source_event_id, type, severity, event_date,
                         title, resource_type, route, revision, first_seen_at, last_seen_at,
                         revision_changed_at)
                    VALUES (1, 'X', 'X', 'INVALID', 'INFO', '2026-07-01', 'X',
                            'FORECAST', '/forecast', 1, now(), now(), now())
                    """)).hasMessageContaining("ck_notifications_type");
            assertThatThrownBy(() -> sql.execute("""
                    INSERT INTO notifications
                        (user_id, source_key, source_event_id, type, severity, event_date,
                         title, resource_type, route, revision, read_revision,
                         first_seen_at, last_seen_at, revision_changed_at)
                    VALUES (1, 'FORECAST:INSUFFICIENT_CASH', 'event',
                            'INSUFFICIENT_CASH_PROJECTED', 'CRITICAL', '2026-07-01',
                            'Risco', 'FORECAST', '/forecast', 1, 2, now(), now(), now())
                    """)).hasMessageContaining("ck_notifications_revision_states");
            assertThatThrownBy(() -> sql.execute("""
                    INSERT INTO notifications
                        (user_id, source_key, source_event_id, type, severity, event_date,
                         title, resource_type, route, revision, first_seen_at, last_seen_at,
                         revision_changed_at)
                    VALUES (999, 'FORECAST:INSUFFICIENT_CASH', 'event',
                            'INSUFFICIENT_CASH_PROJECTED', 'CRITICAL', '2026-07-01',
                            'Risco', 'FORECAST', '/forecast', 1, now(), now(), now())
                    """)).hasMessageContaining("notifications_user_id_fkey");
        }
    }

    private static void assertCount(Statement sql, String table, int expected) throws Exception {
        try (ResultSet rows = sql.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            assertThat(rows.getInt(1)).as(table).isEqualTo(expected);
        }
    }
}
