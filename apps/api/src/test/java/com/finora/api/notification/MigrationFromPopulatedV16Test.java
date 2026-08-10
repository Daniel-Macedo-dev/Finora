package com.finora.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
 * Real PostgreSQL proof that a populated V16 notification inbox survives V17.
 *
 * <p>Three contracts are defended. First, provenance that still exists is used:
 * a commitment notification takes the currency of its commitment and a card
 * invoice notification the currency of its card, because those records are the
 * authority and are still here to ask. Second, provenance that is gone is not
 * invented: a forecast cash-risk row names no resource and its old identity
 * named no currency, so it is never relabelled — instead the figure it cannot
 * denominate is withdrawn, unless the row predates V15, where BRL is a fact
 * about a single-currency product rather than a guess. Third, nothing else
 * moves: no lifecycle state, no revision, no history, no other owner's row.
 */
class MigrationFromPopulatedV16Test {

    private static PostgreSQLContainer<?> postgres;

    private static final String OWNER = "(SELECT id FROM users WHERE email = 'owner@finora.test')";
    private static final String STRANGER =
            "(SELECT id FROM users WHERE email = 'stranger@finora.test')";
    private static final String CATEGORY =
            "(SELECT id FROM categories WHERE name = 'Casa notificações')";
    private static final String ACCOUNT_USD =
            "(SELECT id FROM accounts WHERE name = 'Conta USD')";

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
    void populatedV16GainsHonestDenominationWithoutInventingOrDisturbingHistory()
            throws Exception {
        migrateFreshTo("16");

        Map<String, Object> before = new LinkedHashMap<>();
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            seedV16(sql);
            captureInbox(sql, before);
        }

        flywayTo("17").migrate();

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            // Nothing was created or dropped.
            assertCount(sql, "notifications", 8);

            // ── deterministic provenance is used ──────────────────────────
            // The dollar commitment's notification stops claiming reais...
            assertThat(scalar(sql, currencyOf("COMMITMENT:200:2026-07-02"))).isEqualTo("USD");
            // ...and so does the yen one.
            assertThat(scalar(sql, currencyOf("COMMITMENT:300:2026-07-03"))).isEqualTo("JPY");
            // The invoice takes the currency of the card that bills it.
            assertThat(scalar(sql, currencyOf("CARD_INVOICE:500"))).isEqualTo("USD");
            // A row that was already right is left exactly as it was.
            assertThat(scalar(sql, currencyOf("COMMITMENT:100:2026-07-01"))).isEqualTo("BRL");

            // ── provenance that is gone is not invented ───────────────────
            // No forecast row is relabelled: the currency column is untouched
            // everywhere, because nothing can vouch for a different value.
            assertThat(count(sql, "SELECT count(*) FROM notifications"
                    + " WHERE resource_type = 'FORECAST' AND currency <> 'BRL'")).isZero();
            // The post-V15 row could have been foreign, so its figure — which
            // only means something under a currency nobody can confirm — goes.
            assertThat(scalar(sql, amountOf("FORECAST:INSUFFICIENT_CASH"))).isNull();
            // The pre-V15 row is evidence, not a guess: Finora had one currency
            // then, which is exactly what V15's own backfill recorded.
            assertThat(new BigDecimal(scalar(sql, amountOf("FORECAST:INSUFFICIENT_CASH:LEGACY"))))
                    .isEqualByComparingTo("-75.50");

            // ── the repair never crosses an owner ─────────────────────────
            // The stranger's row points at the owner's dollar commitment id and
            // must not be touched by it.
            assertThat(scalar(sql, currencyOf("COMMITMENT:200:2026-07-09"))).isEqualTo("BRL");

            // ── nothing else moved ────────────────────────────────────────
            Map<String, Object> after = new LinkedHashMap<>();
            captureInbox(sql, after);
            assertThat(after).isEqualTo(before);

