# Workbench Phase 1: Assistant Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Generalize the assistant session foundation so the Workflow Workbench can reuse it. This phase
adds template capabilities in place of the hard-coded `axiom-config-assistant` checks, a persisted
conversation history journal, and per-session MCP tokens.

**Architecture:**
- A `SessionCapability` enum is declared by built-in template JSON. It flows through the `SessionTemplate`
  record, the `AssistantSession`, and the REST beans, and every hard-coded template-ID check uses it instead.
- A `HistoryJournal` listener appends every session event to `history.jsonl` in the session directory.
- A random per-session token is handed to the `@axiom-assistant` MCP server, sent as
  `X-Axiom-Session-Token`, and required by the only MCP-only endpoint, `/assistant/sessions/{id}/validate`.

**Tech Stack:** Java 25, Quarkus 3.40.1, Jackson, JUnit 5, RestAssured, React 19 + TypeScript, Vitest.

**Spec:** `docs/superpowers/specs/2026-10-08-workflow-workbench-design.md`, "§1 Architecture → Shared
assistant foundation" and "§6 Phasing" item 1.

## Global Constraints

- REST changes start in `common/api/src/main/resources/openapi.json`. Regenerate with
  `mvn -q -pl common/api install -DskipTests`. Never add `@Path` to `*ResourceImpl` classes, and use the
  generated beans.
- Java style: Javadoc on public methods, 4-space indent, explicit types (no ambiguous `var`), JUnit 5.
- Markdown wraps at 110 columns.
- **Do not run `./build.sh` or `mvn clean`**: the user may have `quarkus:dev` running. Use the focused
  command `mvn -q -pl app -am test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false`.
- `rg` is not installed. Use `git grep`.
- Commit with conventional commit messages and no AI attribution. Do not push.
- The Configuration Assistant's behaviour must not change. The only expected change to an existing test is
  that `AssistantValidateEndpointTest` sends the token header (Task 4).
- Capability JSON names: `config-items`, `validation-feedback`, `composition`.
- Token header name: `X-Axiom-Session-Token`. Token environment variable for MCP servers:
  `AXIOM_SESSION_TOKEN`.
- History file: `<sessionDir>/history.jsonl`, one JSON object `{"type": ..., "data": ...}` per line.

## File Map

| File | Change |
|---|---|
| `app/src/main/java/io/apitomy/axiom/app/assistant/SessionCapability.java` | Create: capability enum |
| `app/src/main/java/io/apitomy/axiom/app/assistant/SessionTemplateService.java` | Add `capabilities` to the record and parse it |
| `app/src/main/resources/templates/assistant-templates/axiom-config-assistant.json` | Declare capabilities |
| `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSession.java` | Carry capabilities and the MCP token |
| `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java` | Use capabilities, attach the journal, generate the token |
| `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantContextBuilder.java` | Use capabilities for the working-directory subfolders |
| `app/src/main/java/io/apitomy/axiom/app/assistant/HistoryJournal.java` | Create: JSONL journal |
| `app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java` | Capability checks, beans, token check |
| `common/api/src/main/resources/openapi.json` | `capabilities` on `SessionTemplate` and `AssistantSessionInfo`; document the token |
| `app/src/main/resources/templates/axiom-assistant-mcp/server.js` | Send the token header |
| `ui/src/config/capabilities.ts` (+ `.test.ts`) | Create: `hasCapability` helper |
| `ui/src/config/api.ts`, `ui/src/pages/AssistantSessionPage.tsx`, `ui/src/pages/SessionTemplatesPage.tsx` | Use capabilities |
| Tests in `app/src/test/java/io/apitomy/axiom/app/assistant/` | New and updated tests |

---

### Task 1: `SessionCapability` and template capabilities

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/assistant/SessionCapability.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/SessionTemplateService.java` (record at lines
  61-77, `toTemplate` at 234-251, `parseTemplateJson` at 253-272)
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java` (`fromTemplateBean` at
  712-730)
