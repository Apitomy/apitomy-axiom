package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.services.EncryptionService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests that {@link SecretExpiryChecker} emits each expiry event exactly once.
 */
@QuarkusTest
class SecretExpiryCheckerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    @Inject
    SecretExpiryChecker checker;

    @Inject
    EncryptionService encryptionService;

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity.delete("eventSourceId = ?1 and payload like ?2", "system", "%SECT_%");
            SecretEntity.delete("name like ?1", "SECT_%");
        });
    }

    private void secret(String name, Instant expiresOn, Integer warnDays) {
        QuarkusTransaction.requiringNew().run(() -> {
            SecretEntity s = new SecretEntity();
            s.name = name;
            s.encryptedValue = encryptionService.encrypt("super-secret-value");
            s.expiresOn = expiresOn;
            s.warnDaysBefore = warnDays;
            s.persist();
        });
    }

    private List<StreamEventEntity> events(String name) {
        return QuarkusTransaction.requiringNew().call(() -> StreamEventEntity.<StreamEventEntity>list(
                "eventSourceId = ?1 and payload like ?2 order by createdOn", "system",
                "%\"" + name + "\"%"));
    }

    @Test
    void secretWithoutExpiryEmitsNothing() {
        secret("SECT_NONE", null, null);
        checker.check(NOW);
        assertEquals(0, events("SECT_NONE").size());
    }

    @Test
    void secretOutsideItsWindowEmitsNothing() {
        secret("SECT_FAR", NOW.plus(Duration.ofDays(30)), null);
        checker.check(NOW);
        assertEquals(0, events("SECT_FAR").size());
    }

    @Test
    void expiringSecretEmitsOneWarning() throws Exception {
        secret("SECT_SOON", NOW.plus(Duration.ofDays(3)), null);
        checker.check(NOW);
        checker.check(NOW.plusSeconds(60));
        List<StreamEventEntity> events = events("SECT_SOON");
        assertEquals(1, events.size());
        StreamEventEntity event = events.get(0);
        assertEquals("secret.expiring", event.type);
        assertEquals("axiom", event.source);
        assertEquals("{\"login\":\"system\"}", event.actor);
        JsonNode payload = MAPPER.readTree(event.payload);
        assertEquals("SECT_SOON", payload.path("secretName").asText());
        assertEquals(3, payload.path("daysRemaining").asInt());
        assertEquals(NOW.plus(Duration.ofDays(3)).toString(), payload.path("expiresOn").asText());
        assertFalse(event.payload.contains("super-secret-value"));
    }

    @Test
    void perSecretOverrideDelaysTheWarning() {
        secret("SECT_OVR", NOW.plus(Duration.ofDays(3)), 1);
        checker.check(NOW);
        assertEquals(0, events("SECT_OVR").size());
        checker.check(NOW.plus(Duration.ofDays(2)).plusSeconds(1));
        assertEquals(1, events("SECT_OVR").size());
    }

    @Test
    void expiredSecretEmitsWarningThenExpiredOnce() {
        Instant expiresOn = NOW.plus(Duration.ofDays(3));
        secret("SECT_EXP", expiresOn, null);
        checker.check(NOW);
        checker.check(expiresOn.plusSeconds(1));
        checker.check(expiresOn.plusSeconds(120));
        List<String> types = events("SECT_EXP").stream().map(e -> e.type).toList();
        assertEquals(List.of("secret.expiring", "secret.expired"), types);
    }

    @Test
    void alreadyExpiredSecretEmitsOnlyExpired() {
        secret("SECT_OLD", NOW.minus(Duration.ofDays(1)), null);
        checker.check(NOW);
        checker.check(NOW.plusSeconds(60));
        List<String> types = events("SECT_OLD").stream().map(e -> e.type).toList();
        assertEquals(List.of("secret.expired"), types);
    }
}
