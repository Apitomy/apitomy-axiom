package io.apitomy.axiom.app.db;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.apitomy.axiom.core.entities.AgentEntity;
import jakarta.persistence.Entity;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.SessionFactory;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;
import org.junit.jupiter.api.Test;

/**
 * Regression guard against drift between the Flyway migrations and the JPA entity model.
 *
 * <p>Tests run with Hibernate {@code drop-and-create}, so a migration that forgets a table, column or
 * {@code <table>_SEQ} sequence is invisible to the rest of the suite, but breaks the {@code prod} profile
 * (Flyway + {@code validate}). This test applies every migration to a fresh, private in-memory H2 database
 * using Flyway's Java API, then runs Hibernate's schema validation ({@code hbm2ddl.auto=validate}) against it using metadata built
 * from every {@code @Entity} in the core module. It is a plain JUnit test (no Quarkus boot), so it is fast.</p>
 */
class MigrationSchemaValidationTest {

    private static final DotName ENTITY = DotName.createSimple(Entity.class.getName());

    /**
     * Migrates a fresh database and validates it against the entity metamodel.
     *
     * @throws Exception if the entity index can't be read or an entity class can't be loaded
     */
    @Test
    void migrationsMatchEntityModel() throws Exception {
        String url = migrateFreshDatabase();

        List<Class<?>> entities = findEntityClasses();
        assertFalse(entities.isEmpty(), "No @Entity classes found in the core Jandex index");

        StandardServiceRegistry registry = buildRegistry(url, "validate");
        try {
            MetadataSources sources = new MetadataSources(registry);
            entities.forEach(sources::addAnnotatedClass);
            Metadata metadata = sources.buildMetadata();
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
     * @throws Exception if the database or entity index can't be read
     */
    @Test
    void everyEntitySequenceExists() throws Exception {
        String url = migrateFreshDatabase();
        StandardServiceRegistry registry = buildRegistry(url, "none");
        try {
            MetadataSources sources = new MetadataSources(registry);
            findEntityClasses().forEach(sources::addAnnotatedClass);
            Metadata metadata = sources.buildMetadata();
            Set<String> expected = new TreeSet<>();
            for (Namespace namespace : metadata.getDatabase().getNamespaces()) {
                for (Sequence sequence : namespace.getSequences()) {
                    expected.add(sequence.getName().getSequenceName().getText().toUpperCase(Locale.ROOT));
                }
            }
            assertFalse(expected.isEmpty(), "Entity model declares no sequences");

            Set<String> actual = new TreeSet<>();
            try (Connection connection = DriverManager.getConnection(url, "sa", "");
                    ResultSet rs = connection.createStatement().executeQuery(
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
     * Creates a private in-memory H2 database and applies all Flyway migrations to it.
     *
     * @return the JDBC URL of the migrated database
     */
    private static String migrateFreshDatabase() {
        String url = "jdbc:h2:mem:migration-guard-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return url;
    }

    /**
     * Builds a Hibernate service registry pointing at the given database.
     *
     * @param url the JDBC URL
     * @param hbm2ddl the {@code hibernate.hbm2ddl.auto} action
     * @return the registry
     */
    private static StandardServiceRegistry buildRegistry(String url, String hbm2ddl) {
        return new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, url)
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
