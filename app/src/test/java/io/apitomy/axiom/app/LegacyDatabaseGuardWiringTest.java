package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application's Flyway instance runs {@link LegacyDatabaseGuard}, so startup migration is guarded.
 */
@QuarkusTest
class LegacyDatabaseGuardWiringTest {

    @Inject
    Flyway flyway;

    @Test
    void applicationFlywayUsesTheGuard() {
        Callback[] callbacks = flyway.getConfiguration().getCallbacks();
        assertTrue(Arrays.stream(callbacks).anyMatch(c -> c instanceof LegacyDatabaseGuard),
                Arrays.toString(callbacks));
    }
}
