package io.apitomy.axiom.app.db;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.apitomy.axiom.core.entities.AgentEntity;
import jakarta.persistence.Entity;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression guard against drift between the Flyway migrations and the JPA entity model.
 *
 * <p>Tests run with Hibernate {@code drop-and-create}, so a migration that forgets a table, column or
 * {@code <table>_SEQ} sequence is invisible to the rest of the suite, but breaks the {@code prod} profile
 * (Flyway + {@code validate}). This test applies every migration once to a fresh, private in-memory H2
 * database using Flyway's Java API, then validates it against Hibernate metadata built from every
 * {@code @Entity} in the core module. It is a plain JUnit test (no Quarkus boot), so it is fast.</p>
 *
 * <p>The guard covers H2 only; it does not run the migrations against PostgreSQL.</p>
 */
class MigrationSchemaValidationTest {

    private static final DotName ENTITY = DotName.createSimple(Entity.class.getName());

    private static String url;
    private static List<Class<?>> entities;

    /**
     * Migrates the shared fresh database and scans the entity classes once for all tests.
     *
     * @throws Exception if the entity index can't be read or an entity class can't be loaded
     */
    @BeforeAll
    static void migrateAndScan() throws Exception {
        url = "jdbc:h2:mem:migration-guard-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        flyway(url, null).migrate();
        entities = findEntityClasses();
        assertFalse(entities.isEmpty(), "No @Entity classes found in the core Jandex index");
    }

    /**
     * Validates the migrated database against the entity metamodel.
     */
    @Test
    void migrationsMatchEntityModel() {
        StandardServiceRegistry registry = buildRegistry(url, "validate");
        try {
            Metadata metadata = buildMetadata(registry);
            // Building the session factory with hbm2ddl.auto=validate runs Hibernate's schema validator
            // (tables, columns, column types and sequences) and throws on the first mismatch.
            assertDoesNotThrow(() -> {
                try (SessionFactory sessionFactory = metadata.buildSessionFactory()) {
                    sessionFactory.getMetamodel();
                }
            }, "Schema produced by Flyway migrations does not match the entity model");
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /**
     * Checks every sequence the entity model expects (one {@code <table>_SEQ} per {@code PanacheEntity}) and
     * reports all missing ones at once, which gives a clearer message than the validator's fail-fast error.
     *
     * @throws SQLException if the database can't be read
     */
    @Test
    void everyEntitySequenceExists() throws SQLException {
        StandardServiceRegistry registry = buildRegistry(url, "none");
        try {
            Metadata metadata = buildMetadata(registry);
            Set<String> expected = new TreeSet<>();
            for (Namespace namespace : metadata.getDatabase().getNamespaces()) {
                for (Sequence sequence : namespace.getSequences()) {
                    expected.add(sequence.getName().getSequenceName().getText().toUpperCase(Locale.ROOT));
                }
            }
            assertFalse(expected.isEmpty(), "Entity model declares no sequences");

            Set<String> actual = new TreeSet<>();
            try (Connection connection = DriverManager.getConnection(url, "sa", "");
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery(
                            "SELECT SEQUENCE_NAME FROM INFORMATION_SCHEMA.SEQUENCES")) {
                while (rs.next()) {
                    actual.add(rs.getString(1).toUpperCase(Locale.ROOT));
                }
            }
            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(actual);
            assertTrue(missing.isEmpty(), "Sequences missing after migrations: " + missing);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /**
     * Upgrade path: an install that already has an {@code agent_SEQ} ahead of {@code max(agent.id)} (ids in
     * flight on another node) must not have it lowered by V73.
     *
     * @param dir temporary directory for the file database
     * @throws SQLException if the database can't be accessed
     */
    @Test
    void v73NeverLowersExistingAgentSequence(@TempDir Path dir) throws SQLException {
        String fileUrl = "jdbc:h2:file:" + dir.resolve("upgrade") + ";AUTO_SERVER=TRUE";
        flyway(fileUrl, "72").migrate();
        try (Connection connection = DriverManager.getConnection(fileUrl, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO agent(id, name, agent_type) VALUES (150, 'old', 'claude-code')");
            statement.execute("CREATE SEQUENCE agent_SEQ START WITH 10000 INCREMENT BY 50");
        }

        flyway(fileUrl, null).migrate();

        try (Connection connection = DriverManager.getConnection(fileUrl, "sa", "");
                Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("SELECT NEXT VALUE FOR agent_SEQ")) {
                assertTrue(rs.next());
                assertEquals(10050L, rs.getLong(1), "Existing agent_SEQ was lowered");
            }
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SEQUENCES"
                    + " WHERE SEQUENCE_NAME = 'ACTOR_SEQ'")) {
                assertTrue(rs.next());
                assertEquals(0L, rs.getLong(1), "actor_SEQ should be dropped");
            }
        }
    }

    /**
     * Creates a Flyway instance for all migrations in {@code db/migration}.
     *
     * @param jdbcUrl the JDBC URL
     * @param target the target version, or {@code null} for latest
     * @return the Flyway instance
     */
    private static Flyway flyway(String jdbcUrl, String target) {
        FluentConfiguration config = Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    /**
     * Builds Hibernate metadata for all scanned entities.
     *
     * @param registry the service registry
     * @return the metadata
     */
    private static Metadata buildMetadata(StandardServiceRegistry registry) {
        MetadataSources sources = new MetadataSources(registry);
        entities.forEach(sources::addAnnotatedClass);
        return sources.buildMetadata();
    }

    /**
     * Builds a Hibernate service registry pointing at the given database.
     *
     * @param jdbcUrl the JDBC URL
     * @param hbm2ddl the {@code hibernate.hbm2ddl.auto} action
     * @return the registry
     */
    private static StandardServiceRegistry buildRegistry(String jdbcUrl, String hbm2ddl) {
        return new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, jdbcUrl)
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                .applySetting(AvailableSettings.HBM2DDL_AUTO, hbm2ddl)
                .build();
    }

    /**
     * Reads the Jandex index of the module that contains the entities and returns all {@code @Entity}
     * classes in it.
     *
     * @return the entity classes
     * @throws IOException if the index can't be read
     * @throws ClassNotFoundException if an indexed entity class can't be loaded
     */
    private static List<Class<?>> findEntityClasses() throws IOException, ClassNotFoundException {
        ClassLoader loader = AgentEntity.class.getClassLoader();
        String entityPackage = AgentEntity.class.getPackageName();
        List<Class<?>> result = new ArrayList<>();
        Enumeration<URL> indexes = loader.getResources("META-INF/jandex.idx");
        while (indexes.hasMoreElements()) {
            Index index;
            try (InputStream in = indexes.nextElement().openStream()) {
                index = new IndexReader(in).read();
            }
            for (AnnotationInstance annotation : index.getAnnotations(ENTITY)) {
                String className = annotation.target().asClass().name().toString();
                if (className.startsWith(entityPackage + ".")) {
                    result.add(Class.forName(className, false, loader));
                }
            }
        }
        return result;
    }
}
