package io.apitomy.axiom.app;

import io.apitomy.axiom.core.services.SecretExpiry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link SecretExpiry} status computation.
 */
class SecretExpiryTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    @Test
    void noExpiryIsNone() {
        assertEquals(SecretExpiry.Status.NONE, SecretExpiry.status(null, null, 7, NOW));
    }

    @Test
    void farFutureIsValid() {
        assertEquals(SecretExpiry.Status.VALID,
                SecretExpiry.status(NOW.plus(Duration.ofDays(30)), null, 7, NOW));
    }

    @Test
    void insideDefaultWindowIsExpiring() {
        assertEquals(SecretExpiry.Status.EXPIRING,
                SecretExpiry.status(NOW.plus(Duration.ofDays(3)), null, 7, NOW));
    }

    @Test
    void perSecretOverrideNarrowsTheWindow() {
        assertEquals(SecretExpiry.Status.VALID,
                SecretExpiry.status(NOW.plus(Duration.ofDays(3)), 1, 7, NOW));
    }

    @Test
    void perSecretOverrideWidensTheWindow() {
        assertEquals(SecretExpiry.Status.EXPIRING,
                SecretExpiry.status(NOW.plus(Duration.ofDays(20)), 30, 7, NOW));
    }

    @Test
    void atExpiryIsExpired() {
        assertEquals(SecretExpiry.Status.EXPIRED, SecretExpiry.status(NOW, null, 7, NOW));
        assertEquals(SecretExpiry.Status.EXPIRED,
                SecretExpiry.status(NOW.minusSeconds(1), null, 7, NOW));
    }

    @Test
    void daysRemainingRoundsDownAndGoesNegative() {
        assertEquals(3, SecretExpiry.daysRemaining(NOW.plus(Duration.ofHours(80)), NOW));
        assertEquals(-1, SecretExpiry.daysRemaining(NOW.minus(Duration.ofHours(30)), NOW));
    }
}
