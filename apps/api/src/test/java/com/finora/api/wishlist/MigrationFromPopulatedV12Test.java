package com.finora.api.wishlist;

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

/** Real PostgreSQL proof that populated V12 wishlist data migrates safely to V13. */
class MigrationFromPopulatedV12Test {

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
    void populatedV12MigratesWithoutFabricatingHistoryAndEnforcesLifecycle() throws Exception {
        flywayTo("12").migrate();
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    INSERT INTO users (display_name, email, password_hash, status)
                    VALUES ('Owner A', 'a@finora.test', 'hash', 'ACTIVE'),
                           ('Owner B', 'b@finora.test', 'hash', 'ACTIVE')
                    """);
            sql.execute("""
                    INSERT INTO app_settings
                        (minimum_cash_buffer, max_installment_commitment_ratio,
                         monthly_opportunity_rate, budget_warning_threshold, user_id)
                    VALUES (100, .3, .01, .8, 1), (0, .3, 0, .8, 2)
                    """);
            sql.execute("""
                    INSERT INTO categories (name, type, user_id)
                    VALUES ('Casa A', 'EXPENSE', 1), ('Casa B', 'EXPENSE', 2)
                    """);
            sql.execute("""
                    INSERT INTO accounts (name, type, opening_balance, user_id)
                    VALUES ('Conta A', 'CHECKING', 1000, 1), ('Conta B', 'CHECKING', 500, 2)
                    """);
            sql.execute("""
                    INSERT INTO credit_cards
                        (user_id, name, brand, credit_limit, closing_day, due_day)
                    VALUES (1, 'Cartão A', 'VISA', 2000, 5, 12)
                    """);
            sql.execute("""
                    INSERT INTO wishlist_items
                        (user_id, name, category_id, reference_price, target_price,
                         priority, status)
                    VALUES (1, 'Notebook', (SELECT id FROM categories WHERE user_id = 1),
                            5000, 4500, 'HIGH', 'MONITORING'),
                           (2, 'Mesa', (SELECT id FROM categories WHERE user_id = 2),
                            900, 800, 'MEDIUM', 'PLANNING')
                    """);
            sql.execute("""
                    INSERT INTO purchase_options
                        (wishlist_item_id, merchant, payment_kind, base_price, shipping,
                         fees, installment_count, installment_amount, credit_card_id)
                    VALUES ((SELECT id FROM wishlist_items WHERE user_id = 1),
                            'Loja sintética', 'INSTALLMENT', 4800, 20, 10, 12, 400,
                            (SELECT id FROM credit_cards WHERE user_id = 1)),
                           ((SELECT id FROM wishlist_items WHERE user_id = 2),
                            'Outra loja', 'CASH', 850, 0, 0, NULL, NULL, NULL)
                    """);
            sql.execute("""
                    INSERT INTO transactions
                        (user_id, type, amount, description, occurred_on, category_id,
                         account_id, payment_method)
                    VALUES (1, 'EXPENSE', 10, 'Dado sintético', '2026-07-01',
                            (SELECT id FROM categories WHERE user_id = 1),
                            (SELECT id FROM accounts WHERE user_id = 1), 'PIX')
                    """);
            sql.execute("""
                    INSERT INTO statement_import_batches
                        (user_id, account_id, original_filename, format, file_sha256,
                         file_size_bytes, parser_version, fingerprint_version, status)
                    VALUES (1, (SELECT id FROM accounts WHERE user_id = 1),
                            'synthetic.csv', 'CSV', repeat('a', 64), 10, 1, 1,
                            'PREVIEW_READY')
                    """);
            sql.execute("""
                    INSERT INTO notification_preferences (user_id)
                    VALUES (1), (2)
                    """);
            sql.execute("""
                    INSERT INTO notifications
                        (user_id, source_key, source_event_id, type, severity, event_date,
                         title, resource_type, route, revision, first_seen_at, last_seen_at,
                         revision_changed_at)
                    VALUES (1, 'FORECAST:INSUFFICIENT_CASH', 'synthetic-event',
                            'INSUFFICIENT_CASH_PROJECTED', 'WARNING', '2026-07-02',
                            'Risco sintético', 'FORECAST', '/forecast', 1,
                            now(), now(), now())
                    """);
        }

        flywayTo("13").migrate();
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            assertCount(sql, "users", 2);
            assertCount(sql, "wishlist_items", 2);
            assertCount(sql, "purchase_options", 2);
            assertCount(sql, "transactions", 1);
            assertCount(sql, "statement_import_batches", 1);
            assertCount(sql, "notification_preferences", 2);
            assertCount(sql, "notifications", 1);
            assertCount(sql, "wishlist_price_snapshots", 0);

            insertSnapshot(sql, 1, "00000000-0000-0000-0000-000000000001");
            assertThatThrownBy(() -> insertSnapshot(
                    sql, 1, "00000000-0000-0000-0000-000000000001"))
                    .hasMessageContaining("uq_price_snapshots_user_request");
            insertSnapshot(sql, 2, "00000000-0000-0000-0000-000000000001");

            assertThatThrownBy(() -> sql.execute("""
                    INSERT INTO wishlist_price_snapshots
                        (user_id, wishlist_item_id, series_key, client_request_id,
                         merchant, merchant_normalized, payment_kind, base_price,
                         shipping, fees, nominal_cost, observed_on)
                    VALUES (2, (SELECT id FROM wishlist_items WHERE user_id = 1),
                            'MANUAL:x:CASH', gen_random_uuid(), 'X', 'x', 'CASH',
                            10, 0, 0, 10, '2026-07-01')
                    """)).hasMessageContaining("fk_price_snapshots_item_owner");
            assertThatThrownBy(() -> sql.execute("""
                    INSERT INTO wishlist_price_snapshots
                        (user_id, wishlist_item_id, series_key, client_request_id,
                         merchant, merchant_normalized, payment_kind, base_price,
                         shipping, fees, nominal_cost, installment_count,
                         installment_amount, observed_on)
                    VALUES (1, (SELECT id FROM wishlist_items WHERE user_id = 1),
                            'MANUAL:x:CASH', gen_random_uuid(), 'X', 'x', 'CASH',
                            10, 0, 0, 11, 2, 5, '2026-07-01')
                    """)).hasMessageContaining("ck_price_snapshots");

            sql.execute("""
                    DELETE FROM purchase_options
                    WHERE wishlist_item_id = (SELECT id FROM wishlist_items WHERE user_id = 1)
                    """);
            try (ResultSet row = sql.executeQuery("""
                    SELECT purchase_option_id, merchant, series_key
                    FROM wishlist_price_snapshots WHERE user_id = 1
                    """)) {
                row.next();
                assertThat(row.getObject("purchase_option_id")).isNull();
                assertThat(row.getString("merchant")).isEqualTo("Loja sintética");
                assertThat(row.getString("series_key")).startsWith("OPTION:");
            }

            sql.execute("DELETE FROM wishlist_items WHERE user_id = 1");
            assertCountWhere(sql, "wishlist_price_snapshots", "user_id = 1", 0);
            assertCountWhere(sql, "wishlist_price_snapshots", "user_id = 2", 1);
        }
    }

    private static void insertSnapshot(Statement sql, long userId, String requestId)
            throws Exception {
        sql.execute("""
                INSERT INTO wishlist_price_snapshots
                    (user_id, wishlist_item_id, purchase_option_id, series_key,
                     client_request_id, merchant, merchant_normalized, payment_kind,
                     base_price, shipping, fees, nominal_cost, installment_count,
                     installment_amount, observed_on, offer_url)
                SELECT %1$d, w.id, p.id, 'OPTION:' || p.id, '%2$s',
                       'Loja sintética', 'loja sintética', 'INSTALLMENT',
                       4800, 20, 10, 4830, 12, 400, '2026-07-01',
                       'https://example.test/oferta'
                FROM wishlist_items w
                LEFT JOIN purchase_options p ON p.wishlist_item_id = w.id
                WHERE w.user_id = %1$d
                """.formatted(userId, requestId));
    }

    private static void assertCount(Statement sql, String table, int expected) throws Exception {
        assertCountWhere(sql, table, "TRUE", expected);
    }

    private static void assertCountWhere(Statement sql, String table, String condition, int expected)
            throws Exception {
        try (ResultSet rows = sql.executeQuery(
                "SELECT count(*) FROM " + table + " WHERE " + condition)) {
            rows.next();
            assertThat(rows.getInt(1)).as(table).isEqualTo(expected);
        }
    }
}
