package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.beans.HealthCheck;
import io.apitomy.axiom.app.EventSourceHealthService;
import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.entities.EventSourcePollLogEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the detailed system health endpoint and the event source health computation it uses.
 */
@QuarkusTest
class SystemHealthResourceTest {

    @Inject
    EventSourceHealthService eventSourceHealthService;

    /** The overall status is the worst check; a failing enabled event source degrades the system. */
    @Test
    void failingEventSourceDegradesOverallStatus() {
        String sourceId = createSource(true);
        Instant now = Instant.now();
        addLog(sourceId, "success", "Polled 1 repositories", now.minus(Duration.ofMinutes(10)));
        addLog(sourceId, "error", "401 Unauthorized", now.minus(Duration.ofMinutes(5)));
        try {
            JsonPath body = given().when().get("/api/v1/system/health")
                    .then().statusCode(200)
                    .extract().jsonPath();
            assertEquals("DEGRADED", body.getString("status"));
            assertNotNull(body.getString("version"));
            assertNotNull(body.getString("timestamp"));
            List<String> names = body.getList("checks.name");
            assertEquals(List.of("database", "eventSources", "engines", "scheduler"), names);
            assertEquals("UP", body.getString("checks.find { it.name == 'database' }.status"));
            assertEquals("DEGRADED", body.getString("checks.find { it.name == 'eventSources' }.status"));
            assertTrue(body.getString("checks.find { it.name == 'eventSources' }.message").contains(sourceId));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                EventSourceEntity source = EventSourceEntity.findById(sourceId);
                source.enabled = false;
            });
        }
    }

    /** Worst-of semantics: DOWN beats DEGRADED beats UP. */
    @Test
    void worstStatusWins() {
        assertEquals(HealthCheck.Status.UP, SystemResourceImpl.worst(List.of(check(HealthCheck.Status.UP))));
        assertEquals(HealthCheck.Status.DEGRADED, SystemResourceImpl.worst(
                List.of(check(HealthCheck.Status.UP), check(HealthCheck.Status.DEGRADED))));
        assertEquals(HealthCheck.Status.DOWN, SystemResourceImpl.worst(List.of(check(HealthCheck.Status.DEGRADED),
                check(HealthCheck.Status.DOWN), check(HealthCheck.Status.UP))));
    }

    /** Event source states follow the enabled flag and the latest poll log. */
    @Test
    void eventSourceStates() {
        Instant now = Instant.now();
        String never = createSource(true);
        assertEquals(EventSourceHealthService.NEVER_POLLED, health(never).state());

        String disabled = createSource(false);
        addLog(disabled, "error", "boom", now);
        assertEquals(EventSourceHealthService.DISABLED, health(disabled).state());

        String baseline = createSource(true);
        addLog(baseline, "success", "Baseline set (first poll)", now);
        assertEquals(EventSourceHealthService.BASELINE, health(baseline).state());

        String recovered = createSource(true);
        addLog(recovered, "error", "old failure", now.minus(Duration.ofMinutes(10)));
        addLog(recovered, "success", "Polled 2 projects", now.minus(Duration.ofMinutes(1)));
        EventSourceHealthService.Health recoveredHealth = health(recovered);
        assertEquals(EventSourceHealthService.HEALTHY, recoveredHealth.state());
        assertNull(recoveredHealth.lastError(), "lastError only reported when after the last success");
        assertNotNull(recoveredHealth.lastErrorAt());
        assertEquals(0, recoveredHealth.consecutiveFailures());

        String failing = createSource(true);
        addLog(failing, "success", "Polled", now.minus(Duration.ofMinutes(10)));
        addLog(failing, "error", "first", now.minus(Duration.ofMinutes(5)));
        addLog(failing, "error", "second", now.minus(Duration.ofMinutes(1)));
        EventSourceHealthService.Health failingHealth = health(failing);
        assertEquals(EventSourceHealthService.FAILING, failingHealth.state());
        assertEquals("second", failingHealth.lastError());
        assertEquals(2, failingHealth.consecutiveFailures());

        QuarkusTransaction.requiringNew().run(() -> List.of(never, baseline, recovered, failing).forEach(id -> {
            EventSourceEntity source = EventSourceEntity.findById(id);
            source.enabled = false;
        }));
    }

    private EventSourceHealthService.Health health(String sourceId) {
        return QuarkusTransaction.requiringNew().call(
                () -> eventSourceHealthService.compute(EventSourceEntity.findById(sourceId)));
    }

    private static HealthCheck check(HealthCheck.Status status) {
        HealthCheck check = new HealthCheck();
        check.setName("x");
        check.setStatus(status);
        return check;
    }

    private static String createSource(boolean enabled) {
        String id = "health-" + UUID.randomUUID().toString().substring(0, 8);
        QuarkusTransaction.requiringNew().run(() -> {
            EventSourceEntity source = new EventSourceEntity();
            source.id = id;
            source.name = id;
            source.sourceType = "github";
            source.enabled = enabled;
            source.baseUrl = "https://api.github.com";
            source.configuration = "{}";
            source.pollInterval = 86400;
            source.createdOn = Instant.now();
            source.modifiedOn = Instant.now();
            source.persist();
        });
        return id;
    }

    private static void addLog(String sourceId, String status, String message, Instant createdOn) {
        QuarkusTransaction.requiringNew().run(() -> {
            EventSourcePollLogEntity log = new EventSourcePollLogEntity();
            log.eventSourceId = sourceId;
            log.status = status;
            log.message = message;
            log.createdOn = createdOn;
            log.persist();
        });
    }
}
