package com.finora.api.statementimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
 * Real PostgreSQL proof that a populated V15 statement-import ledger survives
 * V16.
 *
 * <p>Two contracts are defended. First, classification is honest: a CSV batch
 * always inherited the account currency and becomes {@code ACCOUNT}, while an
 * existing OFX batch becomes {@code LEGACY_UNKNOWN} rather than
 * {@code ACCOUNT_ASSUMED} — the old parser never looked at {@code CURDEF}, so
 * claiming the file omitted it would be a fabricated statement about the source
 * document. Second, nothing financial moves: no amount, date, id or
 * statement-import link changes, and completed history stays readable.
 */
class MigrationFromPopulatedV15Test {

    private static PostgreSQLContainer<?> postgres;

    // V1 ships default categories, so generated ids are not predictable. Every
    // reference below resolves through a natural key instead of a literal id.
    private static final String OWNER = "(SELECT id FROM users WHERE email = 'owner@finora.test')";
    private static final String CATEGORY =
            "(SELECT id FROM categories WHERE name = 'Casa import')";
    private static final String ACCOUNT_BRL =
            "(SELECT id FROM accounts WHERE name = 'Conta BRL')";
    private static final String ACCOUNT_USD =
            "(SELECT id FROM accounts WHERE name = 'Conta USD')";
    private static final String CSV_BATCH =
            "(SELECT id FROM statement_import_batches WHERE original_filename = 'legado.csv')";
    private static final String OFX_PENDING =
            "(SELECT id FROM statement_import_batches WHERE original_filename = 'legado.ofx')";
    private static final String OFX_DONE =
            "(SELECT id FROM statement_import_batches"
                    + " WHERE original_filename = 'legado-concluido.ofx')";

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

