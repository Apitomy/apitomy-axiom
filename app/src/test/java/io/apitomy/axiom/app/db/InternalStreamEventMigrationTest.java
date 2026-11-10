package io.apitomy.axiom.app.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies, against a Flyway-migrated schema, that internally generated stream events (source ids such as
 * {@code system} and {@code sdk}) can be stored without a matching {@code event_source} row.
 */
class InternalStreamEventMigrationTest {

    private static String url;

    /**
     * Migrates a fresh private in-memory H2 database with all Flyway migrations.
     */
    @BeforeAll
    static void migrate() {
        url = "jdbc:h2:mem:internal-events-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /**
     * Inserts a stream event whose event source id has no {@code event_source} row.
     *
     * @param eventSourceId the internal event source id
     * @throws SQLException if the insert fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"system", "sdk"})
    void internalStreamEventNeedsNoEventSourceRow(String eventSourceId) throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                PreparedStatement insert = connection.prepareStatement(
                        "insert into stream_event (id, created_on, timestamp, source, event_source_id, type, ref,"
                                + " actor, payload, source_event_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setTimestamp(2, now);
            insert.setTimestamp(3, now);
            insert.setString(4, eventSourceId);
            insert.setString(5, eventSourceId);
            insert.setString(6, "test.event");
            insert.setString(7, "ref");
            insert.setString(8, "{}");
            insert.setString(9, "{}");
            insert.setString(10, UUID.randomUUID().toString());
            assertEquals(1, insert.executeUpdate());
        }
    }
}
