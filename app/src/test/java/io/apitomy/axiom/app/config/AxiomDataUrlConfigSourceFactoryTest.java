package io.apitomy.axiom.app.config;

import io.apitomy.axiom.core.config.AxiomHome;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The database URL always holds an absolute path, because H2 rejects paths implicitly relative to the working
 * directory.
 */
class AxiomDataUrlConfigSourceFactoryTest {

    @Test
    void aRelativeHomeBecomesAnAbsoluteUrl() {
        Path expected = Path.of("relhome").toAbsolutePath().normalize().resolve("data").resolve("axiom");
        assertEquals("jdbc:h2:file:" + expected + ";AUTO_SERVER=TRUE;DB_CLOSE_ON_EXIT=FALSE",
                AxiomDataUrlConfigSourceFactory.dataUrl(AxiomHome.of("relhome")));
    }

    /**
     * H2's own JVM shutdown hook must not close the database: it runs concurrently with the Quarkus shutdown
     * hook, whose shutdown observers still need the database. Agroal closes the pool (and so the database).
     */
    @Test
    void theDatabaseIsNotClosedByH2sShutdownHook() {
        String url = AxiomDataUrlConfigSourceFactory.dataUrl(AxiomHome.of("relhome"));
        assertEquals(true, url.contains(";DB_CLOSE_ON_EXIT=FALSE"));
    }

    @Test
    void aBlankHomeFallsBackToTheDefault() {
        Path expected = Path.of(System.getProperty("user.home"), ".axiom").toAbsolutePath().normalize();
        assertEquals(expected, AxiomHome.of(""));
        assertEquals(expected, AxiomHome.of(null));
    }
}
