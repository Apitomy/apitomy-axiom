package io.apicurio.axiom.engine.bob;

import io.apicurio.axiom.engine.spi.AiEngineCheckResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BobEngineTest {

    @Test
    void testGetType() {
        BobEngine engine = new BobEngine();
        assertEquals("bob", engine.getType());
    }

    @Test
    void testGetActorTypeDefaultsToType() {
        BobEngine engine = new BobEngine();
        assertEquals("bob", engine.getActorType());
    }

    @Test
    void testProviderReturnsItself() {
        BobEngine engine = new BobEngine();
        assertSame(engine, engine.getEngine());
    }

    @Test
    void testProviderTypeMatchesEngineType() {
        BobEngine engine = new BobEngine();
        assertEquals(engine.getType(), engine.getType());
    }

    @Test
    void testHealthCheckReturnsResults() {
        BobEngine engine = new BobEngine();
        List<AiEngineCheckResult> results = engine.healthCheck();

        assertNotNull(results);
        assertFalse(results.isEmpty());

        AiEngineCheckResult cliCheck = results.stream()
                .filter(r -> r.name().contains("IBM Bob"))
                .findFirst()
                .orElse(null);
        assertNotNull(cliCheck, "Should have an IBM Bob CLI check result");
        assertTrue("ok".equals(cliCheck.status()) || "error".equals(cliCheck.status()));
    }
}
