# Secret Expiration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Let users record an optional expiration on a secret; Axiom shows expiry status, emits
`secret.expiring` / `secret.expired` stream events once each, and refuses to resolve expired secrets.

**Architecture:** Two nullable columns on `secret` plus two dedupe columns. A pure `SecretExpiry` helper
computes status. `SecretsResourceImpl` validates and exposes status, `EnvironmentResolver` blocks expired
secrets, a `@Scheduled` `SecretExpiryChecker` publishes stream events through a new `SystemEventPublisher`
(extracted from `SdkCallService`), and validation reports expired/expiring references. The UI adds an
Expires column, form fields, a banner, and a sidebar badge, refreshed by a `secret-expiry` SSE event.

**Tech Stack:** Java 21, Quarkus (Panache, Scheduler, RESTEasy), H2 + Flyway, JUnit 5 + RestAssured,
React + PatternFly 6 + Vitest.

**Spec:** `docs/superpowers/specs/2026-11-10-secret-expiration-design.md`

## Global Constraints

- Contract-first: REST changes start in `common/api/src/main/resources/openapi.json`; rebuild with
  `mvn -q install -pl common/api -DskipTests` to regenerate beans. Generated `date-time` fields are
  `java.util.Date`.
- 4-space indentation, Javadoc on every public method, explicit types (no ambiguous `var`), JUnit 5.
- Secret values never appear in events, logs, or responses.
- Stream events: `eventSourceId = "system"`, `source = "axiom"`, `actor = {"login":"system"}`, types
  `secret.expiring` and `secret.expired`, payload `{secretId, secretName, expiresOn, daysRemaining}`.
- SSE event type: `secret-expiry`.
- Config: `axiom.secrets.expiry-warning-days` (default `7`), `axiom.secrets.expiry-check-interval`
  (default `15m`; `off` in `%test`).
- Expired-secret error message: `Secret '<name>' expired on <ISO instant>. Update it on the Secrets page.`
- Never test against ports 9191/9090 or `~/.axiom` (see AGENTS.md "Live Testing").
- Commit messages: imperative, no AI attribution.

## Deviations From the Spec (decided while planning)

- Migration is `V11__secret_expiration.sql` (`V10` already exists).
- Warning window config is an integer day count, `axiom.secrets.expiry-warning-days=7`, not a duration.
- `PUT /secrets/{id}` is a full replace and always carries `value`, so every update is a rotation:
  omitting `expiresOn` clears it and a past `expiresOn` is rejected. That satisfies the "can't stay stuck
  expired" rule without a separate check. Every update resets the dedupe columns.
- Secrets are not part of configuration-pack import/export (only `secretName` references are), so there is
  no import/export change.
- `ExpiredSecretException extends MissingSecretException` instead of a new common parent: every existing
  `catch (MissingSecretException)` site handles expired secrets with no edits.
- No injectable `Clock` (the codebase has none). Time-dependent logic takes an explicit `Instant now`
  parameter; runtime callers pass `Instant.now()`.
- The global warning window reaches the UI as `SystemConfig.secretExpiryWarningDays`.

## File Map

| File | Change |
|---|---|
| `app/src/main/resources/db/migration/V11__secret_expiration.sql` | Create: 4 columns |
| `core/.../core/entities/SecretEntity.java` | Modify: 4 fields |
| `core/.../core/services/SecretExpiry.java` | Create: status computation |
| `core/.../core/services/ExpiredSecretException.java` | Create |
| `core/.../core/services/EnvironmentResolver.java` | Modify: block expired |
| `core/.../core/events/SseEvent.java` | Modify: `secretExpiry` factory |
| `common/api/src/main/resources/openapi.json` | Modify: `Secret`, `NewSecret`, `SystemConfig` |
| `app/.../app/rest/SecretsResourceImpl.java` | Modify: fields, validation, SSE |
| `app/.../app/rest/SystemResourceImpl.java` | Modify: expose warning days |
| `app/.../app/SystemEventPublisher.java` | Create: extracted from `SdkCallService` |
| `app/.../app/SdkCallService.java` | Modify: use publisher |
| `app/.../app/SecretExpiryChecker.java` | Create: scheduled checker |
| `app/.../app/validation/ValidationContext.java` | Modify: `secretStatuses` component |
| `app/.../app/validation/ValidationContextFactory.java` | Modify: populate statuses |
| `app/.../app/validation/ConfigItemValidationService.java` | Modify: expired/expiring problems |
| `app/src/main/resources/application.properties` | Modify: config keys |
| `ui/src/config/api.ts` | Modify: types |
| `ui/src/config/secretExpiry.ts` | Create: label helpers |
| `ui/src/pages/SecretsPage.tsx` | Modify: column, form fields, SSE refresh |
| `ui/src/components/SecretExpiryBanner.tsx` | Create |
| `ui/src/components/AppSidebar.tsx` | Modify: badge |
| `ui/src/App.tsx` | Modify: render banner |

(`core/...` = `core/src/main/java/io/apitomy/axiom`, `app/...` = `app/src/main/java/io/apitomy/axiom`.)

---

### Task 1: Data model and expiry status helper

**Files:**
- Create: `app/src/main/resources/db/migration/V11__secret_expiration.sql`
- Modify: `core/src/main/java/io/apitomy/axiom/core/entities/SecretEntity.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/services/SecretExpiry.java`
- Modify: `app/src/main/resources/application.properties`
- Test: `app/src/test/java/io/apitomy/axiom/app/SecretExpiryTest.java`

**Interfaces:**
- Produces: `SecretEntity.expiresOn` (`Instant`), `.warnDaysBefore` (`Integer`), `.expiryWarningSentOn`
  (`Instant`), `.expiredEventSentOn` (`Instant`).
- Produces: `SecretExpiry.Status { NONE, VALID, EXPIRING, EXPIRED }`;
  `static Status status(Instant expiresOn, Integer warnDaysBefore, int defaultWarnDays, Instant now)`;
  `static long daysRemaining(Instant expiresOn, Instant now)`.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -pl app -Dtest=SecretExpiryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `SecretExpiry` not found.

- [ ] **Step 3: Write the migration**

`app/src/main/resources/db/migration/V11__secret_expiration.sql`:

```sql
-- Axiom 3.x incremental migration 11.
--
-- Optional secret expiration, plus bookkeeping so each expiry event fires once.
alter table secret add column expires_on timestamp(6) with time zone;
alter table secret add column warn_days_before integer;
alter table secret add column expiry_warning_sent_on timestamp(6) with time zone;
alter table secret add column expired_event_sent_on timestamp(6) with time zone;
```

- [ ] **Step 4: Add entity fields**

In `SecretEntity.java` add `import java.time.Instant;` and, after `encryptedValue`:

```java
    /** When the secret expires; null means it never expires. */
    @Column(name = "expires_on")
    public Instant expiresOn;

    /** Per-secret warning window in days; null uses {@code axiom.secrets.expiry-warning-days}. */
    @Column(name = "warn_days_before")
    public Integer warnDaysBefore;

    /** When the {@code secret.expiring} event was emitted for the current expiry; null if not yet. */
    @Column(name = "expiry_warning_sent_on")
    public Instant expiryWarningSentOn;

    /** When the {@code secret.expired} event was emitted for the current expiry; null if not yet. */
    @Column(name = "expired_event_sent_on")
    public Instant expiredEventSentOn;
```

