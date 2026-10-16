package io.apitomy.axiom.app;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Axiom 3 refuses to start on a database created by an earlier major version, with a clear message, instead of
 * failing inside Flyway or silently baselining it.
 */
class LegacyDatabaseGuardTest {

    private static String newUrl() {
        return "jdbc:h2:mem:guard-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    private static void exec(String url, String... sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            for (String statement : sql) {
                s.execute(statement);
            }
        }
    }

    private static Optional<String> check(String url) throws SQLException {
        try (Connection c = DriverManager.getConnection(url)) {
            return LegacyDatabaseGuard.incompatibility(c);
        }
    }

    private static Flyway flyway(String url) {
        return Flyway.configure().dataSource(url, null, null)
                .locations("classpath:db/migration")
                .callbacks(new LegacyDatabaseGuard())
                .load();
    }

    @Test
    void anEmptyDatabaseIsCompatible() throws SQLException {
        assertTrue(check(newUrl()).isEmpty());
    }

    @Test
    void a3xDatabaseIsCompatible() throws SQLException {
        String url = newUrl();
        flyway(url).migrate();
        assertTrue(check(url).isEmpty());
        flyway(url).migrate();   // starting again is fine
    }

    @Test
    void a2xMigrationHistoryIsIncompatible() throws SQLException {
        String url = newUrl();
        exec(url, "CREATE TABLE \"flyway_schema_history\" (\"installed_rank\" INT, \"version\" VARCHAR(50), "
                        + "\"description\" VARCHAR(200), \"type\" VARCHAR(20), \"script\" VARCHAR(1000), "
                        + "\"checksum\" INT, \"installed_by\" VARCHAR(100), \"installed_on\" TIMESTAMP, "
                        + "\"execution_time\" INT, \"success\" BOOLEAN)",
                "INSERT INTO \"flyway_schema_history\" VALUES (1, '1', 'baseline', 'SQL', 'V1__baseline.sql', 1, "
                        + "'sa', CURRENT_TIMESTAMP, 1, TRUE)",
                "INSERT INTO \"flyway_schema_history\" VALUES (2, '22', 'add environment', 'SQL', 'V22__x.sql', 1, "
                        + "'sa', CURRENT_TIMESTAMP, 1, TRUE)");
        assertEquals(Optional.of(LegacyDatabaseGuard.MESSAGE), check(url));
        FlywayException e = assertThrows(FlywayException.class, () -> flyway(url).migrate());
        assertTrue(e.getMessage().contains(LegacyDatabaseGuard.MESSAGE), e.getMessage());
    }

    @Test
    void aBaselinedHistoryIsIncompatible() throws SQLException {
        String url = newUrl();
        exec(url, "CREATE TABLE \"flyway_schema_history\" (\"installed_rank\" INT, \"version\" VARCHAR(50), "
                        + "\"type\" VARCHAR(20), \"success\" BOOLEAN)",
                "INSERT INTO \"flyway_schema_history\" VALUES (1, '1', 'BASELINE', TRUE)");
        assertEquals(Optional.of(LegacyDatabaseGuard.MESSAGE), check(url));
    }

    @Test
    void tablesWithoutMigrationHistoryAreIncompatible() throws SQLException {
        String url = newUrl();
        exec(url, "CREATE TABLE subject (id BIGINT)");
        assertEquals(Optional.of(LegacyDatabaseGuard.MESSAGE), check(url));
    }
}
