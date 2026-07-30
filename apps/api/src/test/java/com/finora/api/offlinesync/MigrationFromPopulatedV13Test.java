package com.finora.api.offlinesync;

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

/**
 * Real PostgreSQL proof that a populated V13 database survives V14.
 *
 * <p>The fixture deliberately contains the awkward rows: two owners, an
 * imported transaction, a recurring-generated one, a legacy-credit audit record
 * that is no longer financially active, budgets, goals, wishlist items with
 * options, a price snapshot with its own version, notifications and a statement
 * import. If V14 rewrote, dropped or invented anything, one of these would move.
 *
 * <p>All fixture data is synthetic.
 */
class MigrationFromPopulatedV13Test {

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
    void populatedV13MigratesWithoutFabricatingIdentitiesOrReceipts() throws Exception {
        flywayTo("13").migrate();
        seedV13();

        flywayTo("14").migrate();
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            assertEverySeededRowSurvived(sql);
            assertVersionsStartDeterministically(sql);
            assertExistingSnapshotVersionPreserved(sql);
            assertNothingWasFabricated(sql);
            assertClientIdentityIsOwnerScoped(sql);
            assertReceiptInvariants(sql);
            assertPurchaseOptionOwnershipIsBoundToItsItem(sql);
        }
    }

    private void seedV13() throws Exception {
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
                    INSERT INTO commitments
                        (user_id, description, amount, category_id, cadence, due_day,
                         start_date, payment_method)
                    VALUES (1, 'Assinatura sintética', 30,
                            (SELECT id FROM categories WHERE user_id = 1),
                            'MONTHLY', 10, '2026-01-01', 'PIX')
                    """);
            sql.execute("""
                    INSERT INTO statement_import_batches
                        (user_id, account_id, original_filename, format, file_sha256,
                         file_size_bytes, parser_version, fingerprint_version, status)
                    VALUES (1, (SELECT id FROM accounts WHERE user_id = 1),
                            'synthetic.csv', 'CSV', repeat('b', 64), 10, 1, 1, 'COMPLETED')
                    """);
            sql.execute("""
                    INSERT INTO statement_import_items
                        (batch_id, user_id, account_id, source_index, posted_date,
                         description, normalized_description, amount, type,
                         fingerprint, status, imported_at)
                    VALUES ((SELECT id FROM statement_import_batches WHERE user_id = 1),
                            1, (SELECT id FROM accounts WHERE user_id = 1), 1,
                            '2026-07-03', 'Importada', 'importada', 25, 'EXPENSE',
                            repeat('c', 64), 'IMPORTED', now())
                    """);
            // Four transactions covering every protected shape plus one ordinary row.
            sql.execute("""
                    INSERT INTO transactions
                        (user_id, type, amount, description, occurred_on, category_id,
                         account_id, payment_method)
                    VALUES (1, 'EXPENSE', 10, 'Comum', '2026-07-01',
                            (SELECT id FROM categories WHERE user_id = 1),
                            (SELECT id FROM accounts WHERE user_id = 1), 'PIX')
                    """);
            sql.execute("""
                    INSERT INTO transactions
                        (user_id, type, amount, description, occurred_on, category_id,
                         statement_import_item_id)
                    VALUES (1, 'EXPENSE', 25, 'Importada', '2026-07-03',
                            (SELECT id FROM categories WHERE user_id = 1),
                            (SELECT id FROM statement_import_items WHERE user_id = 1))
                    """);
            sql.execute("""
                    INSERT INTO transactions
                        (user_id, type, amount, description, occurred_on, category_id,
                         commitment_id)
                    VALUES (1, 'EXPENSE', 30, 'Recorrente', '2026-07-10',
                            (SELECT id FROM categories WHERE user_id = 1),
                            (SELECT id FROM commitments WHERE user_id = 1))
                    """);
            sql.execute("""
                    INSERT INTO transactions
                        (user_id, type, amount, description, occurred_on, category_id,
                         payment_method, legacy_credit, financially_active)
                    VALUES (1, 'EXPENSE', 99, 'Crédito legado', '2025-12-01',
                            (SELECT id FROM categories WHERE user_id = 1),
                            'CREDIT', TRUE, FALSE)
                    """);
            sql.execute("""
                    INSERT INTO budgets (user_id, month_ref, category_id, limit_amount)
                    VALUES (1, '2026-07-01', (SELECT id FROM categories WHERE user_id = 1), 800),
                           (2, '2026-07-01', (SELECT id FROM categories WHERE user_id = 2), 400)
                    """);
            sql.execute("""
                    INSERT INTO goals (user_id, name, target_amount, current_amount, target_date)
                    VALUES (1, 'Reserva', 10000, 2500, '2027-01-01'),
                           (2, 'Viagem', 5000, 100, NULL)
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
                        (wishlist_item_id, merchant, payment_kind, base_price, shipping, fees)
                    VALUES ((SELECT id FROM wishlist_items WHERE user_id = 1),
                            'Loja sintética', 'CASH', 4800, 20, 10),
                           ((SELECT id FROM wishlist_items WHERE user_id = 2),
                            'Outra loja', 'CASH', 850, 0, 0)
                    """);
            sql.execute("""
                    INSERT INTO wishlist_price_snapshots
                        (user_id, wishlist_item_id, series_key, client_request_id,
                         merchant, merchant_normalized, payment_kind, base_price,
                         shipping, fees, nominal_cost, observed_on, version)
                    VALUES (1, (SELECT id FROM wishlist_items WHERE user_id = 1),
                            'MANUAL:loja:CASH', '11111111-1111-1111-1111-111111111111',
                            'Loja', 'loja', 'CASH', 4800, 20, 10, 4830, '2026-07-05', 7)
                    """);
            sql.execute("INSERT INTO notification_preferences (user_id) VALUES (1), (2)");
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
    }

    private static void assertEverySeededRowSurvived(Statement sql) throws Exception {
        assertCount(sql, "users", 2);
        assertCount(sql, "categories", 2);
        assertCount(sql, "accounts", 2);
        assertCount(sql, "commitments", 1);
        assertCount(sql, "statement_import_batches", 1);
        assertCount(sql, "statement_import_items", 1);
        assertCount(sql, "transactions", 4);
        assertCount(sql, "budgets", 2);
        assertCount(sql, "goals", 2);
        assertCount(sql, "wishlist_items", 2);
        assertCount(sql, "purchase_options", 2);
        assertCount(sql, "wishlist_price_snapshots", 1);
        assertCount(sql, "notifications", 1);

        // The protected shapes kept the flags that make them protected.
        assertCountWhere(sql, "transactions", "statement_import_item_id IS NOT NULL", 1);
        assertCountWhere(sql, "transactions", "commitment_id IS NOT NULL", 1);
        assertCountWhere(sql, "transactions", "legacy_credit AND NOT financially_active", 1);
    }

    private static void assertVersionsStartDeterministically(Statement sql) throws Exception {
        for (String table : new String[] {
                "transactions", "budgets", "goals", "wishlist_items", "purchase_options"}) {
            assertCountWhere(sql, table, "version <> 0", 0);
        }
    }

    private static void assertExistingSnapshotVersionPreserved(Statement sql) throws Exception {
        try (ResultSet row = sql.executeQuery(
                "SELECT version FROM wishlist_price_snapshots WHERE user_id = 1")) {
            row.next();
            assertThat(row.getLong("version"))
                    .as("V13 snapshot versions must survive untouched")
                    .isEqualTo(7L);
        }
    }

    private static void assertNothingWasFabricated(Statement sql) throws Exception {
        assertCount(sql, "offline_mutation_receipts", 0);
        for (String table : new String[] {
                "transactions", "budgets", "goals", "wishlist_items", "purchase_options"}) {
            assertCountWhere(sql, table, "client_resource_id IS NOT NULL", 0);
        }
    }

    private static void assertClientIdentityIsOwnerScoped(Statement sql) throws Exception {
        String shared = "22222222-2222-2222-2222-222222222222";
        sql.execute("UPDATE goals SET client_resource_id = '%s' WHERE user_id = 1"
                .formatted(shared));
        // The very same UUID for a different owner is legitimate and must be allowed.
        sql.execute("UPDATE goals SET client_resource_id = '%s' WHERE user_id = 2"
                .formatted(shared));

        sql.execute("""
                INSERT INTO goals (user_id, name, target_amount, current_amount)
                VALUES (1, 'Outra', 100, 0)
                """);
        assertThatThrownBy(() -> sql.execute("""
                UPDATE goals SET client_resource_id = '%s'
                WHERE user_id = 1 AND name = 'Outra'
                """.formatted(shared)))
                .hasMessageContaining("uq_goals_user_client_resource");

        // Null stays repeatable: existing rows are not forced into an identity.
        assertCountWhere(sql, "goals", "client_resource_id IS NULL", 1);
        sql.execute("DELETE FROM goals WHERE name = 'Outra'");
    }

    private static void assertReceiptInvariants(Statement sql) throws Exception {
        insertReceipt(sql, 1, "33333333-3333-3333-3333-333333333333", "TRANSACTION", "CREATE");
        // Same key, other owner: independent and allowed.
        insertReceipt(sql, 2, "33333333-3333-3333-3333-333333333333", "TRANSACTION", "CREATE");
        assertThatThrownBy(() -> insertReceipt(
                sql, 1, "33333333-3333-3333-3333-333333333333", "BUDGET", "UPDATE"))
                .hasMessageContaining("uq_offline_receipts_user_mutation");

        assertThatThrownBy(() -> insertReceipt(
                sql, 1, "44444444-4444-4444-4444-444444444444", "CREDIT_CARD", "CREATE"))
                .hasMessageContaining("ck_offline_receipts_resource_type");
        assertThatThrownBy(() -> insertReceipt(
                sql, 1, "44444444-4444-4444-4444-444444444444", "TRANSACTION", "UPSERT"))
                .hasMessageContaining("ck_offline_receipts_operation");
        assertThatThrownBy(() -> sql.execute("""
                INSERT INTO offline_mutation_receipts
                    (user_id, client_mutation_id, resource_type, operation, request_hash,
                     result_code, response_payload)
                VALUES (1, '44444444-4444-4444-4444-444444444444', 'TRANSACTION', 'CREATE',
                        'NOT-A-HASH', 'APPLIED', '{}'::jsonb)
                """)).hasMessageContaining("ck_offline_receipts_request_hash");
        assertThatThrownBy(() -> sql.execute("""
                INSERT INTO offline_mutation_receipts
                    (user_id, client_mutation_id, resource_type, operation, request_hash,
                     result_code, response_payload)
                VALUES (1, '44444444-4444-4444-4444-444444444444', 'TRANSACTION', 'CREATE',
                        repeat('a', 64), 'APPLIED', '[]'::jsonb)
                """)).hasMessageContaining("ck_offline_receipts_response_payload");
        // Uppercase hex is not the canonical form the application writes.
        assertThatThrownBy(() -> sql.execute("""
                INSERT INTO offline_mutation_receipts
                    (user_id, client_mutation_id, resource_type, operation, request_hash,
                     result_code, response_payload)
                VALUES (1, '44444444-4444-4444-4444-444444444444', 'TRANSACTION', 'CREATE',
                        repeat('A', 64), 'APPLIED', '{}'::jsonb)
                """)).hasMessageContaining("ck_offline_receipts_request_hash");

        // Receipts belong to their owner: deleting a user with receipts is refused
        // by the same foreign key that protects every other financial table.
        assertThatThrownBy(() -> sql.execute("DELETE FROM users WHERE id = 2"))
                .hasMessageContaining("violates foreign key constraint");

        sql.execute("DELETE FROM offline_mutation_receipts");
    }

    private static void assertPurchaseOptionOwnershipIsBoundToItsItem(Statement sql)
            throws Exception {
        // Backfilled from the parent, never guessed.
        try (ResultSet rows = sql.executeQuery("""
                SELECT count(*) FROM purchase_options o
                JOIN wishlist_items i ON i.id = o.wishlist_item_id
                WHERE o.user_id <> i.user_id
                """)) {
            rows.next();
            assertThat(rows.getInt(1)).as("option owner must match its item").isZero();
        }
        assertThatThrownBy(() -> sql.execute("""
                UPDATE purchase_options SET user_id = 2
                WHERE wishlist_item_id = (SELECT id FROM wishlist_items WHERE user_id = 1)
                """)).hasMessageContaining("fk_purchase_options_item_owner");
    }

    private static void insertReceipt(Statement sql, long userId, String mutationId,
                                      String resourceType, String operation) throws Exception {
        sql.execute("""
                INSERT INTO offline_mutation_receipts
                    (user_id, client_mutation_id, resource_type, operation, request_hash,
                     result_code, response_payload)
                VALUES (%d, '%s', '%s', '%s', repeat('a', 64), 'APPLIED', '{}'::jsonb)
                """.formatted(userId, mutationId, resourceType, operation));
    }

    private static void assertCount(Statement sql, String table, int expected) throws Exception {
        assertCountWhere(sql, table, "TRUE", expected);
    }

    private static void assertCountWhere(Statement sql, String table, String condition,
                                         int expected) throws Exception {
        try (ResultSet rows = sql.executeQuery(
                "SELECT count(*) FROM " + table + " WHERE " + condition)) {
            rows.next();
            assertThat(rows.getInt(1)).as(table + " where " + condition).isEqualTo(expected);
        }
    }
}
