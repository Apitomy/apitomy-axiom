package io.apitomy.axiom.core.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards that test runs use an isolated Axiom home, never the developer's real {@code ~/.axiom}.
 */
class AxiomHomeTest {

    @Test
    void testRunsUseAnIsolatedHome() {
        Path realHome = Path.of(System.getProperty("user.home"), ".axiom");
        assertNotEquals(realHome, AxiomHome.path(), "tests must not use ~/.axiom");
        assertTrue(AxiomHome.path().toString().contains("axiom-test-home"), AxiomHome.path().toString());
    }

    @Test
    void resolvesPathsUnderTheHome() {
        assertEquals(AxiomHome.path().resolve("assistant").resolve("sessions"),
                AxiomHome.resolve("assistant", "sessions"));
    }
}
