package io.apitomy.axiom.agents.opencode;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link OpenCodeModelDiscovery}.
 */
class OpenCodeModelDiscoveryTest {

    @Test
    void discoversModelsAndCachesForConfiguredTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-11T00:00:00Z"));
        AtomicInteger executions = new AtomicInteger();

        OpenCodeModelDiscovery discovery = new OpenCodeModelDiscovery(
                "opencode",
                true,
                8,
                86400,
                clock,
                (executable, timeout) -> {
                    executions.incrementAndGet();
                    return new OpenCodeModelDiscovery.CommandResult(
                            0,
                            "github-copilot/claude-sonnet-5\nopenai/gpt-5.4\n"
                    );
                }
        );

        List<String> first = discovery.discoverModels();
        List<String> second = discovery.discoverModels();
        clock.advance(Duration.ofHours(23));
        List<String> third = discovery.discoverModels();

        assertEquals(List.of("github-copilot/claude-sonnet-5", "openai/gpt-5.4"), first);
        assertEquals(first, second);
        assertEquals(first, third);
        assertEquals(1, executions.get());

        clock.advance(Duration.ofHours(2));
        List<String> afterExpiry = discovery.discoverModels();

        assertEquals(first, afterExpiry);
        assertEquals(2, executions.get());
    }

    @Test
    void doesNotCacheFailures() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-11T00:00:00Z"));
        AtomicInteger executions = new AtomicInteger();

        OpenCodeModelDiscovery discovery = new OpenCodeModelDiscovery(
                "opencode",
                true,
                8,
                86400,
                clock,
                (executable, timeout) -> {
                    if (executions.getAndIncrement() == 0) {
                        return new OpenCodeModelDiscovery.CommandResult(1, "boom");
                    }
                    return new OpenCodeModelDiscovery.CommandResult(0, "opencode/gpt-5.3-codex\n");
                }
        );

        List<String> first = discovery.discoverModels();
        List<String> second = discovery.discoverModels();

        assertTrue(first.isEmpty());
        assertEquals(List.of("opencode/gpt-5.3-codex"), second);
        assertEquals(2, executions.get());
    }

    @Test
    void returnsEmptyWhenDiscoveryDisabled() {
        OpenCodeModelDiscovery discovery = new OpenCodeModelDiscovery(
                "opencode",
                false,
                8,
                86400,
                Clock.systemUTC(),
                (executable, timeout) -> new OpenCodeModelDiscovery.CommandResult(0, "opencode/gpt-5.3-codex\n")
        );

        assertTrue(discovery.discoverModels().isEmpty());
    }

    @Test
    void refreshModelsBypassesCacheAndUpdatesCachedValue() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-11T00:00:00Z"));
        AtomicInteger executions = new AtomicInteger();

        OpenCodeModelDiscovery discovery = new OpenCodeModelDiscovery(
                "opencode",
                true,
                8,
                86400,
                clock,
                (executable, timeout) -> {
                    int attempt = executions.incrementAndGet();
                    if (attempt == 1) {
                        return new OpenCodeModelDiscovery.CommandResult(0, "openai/gpt-5.4\n");
                    }
                    return new OpenCodeModelDiscovery.CommandResult(0, "openai/gpt-5.5\n");
                }
        );

        List<String> first = discovery.discoverModels();
        List<String> refreshed = discovery.refreshModels();
        List<String> fromCacheAfterRefresh = discovery.discoverModels();

        assertEquals(List.of("openai/gpt-5.4"), first);
        assertEquals(List.of("openai/gpt-5.5"), refreshed);
        assertEquals(List.of("openai/gpt-5.5"), fromCacheAfterRefresh);
        assertEquals(2, executions.get());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