- Modify: `app/src/main/resources/templates/assistant-templates/axiom-config-assistant.json`
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/SessionCapabilityTest.java`

**Interfaces:**
- Produces:
  - `enum SessionCapability { CONFIG_ITEMS, VALIDATION_FEEDBACK, COMPOSITION }`, with `String jsonName()`,
    `static Optional<SessionCapability> fromJsonName(String)` and
    `static Set<SessionCapability> parse(JsonNode array)`.
  - A new last component on `SessionTemplateService.SessionTemplate`:
    `Set<SessionCapability> capabilities`, placed after `boolean builtIn`.
  - User-defined (database) templates always have an empty set.

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies capability parsing and the capabilities declared by the built-in templates.
 */
class SessionCapabilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesKnownNamesAndIgnoresUnknown() throws IOException {
        JsonNode array = MAPPER.readTree("[\"config-items\", \"composition\", \"bogus\"]");
        Set<SessionCapability> parsed = SessionCapability.parse(array);
        assertEquals(EnumSet.of(SessionCapability.CONFIG_ITEMS, SessionCapability.COMPOSITION), parsed);
    }

    @Test
    void missingArrayParsesToEmptySet() {
        assertTrue(SessionCapability.parse(MAPPER.missingNode()).isEmpty());
    }

    @Test
    void jsonNamesRoundTrip() {
        for (SessionCapability capability : SessionCapability.values()) {
            assertEquals(Optional.of(capability), SessionCapability.fromJsonName(capability.jsonName()));
        }
        assertEquals("validation-feedback", SessionCapability.VALIDATION_FEEDBACK.jsonName());
    }

    @Test
    void configAssistantDeclaresItemsAndFeedback() throws IOException {
        assertEquals(EnumSet.of(SessionCapability.CONFIG_ITEMS, SessionCapability.VALIDATION_FEEDBACK),
                capabilitiesOf("axiom-config-assistant.json"));
    }

    @Test
    void otherBuiltInsDeclareNothing() throws IOException {
        assertTrue(capabilitiesOf("general-assistant.json").isEmpty());
        assertTrue(capabilitiesOf("subject-assistant.json").isEmpty());
    }

    private static Set<SessionCapability> capabilitiesOf(String file) throws IOException {
        try (InputStream is = SessionCapabilityTest.class.getResourceAsStream(
                "/templates/assistant-templates/" + file)) {
            return SessionCapability.parse(MAPPER.readTree(is).path("capabilities"));
        }
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=SessionCapabilityTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: a compilation failure, because `SessionCapability` does not exist.

- [ ] **Step 3: Create the enum**

```java
package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Optional behaviours a session template enables. Code checks capabilities instead of template IDs, so new
 * built-in templates (such as the workbench) can opt in to shared behaviour.
 */
public enum SessionCapability {

    /** File-based configuration items in the working directory, with the items, validate and apply endpoints. */
    CONFIG_ITEMS("config-items"),

    /** Re-validate working-directory items after each tool result and feed problems back to the agent. */
    VALIDATION_FEEDBACK("validation-feedback"),

    /** The session is bound to a workbench composition. */
    COMPOSITION("composition");

    private final String jsonName;

    SessionCapability(String jsonName) {
        this.jsonName = jsonName;
    }

    /**
     * Returns the name used in template JSON and REST payloads.
     *
     * @return the JSON name, for example {@code config-items}
     */
    public String jsonName() {
        return jsonName;
    }

    /**
     * Looks up a capability by its JSON name.
     *
     * @param name the JSON name
     * @return the capability, or empty if the name is unknown
     */
    public static Optional<SessionCapability> fromJsonName(String name) {
        return Arrays.stream(values()).filter(c -> c.jsonName.equals(name)).findFirst();
    }

    /**
     * Parses a JSON array of capability names. Unknown names are ignored, and a missing or non-array node gives
     * an empty set.
     *
     * @param array the JSON node, typically a template's {@code capabilities} field
     * @return an unmodifiable set of capabilities
     */
    public static Set<SessionCapability> parse(JsonNode array) {
        if (array == null || !array.isArray()) {
            return Collections.emptySet();
        }
        EnumSet<SessionCapability> result = EnumSet.noneOf(SessionCapability.class);
        array.forEach(n -> fromJsonName(n.asText()).ifPresent(result::add));
        return Collections.unmodifiableSet(result);
    }
}
```

- [ ] **Step 4: Add `capabilities` to the template record and its constructors**

In `SessionTemplateService.java`:
1. Add `java.util.Set` to the imports.
2. Append the component `Set<SessionCapability> capabilities` after `boolean builtIn` in the record, and
   add `@param capabilities behaviours this template enables (empty for user-defined templates)` to its
   Javadoc.
3. In `toTemplate(...)`, pass `Set.of()` as the new last argument after `false`.
4. In `parseTemplateJson(...)`, pass `SessionCapability.parse(node.path("capabilities"))` after `builtIn`.

In `AssistantResourceImpl.fromTemplateBean(...)`, pass `java.util.Set.of()` after the final `false`
argument. User templates cannot declare capabilities.

- [ ] **Step 5: Declare the capabilities in the config-assistant template**

In `axiom-config-assistant.json`, add this line directly after the `"description": ...,` line:

```json
  "capabilities": ["config-items", "validation-feedback"],