- [ ] **Step 5: Write `SecretExpiry`**

```java
package io.apitomy.axiom.core.services;

import java.time.Duration;
import java.time.Instant;

/**
 * Computes a secret's expiry status. Pure functions; callers supply "now".
 */
public final class SecretExpiry {

    /** Expiry status of a secret. */
    public enum Status {
        /** No expiration set. */
        NONE,
        /** Expires later than its warning window. */
        VALID,
        /** Inside its warning window but not yet expired. */
        EXPIRING,
        /** At or past its expiration. */
        EXPIRED
    }

    private SecretExpiry() {
    }

    /**
     * Returns the status of a secret.
     *
     * @param expiresOn       the expiration, or null
     * @param warnDaysBefore  the per-secret warning window in days, or null for the default
     * @param defaultWarnDays the global warning window in days
     * @param now             the current time
     * @return the status
     */
    public static Status status(Instant expiresOn, Integer warnDaysBefore, int defaultWarnDays, Instant now) {
        if (expiresOn == null) {
            return Status.NONE;
        }
        if (!now.isBefore(expiresOn)) {
            return Status.EXPIRED;
        }
        int warnDays = warnDaysBefore != null ? warnDaysBefore : defaultWarnDays;
        Instant warningStart = expiresOn.minus(Duration.ofDays(warnDays));
        return now.isBefore(warningStart) ? Status.VALID : Status.EXPIRING;
    }

    /**
     * Returns whole days until expiry, rounded toward zero; negative once expired.
     *
     * @param expiresOn the expiration
     * @param now       the current time
     * @return the days remaining
     */
    public static long daysRemaining(Instant expiresOn, Instant now) {
        return Duration.between(now, expiresOn).toDays();
    }
}
```

- [ ] **Step 6: Add config**

In `application.properties`, next to the other `axiom.*` properties:

```properties
axiom.secrets.expiry-warning-days=7
axiom.secrets.expiry-check-interval=15m
%test.axiom.secrets.expiry-check-interval=off
```

- [ ] **Step 7: Run tests**

Run: `mvn -q install -pl core -DskipTests && mvn -q test -pl app -Dtest='SecretExpiryTest,SecretInjectionTest'`
Expected: PASS (`SecretInjectionTest` confirms the schema still works).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/resources/db/migration/V11__secret_expiration.sql \
    core/src/main/java/io/apitomy/axiom/core/entities/SecretEntity.java \
    core/src/main/java/io/apitomy/axiom/core/services/SecretExpiry.java \
    app/src/main/resources/application.properties \
    app/src/test/java/io/apitomy/axiom/app/SecretExpiryTest.java
git commit -m "Add secret expiration columns and status helper"
```

---

### Task 2: REST API for expiration

**Files:**
- Modify: `common/api/src/main/resources/openapi.json` (`Secret`, `NewSecret`, `SystemConfig` schemas;
  add `400` response to `createSecret` and `updateSecret`)
- Modify: `core/src/main/java/io/apitomy/axiom/core/events/SseEvent.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/SecretsResourceImpl.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/SystemResourceImpl.java` (around line 177)
- Test: `app/src/test/java/io/apitomy/axiom/app/rest/SecretsResourceTest.java`

**Interfaces:**
- Consumes: Task 1 entity fields, `SecretExpiry`.
- Produces: beans `Secret.getExpiresOn()/getWarnDaysBefore()/getExpiryStatus()` and
  `NewSecret.getExpiresOn()/getWarnDaysBefore()`; `SystemConfig.getSecretExpiryWarningDays()`;
  `SseEvent.secretExpiry(Long secretId, String status)`; on every update the dedupe columns are null.

- [ ] **Step 1: Update the OpenAPI spec**

Edit with the apicurio-data-models MCP tools if available, otherwise by hand.

`Secret.properties` add:

```json
"expiresOn": { "type": "string", "format": "date-time", "nullable": true,
               "description": "When the secret expires; absent if it never expires" },
"warnDaysBefore": { "type": "integer", "format": "int32", "nullable": true,
                    "description": "Per-secret warning window in days; absent uses the global default" },
"expiryStatus": { "type": "string", "enum": ["NONE", "VALID", "EXPIRING", "EXPIRED"],
                  "description": "Expiry status computed by the server" }
```

Add `"expiryStatus"` to `Secret.required`.

`NewSecret.properties` add:

```json
"expiresOn": { "type": "string", "format": "date-time", "nullable": true,
               "description": "When the secret expires; must be in the future. Omit to never expire." },
"warnDaysBefore": { "type": "integer", "format": "int32", "minimum": 0, "nullable": true,
                    "description": "Days before expiry to start warning; omit for the global default" }
```

`SystemConfig.properties` add:

```json
"secretExpiryWarningDays": { "type": "integer", "format": "int32",
                             "description": "Global default warning window for expiring secrets, in days" }
```

For `post` on `/secrets` and `put` on `/secrets/{secretId}`, add to `responses`:

```json
"400": { "description": "Invalid expiration settings" }
```

Run: `mvn -q install -pl common/api -DskipTests`
Expected: success. Check the generated enum name with
`grep -n "enum\|ExpiryStatus" common/api/target/generated-sources/jaxrs/io/apitomy/axiom/api/beans/Secret*.java`
and use that name in Step 4 (expected `Secret.ExpiryStatus` or a top-level `SecretExpiryStatus`; adjust the
import if different).

- [ ] **Step 2: Write the failing test**

```java
package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.core.entities.SecretEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * REST tests for secret expiration fields and validation.
 */
