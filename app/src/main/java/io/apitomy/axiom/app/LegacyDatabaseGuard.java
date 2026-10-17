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
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stops Axiom 3 from starting on a database created by an earlier major version. Axiom 3 starts from a new
 * schema baseline, so a 2.x database cannot be migrated; without this check Flyway would fail with a checksum
 * error, or quietly baseline a database it does not understand.
 *
 * <p>Both 2.x and 3.x start their history with {@code V1__baseline.sql}, and 3.x adds its own {@code V2+}
 * migrations, so version numbers alone cannot tell them apart. A database is incompatible when:</p>
 * <ul>
 *     <li>it has tables but no migration history;</li>
 *     <li>its history contains a Flyway baseline marker;</li>
 *     <li>its history contains one of the 2.x migration scripts ({@link #LEGACY_SCRIPTS}); or</li>
 *     <li>it contains a table that only 2.x created ({@link #LEGACY_TABLES}).</li>
 * </ul>
 * <p>Runs before Flyway validates the history.</p>
 */
@ApplicationScoped
public class LegacyDatabaseGuard implements Callback, FlywayConfigurationCustomizer {

    /** Shown when the database was created by an earlier major version. */
    public static final String MESSAGE = "This database was created by an earlier major version of Axiom (2.x or "
            + "older), which Axiom 3 cannot use. Back up and then delete the data directory (by default "
            + "~/.axiom/data), then start Axiom again to create a new database. See the 3.0.0 release notes.";

    private static final String HISTORY_TABLE = "flyway_schema_history";

    /** Migration scripts shipped by Axiom 2.x after its baseline (V2 to V22, as of v2.1.2). */
    static final Set<String> LEGACY_SCRIPTS = Set.of(
            "V2__add_manager_triggerable.sql",
            "V3__create_secret_table.sql",
            "V4__add_engine_and_fix_sequences.sql",
            "V5__replace_repositories_with_event_sources.sql",
            "V6__add_event_source_sequence.sql",
            "V7__add_secret_name_to_event_source.sql",
            "V8__drop_webhook_secret_from_event_source.sql",
            "V9__add_environment_to_action_type_and_report.sql",
            "V10__add_event_source_id_to_event.sql",
            "V11__add_schedule_day_of_week.sql",
            "V12__add_timeout_to_report_definition.sql",
            "V13__add_duration_to_report.sql",
            "V14__drop_stale_report_definition_repository.sql",
            "V15__create_event_source_log.sql",
            "V16__add_detail_to_event_source_log.sql",
            "V17__add_labels.sql",
            "V18__add_report_labels.sql",
            "V19__create_missing_sequences.sql",
            "V20__rename_sdk_tool_prefix.sql",
            "V21__add_title_template_to_report_definition.sql",
            "V22__create_tracing_tables.sql");

    /** Tables created by the Axiom 2.x baseline that Axiom 3 does not have (lower case). */
    static final Set<String> LEGACY_TABLES = Set.of(
            "project", "actor", "report", "report_definition", "manager_config", "event_queue");

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
        Set<String> legacyTables = new HashSet<>();
        try (ResultSet tables = meta.getTables(null, schema, "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if (HISTORY_TABLE.equalsIgnoreCase(name)) {
                    historyTable = name;
                } else {
                    otherTables = true;
                    if (LEGACY_TABLES.contains(name.toLowerCase(Locale.ROOT))) {
                        legacyTables.add(name);
                    }
                }
            }
        }
        if (historyTable == null) {
            return otherTables ? Optional.of(MESSAGE) : Optional.empty();
        }
        if (!legacyTables.isEmpty()) {
            return Optional.of(MESSAGE);
        }
        String scripts = LEGACY_SCRIPTS.stream().map(script -> "'" + script + "'")
                .collect(Collectors.joining(", "));
        String sql = "SELECT COUNT(*) FROM \"" + historyTable + "\" WHERE \"type\" = 'BASELINE' "
                + "OR \"script\" IN (" + scripts + ")";
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1) > 0 ? Optional.of(MESSAGE) : Optional.empty();
        }
    }
}
