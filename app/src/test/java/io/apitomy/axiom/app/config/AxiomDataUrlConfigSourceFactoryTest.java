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
        assertEquals("jdbc:h2:file:" + expected + ";AUTO_SERVER=TRUE",
                AxiomDataUrlConfigSourceFactory.dataUrl(AxiomHome.of("relhome")));
    }

    @Test
    void aBlankHomeFallsBackToTheDefault() {
        Path expected = Path.of(System.getProperty("user.home"), ".axiom").toAbsolutePath().normalize();
        assertEquals(expected, AxiomHome.of(""));
        assertEquals(expected, AxiomHome.of(null));
    }
}