```

- [ ] **Step 6: Run the test and confirm it passes**

Run: `mvn -q -pl app -am test -Dtest=SessionCapabilityTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/SessionCapability.java \
  app/src/main/java/io/apitomy/axiom/app/assistant/SessionTemplateService.java \
  app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java \
  app/src/main/resources/templates/assistant-templates/axiom-config-assistant.json \
  app/src/test/java/io/apitomy/axiom/app/assistant/SessionCapabilityTest.java
git commit -m "feat(assistant): add session template capabilities"
```

---

### Task 2: Replace template-ID checks with capability checks (backend and REST)

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSession.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java` (lines ~209-212,
  ~267-268, ~293-295)
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantContextBuilder.java` (lines 61-87)
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java` (`requireConfigAssistant`
  at 638-643, `toSessionInfo` at 651-670, `toTemplateBean` at 681-700)
- Modify: `common/api/src/main/resources/openapi.json` (the `AssistantSessionInfo` schema at ~6773 and the
  `SessionTemplate` schema at ~7641)
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/SessionCapabilityEndpointTest.java`

**Interfaces:**
- Consumes: `SessionCapability` and `SessionTemplate.capabilities()` from Task 1.
- Produces:
  - `AssistantSession.getCapabilities(): Set<SessionCapability>`,
    `AssistantSession.hasCapability(SessionCapability): boolean` and
    `AssistantSession.setCapabilities(Set<SessionCapability>)`.
  - `AssistantContextBuilder.createWorkingDirectory(Path sessionDir, Set<SessionCapability> capabilities)`,
    which replaces the `String templateId` overload.
  - REST: `capabilities: string[]` on `AssistantSessionInfo` and `SessionTemplate`.

- [ ] **Step 1: Write the failing integration test**

```java
package io.apitomy.axiom.app.assistant;

import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriverFactory;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that capabilities, not template IDs, drive config-assistant behaviour and are exposed over REST.
 */
@QuarkusTest
class SessionCapabilityEndpointTest {

