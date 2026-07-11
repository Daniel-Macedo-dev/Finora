package com.finora.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the two paths of migration V4 without a Spring context:
 *
 * <ul>
 *   <li>a database that already contains v1 personal data keeps every row and
 *       hands ownership to a PENDING_LEGACY_CLAIM user;</li>
 *   <li>a fresh database ends up with no legacy user and no global seed rows
 *       (defaults become per-user at registration).</li>
 * </ul>
 */
@Testcontainers
class LegacyDataMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.6-alpine");

    private Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Flyway flyway(String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration = configuration.target(target);
        }
        return configuration.load();
    }

    @Test
    void existingV1DataIsPreservedUnderAPendingLegacyOwner() throws Exception {
        String schema = "legacy_case";
        // 1. Bring the schema to the end of v1 (V3) and enter personal data the
        //    way the single-user app would have.
        flyway(schema, "3").migrate();
        long categoryIdValue;
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            statement.execute("""
                    INSERT INTO accounts (name, type, opening_balance) VALUES ('Conta v1', 'CHECKING', 2500.00)
                    """);
            try (ResultSet rs = statement.executeQuery(
                    "SELECT id FROM categories WHERE name = 'Moradia' AND type = 'EXPENSE'")) {
                rs.next();
                categoryIdValue = rs.getLong(1);
            }
            statement.execute("""
                    INSERT INTO transactions (type, amount, description, occurred_on, category_id)
                    VALUES ('EXPENSE', 1800.00, 'Aluguel v1', '2026-06-10', %d)
                    """.formatted(categoryIdValue));
            statement.execute("""
                    INSERT INTO budgets (month_ref, category_id, limit_amount)
                    VALUES ('2026-06-01', %d, 2000.00)
                    """.formatted(categoryIdValue));
            statement.execute("""
                    INSERT INTO goals (name, target_amount, current_amount) VALUES ('Meta v1', 5000.00, 1000.00)
                    """);
        }

        // 2. Run the identity migration (V4+).
        flyway(schema, null).migrate();

        // 3. Every row survived and belongs to the pending legacy owner.
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);

            try (ResultSet rs = statement.executeQuery(
                    "SELECT id, status, password_hash FROM users")) {
                assertThat(rs.next()).isTrue();
                long legacyId = rs.getLong("id");
                assertThat(rs.getString("status")).isEqualTo("PENDING_LEGACY_CLAIM");
                // Placeholder hash can never match a real password.
                assertThat(rs.getString("password_hash")).isEqualTo("unclaimable");
                assertThat(rs.next()).as("apenas o dono legado deve existir").isFalse();

                for (String table : new String[] {
                        "accounts", "categories", "transactions", "budgets", "goals", "app_settings"}) {
                    try (Statement check = connection.createStatement();
                         ResultSet counts = check.executeQuery(
                                 ("SELECT count(*) AS total, count(*) FILTER (WHERE user_id = %d) AS owned "
                                         + "FROM %s").formatted(legacyId, table))) {
                        counts.next();
                        assertThat(counts.getInt("owned"))
                                .as("todas as linhas de %s pertencem ao dono legado", table)
                                .isEqualTo(counts.getInt("total"));
                    }
                }
                // Nothing was deleted.
                try (Statement check = connection.createStatement();
                     ResultSet count = check.executeQuery("SELECT count(*) FROM transactions")) {
                    count.next();
                    assertThat(count.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void freshDatabaseEndsWithoutLegacyUserOrGlobalSeeds() throws Exception {
        String schema = "fresh_case";
        flyway(schema, null).migrate();

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            for (String table : new String[] {"users", "categories", "app_settings"}) {
                try (ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    rs.next();
                    assertThat(rs.getInt(1))
                            .as("instalação nova não deve ter linhas em %s", table)
                            .isZero();
                }
            }
        }
    }
}
