package io.apitomy.axiom.app;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link CronSchedules}.
 */
class CronSchedulesTest {

    /** Next execution of a daily 02:00 UTC cron. */
    @Test
    void nextAfterDailyCron() {
        Instant next = CronSchedules.nextAfter("0 2 * * *", Instant.parse("2026-10-05T03:00:00Z"));
        assertEquals(Instant.parse("2026-10-06T02:00:00Z"), next);
    }

    /** The next execution is strictly after the reference instant. */
    @Test
    void nextAfterIsStrictlyAfter() {
        Instant next = CronSchedules.nextAfter("0 2 * * *", Instant.parse("2026-10-05T02:00:00Z"));
        assertEquals(Instant.parse("2026-10-06T02:00:00Z"), next);
    }

    /** Invalid expressions are rejected. */
    @Test
    void invalidCronRejected() {
        assertThrows(IllegalArgumentException.class, () -> CronSchedules.validate("not a cron"));
    }
}
