package io.apitomy.axiom.app.config;

import io.apitomy.axiom.core.config.AxiomHome;
import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.PropertiesConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Defines {@value #DATA_URL}, the JDBC URL of the file-based H2 database under {@code axiom.home}, which the
 * {@code prod} and {@code persist} profiles use as their datasource URL.
 *
 * <p>The URL is built from {@link AxiomHome#of(String)}, so it always holds an absolute path. H2 refuses file
 * paths that are implicitly relative to the working directory, so a relative {@code axiom.home} (for example
 * {@code -Daxiom.home=relhome}) would otherwise stop Axiom from starting.</p>
 */
public class AxiomDataUrlConfigSourceFactory implements ConfigSourceFactory {

    /** The configuration key holding the database URL. */
    public static final String DATA_URL = "axiom.data-url";

    /** Low ordinal, so an explicitly configured {@value #DATA_URL} still wins. */
    private static final int ORDINAL = 50;

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        ConfigValue home = context.getValue(AxiomHome.PROPERTY);
        String configured = home == null ? null : home.getValue();
        return List.of(new PropertiesConfigSource(Map.of(DATA_URL, dataUrl(AxiomHome.of(configured))),
                "AxiomDataUrlConfigSource", ORDINAL));
    }

    /**
     * Builds the H2 file database URL for an Axiom home directory.
     *
     * <p>{@code DB_CLOSE_ON_EXIT=FALSE} stops H2 from registering its own JVM shutdown hook. JVM shutdown
     * hooks run concurrently, so H2's hook would close the database while Quarkus shutdown observers (for
     * example the assistant session cleanup) still use it. The database is closed instead when Agroal closes
     * the connection pool during Quarkus shutdown. {@code AUTO_SERVER=TRUE} cannot be combined with it: H2
     * rejects {@code AUTO_SERVER=TRUE && DB_CLOSE_ON_EXIT=FALSE} at open time, so only one process may open
     * the database.</p>
     *
     * @param home the Axiom home directory
     * @return the JDBC URL of {@code <home>/data/axiom}
     */
    public static String dataUrl(Path home) {
        return "jdbc:h2:file:" + home.toAbsolutePath().normalize().resolve("data").resolve("axiom")
                + ";DB_CLOSE_ON_EXIT=FALSE";
    }
}