@QuarkusTest
class SecretsResourceTest {

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> SecretEntity.delete("name like ?1", "SRT_%"));
    }

    private static String body(String name, String expiresOn, Integer warnDays) {
        StringBuilder json = new StringBuilder("{\"name\":\"" + name + "\",\"value\":\"v\"");
        if (expiresOn != null) {
            json.append(",\"expiresOn\":\"").append(expiresOn).append('"');
        }
        if (warnDays != null) {
            json.append(",\"warnDaysBefore\":").append(warnDays);
        }
        return json.append('}').toString();
    }

    private static long create(String name, String expiresOn, Integer warnDays) {
        return given().contentType(ContentType.JSON).body(body(name, expiresOn, warnDays))
                .post("/api/v1/secrets").then().statusCode(200)
                .extract().jsonPath().getLong("id");
    }

    @Test
    void secretWithoutExpiryHasStatusNone() {
        given().contentType(ContentType.JSON).body(body("SRT_NONE", null, null))
                .post("/api/v1/secrets").then().statusCode(200)
                .body("expiryStatus", equalTo("NONE"))
                .body("expiresOn", nullValue());
    }

    @Test
    void secretExpiringInThreeDaysIsExpiring() {
        String expires = Instant.now().plus(Duration.ofDays(3)).toString();
        given().contentType(ContentType.JSON).body(body("SRT_SOON", expires, null))
                .post("/api/v1/secrets").then().statusCode(200)
                .body("expiryStatus", equalTo("EXPIRING"));
    }

    @Test
    void perSecretWarnDaysIsReturnedAndApplied() {
        String expires = Instant.now().plus(Duration.ofDays(3)).toString();
        given().contentType(ContentType.JSON).body(body("SRT_WARN", expires, 1))
                .post("/api/v1/secrets").then().statusCode(200)
                .body("warnDaysBefore", equalTo(1))
                .body("expiryStatus", equalTo("VALID"));
    }

    @Test
    void pastExpiryIsRejectedOnCreate() {
        String past = Instant.now().minus(Duration.ofDays(1)).toString();
        given().contentType(ContentType.JSON).body(body("SRT_PAST", past, null))
                .post("/api/v1/secrets").then().statusCode(400);
    }

    @Test
    void negativeWarnDaysIsRejected() {
        String expires = Instant.now().plus(Duration.ofDays(30)).toString();
        given().contentType(ContentType.JSON).body(body("SRT_NEG", expires, -1))
                .post("/api/v1/secrets").then().statusCode(400);
    }

    @Test
    void pastExpiryIsRejectedOnUpdate() {
        long id = create("SRT_UPD", null, null);
        String past = Instant.now().minus(Duration.ofDays(1)).toString();
        given().contentType(ContentType.JSON).body(body("SRT_UPD", past, null))
                .put("/api/v1/secrets/" + id).then().statusCode(400);
    }

    @Test
    void updatingAnExpiredSecretWithoutADateClearsTheExpiry() {
        long id = create("SRT_EXP", Instant.now().plus(Duration.ofDays(1)).toString(), null);
        QuarkusTransaction.requiringNew().run(() -> {
            SecretEntity s = SecretEntity.findById(id);
            s.expiresOn = Instant.now().minus(Duration.ofDays(1));
        });
        given().contentType(ContentType.JSON).body(body("SRT_EXP", null, null))
                .put("/api/v1/secrets/" + id).then().statusCode(200)
                .body("expiryStatus", equalTo("NONE"));
    }

    @Test
    void updateResetsEventBookkeeping() {
        long id = create("SRT_RST", Instant.now().plus(Duration.ofDays(2)).toString(), null);
        QuarkusTransaction.requiringNew().run(() -> {
            SecretEntity s = SecretEntity.findById(id);
            s.expiryWarningSentOn = Instant.now();
            s.expiredEventSentOn = Instant.now();
        });
        String later = Instant.now().plus(Duration.ofDays(60)).toString();
        given().contentType(ContentType.JSON).body(body("SRT_RST", later, null))
                .put("/api/v1/secrets/" + id).then().statusCode(200)
                .body("expiryStatus", equalTo("VALID"));
        SecretEntity after = QuarkusTransaction.requiringNew().call(() -> SecretEntity.findById(id));
        assertNull(after.expiryWarningSentOn);
        assertNull(after.expiredEventSentOn);
    }

    @Test
    void systemConfigExposesWarningDays() {
        given().get("/api/v1/system/config").then().statusCode(200)
                .body("secretExpiryWarningDays", equalTo(7));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q test -pl app -Dtest=SecretsResourceTest`
Expected: FAIL (`expiryStatus` missing, past dates accepted).

- [ ] **Step 4: Add the SSE factory**

In `SseEvent.java`, next to the other factories:

```java
    /**
     * Creates a secret-expiry event, fired when a secret's expiry state may have changed.
     *
     * @param secretId the secret, or null when several changed
     * @param status   the new expiry status name, or null
     * @return a new SSE event
     */
    public static SseEvent secretExpiry(Long secretId, String status) {
        return new SseEvent("secret-expiry",
                "{\"secretId\":" + secretId
                        + ",\"status\":" + (status == null ? "null" : "\"" + escapeJson(status) + "\"")
                        + "}");
    }
```

- [ ] **Step 5: Implement `SecretsResourceImpl`**

Replace the class body with the following (keep the class Javadoc and annotations). Use the enum name found
in Step 1 (shown here as `Secret.ExpiryStatus`).

```java
    @Inject
    EncryptionService encryptionService;

    @Inject
    Event<SseEvent> sseEvents;

    @ConfigProperty(name = "axiom.secrets.expiry-warning-days", defaultValue = "7")
    int defaultWarnDays;

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Secret> listSecrets() {
        Instant now = Instant.now();
        return SecretEntity.<SecretEntity>listAll()
                .stream().map(entity -> toBean(entity, now)).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Secret createSecret(NewSecret data) {
        Instant now = Instant.now();
        validateExpiry(data, now);
        SecretEntity entity = new SecretEntity();
        apply(entity, data);
        entity.persist();
        Secret bean = toBean(entity, now);
        sseEvents.fire(SseEvent.secretExpiry(entity.id, bean.getExpiryStatus().toString()));
        return bean;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Secret updateSecret(long secretId, NewSecret data) {
        Instant now = Instant.now();
        SecretEntity entity = findOrThrow(secretId);
        validateExpiry(data, now);
        apply(entity, data);
        Secret bean = toBean(entity, now);
        sseEvents.fire(SseEvent.secretExpiry(entity.id, bean.getExpiryStatus().toString()));
        return bean;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteSecret(long secretId) {
        SecretEntity entity = findOrThrow(secretId);
        entity.delete();
        sseEvents.fire(SseEvent.secretExpiry(secretId, null));
    }

    /**
     * Copies request data onto the entity. Every create/update carries a new value (a rotation), so the
     * expiry event bookkeeping is reset.
     */
    private void apply(SecretEntity entity, NewSecret data) {
        entity.name = data.getName();
        entity.description = data.getDescription();
        entity.encryptedValue = encryptionService.encrypt(data.getValue());
        entity.expiresOn = data.getExpiresOn() != null ? data.getExpiresOn().toInstant() : null;
        entity.warnDaysBefore = data.getWarnDaysBefore();
        entity.expiryWarningSentOn = null;
        entity.expiredEventSentOn = null;
    }

    private static void validateExpiry(NewSecret data, Instant now) {
        if (data.getExpiresOn() != null && !data.getExpiresOn().toInstant().isAfter(now)) {
            throw new WebApplicationException("expiresOn must be in the future", 400);
        }
        if (data.getWarnDaysBefore() != null && data.getWarnDaysBefore() < 0) {
            throw new WebApplicationException("warnDaysBefore must not be negative", 400);
        }
    }

    private SecretEntity findOrThrow(long id) {
        SecretEntity entity = SecretEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Secret not found: " + id, 404);
        }
        return entity;
    }

    private Secret toBean(SecretEntity entity, Instant now) {
        Secret secret = new Secret();
        secret.setId(entity.id);
        secret.setName(entity.name);
        secret.setDescription(entity.description);
        secret.setExpiresOn(entity.expiresOn != null ? Date.from(entity.expiresOn) : null);
        secret.setWarnDaysBefore(entity.warnDaysBefore);
        SecretExpiry.Status status = SecretExpiry.status(entity.expiresOn, entity.warnDaysBefore,
                defaultWarnDays, now);
        secret.setExpiryStatus(Secret.ExpiryStatus.valueOf(status.name()));
        return secret;
    }
```

Add imports: `io.apitomy.axiom.core.events.SseEvent`, `io.apitomy.axiom.core.services.SecretExpiry`,
`jakarta.enterprise.event.Event`, `org.eclipse.microprofile.config.inject.ConfigProperty`,
`java.time.Instant`, `java.util.Date`.

- [ ] **Step 6: Expose the warning window in system config**

In `SystemResourceImpl`, add a field:

```java
    @ConfigProperty(name = "axiom.secrets.expiry-warning-days", defaultValue = "7")
    int secretExpiryWarningDays;
```

and after `config.setFeatures(new Features());` (around line 182):

```java
        config.setSecretExpiryWarningDays(secretExpiryWarningDays);
```

- [ ] **Step 7: Run tests**

Run: `mvn -q test -pl app -Dtest='SecretsResourceTest,SecretInjectionTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add common/api/src/main/resources/openapi.json \
    core/src/main/java/io/apitomy/axiom/core/events/SseEvent.java \
    app/src/main/java/io/apitomy/axiom/app/rest/SecretsResourceImpl.java \
    app/src/main/java/io/apitomy/axiom/app/rest/SystemResourceImpl.java \
    app/src/test/java/io/apitomy/axiom/app/rest/SecretsResourceTest.java
git commit -m "Expose secret expiration in the REST API"
```

---

### Task 3: Block expired secrets at runtime

**Files:**
- Create: `core/src/main/java/io/apitomy/axiom/core/services/ExpiredSecretException.java`
- Modify: `core/src/main/java/io/apitomy/axiom/core/services/EnvironmentResolver.java:104-115`
- Test: `app/src/test/java/io/apitomy/axiom/app/SecretInjectionTest.java`

**Interfaces:**
- Consumes: `SecretEntity.expiresOn`.
- Produces: `ExpiredSecretException extends MissingSecretException`, constructor
  `ExpiredSecretException(String secretName, Instant expiredOn)`. Existing `catch (MissingSecretException)`
  sites need no change.

- [ ] **Step 1: Write the failing tests**

Add to `SecretInjectionTest` (imports `io.apitomy.axiom.core.services.ExpiredSecretException`,
`java.time.Duration`, `java.time.Instant`):

```java
    private void setExpiry(String name, Instant expiresOn) {
        QuarkusTransaction.requiringNew().run(() -> {
            SecretEntity secret = SecretEntity.find("name", name).firstResult();
            secret.expiresOn = expiresOn;
        });
    }

    @Test
    void resolvingAnExpiredSecretThrows() {
        Instant expired = Instant.parse("2026-01-02T03:04:05Z");
        setExpiry("SIT_ALPHA", expired);
        ExpiredSecretException e = assertThrows(ExpiredSecretException.class,
                () -> QuarkusTransaction.requiringNew().call(() ->
                        environmentResolver.resolve("{\"X\": \"${secret:SIT_ALPHA}\"}")));
        assertEquals("Secret 'SIT_ALPHA' expired on 2026-01-02T03:04:05Z. Update it on the Secrets page.",
                e.getMessage());
    }

    @Test
    void anExpiredSecretIsAlsoAMissingSecretForExistingHandlers() {
        setExpiry("SIT_ALPHA", Instant.now().minusSeconds(60));
        assertThrows(MissingSecretException.class, () -> QuarkusTransaction.requiringNew().call(() ->
                environmentResolver.resolve("{\"X\": \"${secret:SIT_ALPHA}\"}")));
    }

    @Test
    void aSecretThatExpiresLaterStillResolves() {
        setExpiry("SIT_ALPHA", Instant.now().plus(Duration.ofDays(1)));
        Map<String, String> env = QuarkusTransaction.requiringNew().call(() ->
                environmentResolver.resolve("{\"X\": \"${secret:SIT_ALPHA}\"}"));
        assertEquals(Map.of("X", "sit_alpha-value"), env);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -pl app -Dtest=SecretInjectionTest`
Expected: compilation failure, `ExpiredSecretException` not found.

- [ ] **Step 3: Create the exception**

```java
package io.apitomy.axiom.core.services;

import java.time.Instant;

/**
 * Thrown when an environment references a secret whose expiration has passed. Extends
 * {@link MissingSecretException} so every existing handler treats it the same way: the process that needed
 * the secret must not start.
 */
public class ExpiredSecretException extends MissingSecretException {

    /**
     * Creates the exception.
     *
     * @param secretName the expired secret's name
     * @param expiredOn  when it expired
     */
    public ExpiredSecretException(String secretName, Instant expiredOn) {
        super("Secret '" + secretName + "' expired on " + expiredOn + ". Update it on the Secrets page.");
    }
}
```

- [ ] **Step 4: Check expiry in `EnvironmentResolver.lookupSecret`**

Add `import java.time.Instant;` and, right after the `secret == null` check:

```java
        if (secret.expiresOn != null && !Instant.now().isBefore(secret.expiresOn)) {
            throw new ExpiredSecretException(secretName, secret.expiresOn);
        }
```

Update the class Javadoc sentence to: "A reference to a secret that does not exist, has expired, or cannot
be decrypted, throws {@link MissingSecretException} (or its subclass {@link ExpiredSecretException})..."

- [ ] **Step 5: Run tests**

Run: `mvn -q install -pl core -DskipTests && mvn -q test -pl app -Dtest='SecretInjectionTest,McpServerSecretsTest,McpEnvironmentIsolationTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/io/apitomy/axiom/core/services/ExpiredSecretException.java \
    core/src/main/java/io/apitomy/axiom/core/services/EnvironmentResolver.java \
    app/src/test/java/io/apitomy/axiom/app/SecretInjectionTest.java
git commit -m "Refuse to resolve expired secrets"
```

---

### Task 4: System event publisher and expiry checker

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/SystemEventPublisher.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/SdkCallService.java:163-188`
- Create: `app/src/main/java/io/apitomy/axiom/app/SecretExpiryChecker.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/SecretExpiryCheckerTest.java`

**Interfaces:**
- Consumes: Task 1 fields and `SecretExpiry`; `SseEvent.secretExpiry` from Task 2.
- Produces: `SystemEventPublisher.publish(String eventSourceId, String source, String type, String ref,
  String actorJson, String payloadJson, Instant timestamp)` returning `UUID`;
  `SecretExpiryChecker.check(Instant now)` returning the number of events emitted.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -pl app -Dtest=SecretExpiryCheckerTest`
Expected: compilation failure, `SecretExpiryChecker` not found.

- [ ] **Step 3: Create `SystemEventPublisher`**

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.events.StreamEventPersisted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists internally generated stream events (SDK calls, system lifecycle events) so event subscriptions
 * and waiting workflows can react to them like any external event.
 */
@ApplicationScoped
public class SystemEventPublisher {

    @Inject
    Event<StreamEventPersisted> persistedEvents;

    /**
     * Persists a stream event and announces it. Must be called inside a transaction.
     *
     * @param eventSourceId the event source id (e.g. {@code "sdk"}, {@code "system"})
     * @param source        the source name (e.g. {@code "axiom"})
     * @param type          the event type (e.g. {@code "secret.expired"})
     * @param ref           the event reference, may be empty
     * @param actorJson     the actor JSON
     * @param payloadJson   the payload JSON
     * @param timestamp     when the event happened
     * @return the new event's id
     */
    public UUID publish(String eventSourceId, String source, String type, String ref, String actorJson,
                        String payloadJson, Instant timestamp) {
        StreamEventEntity event = new StreamEventEntity();
        event.id = UUID.randomUUID();
        event.sourceEventId = eventSourceId + "-" + event.id;
        event.source = source;
        event.eventSourceId = eventSourceId;
        event.type = type;
        event.ref = ref;
        event.timestamp = timestamp;
        event.actor = actorJson;
        event.payload = payloadJson;
        event.createdOn = Instant.now();
        event.persist();
        persistedEvents.fire(new StreamEventPersisted(event.id.toString(), event.type, event.eventSourceId));
        return event.id;
    }
}
```

- [ ] **Step 4: Refactor `SdkCallService.fireEvent`**

Replace the `@Inject Event<StreamEventPersisted> persistedEvents;` field with
`@Inject SystemEventPublisher systemEventPublisher;` (remove now-unused imports of `StreamEventEntity`,
`StreamEventPersisted`, and `jakarta.enterprise.event.Event` if nothing else uses them), and replace the body
of `fireEvent` after the null checks with:

```java
        java.util.UUID eventId = systemEventPublisher.publish("sdk", source, eventType,
                toString(params.getOrDefault("ref", "")), "{\"login\":\"workflow\"}", payload, Instant.now());

        return SdkCallResult.ok("Event fired: " + eventType,
                Map.of("eventId", eventId.toString()));
```

Note the original `sourceEventId` was `"sdk-" + id`, which `publish` reproduces.

- [ ] **Step 5: Create `SecretExpiryChecker`**

```java
package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.axiom.core.services.SecretExpiry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * Periodically emits {@code secret.expiring} and {@code secret.expired} stream events. Each event fires at
 * most once per expiry; updating a secret resets the bookkeeping.
 */
@ApplicationScoped
public class SecretExpiryChecker {

    private static final Logger LOG = Logger.getLogger(SecretExpiryChecker.class);
    static final String EXPIRING = "secret.expiring";
    static final String EXPIRED = "secret.expired";

    @Inject
    SystemEventPublisher publisher;

    @Inject
    Event<SseEvent> sseEvents;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "axiom.secrets.expiry-warning-days", defaultValue = "7")
    int defaultWarnDays;

    @Scheduled(every = "${axiom.secrets.expiry-check-interval:15m}", delayed = "1m",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledCheck() {
        check(Instant.now());
    }

    /**
     * Checks every secret with an expiration, each in its own transaction.
     *
     * @param now the current time
     * @return the number of events emitted
     */
    public int check(Instant now) {
        List<Long> ids = QuarkusTransaction.requiringNew().call(() ->
                SecretEntity.<SecretEntity>list("expiresOn is not null").stream().map(s -> s.id).toList());
        int emitted = 0;
        for (Long id : ids) {
            try {
                emitted += QuarkusTransaction.requiringNew().call(() -> checkOne(id, now));
            } catch (Exception e) {
                LOG.warnf(e, "Secret expiry check failed for secret %d", id);
            }
        }
        return emitted;
    }

    private int checkOne(Long id, Instant now) {
        SecretEntity secret = SecretEntity.findById(id);
        if (secret == null || secret.expiresOn == null) {
            return 0;
        }
        SecretExpiry.Status status = SecretExpiry.status(secret.expiresOn, secret.warnDaysBefore,
                defaultWarnDays, now);
        if (status == SecretExpiry.Status.EXPIRED && secret.expiredEventSentOn == null) {
            emit(secret, EXPIRED, now);
            secret.expiredEventSentOn = now;
            if (secret.expiryWarningSentOn == null) {
                secret.expiryWarningSentOn = now;
            }
            sseEvents.fire(SseEvent.secretExpiry(secret.id, status.name()));
            return 1;
        }
        if (status == SecretExpiry.Status.EXPIRING && secret.expiryWarningSentOn == null) {
            emit(secret, EXPIRING, now);
            secret.expiryWarningSentOn = now;
            sseEvents.fire(SseEvent.secretExpiry(secret.id, status.name()));
            return 1;
        }
        return 0;
    }

    private void emit(SecretEntity secret, String type, Instant now) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("secretId", secret.id);
        payload.put("secretName", secret.name);
        payload.put("expiresOn", secret.expiresOn.toString());
        payload.put("daysRemaining", SecretExpiry.daysRemaining(secret.expiresOn, now));
        publisher.publish("system", "axiom", type, secret.name, "{\"login\":\"system\"}",
                payload.toString(), now);
        LOG.infof("Emitted %s for secret '%s' (expires %s)", type, secret.name, secret.expiresOn);
    }
}
```

- [ ] **Step 6: Run tests**

Run: `mvn -q test -pl app -Dtest='SecretExpiryCheckerTest,SdkResourceTest,StreamSseNotificationsTest'`
Expected: PASS.

If `EventStreamOrchestrator` or event validation rejects the unknown `system` source or the
`secret.*` types (check `grep -n "eventSourceId\|EventType.from\|validationErrors" app/src/main/java/io/apitomy/axiom/app/EventStreamOrchestrator.java`),
treat `system` exactly the way `sdk` is treated there and add a test that a `secret.expired` event reaches a
subscription. If `sdk` needs no special case, `system` doesn't either.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/SystemEventPublisher.java \
    app/src/main/java/io/apitomy/axiom/app/SdkCallService.java \
    app/src/main/java/io/apitomy/axiom/app/SecretExpiryChecker.java \
    app/src/test/java/io/apitomy/axiom/app/SecretExpiryCheckerTest.java
git commit -m "Emit secret.expiring and secret.expired stream events"
```

---

### Task 5: Validation of expired and expiring references

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/validation/ValidationContext.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/validation/ValidationContextFactory.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/validation/ConfigItemValidationService.java:131-142`
- Test: `app/src/test/java/io/apitomy/axiom/app/validation/ConfigItemValidationServiceTest.java`

**Interfaces:**
- Consumes: `SecretExpiry`.
- Produces: `ValidationContext.secretStatuses()` (`Map<String, SecretExpiry.Status>`, null disables);
  problem codes `EXPIRED_SECRET` (error) and `EXPIRING_SECRET` (warning).

- [ ] **Step 1: Write the failing tests**

Add to `ConfigItemValidationServiceTest` (imports `io.apitomy.axiom.core.services.SecretExpiry`,
`java.util.Map`):

```java
    private static ValidationContext secretsWithStatus(Map<String, SecretExpiry.Status> statuses) {
        return new ValidationContext(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                statuses.keySet(), null, null, null, statuses);
    }

    @Test
    void referencingAnExpiredSecretIsAnError() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.TOOL, json("""
            {"name": "t", "description": "d", "parameters": [], "scriptTemplate": "echo hi",
             "environment": {"TOKEN": "${secret:OLD}"}}
            """), secretsWithStatus(Map.of("OLD", SecretExpiry.Status.EXPIRED)));
        List<ConfigProblem> expired = problems.stream().filter(p -> p.code().equals("EXPIRED_SECRET")).toList();
        assertEquals(1, expired.size(), problems.toString());
        assertTrue(expired.get(0).isError());
        assertEquals("environment.TOKEN", expired.get(0).path());
    }

    @Test
    void referencingAnExpiringSecretIsAWarning() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.TOOL, json("""
            {"name": "t", "description": "d", "parameters": [], "scriptTemplate": "echo hi",
             "environment": {"TOKEN": "${secret:SOON}"}}
            """), secretsWithStatus(Map.of("SOON", SecretExpiry.Status.EXPIRING)));
        List<ConfigProblem> expiring = problems.stream().filter(p -> p.code().equals("EXPIRING_SECRET")).toList();
        assertEquals(1, expiring.size(), problems.toString());
        assertFalse(expiring.get(0).isError());
    }

    @Test
    void referencingAValidSecretReportsNothingAboutExpiry() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.TOOL, json("""
            {"name": "t", "description": "d", "parameters": [], "scriptTemplate": "echo hi",
             "environment": {"TOKEN": "${secret:OK}"}}
            """), secretsWithStatus(Map.of("OK", SecretExpiry.Status.VALID)));
        assertTrue(problems.stream().noneMatch(p -> p.code().endsWith("_SECRET")
                && !p.code().equals("UNDECLARED_SECRET")), problems.toString());
    }
```

(Add `assertFalse` to the static imports if missing.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -pl app -Dtest=ConfigItemValidationServiceTest`
Expected: compilation failure (no 11-argument constructor).

- [ ] **Step 3: Extend `ValidationContext`**

Add `Map<String, SecretExpiry.Status> secretStatuses` as the last record component, document it in the class
Javadoc ("`secretStatuses` maps secret name to expiry status; null disables expiry checks"), and add a
10-argument constructor matching the old canonical one that delegates with `null`:

```java
    /**
     * Creates a context without secret expiry statuses (expiry checks disabled).
     *
     * @param toolNames               tool names
     * @param toolsetNames            toolset names
     * @param actionTypeNames         action type names
     * @param workflowDefinitionNames workflow definition names
     * @param workflowDefinitionIds   workflow definition ids
     * @param eventSourceIds          event source ids
     * @param secretNames             secret names
     * @param actionTypeInputs        action type declared inputs
     * @param actionTypeOutputs       action type declared outputs
     * @param mcpServerNames          MCP server names
     */
    public ValidationContext(Set<String> toolNames, Set<String> toolsetNames, Set<String> actionTypeNames,
                             Set<String> workflowDefinitionNames, Set<Long> workflowDefinitionIds,
                             Set<String> eventSourceIds, Set<String> secretNames,
                             Map<String, Map<String, Boolean>> actionTypeInputs,
                             Map<String, Set<String>> actionTypeOutputs,
                             Set<String> mcpServerNames) {
        this(toolNames, toolsetNames, actionTypeNames, workflowDefinitionNames, workflowDefinitionIds,
                eventSourceIds, secretNames, actionTypeInputs, actionTypeOutputs, mcpServerNames, null);
    }
```

Change the 9-argument constructor to call this 10-argument one (unchanged call), and `unchecked()` to pass 11
nulls.

- [ ] **Step 4: Populate statuses in `ValidationContextFactory`**

Add a field and imports (`io.apitomy.axiom.core.services.SecretExpiry`,
`org.eclipse.microprofile.config.inject.ConfigProperty`, `java.time.Instant`):

```java
    @ConfigProperty(name = "axiom.secrets.expiry-warning-days", defaultValue = "7")
    int defaultWarnDays;
```

In `databaseOnly()`, load secrets once and pass both names and statuses:

```java
            List<SecretEntity> secrets = SecretEntity.listAll();
            Instant now = Instant.now();
            Map<String, SecretExpiry.Status> secretStatuses = secrets.stream().collect(Collectors.toMap(
                    s -> s.name,
                    s -> SecretExpiry.status(s.expiresOn, s.warnDaysBefore, defaultWarnDays, now)));
```

replace `collect(SecretEntity.<SecretEntity>listAll(), s -> s.name)` with `collect(secrets, s -> s.name)`,
and append `secretStatuses` as the last constructor argument. In `forPack`, `forItems`, and `forDirectory`,
append `db.secretStatuses()` as the last argument.

- [ ] **Step 5: Report expiry in `checkSecretReferences`**

Replace the loop body:

```java
            while (m.find()) {
                String name = m.group(1);
                String path = "environment." + entry.getKey();
                if (!context.secretNames().contains(name)) {
                    problems.add(ConfigProblem.error(path, "UNKNOWN_SECRET",
                            "References secret '" + name + "', which does not exist."));
                } else if (context.secretStatuses() != null) {
                    SecretExpiry.Status status = context.secretStatuses().get(name);
                    if (status == SecretExpiry.Status.EXPIRED) {
                        problems.add(ConfigProblem.error(path, "EXPIRED_SECRET", "References secret '" + name
                                + "', which has expired. Update it on the Secrets page."));
                    } else if (status == SecretExpiry.Status.EXPIRING) {
                        problems.add(ConfigProblem.warning(path, "EXPIRING_SECRET", "References secret '"
                                + name + "', which expires soon."));
                    }
                }
            }
```

Update the method Javadoc to mention expired/expiring secrets. Add the `SecretExpiry` import.

- [ ] **Step 6: Run tests**

Run: `mvn -q test -pl app -Dtest='ConfigItemValidationServiceTest,*Validation*Test'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/validation/ \
    app/src/test/java/io/apitomy/axiom/app/validation/ConfigItemValidationServiceTest.java
git commit -m "Report references to expired and expiring secrets"
```

---

### Task 6: UI

**Files:**
- Modify: `ui/src/config/api.ts` (`SystemConfig` ~line 82; `Secret`/`NewSecret` ~line 925; error messages)
- Create: `ui/src/config/secretExpiry.ts`, `ui/src/config/secretExpiry.test.ts`
- Modify: `ui/src/pages/SecretsPage.tsx`
- Create: `ui/src/components/SecretExpiryBanner.tsx`, `ui/src/components/SecretExpiryBanner.test.tsx`
- Modify: `ui/src/components/AppSidebar.tsx`, `ui/src/components/AppSidebar.test.tsx`
- Modify: `ui/src/App.tsx`

**Interfaces:**
- Consumes: REST fields from Task 2, SSE type `secret-expiry`.
- Produces: `expiryLabel(secret, now)`, `countExpiry(secrets)`, `useSecretExpiryCounts()`.

- [ ] **Step 1: Update API types**

In `api.ts`:

```ts
export type SecretExpiryStatus = "NONE" | "VALID" | "EXPIRING" | "EXPIRED";

export interface Secret {
    id: number;
    name: string;
    description?: string;
    expiresOn?: string | null;
    warnDaysBefore?: number | null;
    expiryStatus: SecretExpiryStatus;
}

export interface NewSecret {
    name: string;
    description?: string;
    value: string;
    expiresOn?: string | null;
    warnDaysBefore?: number | null;
}
```

Add `secretExpiryWarningDays?: number;` to `SystemConfig`. In `createSecret` and `updateSecret`, surface the
server message on 400:

```ts
    if (!response.ok) {
        const detail = response.status === 400 ? await response.text() : "";
        throw new Error(detail || `Failed to create secret: ${response.status}`);
    }
```

(`update` wording for `updateSecret`.)

- [ ] **Step 2: Write the failing helper test**

`ui/src/config/secretExpiry.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { countExpiry, expiryLabel } from "./secretExpiry";
import type { Secret } from "./api";

const NOW = new Date("2026-11-10T12:00:00Z");
const s = (over: Partial<Secret>): Secret => ({ id: 1, name: "X", expiryStatus: "NONE", ...over });

describe("expiryLabel", () => {
    it("is Never for secrets that never expire", () => {
        expect(expiryLabel(s({}), NOW)).toBe("Never");
    });
    it("shows days remaining while expiring", () => {
        expect(expiryLabel(s({ expiryStatus: "EXPIRING", expiresOn: "2026-11-15T12:00:00Z" }), NOW))
            .toBe("Expires in 5d");
    });
    it("shows hours on the last day", () => {
        expect(expiryLabel(s({ expiryStatus: "EXPIRING", expiresOn: "2026-11-10T15:00:00Z" }), NOW))
            .toBe("Expires in 3h");
    });
    it("shows Expired", () => {
        expect(expiryLabel(s({ expiryStatus: "EXPIRED", expiresOn: "2026-11-01T00:00:00Z" }), NOW))
            .toBe("Expired");
    });
});

describe("countExpiry", () => {
    it("counts expiring and expired secrets", () => {
        expect(countExpiry([
            s({ expiryStatus: "EXPIRING" }), s({ expiryStatus: "EXPIRED" }),
            s({ expiryStatus: "EXPIRED" }), s({ expiryStatus: "VALID" }),
        ])).toEqual({ expiring: 1, expired: 2 });
    });
});
```

Run: `cd ui && npx vitest run src/config/secretExpiry.test.ts` → FAIL (module missing).

- [ ] **Step 3: Implement the helper and hook**

`ui/src/config/secretExpiry.ts`:

```ts
import { useEffect, useState } from "react";
import { fetchSecrets, type Secret } from "./api";
import { sseClient, type AxiomSseEvent } from "./sse";

/** Human label for a secret's expiry, e.g. "Expires in 5d". */
export function expiryLabel(secret: Secret, now: Date = new Date()): string {
    if (secret.expiryStatus === "NONE" || !secret.expiresOn) return "Never";
    if (secret.expiryStatus === "EXPIRED") return "Expired";
    const ms = new Date(secret.expiresOn).getTime() - now.getTime();
    const days = Math.floor(ms / 86_400_000);
    if (days >= 1) return `Expires in ${days}d`;
    return `Expires in ${Math.max(1, Math.floor(ms / 3_600_000))}h`;
}

/** Counts secrets that are expiring soon or expired. */
export function countExpiry(secrets: Secret[]): { expiring: number; expired: number } {
    return {
        expiring: secrets.filter((s) => s.expiryStatus === "EXPIRING").length,
        expired: secrets.filter((s) => s.expiryStatus === "EXPIRED").length,
    };
}

/** Live expiring/expired counts, refreshed on {@code secret-expiry} SSE events. */
export function useSecretExpiryCounts(): { expiring: number; expired: number } {
    const [counts, setCounts] = useState({ expiring: 0, expired: 0 });
    useEffect(() => {
        const load = () => fetchSecrets().then((list) => setCounts(countExpiry(list))).catch(() => {});
        load();
        return sseClient.subscribe((event: AxiomSseEvent) => {
            if (event.type === "secret-expiry") load();
        });
    }, []);
    return counts;
}
```

Run the test → PASS.

- [ ] **Step 4: Secrets page**

In `SecretsPage.tsx`:

1. Imports: add `Alert` and `Label` to the PatternFly import; add `fetchSystemConfig` to the
   `../config/api` import; import `expiryLabel` from `../config/secretExpiry` and
   `sseClient, type AxiomSseEvent` from `../config/sse`.
2. State:

```tsx
    const [formExpiresOn, setFormExpiresOn] = useState("");      // "YYYY-MM-DDTHH:mm" local
    const [formWarnDays, setFormWarnDays] = useState("");
    const [defaultWarnDays, setDefaultWarnDays] = useState<number>(7);
    const [saveError, setSaveError] = useState<string | null>(null);
```

3. Effects: load the default and subscribe to SSE:

```tsx
    useEffect(() => {
        fetchSystemConfig().then((c) => setDefaultWarnDays(c.secretExpiryWarningDays ?? 7)).catch(() => {});
        return sseClient.subscribe((event: AxiomSseEvent) => {
            if (event.type === "secret-expiry") load();
        });
    }, [load]);
```

4. Date conversion helpers (top of file):

```tsx
/** ISO instant to the local "YYYY-MM-DDTHH:mm" value a datetime-local input expects. */
function toLocalInput(iso?: string | null): string {
    if (!iso) return "";
    const d = new Date(iso);
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
```

5. `openCreate` resets `formExpiresOn`/`formWarnDays`/`saveError` to `""`/`""`/`null`; `openEdit` sets
   `setFormExpiresOn(toLocalInput(secret.expiresOn))`, `setFormWarnDays(secret.warnDaysBefore?.toString() ?? "")`,
   `setSaveError(null)`.
6. Validation and save:

```tsx
    const expiresOnInPast = formExpiresOn !== "" && new Date(formExpiresOn).getTime() <= Date.now();

    const handleSave = () => {
        const data = {
            name: formName,
            description: formDescription || undefined,
            value: formValue,
            expiresOn: formExpiresOn ? new Date(formExpiresOn).toISOString() : null,
            warnDaysBefore: formWarnDays === "" ? null : Number(formWarnDays),
        };
        const promise = editing ? updateSecret(editing.id, data) : createSecret(data);
        promise
            .then(() => { setIsModalOpen(false); load(); })
            .catch((e: Error) => setSaveError(e.message));
    };
```

7. Table: add `<Th>Expires</Th>` after Description and the cell:

```tsx
<Td>
    {s.expiryStatus === "NONE" ? "—" : (
        <>
            {new Date(s.expiresOn!).toLocaleString()}{" "}
            {s.expiryStatus !== "VALID" && (
                <Label color={s.expiryStatus === "EXPIRED" ? "red" : "orange"} isCompact>
                    {expiryLabel(s)}
                </Label>
            )}
        </>
    )}
</Td>
```

   Sort rows so expired come first, then expiring, then by date, then secrets with no expiry:

```tsx
const STATUS_ORDER = { EXPIRED: 0, EXPIRING: 1, VALID: 2, NONE: 3 } as const;
const sorted = [...secrets].sort((a, b) =>
    STATUS_ORDER[a.expiryStatus] - STATUS_ORDER[b.expiryStatus]
    || (a.expiresOn ?? "").localeCompare(b.expiresOn ?? "")
    || a.name.localeCompare(b.name));
```

   and render `sorted.map(...)` instead of `secrets.map(...)`.
8. Form groups after Value:

```tsx
<FormGroup label="Expires on" fieldId="expiresOn">
    <HelperText><HelperTextItem variant={expiresOnInPast ? "error" : "default"}>
        {expiresOnInPast
            ? "This date has passed. Enter a new expiration or clear it."
            : "Optional. Expired secrets are not given to agents, tools, or MCP servers."}
    </HelperTextItem></HelperText>
    <Flex>
        <TextInput id="expiresOn" type="datetime-local" value={formExpiresOn}
            validated={expiresOnInPast ? "error" : "default"}
            onChange={(_e, v) => setFormExpiresOn(v)} />
        {formExpiresOn && (
            <Button variant="link" onClick={() => setFormExpiresOn("")}>Clear</Button>
        )}
    </Flex>
</FormGroup>
<FormGroup label="Warn days before" fieldId="warnDaysBefore">
    <TextInput id="warnDaysBefore" type="number" min={0} value={formWarnDays}
        isDisabled={!formExpiresOn}
        onChange={(_e, v) => setFormWarnDays(v)}
        placeholder={`Default: ${defaultWarnDays}`} />
</FormGroup>
{saveError && <Alert variant="danger" isInline title={saveError} />}
```

9. Save button: `isDisabled={!formName.trim() || !formValue.trim() || expiresOnInPast || Number(formWarnDays) < 0}`.

- [ ] **Step 5: Banner, failing test first**

`ui/src/components/SecretExpiryBanner.test.tsx`:

```tsx
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SecretExpiryBanner } from "./SecretExpiryBanner";
import { renderWithRouter } from "../test/renderWithRouter";

const counts = { expiring: 0, expired: 0 };
vi.mock("../config/secretExpiry", () => ({ useSecretExpiryCounts: () => counts }));

describe("SecretExpiryBanner", () => {
    beforeEach(() => { localStorage.clear(); counts.expiring = 0; counts.expired = 0; });

    it("renders nothing when no secrets need attention", () => {
        renderWithRouter(<SecretExpiryBanner />);
        expect(screen.queryByText(/secret/i)).not.toBeInTheDocument();
    });

    it("summarizes expiring and expired secrets", () => {
        counts.expiring = 2; counts.expired = 1;
        renderWithRouter(<SecretExpiryBanner />);
        expect(screen.getByText("1 secret has expired; 2 secrets are expiring soon.")).toBeInTheDocument();
        expect(screen.getByText("View secrets")).toBeInTheDocument();
    });

    it("stays dismissed until the counts change", async () => {
        counts.expiring = 1;
        const first = renderWithRouter(<SecretExpiryBanner />);
        await userEvent.click(screen.getByRole("button", { name: /close/i }));
        expect(screen.queryByText(/expiring soon/)).not.toBeInTheDocument();
        first.unmount();
        const second = renderWithRouter(<SecretExpiryBanner />);
        expect(screen.queryByText(/expiring soon/)).not.toBeInTheDocument();
        second.unmount();
        counts.expiring = 2;
        renderWithRouter(<SecretExpiryBanner />);
        expect(screen.getByText(/expiring soon/)).toBeInTheDocument();
    });
});
```

Run: `cd ui && npx vitest run src/components/SecretExpiryBanner.test.tsx` → FAIL (module missing).
(`renderWithRouter` returns RTL's `RenderResult`, so `unmount` is available.)

- [ ] **Step 6: Implement the banner**

`ui/src/components/SecretExpiryBanner.tsx`:

```tsx
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Alert, AlertActionCloseButton, AlertActionLink, PageSection } from "@patternfly/react-core";
import { useSecretExpiryCounts } from "../config/secretExpiry";

const DISMISS_KEY = "axiom.secretExpiryBanner.dismissed";

function plural(n: number, singular: string, pluralForm: string): string {
    return `${n} ${n === 1 ? singular : pluralForm}`;
}

/**
 * App-wide warning shown while any secret is expired or expiring soon. Dismissal is remembered until the
 * counts change.
 */
export function SecretExpiryBanner() {
    const navigate = useNavigate();
    const { expiring, expired } = useSecretExpiryCounts();
    const signature = `${expired}:${expiring}`;
    const [dismissed, setDismissed] = useState(() => localStorage.getItem(DISMISS_KEY));

    if (expiring + expired === 0 || dismissed === signature) return null;

    const parts: string[] = [];
    if (expired > 0) parts.push(`${plural(expired, "secret has", "secrets have")} expired`);
    if (expiring > 0) parts.push(`${plural(expiring, "secret is", "secrets are")} expiring soon`);

    return (
        <PageSection padding={{ default: "noPadding" }}>
            <Alert
                variant={expired > 0 ? "danger" : "warning"}
                isInline
                title={`${parts.join("; ")}.`}
                actionClose={<AlertActionCloseButton onClose={() => {
                    localStorage.setItem(DISMISS_KEY, signature);
                    setDismissed(signature);
                }} />}
                actionLinks={<AlertActionLink onClick={() => navigate("/secrets")}>View secrets</AlertActionLink>}
            />
        </PageSection>
    );
}
```

Run the test → PASS.

- [ ] **Step 7: Mount the banner**

In `App.tsx`, import `SecretExpiryBanner` and in the non-error branch wrap the routes:

```tsx
                ) : (
                    <>
                        {!hideChrome && <SecretExpiryBanner />}
                        <Routes>
                            ...existing routes unchanged...
                        </Routes>
                    </>
                )}
```

- [ ] **Step 8: Sidebar badge**

In `AppSidebar.tsx` import `useSecretExpiryCounts` from `../config/secretExpiry`, add
`const secretCounts = useSecretExpiryCounts();` and change the Secrets item:

```tsx
<NavItem isActive={location.pathname === "/secrets"} onClick={() => navigate("/secrets")}>
    Secrets{" "}
    {secretCounts.expiring + secretCounts.expired > 0 && (
        <Badge isRead={false}>{secretCounts.expiring + secretCounts.expired}</Badge>
    )}
</NavItem>
```

In `AppSidebar.test.tsx` add `fetchSecrets: vi.fn().mockResolvedValue([])` to the `../config/api` mock.

- [ ] **Step 9: Verify**

Run: `cd ui && npx vitest run && npm run build`
Expected: all tests PASS, build succeeds with no type errors.

- [ ] **Step 10: Commit**

```bash
git add ui/src
git commit -m "Show secret expiration in the UI"
```

---

### Task 7: Full verification, live check, and spec update

**Files:**
- Modify: `docs/superpowers/specs/2026-11-10-secret-expiration-design.md` (record the deviations listed
  above)

- [ ] **Step 1: Full backend build**

Run: `mvn -q install` (or `./build.sh`) from the repo root.
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 2: Start an isolated instance**

```bash
rm -rf /tmp/axiom-test && mkdir -p /tmp/axiom-test/home
AXIOM_HOME=/tmp/axiom-test/home ./dev.sh --skip-ui --portOffset=500 --dataDir=/tmp/axiom-test/data
```

Wait until `http://localhost:9590/api/v1/system/config` responds; confirm `secretExpiryWarningDays` is `7`.

- [ ] **Step 3: Exercise the API**

```bash
EXP=$(date -u -d '+3 days' +%Y-%m-%dT%H:%M:%SZ)
curl -s -XPOST localhost:9590/api/v1/secrets -H 'Content-Type: application/json' \
    -d "{\"name\":\"LIVE_TOKEN\",\"value\":\"x\",\"expiresOn\":\"$EXP\"}"
# expect "expiryStatus":"EXPIRING"
curl -s -o /dev/null -w '%{http_code}\n' -XPOST localhost:9590/api/v1/secrets \
    -H 'Content-Type: application/json' \
    -d '{"name":"LIVE_PAST","value":"x","expiresOn":"2020-01-01T00:00:00Z"}'
# expect 400
```

After the checker's first run (1 minute after startup), confirm a `secret.expiring` event appears on the
event stream (`GET /api/v1/events/stream` or the Event Stream page) with no secret value in its payload.

- [ ] **Step 4: Check the UI**

Run the UI against the test backend (see `dev.sh --help` for the UI port with the offset) and verify with
Chrome DevTools MCP: the Expires column and badge, the banner and its dismissal, the sidebar badge, the form
rejecting a past date, and the edit form pre-filled with the existing expiry.

- [ ] **Step 5: Stop the instance**

Stop `dev.sh` and `rm -rf /tmp/axiom-test`.

- [ ] **Step 6: Update the spec**

Add a "Implementation notes" section to the spec listing the deviations from this plan's "Deviations From
the Spec" section, and fix `V10` to `V11`, `expiry-warning-window` to `expiry-warning-days`, and remove the
import/export paragraph.

- [ ] **Step 7: Commit**

```bash
git add docs/superpowers/specs/2026-11-10-secret-expiration-design.md
git commit -m "Record secret expiration implementation notes in the spec"
```
