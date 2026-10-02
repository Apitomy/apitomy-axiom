package io.apitomy.axiom.app;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of the startup validation of the retry configuration (#422).
 */
class RetryConfigValidationTest {

    @Test
    void defaultsAreValid() {
        assertDoesNotThrow(() -> EventStreamOrchestrator.validateRetryConfig(
                3, Duration.ofSeconds(30), Duration.ofHours(1)));
    }

    @Test
    void equalDelaysAndASingleAttemptAreValid() {
        assertDoesNotThrow(() -> EventStreamOrchestrator.validateRetryConfig(
                1, Duration.ofSeconds(5), Duration.ofSeconds(5)));
    }

    @Test
    void maxAttemptsMustBeAtLeastOne() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                EventStreamOrchestrator.validateRetryConfig(0, Duration.ofSeconds(30),
                        Duration.ofHours(1)));
        assertTrue(e.getMessage().contains("axiom.stream-pipeline.max-attempts"), e.getMessage());
    }

    @Test
    void initialDelayMustBePositive() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                EventStreamOrchestrator.validateRetryConfig(3, Duration.ZERO, Duration.ofHours(1)));
        assertTrue(e.getMessage().contains("axiom.stream-pipeline.retry-initial-delay"),
                e.getMessage());
        assertThrows(IllegalStateException.class, () ->
                EventStreamOrchestrator.validateRetryConfig(3, Duration.ofSeconds(-1),
                        Duration.ofHours(1)));
    }

    @Test
    void maxDelayMustNotBeBelowInitialDelay() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                EventStreamOrchestrator.validateRetryConfig(3, Duration.ofMinutes(5),
                        Duration.ofMinutes(1)));
        assertTrue(e.getMessage().contains("axiom.stream-pipeline.retry-max-delay"), e.getMessage());
    }
}
