package io.apitomy.axiom.app;

import io.quarkus.flyway.FlywayConfigurationCustomizer;
import jakarta.enterprise.context.ApplicationScoped;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * Stops Axiom 3 from starting on a database created by an earlier major version. Axiom 3 starts from a new
 * schema baseline, so a 2.x database cannot be migrated; without this check Flyway would fail with a checksum
 * error, or quietly baseline a database it does not understand.
 *
 * <p>A database is incompatible when its migration history contains anything other than Axiom 3's migrations
 * (a version 2 or later from 2.x, or a Flyway baseline marker), or when it has tables but no migration
 * history. Runs before Flyway validates the history.</p>
 */
@ApplicationScoped
public class LegacyDatabaseGuard implements Callback, FlywayConfigurationCustomizer {

    /** Shown when the database was created by an earlier major version. */
    public static final String MESSAGE = "This database was created by an earlier major version of Axiom (2.x or "
            + "older), which Axiom 3 cannot use. Back up and then delete the data directory (by default "
            + "~/.axiom/data), then start Axiom again to create a new database. See the 3.0.0 release notes.";

    private static final String HISTORY_TABLE = "flyway_schema_history";

    /**
     * {@inheritDoc}
     */
    @Override
    public void customize(FluentConfiguration configuration) {
        configuration.callbacks(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_VALIDATE || event == Event.BEFORE_MIGRATE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handle(Event event, Context context) {
        Optional<String> problem;
        try {
            problem = incompatibility(context.getConnection());
        } catch (SQLException e) {
            throw new FlywayException("Could not check the database version: " + e.getMessage(), e);
        }
        if (problem.isPresent()) {
            throw new FlywayException(problem.get());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getCallbackName() {
        return "axiom-legacy-database-guard";
    }

    /**
     * Checks whether a database was created by an earlier major version of Axiom.
     *
     * @param connection a connection to the database
     * @return {@link #MESSAGE} when the database is incompatible, otherwise empty
     * @throws SQLException when the database cannot be inspected
     */
    public static Optional<String> incompatibility(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String schema = connection.getSchema();
        String historyTable = null;
        boolean otherTables = false;
        try (ResultSet tables = meta.getTables(null, schema, "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if (HISTORY_TABLE.equalsIgnoreCase(name)) {
                    historyTable = name;
                } else {
                    otherTables = true;
                }
            }
        }
        if (historyTable == null) {
            return otherTables ? Optional.of(MESSAGE) : Optional.empty();
        }
        String sql = "SELECT COUNT(*) FROM \"" + historyTable + "\" WHERE \"type\" = 'BASELINE' "
                + "OR (\"version\" IS NOT NULL AND \"version\" <> '1')";
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1) > 0 ? Optional.of(MESSAGE) : Optional.empty();
        }
    }
}