    @InjectMock
    InteractiveSessionDriverFactory interactiveSessionDriverFactory;

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        created.forEach(id -> given().delete("/api/v1/assistant/sessions/{id}", id));
    }

    private String createSession(String templateId) throws IOException {
        when(interactiveSessionDriverFactory.createDriver(any()))
                .thenAnswer(i -> mock(InteractiveSessionDriver.class));
        String id = given().contentType(ContentType.JSON)
                .body("{\"templateId\":\"" + templateId + "\",\"name\":\"cap-test\"}")
                .post("/api/v1/assistant/sessions")
                .then().statusCode(200).extract().path("id");
        created.add(id);
        return id;
    }

    @Test
    void configAssistantSessionExposesCapabilitiesAndItems() throws IOException {
        String id = createSession("axiom-config-assistant");
        given().get("/api/v1/assistant/sessions/{id}", id)
                .then().statusCode(200)
                .body("capabilities", containsInAnyOrder("config-items", "validation-feedback"));
        given().get("/api/v1/assistant/sessions/{id}/items", id).then().statusCode(200);
    }

    @Test
    void generalSessionHasNoCapabilitiesAndNoItems() throws IOException {
        String id = createSession("general-assistant");
        given().get("/api/v1/assistant/sessions/{id}", id)
                .then().statusCode(200).body("capabilities", empty());
        given().get("/api/v1/assistant/sessions/{id}/items", id).then().statusCode(400);
    }

    @Test
    void templatesExposeCapabilities() {
        given().get("/api/v1/assistant/templates/{id}", "axiom-config-assistant")
                .then().statusCode(200)
                .body("capabilities", containsInAnyOrder("config-items", "validation-feedback"));
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=SessionCapabilityEndpointTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL. The `capabilities` path is null in the responses.

- [ ] **Step 3: Update the OpenAPI contract**

In `openapi.json`, add this property to the `AssistantSessionInfo` schema's `properties`, after
`allowAll`, and to the `SessionTemplate` schema's `properties`, after `builtIn`:

```json
          "capabilities": {
            "description": "Behaviours enabled by the session's template, for example config-items, validation-feedback or composition. Read-only; user-defined templates have none.",
            "readOnly": true,
            "type": "array",
            "items": {
              "type": "string"
            }
          },
```

Watch the commas: `allowAll` is currently the last property in `AssistantSessionInfo`, so add a comma after
its closing brace and none after the new property. Then regenerate:

`mvn -q -pl common/api install -DskipTests`

- [ ] **Step 4: Carry capabilities on `AssistantSession`**

In `AssistantSession.java`:
1. Add `import java.util.Collections;` and `import java.util.EnumSet;`. `Set` is already imported.
2. Add the field and methods:

```java
    private volatile Set<SessionCapability> capabilities = Collections.emptySet();

    /**
     * Returns the capabilities of the template this session was created from.
     *
     * @return an unmodifiable set, empty if none
     */
    public Set<SessionCapability> getCapabilities() {
        return capabilities;
    }

    /**
     * Sets the capabilities of this session. Called once by the session manager before the session starts.
     *
     * @param capabilities the template's capabilities
     */
    public void setCapabilities(Set<SessionCapability> capabilities) {
        this.capabilities = capabilities.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
    }

    /**
     * Returns whether this session has a capability.
     *
     * @param capability the capability
     * @return true if the session's template declares it
     */
    public boolean hasCapability(SessionCapability capability) {
        return capabilities.contains(capability);
    }
```

- [ ] **Step 5: Use capabilities in `AssistantContextBuilder` and `AssistantSessionManager`**

`AssistantContextBuilder.createWorkingDirectory`: change the second parameter to
`java.util.Set<SessionCapability> capabilities`, update its Javadoc `@param`, and replace the
`"axiom-config-assistant".equals(templateId)` condition with
`capabilities.contains(SessionCapability.CONFIG_ITEMS)`.

`AssistantSessionManager.createSession`:
- Change both `contextBuilder.createWorkingDirectory(sessionDir, templateId)` calls to
  `contextBuilder.createWorkingDirectory(sessionDir, template.capabilities())`.
- Directly after `AssistantSession session = new AssistantSession(...);` (before `sessionRef.set(session)`),
  add `session.setCapabilities(template.capabilities());`.
- Replace

```java
            if ("axiom-config-assistant".equals(templateId)) {
                session.addListener(createValidationListener(session));
            }
```

with

```java
            if (session.hasCapability(SessionCapability.VALIDATION_FEEDBACK)) {
                session.addListener(createValidationListener(session));
            }
```

- [ ] **Step 6: Use capabilities in `AssistantResourceImpl`**

Replace the body of `requireConfigAssistant`, keeping the method name and message:

```java
    private void requireConfigAssistant(AssistantSession session) {
        if (!session.hasCapability(SessionCapability.CONFIG_ITEMS)) {
            throw new WebApplicationException(
                    "Items are only available for Configuration Assistant sessions", 400);
        }
    }
```

In `toSessionInfo`, add
`info.setCapabilities(session.getCapabilities().stream().map(SessionCapability::jsonName).sorted().toList());`.
In `toTemplateBean`, add
`bean.setCapabilities(template.capabilities().stream().map(SessionCapability::jsonName).sorted().toList());`.
Import `io.apitomy.axiom.app.assistant.SessionCapability`.

- [ ] **Step 7: Confirm no template-ID checks remain in main code**

Run: `git grep -n '"axiom-config-assistant"' -- app/src/main/java`

Expected: only `SessionTemplateService.java` (the `BUILT_IN_FILES` entry `"axiom-config-assistant.json"`).

- [ ] **Step 8: Run the new and existing assistant tests**

Run:
`mvn -q -pl app -am test -Dtest='SessionCapabilityEndpointTest,SessionCapabilityTest,Assistant*Test,InteractiveSessionDriverFactoryTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS. If an existing test fails, fix the production code, not the test.

- [ ] **Step 9: Commit**

```bash
git add common/api/src/main/resources/openapi.json app/src/main/java/io/apitomy/axiom/app/assistant \
  app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java \
  app/src/test/java/io/apitomy/axiom/app/assistant/SessionCapabilityEndpointTest.java
git commit -m "refactor(assistant): drive config-assistant behaviour by template capabilities"
```

---

### Task 3: UI uses capabilities

**Files:**
- Create: `ui/src/config/capabilities.ts`
- Create: `ui/src/config/capabilities.test.ts`
- Modify: `ui/src/config/api.ts` (the `AssistantSessionInfo` interface at ~1087 and the session template
  interface; find it with `git grep -n "builtIn" ui/src/config/api.ts`)
- Modify: `ui/src/pages/AssistantSessionPage.tsx:261`
- Modify: `ui/src/pages/SessionTemplatesPage.tsx:153`

**Interfaces:**
- Consumes: the REST `capabilities: string[]` from Task 2.
- Produces: `hasCapability(target: { capabilities?: string[] } | null | undefined, capability: SessionCapability): boolean`
  and `type SessionCapability = "config-items" | "validation-feedback" | "composition"`.

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it } from "vitest";
import { hasCapability } from "./capabilities";

describe("hasCapability", () => {
    it("is true when the capability is listed", () => {
        expect(hasCapability({ capabilities: ["config-items"] }, "config-items")).toBe(true);
    });

    it("is false when the capability is absent", () => {
        expect(hasCapability({ capabilities: ["composition"] }, "config-items")).toBe(false);
    });

    it("is false when capabilities are missing or the target is null", () => {
        expect(hasCapability({}, "config-items")).toBe(false);
        expect(hasCapability(null, "config-items")).toBe(false);
        expect(hasCapability(undefined, "composition")).toBe(false);
    });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run (in `ui/`): `npx vitest run src/config/capabilities.test.ts`

Expected: FAIL. The module cannot be resolved.

- [ ] **Step 3: Implement the helper**

```ts
/** Behaviours a session template enables. Mirrors the backend `SessionCapability` JSON names. */
export type SessionCapability = "config-items" | "validation-feedback" | "composition";

/**
 * Returns whether a session or template declares a capability.
 *
 * @param target a session info or template object, possibly missing
 * @param capability the capability to look for
 * @returns true if `target.capabilities` contains `capability`
 */
export function hasCapability(
    target: { capabilities?: string[] } | null | undefined,
    capability: SessionCapability,
): boolean {
    return target?.capabilities?.includes(capability) ?? false;
}
```

- [ ] **Step 4: Use the helper in the pages**

- In `api.ts`, add `capabilities?: string[];` to `AssistantSessionInfo` and to the session template
  interface.
- In `AssistantSessionPage.tsx`, change line 261 to
  `const isConfigAssistant = hasCapability(session, "config-items");` and import `hasCapability` from
  `../config/capabilities`.
- In `SessionTemplatesPage.tsx`, change line 153's condition to
  `!hasCapability(t, "config-items") && (`. Templates with capabilities can't be cloned, because user
  templates cannot carry capabilities. Add the import.

- [ ] **Step 5: Type-check and run the UI tests**

Run (in `ui/`): `npx tsc -b --noEmit && npx vitest run`

Expected: no type errors, and all tests pass (the 38 existing tests plus 3 new ones).

- [ ] **Step 6: Commit**

```bash
git add ui/src/config/capabilities.ts ui/src/config/capabilities.test.ts ui/src/config/api.ts \
  ui/src/pages/AssistantSessionPage.tsx ui/src/pages/SessionTemplatesPage.tsx
git commit -m "refactor(ui): use session capabilities instead of template IDs"
```

---

### Task 4: Per-session MCP token

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSession.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java`
  (`resolveMcpServers` at ~865-893, `createSession`)
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java`
  (`validateAssistantItem` at ~428)
- Modify: `app/src/main/resources/templates/axiom-assistant-mcp/server.js` (`axiomApi` at lines 20-36)
- Modify: `common/api/src/main/resources/openapi.json` (the description of `validateAssistantItem`, plus a
  `401` response)
- Modify: `app/src/test/java/io/apitomy/axiom/app/assistant/AssistantValidateEndpointTest.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/SessionTokenTest.java`

**Interfaces:**
- Consumes: Task 2's session setup.
- Produces:
  - `AssistantSession.getMcpToken(): String`, a 32-byte URL-safe Base64 random value without padding.
  - `AssistantSession.verifyMcpToken(String presented): boolean`, which compares in constant time.
  - The environment variable `AXIOM_SESSION_TOKEN` on the `axiom` (`@axiom-assistant`) MCP server.
  - `POST /assistant/sessions/{id}/validate` returns 401 unless `X-Axiom-Session-Token` matches.

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriverFactory;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the per-session MCP token: generation, hand-off to the MCP server, and enforcement.
 */
@QuarkusTest
class SessionTokenTest {

    private static final String BODY = "{\"type\": \"tool\", \"item\": {\"name\": \"t\"}}";

    @InjectMock
    InteractiveSessionDriverFactory interactiveSessionDriverFactory;

    @Inject
    AssistantSessionManager manager;

    private String sessionId;

    @BeforeEach
    void create() {
        when(interactiveSessionDriverFactory.createDriver(any()))
                .thenAnswer(i -> mock(InteractiveSessionDriver.class));
        sessionId = given().contentType(ContentType.JSON)
                .body("{\"templateId\":\"axiom-config-assistant\",\"name\":\"token-test\"}")
                .post("/api/v1/assistant/sessions").then().statusCode(200).extract().path("id");
    }

    @AfterEach
    void delete() {
        given().delete("/api/v1/assistant/sessions/{id}", sessionId);
    }

    @Test
    void tokenIsPassedToAssistantMcpServer() throws IOException {
        AssistantSession session = manager.getSession(sessionId);
        JsonNode config = new ObjectMapper().readTree(
                Files.readString(session.getSessionDirectory().resolve("mcp-config.json")));
        assertEquals(session.getMcpToken(),
                config.path("mcpServers").path("axiom").path("env").path("AXIOM_SESSION_TOKEN").asText());
    }

    @Test
    void tokensAreUniqueAndVerified() {
        AssistantSession session = manager.getSession(sessionId);
        assertTrue(session.getMcpToken().length() >= 43);
        assertTrue(session.verifyMcpToken(session.getMcpToken()));
        assertFalse(session.verifyMcpToken("wrong"));
        assertFalse(session.verifyMcpToken(null));
        String other = given().contentType(ContentType.JSON)
                .body("{\"templateId\":\"axiom-config-assistant\",\"name\":\"token-test-2\"}")
                .post("/api/v1/assistant/sessions").then().statusCode(200).extract().path("id");
        try {
            assertNotEquals(session.getMcpToken(), manager.getSession(other).getMcpToken());
        } finally {
            given().delete("/api/v1/assistant/sessions/{id}", other);
        }
    }

    @Test
    void validateRequiresToken() {
        given().contentType(ContentType.JSON).body(BODY)
                .post("/api/v1/assistant/sessions/{id}/validate", sessionId).then().statusCode(401);
        given().contentType(ContentType.JSON).header("X-Axiom-Session-Token", "wrong").body(BODY)
                .post("/api/v1/assistant/sessions/{id}/validate", sessionId).then().statusCode(401);
        given().contentType(ContentType.JSON)
                .header("X-Axiom-Session-Token", manager.getSession(sessionId).getMcpToken()).body(BODY)
                .post("/api/v1/assistant/sessions/{id}/validate", sessionId).then().statusCode(200);
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=SessionTokenTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: a compilation failure, because `getMcpToken` does not exist.

- [ ] **Step 3: Generate and verify the token in `AssistantSession`**

Add these imports: `java.security.MessageDigest`, `java.security.SecureRandom`, `java.util.Base64` and
`java.nio.charset.StandardCharsets`. Then add:

```java
    private static final SecureRandom RANDOM = new SecureRandom();

    private volatile String mcpToken = newToken();

    /**
     * Generates a new random session token (32 bytes, URL-safe Base64 without padding).
     *
     * @return the token
     */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Sets this session's MCP token. Called once by the session manager, which generates the token before the
     * session is constructed so it can be written into the MCP configuration.
     *
     * @param token the token
     */
    void setMcpToken(String token) {
        this.mcpToken = token;
    }

    /**
     * Returns the secret token this session's MCP servers present to Axiom REST.
     *
     * @return the token
     */
    public String getMcpToken() {
        return mcpToken;
    }

    /**
     * Checks a presented token against this session's token in constant time.
     *
     * @param presented the value of the {@code X-Axiom-Session-Token} header, possibly null
     * @return true if it matches
     */
    public boolean verifyMcpToken(String presented) {
        return presented != null && MessageDigest.isEqual(
                mcpToken.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
```

- [ ] **Step 4: Pass the token to the MCP server**

`resolveMcpServers` runs before the `AssistantSession` object exists, so the manager generates the token
first and hands it to the session with `setMcpToken`. In `AssistantSessionManager.createSession`:
  - Directly after `String sessionId = UUID.randomUUID().toString();`, add
    `String mcpToken = AssistantSession.newToken();`.
  - Add a `String mcpToken` parameter to `resolveMcpServers` after `sessionId`, and pass it in.
  - Directly after `session.setCapabilities(...)`, add `session.setMcpToken(mcpToken);`.
- In `resolveMcpServers`, inside the `@axiom-assistant` branch, add
  `env.put("AXIOM_SESSION_TOKEN", mcpToken);` after the `AXIOM_ASSISTANT_SESSION_ID` line.

- [ ] **Step 5: Enforce the token on the validate endpoint**

In `AssistantResourceImpl`:
1. Add the field `@jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders httpHeaders;`.
2. Add the constant `static final String SESSION_TOKEN_HEADER = "X-Axiom-Session-Token";`.
3. At the start of `validateAssistantItem`, before the `type`/`item` check, add:

```java
        AssistantSession session = requireSession(sessionId);
        if (!session.verifyMcpToken(httpHeaders.getHeaderString(SESSION_TOKEN_HEADER))) {
            throw new WebApplicationException("Missing or invalid " + SESSION_TOKEN_HEADER, 401);
        }
```

`requireSession` returns 404 for an unknown session, so the existing "no-such-session" test still gets
404.

In `openapi.json`, append this sentence to the `validateAssistantItem` description: `Called by the
session's MCP server; requires the X-Axiom-Session-Token header with the session's token.` Add a response
`"401": {"description": "Missing or invalid session token"}` next to `404`. The header is read through
`HttpHeaders` and is not a generated parameter, so the interface signature does not change. Regenerate
with `mvn -q -pl common/api install -DskipTests`.

- [ ] **Step 6: Send the header from the MCP server**

In `server.js`, add the constant `const AXIOM_SESSION_TOKEN = process.env.AXIOM_SESSION_TOKEN || "";`
next to the other `process.env` constants. In `axiomApi`, after `opts` is built, add:

```js
    if (AXIOM_SESSION_TOKEN) {
        opts.headers["X-Axiom-Session-Token"] = AXIOM_SESSION_TOKEN;
    }
```

Check the syntax with `node --check app/src/main/resources/templates/axiom-assistant-mcp/server.js`.

- [ ] **Step 7: Update the existing validate test to send the token**

In `AssistantValidateEndpointTest`, add `.header("X-Axiom-Session-Token", token(<id>))` to the three
`validate` calls at lines ~100, ~139 and ~160. Leave the `"no-such-session"` call at ~189 unchanged, since
it expects 404. Add the helper:

```java
    private String token(String id) {
        return assistantSessionManager.getSession(id).getMcpToken();
    }
```

- [ ] **Step 8: Run the tests and confirm they pass**

Run:
`mvn -q -pl app -am test -Dtest='SessionTokenTest,AssistantValidateEndpointTest,SessionCapabilityEndpointTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add common/api/src/main/resources/openapi.json app/src/main/java/io/apitomy/axiom/app/assistant \
  app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java \
  app/src/main/resources/templates/axiom-assistant-mcp/server.js \
  app/src/test/java/io/apitomy/axiom/app/assistant/SessionTokenTest.java \
  app/src/test/java/io/apitomy/axiom/app/assistant/AssistantValidateEndpointTest.java
git commit -m "feat(assistant): authenticate MCP calls with a per-session token"
```

---

### Task 5: Persisted conversation history journal

This task persists the history. Rebuilding a live session from the journal after a restart needs the
composition entity, so it belongs to Phase 2. This task provides the writer, the loader and their wiring.

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/assistant/HistoryJournal.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java` (`createSession`)
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/HistoryJournalTest.java`
- Test: extend `app/src/test/java/io/apitomy/axiom/app/assistant/SessionTokenTest.java` with one wiring test

**Interfaces:**
- Produces:
  - `final class HistoryJournal implements Consumer<SseEvent>`, with the constructor
    `HistoryJournal(Path file, ObjectMapper mapper)`.
  - `void accept(SseEvent)`, which appends one line and never throws (I/O errors are logged).
  - `static List<SseEvent> load(Path file, ObjectMapper mapper) throws IOException`. It returns the
    events after the last `conversation_reset` (including that reset), matching in-memory history, and skips
    malformed lines.
  - `static final String FILE_NAME = "history.jsonl"`.

- [ ] **Step 1: Write the failing unit test**

```java
package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the history journal round-trips events and mirrors in-memory reset semantics.
 */
class HistoryJournalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SseEvent event(String type, String text) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("text", text);
        return new SseEvent(type, data);
    }

    @Test
    void roundTripsEventsInOrder(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(HistoryJournal.FILE_NAME);
        HistoryJournal journal = new HistoryJournal(file, MAPPER);
        journal.accept(event("user_message", "hi"));
        journal.accept(event("assistant_text", "hello\nthere"));

        List<SseEvent> loaded = HistoryJournal.load(file, MAPPER);
        assertEquals(2, loaded.size());
        assertEquals("assistant_text", loaded.get(1).type());
        assertEquals("hello\nthere", loaded.get(1).data().path("text").asText());
    }

    @Test
    void loadStartsAtLastConversationReset(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(HistoryJournal.FILE_NAME);
        HistoryJournal journal = new HistoryJournal(file, MAPPER);
        journal.accept(event("user_message", "old"));
        journal.accept(event("conversation_reset", ""));
        journal.accept(event("user_message", "new"));

        List<SseEvent> loaded = HistoryJournal.load(file, MAPPER);
        assertEquals(List.of("conversation_reset", "user_message"),
                loaded.stream().map(SseEvent::type).toList());
    }

    @Test
    void skipsMalformedLinesAndMissingFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(HistoryJournal.FILE_NAME);
        assertTrue(HistoryJournal.load(file, MAPPER).isEmpty());
        new HistoryJournal(file, MAPPER).accept(event("user_message", "ok"));
        Files.writeString(file, "{not json\n", StandardOpenOption.APPEND);
        assertEquals(1, HistoryJournal.load(file, MAPPER).size());
    }

    @Test
    void writeFailureDoesNotThrow(@TempDir Path dir) {
        HistoryJournal journal = new HistoryJournal(dir.resolve("missing-dir/history.jsonl"), MAPPER);
        assertDoesNotThrow(() -> journal.accept(event("user_message", "x")));
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=HistoryJournalTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: a compilation failure, because `HistoryJournal` does not exist.

- [ ] **Step 3: Implement the journal**

```java
package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Appends every event of an assistant session to a JSON Lines file so that the conversation can be shown again
 * after a server restart. Each line is {@code {"type": ..., "data": ...}}.
 */
public final class HistoryJournal implements Consumer<SseEvent> {

    /** File name of the journal inside the session directory. */
    public static final String FILE_NAME = "history.jsonl";

    private static final Logger LOG = Logger.getLogger(HistoryJournal.class);

    private final Path file;
    private final ObjectMapper mapper;

    /**
     * Creates a journal writing to {@code file}.
     *
     * @param file the journal file; its parent directory must exist
     * @param mapper the JSON mapper
     */
    public HistoryJournal(Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = mapper;
    }

    /**
     * Appends one event. Failures are logged and never propagated, so a full disk cannot break a session.
     *
     * @param event the event to record
     */
    @Override
    public synchronized void accept(SseEvent event) {
        ObjectNode line = mapper.createObjectNode();
        line.put("type", event.type());
        line.set("data", event.data());
        try {
            Files.writeString(file, line.toString() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warnf("Failed to append to history journal %s: %s", file, e.getMessage());
        }
    }

    /**
     * Loads a journal. Like the in-memory history, a {@code conversation_reset} discards earlier events.
     * Malformed lines are skipped.
     *
     * @param file the journal file
     * @param mapper the JSON mapper
     * @return the events, oldest first; empty if the file does not exist
     * @throws IOException if the file exists but cannot be read
     */
    public static List<SseEvent> load(Path file, ObjectMapper mapper) throws IOException {
        List<SseEvent> events = new ArrayList<>();
        if (!Files.exists(file)) {
            return events;
        }
        for (String text : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (text.isBlank()) {
                continue;
            }
            JsonNode line;
            try {
                line = mapper.readTree(text);
            } catch (IOException e) {
                LOG.debugf("Skipping malformed history line in %s", file);
                continue;
            }
            String type = line.path("type").asText("");
            if (type.isEmpty()) {
                continue;
            }
            if ("conversation_reset".equals(type)) {
                events.clear();
            }
            events.add(new SseEvent(type, line.path("data")));
        }
        return events;
    }
}
```

- [ ] **Step 4: Run the unit test and confirm it passes**

Run: `mvn -q -pl app -am test -Dtest=HistoryJournalTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS (4 tests).

- [ ] **Step 5: Attach the journal to every session**

In `AssistantSessionManager.createSession`, directly after `sessionRef.set(session);` and before
`session.start();`, add:

```java
            // Persist every event (including the welcome message and startup events) to history.jsonl
            session.addListener(new HistoryJournal(sessionDir.resolve(HistoryJournal.FILE_NAME), objectMapper));
```

- [ ] **Step 6: Add a wiring test**

Append to `SessionTokenTest`. It already creates a config-assistant session with a mocked driver:

```java
    @Test
    void sessionEventsAreJournaled() throws IOException {
        AssistantSession session = manager.getSession(sessionId);
        com.fasterxml.jackson.databind.node.ObjectNode data = new ObjectMapper().createObjectNode();
        data.put("text", "journal me");
        session.addEvent(new AssistantEventParser.SseEvent("assistant_text", data));
        java.util.List<AssistantEventParser.SseEvent> loaded = HistoryJournal.load(
                session.getSessionDirectory().resolve(HistoryJournal.FILE_NAME), new ObjectMapper());
        assertEquals("journal me", loaded.get(loaded.size() - 1).data().path("text").asText());
    }
```

- [ ] **Step 7: Run the assistant test suite**

Run:
`mvn -q -pl app -am test -Dtest='HistoryJournalTest,SessionTokenTest,Assistant*Test,SessionCapability*Test' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/HistoryJournal.java \
  app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java \
  app/src/test/java/io/apitomy/axiom/app/assistant/HistoryJournalTest.java \
  app/src/test/java/io/apitomy/axiom/app/assistant/SessionTokenTest.java
git commit -m "feat(assistant): journal session history to history.jsonl"
```

---

### Task 6: Full verification

**Files:** none. This task only verifies.

- [ ] **Step 1: Full backend build and tests (no clean)**

Run: `mvn package`

Expected: BUILD SUCCESS. The test count is the 847 baseline plus the new tests, with 0 failures.

- [ ] **Step 2: UI checks**

Run (in `ui/`): `npx tsc -b --noEmit && npx vitest run`

Expected: no type errors, and all tests pass.

- [ ] **Step 3: Confirm nothing depends on the template ID**

Run: `git grep -n 'axiom-config-assistant' -- app/src/main ui/src`

Expected: only the template file name and its `templateId` field, `SessionTemplateService.BUILT_IN_FILES`,
and any UI default-template selection, which is fine. There should be no behaviour checks.

- [ ] **Step 4: Report**

Report which commands were run and their results. State that a manual check in the browser (opening a
Configuration Assistant session and applying an item) was **not** done unless it actually was.
