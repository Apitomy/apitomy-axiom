package io.apitomy.axiom.app;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Axiom 3 refuses to start on a database created by an earlier major version, with a clear message, instead of
 * failing inside Flyway or silently baselining it.
 */
class LegacyDatabaseGuardTest {

    private static final String HISTORY_DDL = "CREATE TABLE \"flyway_schema_history\" (\"installed_rank\" INT, "
            + "\"version\" VARCHAR(50), \"description\" VARCHAR(200), \"type\" VARCHAR(20), "
            + "\"script\" VARCHAR(1000), \"checksum\" INT, \"installed_by\" VARCHAR(100), "
            + "\"installed_on\" TIMESTAMP, \"execution_time\" INT, \"success\" BOOLEAN)";

    private static String historyRow(int rank, String version, String description, String script) {
        return "INSERT INTO \"flyway_schema_history\" VALUES (" + rank + ", '" + version + "', '" + description
                + "', 'SQL', '" + script + "', 1, 'sa', CURRENT_TIMESTAMP, 1, TRUE)";
    }

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

    private static void assertMessage(Optional<String> problem) {
        assertTrue(problem.isPresent(), "expected the database to be incompatible");
        assertTrue(problem.get().startsWith(LegacyDatabaseGuard.MESSAGE), problem.get());
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

    /**
     * Runs every current 3.x migration and checks that none of them collides with the 2.x detection markers;
     * a collision would make every 3.x database refuse to start.
     */
    @Test
    void current3xMigrationsDoNotCollideWithLegacyMarkers() throws SQLException {
        String url = newUrl();
        Flyway flyway = flyway(url);
        flyway.migrate();
        try (Connection c = DriverManager.getConnection(url)) {
            Set<String> tables = new HashSet<>();
            try (ResultSet rs = c.getMetaData().getTables(null, c.getSchema(), "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }
            Set<String> tableCollisions = new HashSet<>(tables);
            tableCollisions.retainAll(LegacyDatabaseGuard.LEGACY_TABLES);
            assertTrue(tableCollisions.isEmpty(), "3.x migrations create 2.x marker tables: " + tableCollisions);
        }
        List<String> scripts = Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getScript)
                .collect(Collectors.toList());
        assertFalse(scripts.isEmpty(), "no 3.x migrations were applied");
        List<String> scriptCollisions = scripts.stream()
                .filter(LegacyDatabaseGuard.LEGACY_SCRIPTS::contains)
                .collect(Collectors.toList());
        assertTrue(scriptCollisions.isEmpty(), "3.x migrations reuse 2.x script names: " + scriptCollisions);
        assertTrue(check(url).isEmpty());
    }

    @Test
    void a2xMigrationHistoryIsIncompatible() throws SQLException {
        String url = newUrl();
        exec(url, HISTORY_DDL, historyRow(1, "1", "baseline", "V1__baseline.sql"),
                historyRow(2, "22", "create tracing tables", "V22__create_tracing_tables.sql"));
        assertMessage(check(url));
        FlywayException e = assertThrows(FlywayException.class, () -> flyway(url).migrate());
        assertTrue(e.getMessage().contains(LegacyDatabaseGuard.MESSAGE), e.getMessage());
    }

    @Test
    void aBaselinedHistoryIsIncompatible() throws SQLException {
        String url = newUrl();
        exec(url, HISTORY_DDL, "INSERT INTO \"flyway_schema_history\" VALUES (1, '1', '<< Flyway Baseline >>', "
                + "'BASELINE', '<< Flyway Baseline >>', NULL, 'sa', CURRENT_TIMESTAMP, 0, TRUE)");
        assertMessage(check(url));
    }

    @Test
    void tablesWithoutMigrationHistoryAreIncompatible() throws SQLException {
        String url = newUrl();
        exec(url, "CREATE TABLE subject (id BIGINT)");
        assertMessage(check(url));
    }

    /**
     * The message names the data directory under the configured Axiom home, not a hardcoded path.
     */
    @Test
    void theMessageNamesTheConfiguredDataDirectory(@TempDir Path tempDir) {
        String previous = System.getProperty("axiom.home");
        System.setProperty("axiom.home", tempDir.toString());
        try {
            String message = LegacyDatabaseGuard.fullMessage();
            assertTrue(message.startsWith(LegacyDatabaseGuard.MESSAGE), message);
            assertTrue(message.contains(tempDir.resolve("data").toString()), message);
        } finally {
            if (previous == null) {
                System.clearProperty("axiom.home");
            } else {
                System.setProperty("axiom.home", previous);
            }
        }
    }

    @Test
    void a3xDatabaseWithLaterMigrationsIsCompatible() throws SQLException {
        String url = newUrl();
        flyway(url).migrate();
        // Real 3.x migrations (V1, V2, ...) are already applied; append hypothetical later ones.
        exec(url, historyRow(90, "90", "add widget", "V90__add_widget.sql"),
                historyRow(91, "91", "add gadget", "V91__add_gadget.sql"));
        assertTrue(check(url).isEmpty());
    }

    @Test
    void a2xDatabaseAtItsBaselineIsIncompatible() throws SQLException {
        String url = newUrl();
        exec(url, HISTORY_DDL, historyRow(1, "1", "baseline", "V1__baseline.sql"),
                "CREATE TABLE project (id BIGINT)", "CREATE TABLE actor (id BIGINT)");
        assertMessage(check(url));
    }

    @Test
    void a2xMigrationScriptIsIncompatibleEvenWithoutLegacyTables() throws SQLException {
        String url = newUrl();
        exec(url, HISTORY_DDL, historyRow(1, "1", "baseline", "V1__baseline.sql"),
                historyRow(2, "2", "add manager triggerable", "V2__add_manager_triggerable.sql"));
        assertMessage(check(url));
    }
}
