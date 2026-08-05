package com.finora.api.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL proof that a populated V14 ledger survives V15.
 *
 * <p>The contract this test defends is narrow and absolute: after the
 * migration every pre-existing row is labelled BRL and not one monetary value
 * has changed. A migration that "helpfully" converted or re-scaled amounts
 * would silently rewrite the user's financial history.
 */
class MigrationFromPopulatedV14Test {

    private static PostgreSQLContainer<?> postgres;

    // V1 ships default categories, so generated ids are not predictable. Every
    // reference below resolves through a natural key instead of a literal id.
    private static final String USER_A = "(SELECT id FROM users WHERE email = 'a@finora.test')";
    private static final String USER_B = "(SELECT id FROM users WHERE email = 'b@finora.test')";
    private static final String CATEGORY_A = "(SELECT id FROM categories WHERE name = 'Casa A')";
    private static final String ACCOUNT_A = "(SELECT id FROM accounts WHERE name = 'Conta A')";
    private static final String CARD_A = "(SELECT id FROM credit_cards WHERE name = 'Cartão A')";
    private static final String INVOICE_A =
            "(SELECT id FROM credit_card_invoices WHERE reference_month = '2026-07-01')";
    private static final String COMMITMENT_A =
            "(SELECT id FROM commitments WHERE description = 'Aluguel')";
    private static final String ITEM_A =
            "(SELECT id FROM wishlist_items WHERE name = 'Notebook')";
    private static final String BATCH_A =
            "(SELECT id FROM statement_import_batches WHERE original_filename = 'synthetic.csv')";

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
                .cleanDisabled(false)
                .load();
    }

    /**
     * Migrates to a version from an empty schema. All tests here share one
     * container, so each starts by dropping what the previous one left behind:
     * otherwise row counts would depend on execution order.
     */
    private static void migrateFreshTo(String version) {
        flywayTo(version).clean();
        flywayTo(version).migrate();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void populatedV14BecomesBrlWithoutChangingAnyAmount() throws Exception {
        migrateFreshTo("14");

        Map<String, BigDecimal> before = new LinkedHashMap<>();
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            seedV14(sql);
            captureAmounts(sql, before);
        }

        flywayTo("15").migrate();

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            // Nothing was dropped on the way through.
            assertCount(sql, "users", 2);
            assertCount(sql, "accounts", 2);
            assertCount(sql, "transactions", 2);
            assertCount(sql, "credit_cards", 1);
            assertCount(sql, "credit_card_purchases", 1);
            assertCount(sql, "credit_card_invoices", 1);
            assertCount(sql, "credit_card_payments", 1);
            assertCount(sql, "budgets", 1);
            assertCount(sql, "commitments", 1);
            assertCount(sql, "commitment_occurrences", 1);
            assertCount(sql, "goals", 1);
            assertCount(sql, "wishlist_items", 1);
            assertCount(sql, "purchase_options", 1);
            assertCount(sql, "wishlist_price_snapshots", 1);
            assertCount(sql, "notifications", 1);
            assertCount(sql, "statement_import_batches", 1);
            assertCount(sql, "statement_import_items", 1);
            assertCount(sql, "offline_mutation_receipts", 1);

            // Every monetary root is now explicitly BRL.
            assertAllBrl(sql, "app_settings", "base_currency");
            assertAllBrl(sql, "accounts", "currency");
            assertAllBrl(sql, "transactions", "currency");
            assertAllBrl(sql, "credit_cards", "currency");
            assertAllBrl(sql, "commitments", "currency");
            assertAllBrl(sql, "goals", "currency");
            assertAllBrl(sql, "wishlist_items", "currency");
            assertAllBrl(sql, "notifications", "currency");

            // ...and not one amount moved.
            Map<String, BigDecimal> after = new LinkedHashMap<>();
            captureAmounts(sql, after);
            assertThat(after).isEqualTo(before);
            after.forEach((label, value) ->
                    assertThat(value)
                            .as(label)
                            .isEqualByComparingTo(before.get(label)));
        }
    }

    @Test
    void currencyMustBeResolvedExplicitlyAfterTheBackfill() throws Exception {
        migrateFreshTo("15");
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    INSERT INTO users (display_name, email, password_hash, status)
                    VALUES ('Default probe', 'defaults@finora.test', 'hash', 'ACTIVE')
                    """);

            // The temporary backfill default is gone: an insert that forgets the
            // currency must fail loudly rather than quietly becoming BRL.
            assertThatThrownBy(() -> sql.execute("""
                    INSERT INTO accounts (name, type, opening_balance, user_id)
                    VALUES ('Sem moeda', 'CHECKING', 10,
                            (SELECT id FROM users WHERE email = 'defaults@finora.test'))
                    """)).hasMessageContaining("currency");

            // A newly registered user's base currency legitimately defaults to BRL.
            sql.execute("""
                    INSERT INTO app_settings
                        (minimum_cash_buffer, max_installment_commitment_ratio,
                         monthly_opportunity_rate, budget_warning_threshold, user_id)
                    VALUES (0, .3, 0, .8,
                            (SELECT id FROM users WHERE email = 'defaults@finora.test'))
                    """);
            try (ResultSet row = sql.executeQuery("""
                    SELECT base_currency FROM app_settings
                    WHERE user_id = (SELECT id FROM users WHERE email = 'defaults@finora.test')
                    """)) {
                row.next();
                assertThat(row.getString(1)).isEqualTo("BRL");
            }
        }
    }

    @Test
    void databaseRejectsCurrenciesOutsideTheCatalogue() throws Exception {
        migrateFreshTo("15");
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    INSERT INTO users (display_name, email, password_hash, status)
                    VALUES ('Catalogue probe', 'catalogue@finora.test', 'hash', 'ACTIVE')
                    """);
            assertThatThrownBy(() -> sql.execute("""
                    INSERT INTO accounts (name, type, opening_balance, user_id, currency)
                    VALUES ('Bitcoin', 'CHECKING', 10,
                            (SELECT id FROM users WHERE email = 'catalogue@finora.test'), 'BTC')
                    """)).hasMessageContaining("ck_accounts_currency");
        }
    }

    @Test
    void databaseRefusesAccountLinkedTransactionsInAnotherCurrency() throws Exception {
        migrateFreshTo("15");
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    INSERT INTO users (display_name, email, password_hash, status)
                    VALUES ('Mismatch probe', 'mismatch@finora.test', 'hash', 'ACTIVE')
                    """);
            sql.execute("""
                    INSERT INTO categories (name, type, user_id)
                    VALUES ('Casa', 'EXPENSE',
                            (SELECT id FROM users WHERE email = 'mismatch@finora.test'))
                    """);
            sql.execute("""
                    INSERT INTO accounts (name, type, opening_balance, user_id, currency)
                    VALUES ('Conta USD', 'CHECKING', 100,
                            (SELECT id FROM users WHERE email = 'mismatch@finora.test'), 'USD')
                    """);

            assertThatThrownBy(() -> insertTransaction(sql, "mismatch@finora.test", "BRL"))
                    .hasMessageContaining("fk_transactions_account_currency");

            // The same movement in the account's own currency is accepted.
            insertTransaction(sql, "mismatch@finora.test", "USD");

            // An accountless transaction carries its own currency and has no
            // account to agree with, so the composite FK must not fire.
            sql.execute("""
                    INSERT INTO transactions
                        (user_id, type, amount, description, occurred_on, category_id,
                         account_id, payment_method, currency)
                    VALUES ((SELECT id FROM users WHERE email = 'mismatch@finora.test'),
                            'EXPENSE', 5, 'Sem conta', '2026-07-01',
                            (SELECT id FROM categories
                             WHERE user_id = (SELECT id FROM users
                                              WHERE email = 'mismatch@finora.test')),
                            NULL, 'PIX', 'EUR')
                    """);
        }
    }

    @Test
    void databaseRefusesCardWhoseDefaultPaymentAccountUsesAnotherCurrency() throws Exception {
        migrateFreshTo("15");
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    INSERT INTO users (display_name, email, password_hash, status)
                    VALUES ('Card probe', 'card@finora.test', 'hash', 'ACTIVE')
                    """);
            sql.execute("""
                    INSERT INTO accounts (name, type, opening_balance, user_id, currency)
                    VALUES ('Conta BRL', 'CHECKING', 100,
                            (SELECT id FROM users WHERE email = 'card@finora.test'), 'BRL')
                    """);

            assertThatThrownBy(() -> insertCard(sql, "USD"))
                    .hasMessageContaining("fk_credit_cards_payment_account_currency");

            insertCard(sql, "BRL");
        }
    }

    private static void insertCard(Statement sql, String currency) throws Exception {
        sql.execute("""
                INSERT INTO credit_cards
                    (user_id, name, brand, credit_limit, closing_day, due_day,
                     default_payment_account_id, currency)
                VALUES ((SELECT id FROM users WHERE email = 'card@finora.test'),
                        'Cartão %1$s', 'VISA', 1000, 5, 12,
                        (SELECT id FROM accounts
                         WHERE user_id = (SELECT id FROM users
                                          WHERE email = 'card@finora.test')),
                        '%1$s')
                """.formatted(currency));
    }

    private static void insertTransaction(Statement sql, String email, String currency)
            throws Exception {
        sql.execute("""
                INSERT INTO transactions
                    (user_id, type, amount, description, occurred_on, category_id,
                     account_id, payment_method, currency)
                VALUES ((SELECT id FROM users WHERE email = '%1$s'),
                        'EXPENSE', 10, 'Movimento', '2026-07-01',
                        (SELECT id FROM categories
                         WHERE user_id = (SELECT id FROM users WHERE email = '%1$s')),
                        (SELECT id FROM accounts
                         WHERE user_id = (SELECT id FROM users WHERE email = '%1$s')),
                        'PIX', '%2$s')
                """.formatted(email, currency));
    }

    /**
     * Representative V14 data across every domain V15 touches. Values are
     * deliberately awkward (trailing cents, whole numbers, zero) so a stray
     * re-scale or rounding pass would show up as a difference.
     */
    private static void seedV14(Statement sql) throws Exception {
        sql.execute("""
                INSERT INTO users (display_name, email, password_hash, status)
                VALUES ('Owner A', 'a@finora.test', 'hash', 'ACTIVE'),
                       ('Owner B', 'b@finora.test', 'hash', 'ACTIVE')
                """);
        sql.execute("""
                INSERT INTO app_settings
                    (minimum_cash_buffer, max_installment_commitment_ratio,
                     monthly_opportunity_rate, budget_warning_threshold, user_id)
                VALUES (1234.56, .3, .01, .8, %s), (0, .3, 0, .8, %s)
                """.formatted(USER_A, USER_B));
        sql.execute("""
                INSERT INTO categories (name, type, user_id)
                VALUES ('Casa A', 'EXPENSE', %s), ('Casa B', 'EXPENSE', %s)
                """.formatted(USER_A, USER_B));
        sql.execute("""
                INSERT INTO accounts (name, type, opening_balance, user_id)
                VALUES ('Conta A', 'CHECKING', 8000.45, %s),
                       ('Conta B', 'CHECKING', 0, %s)
                """.formatted(USER_A, USER_B));
        sql.execute("""
                INSERT INTO transactions
                    (user_id, type, amount, description, occurred_on, category_id,
                     account_id, payment_method)
                VALUES (%1$s, 'EXPENSE', 199.99, 'Compra', '2026-07-01', %2$s, %3$s, 'PIX'),
                       (%1$s, 'INCOME', 5000, 'Salário', '2026-07-05', %2$s, NULL, 'PIX')
                """.formatted(USER_A, CATEGORY_A, ACCOUNT_A));
        sql.execute("""
                INSERT INTO credit_cards
                    (user_id, name, brand, credit_limit, closing_day, due_day,
                     default_payment_account_id)
                VALUES (%s, 'Cartão A', 'VISA', 12000.01, 5, 12, %s)
                """.formatted(USER_A, ACCOUNT_A));
        sql.execute("""
                INSERT INTO credit_card_purchases
                    (user_id, card_id, category_id, description, purchase_date,
                     total_amount, installment_count)
                VALUES (%s, %s, %s, 'Notebook', '2026-06-20', 4800.33, 12)
                """.formatted(USER_A, CARD_A, CATEGORY_A));
        sql.execute("""
                INSERT INTO credit_card_invoices
                    (user_id, card_id, reference_month, closing_date, due_date)
                VALUES (%s, %s, '2026-07-01', '2026-07-05', '2026-07-12')
                """.formatted(USER_A, CARD_A));
        sql.execute("""
                INSERT INTO credit_card_payments
                    (user_id, invoice_id, account_id, amount, paid_on)
                VALUES (%s, %s, %s, 400.03, '2026-07-12')
                """.formatted(USER_A, INVOICE_A, ACCOUNT_A));
        sql.execute("""
                INSERT INTO budgets (month_ref, category_id, limit_amount, user_id)
                VALUES ('2026-07-01', %s, 2500.75, %s)
                """.formatted(CATEGORY_A, USER_A));
        sql.execute("""
                INSERT INTO commitments
                    (description, amount, category_id, cadence, start_date, user_id)
                VALUES ('Aluguel', 3200.10, %s, 'MONTHLY', '2026-01-01', %s)
                """.formatted(CATEGORY_A, USER_A));
        sql.execute("""
                INSERT INTO commitment_occurrences
                    (user_id, commitment_id, scheduled_date, effective_date)
                VALUES (%s, %s, '2026-07-01', '2026-07-01')
                """.formatted(USER_A, COMMITMENT_A));
        sql.execute("""
                INSERT INTO goals (name, target_amount, current_amount, user_id)
                VALUES ('Viagem', 15000.99, 2500.01, %s)
                """.formatted(USER_A));
        sql.execute("""
                INSERT INTO wishlist_items
                    (user_id, name, category_id, reference_price, target_price,
                     priority, status)
                VALUES (%s, 'Notebook', %s, 5000.55, 4500.05, 'HIGH', 'MONITORING')
                """.formatted(USER_A, CATEGORY_A));
        sql.execute("""
                INSERT INTO purchase_options
                    (user_id, wishlist_item_id, merchant, payment_kind, base_price,
                     shipping, fees, installment_count, installment_amount, credit_card_id)
                VALUES (%s, %s, 'Loja sintética', 'INSTALLMENT', 4800.20, 20.10, 10.05,
                        12, 400.86, %s)
                """.formatted(USER_A, ITEM_A, CARD_A));
        sql.execute("""
                INSERT INTO wishlist_price_snapshots
                    (user_id, wishlist_item_id, series_key, client_request_id,
                     merchant, merchant_normalized, payment_kind, base_price,
                     shipping, fees, nominal_cost, observed_on)
                VALUES (%s, %s, 'MANUAL:loja:CASH', gen_random_uuid(), 'Loja',
                        'loja', 'CASH', 4790.15, 15.00, 0, 4805.15, '2026-07-01')
                """.formatted(USER_A, ITEM_A));
        sql.execute("""
                INSERT INTO notification_preferences (user_id) VALUES (%s), (%s)
                """.formatted(USER_A, USER_B));
        sql.execute("""
                INSERT INTO notifications
                    (user_id, source_key, source_event_id, type, severity, event_date,
                     title, resource_type, route, revision, first_seen_at, last_seen_at,
                     revision_changed_at, amount)
                VALUES (%s, 'INVOICE:DUE', 'evt-1', 'INVOICE_DUE_SOON', 'WARNING',
                        '2026-07-12', 'Fatura', 'CARD_INVOICE', '/cards/1', 1,
                        now(), now(), now(), 1899.77)
                """.formatted(USER_A));
        sql.execute("""
                INSERT INTO statement_import_batches
                    (user_id, account_id, original_filename, format, file_sha256,
                     file_size_bytes, parser_version, fingerprint_version, status)
                VALUES (%s, %s, 'synthetic.csv', 'CSV', repeat('a', 64), 10, 1, 1,
                        'PREVIEW_READY')
                """.formatted(USER_A, ACCOUNT_A));
        sql.execute("""
                INSERT INTO statement_import_items
                    (user_id, batch_id, account_id, source_index, status, amount)
                VALUES (%s, %s, %s, 0, 'READY', 88.88)
                """.formatted(USER_A, BATCH_A, ACCOUNT_A));
        sql.execute("""
                INSERT INTO offline_mutation_receipts
                    (user_id, client_mutation_id, resource_type, operation,
                     request_hash, result_code, response_payload)
                VALUES (%s, gen_random_uuid(), 'TRANSACTION', 'CREATE',
                        repeat('b', 64), 'APPLIED', '{"amount": 199.99}'::jsonb)
                """.formatted(USER_A));
    }

    private static void captureAmounts(Statement sql, Map<String, BigDecimal> into)
            throws Exception {
        record Probe(String label, String query) {}
        Probe[] probes = {
            new Probe("app_settings.minimum_cash_buffer",
                    "SELECT minimum_cash_buffer FROM app_settings WHERE user_id = " + USER_A),
            new Probe("accounts.opening_balance",
                    "SELECT opening_balance FROM accounts WHERE user_id = " + USER_A),
            new Probe("transactions.expense",
                    "SELECT amount FROM transactions WHERE description = 'Compra'"),
            new Probe("transactions.accountless_income",
                    "SELECT amount FROM transactions WHERE description = 'Salário'"),
            new Probe("credit_cards.credit_limit",
                    "SELECT credit_limit FROM credit_cards WHERE user_id = " + USER_A),
            new Probe("credit_card_purchases.total_amount",
                    "SELECT total_amount FROM credit_card_purchases WHERE user_id = " + USER_A),
            new Probe("credit_card_payments.amount",
                    "SELECT amount FROM credit_card_payments WHERE user_id = " + USER_A),
            new Probe("budgets.limit_amount",
                    "SELECT limit_amount FROM budgets WHERE user_id = " + USER_A),
            new Probe("commitments.amount",
                    "SELECT amount FROM commitments WHERE user_id = " + USER_A),
            new Probe("goals.target_amount",
                    "SELECT target_amount FROM goals WHERE user_id = " + USER_A),
            new Probe("goals.current_amount",
                    "SELECT current_amount FROM goals WHERE user_id = " + USER_A),
            new Probe("wishlist_items.reference_price",
                    "SELECT reference_price FROM wishlist_items WHERE user_id = " + USER_A),
            new Probe("wishlist_items.target_price",
                    "SELECT target_price FROM wishlist_items WHERE user_id = " + USER_A),
            new Probe("purchase_options.base_price",
                    "SELECT base_price FROM purchase_options WHERE wishlist_item_id = " + ITEM_A),
            new Probe("purchase_options.installment_amount",
                    "SELECT installment_amount FROM purchase_options WHERE wishlist_item_id = " + ITEM_A),
            new Probe("wishlist_price_snapshots.nominal_cost",
                    "SELECT nominal_cost FROM wishlist_price_snapshots WHERE user_id = " + USER_A),
            new Probe("notifications.amount",
                    "SELECT amount FROM notifications WHERE user_id = " + USER_A),
            new Probe("statement_import_items.amount",
                    "SELECT amount FROM statement_import_items WHERE user_id = " + USER_A),
        };
        for (Probe probe : probes) {
            try (ResultSet row = sql.executeQuery(probe.query())) {
                row.next();
                into.put(probe.label(), row.getBigDecimal(1));
            }
        }
    }

    private static void assertCount(Statement sql, String table, int expected) throws Exception {
        try (ResultSet rows = sql.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            assertThat(rows.getInt(1)).as(table).isEqualTo(expected);
        }
    }

    private static void assertAllBrl(Statement sql, String table, String column) throws Exception {
        try (ResultSet rows = sql.executeQuery(
                "SELECT count(*) FROM " + table + " WHERE " + column + " <> 'BRL'")) {
            rows.next();
            assertThat(rows.getInt(1)).as(table + "." + column + " not BRL").isZero();
        }
    }
}