            // Every row still satisfies the constraints V12 and V15 declared.
            assertThatCode(() -> sql.execute(
                    "ALTER TABLE notifications VALIDATE CONSTRAINT ck_notifications_currency"))
                    .doesNotThrowAnyException();
            assertThat(count(sql, "SELECT count(*) FROM notifications WHERE currency IS NULL"))
                    .isZero();
        }
    }

    @Test
    void aFreshDatabaseIsUnaffectedAndStillRefusesAnUnlabelledNotification() throws Exception {
        migrateFreshTo("17");
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    INSERT INTO users (display_name, email, password_hash, status)
                    VALUES ('Probe', 'probe@finora.test', 'hash', 'ACTIVE')
                    """);
            // V15 dropped the DEFAULT: after it, the currency must be resolved.
            assertThat(count(sql, """
                    SELECT count(*) FROM information_schema.columns
                     WHERE table_name = 'notifications' AND column_name = 'currency'
                       AND column_default IS NULL AND is_nullable = 'NO'
                    """)).isEqualTo(1);
            assertCount(sql, "notifications", 0);
        }
    }

    private static String currencyOf(String sourceKey) {
        return "SELECT currency FROM notifications WHERE source_key = '%s'".formatted(sourceKey);
    }

    private static String amountOf(String sourceKey) {
        return "SELECT amount FROM notifications WHERE source_key = '%s'".formatted(sourceKey);
    }

    /**
     * A representative V16 inbox, as the pre-fix application would have left it.
     *
     * <p>Every row says BRL, because that is what the entity's field
     * initializer wrote no matter what the money was. Four of them are wrong
     * about it and two of those can be proven wrong from records that still
     * exist. The forecast rows are the interesting ones: one created after V15
     * (denomination unknowable) and one created before it (denomination a fact).
     */
    private static void seedV16(Statement sql) throws Exception {
        sql.execute("""
                INSERT INTO users (display_name, email, password_hash, status)
                VALUES ('Owner', 'owner@finora.test', 'hash', 'ACTIVE'),
                       ('Stranger', 'stranger@finora.test', 'hash', 'ACTIVE')
                """);
        sql.execute("""
                INSERT INTO categories (name, type, user_id)
                VALUES ('Casa notificações', 'EXPENSE', %s)
                """.formatted(OWNER));
        sql.execute("""
                INSERT INTO accounts (name, type, opening_balance, user_id, currency)
                VALUES ('Conta BRL', 'CHECKING', 1000.00, %1$s, 'BRL'),
                       ('Conta USD', 'CHECKING', 2000.00, %1$s, 'USD')
                """.formatted(OWNER));

        // Commitments with explicit ids, so the notifications can reference them.
        sql.execute("""
                INSERT INTO commitments
                    (id, user_id, description, amount, category_id, cadence, due_day,
                     start_date, active, target_kind, currency)
                VALUES (100, %1$s, 'Recorrente BRL', 120.00, %2$s, 'MONTHLY', 1,
                        '2026-05-01', TRUE, 'PROJECTION_ONLY', 'BRL'),
                       (200, %1$s, 'Recorrente USD', 80.00, %2$s, 'MONTHLY', 2,
                        '2026-05-02', TRUE, 'PROJECTION_ONLY', 'USD'),
                       (300, %1$s, 'Recorrente JPY', 3000.00, %2$s, 'MONTHLY', 3,
                        '2026-05-03', TRUE, 'PROJECTION_ONLY', 'JPY')
                """.formatted(OWNER, CATEGORY));

        sql.execute("""
                INSERT INTO credit_cards
                    (id, user_id, name, brand, credit_limit, closing_day, due_day, currency,
                     default_payment_account_id)
                VALUES (400, %s, 'Cartão USD', 'VISA', 5000.00, 20, 28, 'USD', %s)
                """.formatted(OWNER, ACCOUNT_USD));
        sql.execute("""
                INSERT INTO credit_card_invoices
                    (id, user_id, card_id, reference_month, closing_date, due_date)
                VALUES (500, %s, 400, '2026-07-01', '2026-07-20', '2026-07-28')
                """.formatted(OWNER));

        // Everything the pre-fix application wrote said BRL.
        insertNotification(sql, OWNER, "COMMITMENT:100:2026-07-01",
                "RECURRING_OVERDUE", "COMMITMENT", "100", "120.00", "/commitments", "now()");
        insertNotification(sql, OWNER, "COMMITMENT:200:2026-07-02",
                "RECURRING_OVERDUE", "COMMITMENT", "200", "80.00", "/commitments", "now()");
        insertNotification(sql, OWNER, "COMMITMENT:300:2026-07-03",
                "RECURRING_OVERDUE", "COMMITMENT", "300", "3000.00", "/commitments", "now()");
        insertNotification(sql, OWNER, "CARD_INVOICE:500",
                "INVOICE_OVERDUE", "CARD_INVOICE", "500", "400.00",
                "/credit-cards/400/invoices/500", "now()");
        // A commitment notification whose resource is gone: nothing can prove
        // anything about it, so it must be left completely alone.
        insertNotification(sql, OWNER, "COMMITMENT:999:2026-07-04",
                "RECURRING_OVERDUE", "COMMITMENT", "999", "45.00", "/commitments", "now()");
        // Forecast, created after V15 ran: denomination unknowable.
        insertNotification(sql, OWNER, "FORECAST:INSUFFICIENT_CASH",
                "INSUFFICIENT_CASH_PROJECTED", "FORECAST", "NULL", "-250.00", "/forecast",
                "now()");
        // Forecast, created long before V15 ran: Finora was BRL-only, so BRL is
        // what this row always was.
        insertNotification(sql, OWNER, "FORECAST:INSUFFICIENT_CASH:LEGACY",
                "INSUFFICIENT_CASH_PROJECTED", "FORECAST", "NULL", "-75.50", "/forecast",
                "TIMESTAMPTZ '2020-01-01 00:00:00+00'");
        // Another owner's row, pointing at the owner's dollar commitment id.
        insertNotification(sql, STRANGER, "COMMITMENT:200:2026-07-09",
                "RECURRING_OVERDUE", "COMMITMENT", "200", "10.00", "/commitments", "now()");
    }

    private static int notificationCounter;

    private static void insertNotification(Statement sql, String owner, String sourceKey,
                                           String type, String resourceType, String resourceId,
                                           String amount, String route, String createdAt)
            throws Exception {
        notificationCounter++;
        sql.execute("""
                INSERT INTO notifications
                    (user_id, currency, source_key, source_event_id, type, severity, event_date,
                     title, amount, resource_type, resource_id, route, revision, read_revision,
                     first_seen_at, last_seen_at, revision_changed_at, created_at)
                VALUES (%s, 'BRL', '%s', 'evt-%d', '%s', 'CRITICAL', '2026-07-05',
                        'Título %d', %s, '%s', %s, '%s', 2, 1,
                        now(), now(), now(), %s)
                """.formatted(owner, sourceKey, notificationCounter, type, notificationCounter,
                amount, resourceType, resourceId, route, createdAt));
    }

    /**
     * Everything V17 must leave untouched: identity, lifecycle and history.
     *
     * <p>Currency is excluded because the deterministic branches correct it,
     * and the two forecast amounts are excluded because withdrawing one of them
     * is the whole point of the third branch. Every other amount is here.
     */
    private static void captureInbox(Statement sql, Map<String, Object> into) throws Exception {
        record Probe(String label, String query) {}
        Probe[] probes = {
            new Probe("count.by.owner", "SELECT count(*) FROM notifications WHERE user_id = "
                    + OWNER),
            new Probe("usd.commitment.amount", amountOf("COMMITMENT:200:2026-07-02")),
            new Probe("jpy.commitment.amount", amountOf("COMMITMENT:300:2026-07-03")),
            new Probe("brl.commitment.amount", amountOf("COMMITMENT:100:2026-07-01")),
            new Probe("invoice.amount", amountOf("CARD_INVOICE:500")),
            new Probe("orphan.amount", amountOf("COMMITMENT:999:2026-07-04")),
            new Probe("orphan.currency", currencyOf("COMMITMENT:999:2026-07-04")),
            new Probe("stranger.amount", amountOf("COMMITMENT:200:2026-07-09")),
            new Probe("usd.commitment.revision",
                    "SELECT revision FROM notifications"
                            + " WHERE source_key = 'COMMITMENT:200:2026-07-02'"),
            new Probe("usd.commitment.read_revision",
                    "SELECT read_revision FROM notifications"
                            + " WHERE source_key = 'COMMITMENT:200:2026-07-02'"),
            new Probe("usd.commitment.event_date",
                    "SELECT event_date FROM notifications"
                            + " WHERE source_key = 'COMMITMENT:200:2026-07-02'"),
            new Probe("usd.commitment.title",
                    "SELECT title FROM notifications"
                            + " WHERE source_key = 'COMMITMENT:200:2026-07-02'"),
            new Probe("usd.commitment.severity",
                    "SELECT severity FROM notifications"
                            + " WHERE source_key = 'COMMITMENT:200:2026-07-02'"),
            new Probe("usd.commitment.route",
                    "SELECT route FROM notifications"
                            + " WHERE source_key = 'COMMITMENT:200:2026-07-02'"),
            new Probe("forecast.recent.revision",
                    "SELECT revision FROM notifications"
                            + " WHERE source_key = 'FORECAST:INSUFFICIENT_CASH'"),
            new Probe("forecast.recent.title",
                    "SELECT title FROM notifications"
                            + " WHERE source_key = 'FORECAST:INSUFFICIENT_CASH'"),
            new Probe("forecast.recent.currency", currencyOf("FORECAST:INSUFFICIENT_CASH")),
            new Probe("forecast.legacy.currency", currencyOf("FORECAST:INSUFFICIENT_CASH:LEGACY")),
            new Probe("commitment.usd.source.currency",
                    "SELECT currency FROM commitments WHERE id = 200"),
            new Probe("card.usd.source.currency",
                    "SELECT currency FROM credit_cards WHERE id = 400"),
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