    private static void migrateFreshTo(String version) {
        flywayTo(version).clean();
        flywayTo(version).migrate();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void populatedV15GainsHonestCurrencyProvenanceWithoutMovingMoney() throws Exception {
        migrateFreshTo("15");

        Map<String, Object> before = new LinkedHashMap<>();
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            seedV15(sql);
            captureLedger(sql, before);
        }

        flywayTo("16").migrate();

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            // Nothing was dropped on the way through.
            assertCount(sql, "accounts", 2);
            assertCount(sql, "transactions", 4);
            assertCount(sql, "statement_import_batches", 3);
            assertCount(sql, "statement_import_items", 4);

            // CSV always inherited the account currency: that is ACCOUNT.
            assertThat(scalar(sql, "SELECT currency_source FROM statement_import_batches"
                    + " WHERE original_filename = 'legado.csv'")).isEqualTo("ACCOUNT");
            // Existing OFX evidence was discarded by the old parser, not absent.
            assertThat(scalar(sql, "SELECT currency_source FROM statement_import_batches"
                    + " WHERE original_filename = 'legado.ofx'")).isEqualTo("LEGACY_UNKNOWN");
            assertThat(scalar(sql, "SELECT currency_source FROM statement_import_batches"
                    + " WHERE original_filename = 'legado-concluido.ofx'"))
                    .isEqualTo("LEGACY_UNKNOWN");
            // No legacy row may claim a file-declared currency.
            assertThat(count(sql, "SELECT count(*) FROM statement_import_batches"
                    + " WHERE declared_currency IS NOT NULL")).isZero();

            // Completed history remains readable, with its outcome intact.
            assertThat(scalar(sql, "SELECT status FROM statement_import_batches"
                    + " WHERE original_filename = 'legado-concluido.ofx'"))
                    .isEqualTo("COMPLETED");
            assertThat(count(sql, "SELECT count(*) FROM statement_import_items i"
                    + " WHERE i.batch_id = " + OFX_DONE + " AND i.status = 'IMPORTED'"))
                    .isEqualTo(1);

            // The mislabelled statement-import transaction now names the
            // currency of the account it always belonged to...
            assertThat(scalar(sql, "SELECT currency FROM transactions"
                    + " WHERE description = 'Importado USD'")).isEqualTo("USD");
            // ...and every unrelated transaction is exactly as it was.
            assertThat(scalar(sql, "SELECT currency FROM transactions"
                    + " WHERE description = 'Compra comum BRL'")).isEqualTo("BRL");
            assertThat(scalar(sql, "SELECT currency FROM transactions"
                    + " WHERE description = 'Compra comum USD'")).isEqualTo("USD");
            assertThat(scalar(sql, "SELECT currency FROM transactions"
                    + " WHERE description = 'Importado BRL'")).isEqualTo("BRL");

            // Not one amount, date, id or statement-import link moved.
            Map<String, Object> after = new LinkedHashMap<>();
            captureLedger(sql, after);
            assertThat(after).isEqualTo(before);

            // The repaired row satisfies V15's composite currency FK, so the
            // constraint the fixture removed can be reinstated and validated.
            assertThatCode(() -> sql.execute("""
                    ALTER TABLE transactions
                        ADD CONSTRAINT fk_transactions_account_currency
                        FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
                    """)).doesNotThrowAnyException();
        }
    }

    @Test
    void freshDatabaseMigratesThroughV16AndEnforcesTheNewInvariants() throws Exception {
        migrateFreshTo("16");
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    INSERT INTO users (display_name, email, password_hash, status)
                    VALUES ('Probe', 'probe@finora.test', 'hash', 'ACTIVE')
                    """);
            sql.execute("""
                    INSERT INTO accounts (name, type, opening_balance, user_id, currency)
                    VALUES ('Conta', 'CHECKING', 0,
                            (SELECT id FROM users WHERE email = 'probe@finora.test'), 'USD')
                    """);

            // No DEFAULT survived the backfill: the source must be explicit.
            assertThatThrownBy(() -> insertProbeBatch(sql, null, null))
                    .hasMessageContaining("currency_source");

            // Outside the enum.
            assertThatThrownBy(() -> insertProbeBatch(sql, "'GUESS'", null))
                    .hasMessageContaining("ck_import_batches_currency_source");

            // FILE without the code it supposedly read is meaningless.
            assertThatThrownBy(() -> insertProbeBatch(sql, "'FILE'", null))
                    .hasMessageContaining("ck_import_batches_declared_currency_source");

            // A declared code under a source that cannot have read one is a
            // claim the source does not support.
            assertThatThrownBy(() -> insertProbeBatch(sql, "'ACCOUNT'", "'USD'"))
                    .hasMessageContaining("ck_import_batches_declared_currency_source");
            assertThatThrownBy(() -> insertProbeBatch(sql, "'LEGACY_UNKNOWN'", "'USD'"))
                    .hasMessageContaining("ck_import_batches_declared_currency_source");

            // Only the closed catalogue may be persisted.
            assertThatThrownBy(() -> insertProbeBatch(sql, "'FILE'", "'BTC'"))
                    .hasMessageContaining("ck_import_batches_declared_currency");

            // The legitimate shapes are accepted.
            insertProbeBatch(sql, "'ACCOUNT'", null);
            insertProbeBatch(sql, "'ACCOUNT_ASSUMED'", null);
            insertProbeBatch(sql, "'FILE'", "'USD'");
            assertCount(sql, "statement_import_batches", 3);
        }
    }

    private static int probeCounter;

    private static void insertProbeBatch(Statement sql, String source, String declared)
            throws Exception {
        probeCounter++;
        sql.execute("""
                INSERT INTO statement_import_batches
                    (user_id, account_id, original_filename, format, file_sha256,
                     file_size_bytes, parser_version, fingerprint_version, status,
                     currency_source, declared_currency)
                VALUES ((SELECT id FROM users WHERE email = 'probe@finora.test'),
                        (SELECT id FROM accounts WHERE name = 'Conta'),
                        'probe-%d.ofx', 'OFX', repeat('c', 64), 10, 2, 1,
                        'PREVIEW_READY', %s, %s)
                """.formatted(probeCounter, source == null ? "NULL" : source,
                declared == null ? "NULL" : declared));
    }

    /**
     * Representative V15 statement-import state: a CSV batch, a pending OFX
     * batch, a completed OFX batch, ordinary transactions in two currencies and
     * two import-generated transactions.
     *
     * <p>One of those import transactions is the synthetic defect V16 repairs:
     * a BRL-labelled row under a USD account, exactly what the old
     * materializer's missing {@code setCurrency} would have produced. V15's
     * composite foreign key {@code fk_transactions_account_currency} makes that
     * row unpersistable, which is why the fixture drops the constraint to create
     * it — the repair exists for a lineage where that guard is absent, and the
     * test reinstates the constraint afterwards to prove the repaired state is
     * consistent with it.
     */
    private static void seedV15(Statement sql) throws Exception {
        sql.execute("""
                INSERT INTO users (display_name, email, password_hash, status)
                VALUES ('Owner', 'owner@finora.test', 'hash', 'ACTIVE')
                """);
        sql.execute("""
                INSERT INTO categories (name, type, user_id)
                VALUES ('Casa import', 'EXPENSE', %s)
                """.formatted(OWNER));
        sql.execute("""
                INSERT INTO accounts (name, type, opening_balance, user_id, currency)
                VALUES ('Conta BRL', 'CHECKING', 1500.45, %1$s, 'BRL'),
                       ('Conta USD', 'CHECKING', 2000.99, %1$s, 'USD')
                """.formatted(OWNER));

        sql.execute("""
                INSERT INTO statement_import_batches
                    (user_id, account_id, original_filename, format, file_sha256,
                     file_size_bytes, parser_version, fingerprint_version, status)
                VALUES (%1$s, %2$s, 'legado.csv', 'CSV', repeat('a', 64), 120, 1, 1,
                        'PREVIEW_READY'),
                       (%1$s, %3$s, 'legado.ofx', 'OFX', repeat('b', 64), 240, 1, 1,
                        'PARTIALLY_COMPLETED'),
                       (%1$s, %3$s, 'legado-concluido.ofx', 'OFX', repeat('d', 64), 360, 1, 1,
                        'COMPLETED')
                """.formatted(OWNER, ACCOUNT_BRL, ACCOUNT_USD));

        // Preview items (CSV batch) and imported items (both OFX batches).
        sql.execute("""
                INSERT INTO statement_import_items
                    (user_id, batch_id, account_id, source_index, status, amount, posted_date,
                     type, description)
                VALUES (%1$s, %2$s, %3$s, 1, 'READY', 77.77, '2026-07-01', 'EXPENSE', 'Preview')
                """.formatted(OWNER, CSV_BATCH, ACCOUNT_BRL));
        sql.execute("""
                INSERT INTO statement_import_items
                    (user_id, batch_id, account_id, source_index, status, amount, posted_date,
                     type, description, imported_at)
                VALUES (%1$s, %2$s, %3$s, 1, 'IMPORTED', 12.34, '2026-07-02', 'EXPENSE',
                        'Importado BRL', now()),
                       (%1$s, %2$s, %3$s, 2, 'IMPORTED', 56.78, '2026-07-03', 'EXPENSE',
                        'Importado USD', now())
                """.formatted(OWNER, OFX_PENDING, ACCOUNT_USD));
        sql.execute("""
                INSERT INTO statement_import_items
                    (user_id, batch_id, account_id, source_index, status, amount, posted_date,
                     type, description, imported_at)
                VALUES (%1$s, %2$s, %3$s, 1, 'IMPORTED', 90.10, '2026-07-04', 'EXPENSE',
                        'Histórico concluído', now())
                """.formatted(OWNER, OFX_DONE, ACCOUNT_USD));

        // Ordinary transactions, correctly denominated.
        sql.execute("""
                INSERT INTO transactions
                    (user_id, type, amount, description, occurred_on, category_id,
                     account_id, payment_method, currency)
                VALUES (%1$s, 'EXPENSE', 199.99, 'Compra comum BRL', '2026-07-01', %2$s,
                        %3$s, 'PIX', 'BRL'),
                       (%1$s, 'EXPENSE', 42.50, 'Compra comum USD', '2026-07-01', %2$s,
                        %4$s, 'PIX', 'USD')
                """.formatted(OWNER, CATEGORY, ACCOUNT_BRL, ACCOUNT_USD));

        // A correct statement-import transaction: BRL row under a BRL account.
        sql.execute("""
                INSERT INTO transactions
                    (user_id, type, amount, description, occurred_on, category_id,
                     account_id, payment_method, currency, statement_import_item_id)
                VALUES (%1$s, 'EXPENSE', 12.34, 'Importado BRL', '2026-07-02', %2$s, %3$s,
                        'OTHER', 'BRL',
                        (SELECT id FROM statement_import_items
                          WHERE description = 'Importado BRL'))
                """.formatted(OWNER, CATEGORY, ACCOUNT_BRL));

        // The synthetic defect: a BRL label on a USD account's import.
        sql.execute("ALTER TABLE transactions DROP CONSTRAINT fk_transactions_account_currency");
        sql.execute("""
                INSERT INTO transactions
                    (user_id, type, amount, description, occurred_on, category_id,
                     account_id, payment_method, currency, statement_import_item_id)
                VALUES (%1$s, 'EXPENSE', 56.78, 'Importado USD', '2026-07-03', %2$s, %3$s,
                        'OTHER', 'BRL',
                        (SELECT id FROM statement_import_items
                          WHERE description = 'Importado USD'))
                """.formatted(OWNER, CATEGORY, ACCOUNT_USD));
    }

    /**
     * Everything the migration must leave untouched: ids, amounts, dates and
     * the statement-import links. Currency is deliberately excluded — it is the
     * one value V16 is allowed to correct.
     */
    private static void captureLedger(Statement sql, Map<String, Object> into) throws Exception {
        record Probe(String label, String query) {}
        Probe[] probes = {
            new Probe("ordinary.brl.amount",
                    "SELECT amount FROM transactions WHERE description = 'Compra comum BRL'"),
            new Probe("ordinary.usd.amount",
                    "SELECT amount FROM transactions WHERE description = 'Compra comum USD'"),
            new Probe("import.brl.amount",
                    "SELECT amount FROM transactions WHERE description = 'Importado BRL'"),
            new Probe("import.usd.amount",
                    "SELECT amount FROM transactions WHERE description = 'Importado USD'"),
            new Probe("import.usd.id",
                    "SELECT id FROM transactions WHERE description = 'Importado USD'"),
            new Probe("import.usd.item_id",
                    "SELECT statement_import_item_id FROM transactions"
                            + " WHERE description = 'Importado USD'"),
            new Probe("import.usd.account_id",
                    "SELECT account_id FROM transactions WHERE description = 'Importado USD'"),
            new Probe("import.usd.occurred_on",
                    "SELECT occurred_on FROM transactions WHERE description = 'Importado USD'"),
            new Probe("import.usd.type",
                    "SELECT type FROM transactions WHERE description = 'Importado USD'"),
            new Probe("import.usd.category_id",
                    "SELECT category_id FROM transactions WHERE description = 'Importado USD'"),
            new Probe("import.usd.notes",
                    "SELECT coalesce(notes, '') FROM transactions"
                            + " WHERE description = 'Importado USD'"),
            new Probe("item.preview.amount",
                    "SELECT amount FROM statement_import_items WHERE description = 'Preview'"),
            new Probe("item.usd.id",
                    "SELECT id FROM statement_import_items"
                            + " WHERE description = 'Importado USD'"),
            new Probe("batch.csv.id", "SELECT id FROM statement_import_batches"
                    + " WHERE original_filename = 'legado.csv'"),
            new Probe("batch.ofx.id", "SELECT id FROM statement_import_batches"
                    + " WHERE original_filename = 'legado.ofx'"),
            new Probe("batch.ofx.parser_version", "SELECT parser_version"
                    + " FROM statement_import_batches WHERE original_filename = 'legado.ofx'"),
            new Probe("batch.ofx.fingerprint_version", "SELECT fingerprint_version"
                    + " FROM statement_import_batches WHERE original_filename = 'legado.ofx'"),
            new Probe("batch.ofx.file_sha256", "SELECT file_sha256"
                    + " FROM statement_import_batches WHERE original_filename = 'legado.ofx'"),
            new Probe("account.usd.currency",
                    "SELECT currency FROM accounts WHERE name = 'Conta USD'"),
            new Probe("account.brl.opening_balance",
                    "SELECT opening_balance FROM accounts WHERE name = 'Conta BRL'"),
        };
        for (Probe probe : probes) {
            try (ResultSet row = sql.executeQuery(probe.query())) {
                row.next();
                Object value = row.getObject(1);
                into.put(probe.label(),
                        value instanceof BigDecimal decimal ? decimal.stripTrailingZeros()
                                : String.valueOf(value));
            }
        }
    }

    private static String scalar(Statement sql, String query) throws Exception {
        try (ResultSet row = sql.executeQuery(query)) {
            row.next();
            return row.getString(1);
        }
    }

    private static int count(Statement sql, String query) throws Exception {
        try (ResultSet row = sql.executeQuery(query)) {
            row.next();
            return row.getInt(1);
        }
    }

    private static void assertCount(Statement sql, String table, int expected) throws Exception {
        assertThat(count(sql, "SELECT count(*) FROM " + table)).as(table).isEqualTo(expected);
    }
}
