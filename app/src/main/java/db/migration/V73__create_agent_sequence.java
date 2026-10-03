package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Creates the {@code agent_SEQ} sequence that {@code AgentEntity} (a {@code PanacheEntity}) needs, and drops the
 * orphaned {@code actor_SEQ} (issue #445).
 *
 * <p>V19 created {@code actor_SEQ}; V45 renamed the {@code actor} table to {@code agent} but never created
 * {@code agent_SEQ}, so Hibernate {@code validate} failed on a database built purely from migrations.</p>
 *
 * <p>This is a Java migration rather than SQL because the start value has to be computed from the data
 * ({@code max(agent.id)}), and neither H2 nor PostgreSQL accept a subquery in {@code CREATE SEQUENCE ... START
 * WITH}. Reading the value over JDBC and issuing DDL with a literal works identically on both databases.</p>
 *
 * <p>The start value is {@code max(id) + 50}. Hibernate uses the {@code pooled} optimizer with an increment of 50,
 * so a sequence value {@code V} hands out ids in {@code (V - 50, V]}; starting at {@code max(id) + 50} guarantees
 * that no generated id can collide with an existing row, including rows inserted earlier with ids drawn from
 * {@code actor_SEQ}. Any {@code agent_SEQ} that already exists (e.g. on an install whose schema was once generated
 * by Hibernate) is replaced, since every persisted id is at most {@code max(id)}.</p>
 */
public class V73__create_agent_sequence extends BaseJavaMigration {

    /** Must match the Panache / Hibernate default allocationSize. */
    private static final long INCREMENT = 50L;

    /**
     * Applies the migration.
     *
     * @param context the Flyway migration context
     * @throws SQLException if a statement fails
     */
    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            long maxId = 0L;
            try (ResultSet rs = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM agent")) {
                if (rs.next()) {
                    maxId = rs.getLong(1);
                }
            }
            long start = maxId + INCREMENT;
            statement.execute("DROP SEQUENCE IF EXISTS agent_SEQ");
            statement.execute("CREATE SEQUENCE agent_SEQ START WITH " + start + " INCREMENT BY " + INCREMENT);
            statement.execute("DROP SEQUENCE IF EXISTS actor_SEQ");
        }
    }
}
