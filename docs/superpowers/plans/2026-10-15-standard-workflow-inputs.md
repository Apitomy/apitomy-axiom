# Standard Workflow Inputs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** A workflow's Start node declares which of two standard inputs (`subject`, `event`) it needs; Axiom
satisfies exactly the declared ones when it creates a run, and refuses to create a run whose required inputs
it cannot satisfy. Supplied/custom run inputs and Start `defaultValue`s are removed.

**Architecture:** A new `StandardInputs` registry (Axiom `app` module) defines the inputs, their types and how
to resolve them. `WorkflowExecutionService` builds the initial run context from the registry instead of
hard-coded keys. Publish rules, the new-workflow scaffold, a new shadowing warning and the assistant reference
all read the same registry. The Flow engine is unchanged.

**Tech Stack:** Java 25, Quarkus, Hibernate/Panache, JUnit 5 + RestAssured (`@QuarkusTest`), Apitomy Flow 3.0.2
(`io.apitomy.flow.model.*`), React + PatternFly 6 + Vitest (UI), OpenAPI 3.1 contract-first REST.

**Spec:** `docs/superpowers/specs/2026-10-15-standard-workflow-inputs-design.md`

## Global Constraints

- Standard inputs are exactly `subject` (type `object`) and `event` (type `object`).
- `subject` contains exactly `id`, `name`, `type`, `ref`, `refSource`, `repository`; missing/blank values are
  `null`.
- A run's initial context contains only the standard inputs declared on its Start node.
- Required-but-unsatisfiable message, verbatim:
  `Cannot start workflow "<definition name>": required input(s) not available: <comma-separated names>`
  returned as HTTP 400 with JSON body `{"message": "..."}`.
- Shadowing warning code: `SHADOWS_WORKFLOW_INPUT`; message:
  `Output '<key>' of node '<node name>' overwrites the workflow input '<key>' for later nodes.`
- Standard input names are **not** reserved; reading an undeclared standard input is **not** an error.
- REST changes are contract-first: edit `common/api/src/main/resources/openapi.json` (OpenAPI 3.1, minimal
  diffs, no `nullable`), then implement the generated interfaces. Never add `@Path` to impl classes.
- Schema changes edit `app/src/main/resources/db/migration/V1__baseline.sql` in place (no `V2__`).
- Never run `./build.sh`, `mvn clean` or `mvn install`. Backend tests:
  `mvn -q -pl app -am test -Dtest=<Classes> -Dsurefire.failIfNoSpecifiedTests=false`, results in
  `app/target/surefire-reports/*.txt`. UI (cwd `ui/`): `timeout 250 npx tsc -b --noEmit`,
  `timeout 280 npx vitest run`.
- Commit only when the task's tests pass: chain with `&&`. Conventional commits, no AI attribution.
- Java style: 4-space indent, Javadoc on public methods, explicit types (no ambiguous `var`).
- Markdown docs wrap at 110 characters (except tables).

---

### Task 1: `StandardInputs` registry

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/StandardInputs.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/StandardInputsTest.java`

**Interfaces:**
- Produces (used by Tasks 2, 4, 5, 6):
  - `StandardInputs.SUBJECT` = `"subject"`, `StandardInputs.EVENT` = `"event"`
  - `record StandardInputs.StandardInput(String name, String type, String description, String availability)`
  - `List<StandardInput> StandardInputs.ALL` (order: subject, event)
  - `List<String> StandardInputs.SUBJECT_FIELDS` = `id, name, type, ref, refSource, repository`
  - `Optional<StandardInput> StandardInputs.find(String name)`
  - `Set<String> StandardInputs.names()`
  - `Map<String, Object> StandardInputs.subjectValue(SubjectEntity subject)`
  - `ObjectNode StandardInputs.eventValue(StreamEventEntity event, ObjectMapper mapper)`
  - `record StandardInputs.Resolution(Map<String, Object> context, List<String> missingRequired)`
  - `Resolution StandardInputs.resolve(Workflow workflow, SubjectEntity subject, JsonNode event)` — `event`
    null when the run was not started from an event.

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.flow.model.Workflow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the standard workflow inputs registry. */
class StandardInputsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Workflow workflow(String startInputsJson) throws Exception {
        return MAPPER.readValue("""
            {"id": "w", "name": "W",
             "nodes": [
               {"id": "s", "type": "start", "name": "Start", "config": {"inputs": %s}, "position": {"x": 0, "y": 0}},
               {"id": "e", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 100}}],
             "edges": [{"id": "x", "source": "s", "target": "e", "priority": 0, "isDefault": false}]}
            """.formatted(startInputsJson), Workflow.class);
    }

    private static SubjectEntity subject() {
        SubjectEntity s = new SubjectEntity();
        s.id = 7L;
        s.name = "PR 12";
        s.type = "pull-request";
        s.ref = "https://github.com/o/r/pull/12";
        s.refSource = "github";
        s.repository = " ";
        return s;
    }

    @Test
    void registryListsSubjectAndEvent() {
        assertEquals(List.of("subject", "event"), StandardInputs.ALL.stream().map(StandardInputs.StandardInput::name).toList());
        assertEquals("object", StandardInputs.find("subject").orElseThrow().type());
        assertTrue(StandardInputs.find("ref").isEmpty());
    }

    @Test
    void subjectValueHasExactlyTheSixFieldsWithBlankAsNull() {
        Map<String, Object> value = StandardInputs.subjectValue(subject());
        assertEquals(List.of("id", "name", "type", "ref", "refSource", "repository"), List.copyOf(value.keySet()));
        assertEquals(7L, value.get("id"));
        assertEquals("PR 12", value.get("name"));
        assertNull(value.get("repository"));
    }

    @Test
    void eventValueIsTheEnvelope() {
        StreamEventEntity e = new StreamEventEntity();
        e.id = UUID.randomUUID();
        e.type = "issue.created";
        e.source = "github";
        e.eventSourceId = "gh";
        e.ref = "r";
        e.timestamp = Instant.parse("2026-10-15T00:00:00Z");
        e.payload = "{\"a\": 1}";
        ObjectNode node = StandardInputs.eventValue(e, MAPPER);
        assertEquals("issue.created", node.path("type").asText());
        assertEquals(1, node.path("payload").path("a").asInt());
        assertFalse(node.has("actor"));
    }

    @Test
    void resolvesOnlyDeclaredInputs() throws Exception {
        StandardInputs.Resolution r = StandardInputs.resolve(
                workflow("[{\"name\": \"subject\", \"type\": \"object\", \"required\": true}]"), subject(),
                MAPPER.readTree("{\"type\": \"t\"}"));
        assertEquals(List.of("subject"), List.copyOf(r.context().keySet()));
        assertTrue(r.missingRequired().isEmpty());
    }

    @Test
    void optionalUnavailableEventIsAbsentAndRequiredIsReported() throws Exception {
        StandardInputs.Resolution optional = StandardInputs.resolve(
                workflow("[{\"name\": \"event\", \"type\": \"object\", \"required\": false}]"), subject(), null);
        assertFalse(optional.context().containsKey("event"));
        assertTrue(optional.missingRequired().isEmpty());

        StandardInputs.Resolution required = StandardInputs.resolve(
                workflow("[{\"name\": \"event\", \"type\": \"object\", \"required\": true}]"), subject(), null);
        assertEquals(List.of("event"), required.missingRequired());
    }

    @Test
    void noDeclaredInputsGivesEmptyContext() throws Exception {
        JsonNode event = MAPPER.readTree("{}");
        assertTrue(StandardInputs.resolve(workflow("[]"), subject(), event).context().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl app -am test -Dtest=StandardInputsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `StandardInputs` does not exist.

- [ ] **Step 3: Implement**

```java
package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.flow.model.NodeConfig;
import io.apitomy.flow.model.NodeType;
import io.apitomy.flow.model.Workflow;
import io.apitomy.flow.model.WorkflowNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The standard workflow inputs: the only inputs a Start node may declare, and the only values Axiom puts into a
 * new run's context. A run receives exactly the standard inputs its Start node declares.
 */
public final class StandardInputs {

    /** The subject the run is about. */
    public static final String SUBJECT = "subject";
    /** The stream event that started the run. */
    public static final String EVENT = "event";

    /**
     * A standard input.
     *
     * @param name         the input name
     * @param type         the Start input type
     * @param description  what the value is
     * @param availability when Axiom can provide it
     */
    public record StandardInput(String name, String type, String description, String availability) {
    }

    /** The fields of the {@code subject} input, in order. */
    public static final List<String> SUBJECT_FIELDS = List.of("id", "name", "type", "ref", "refSource", "repository");

    /** All standard inputs. */
    public static final List<StandardInput> ALL = List.of(
            new StandardInput(SUBJECT, "object",
                    "The subject the run is about: " + String.join(", ", SUBJECT_FIELDS)
                            + " (id is a number; the others are strings or null)",
                    "always"),
            new StandardInput(EVENT, "object",
                    "The event that started the run: id, type, source, eventSourceId, ref, timestamp, actor, payload",
                    "only when the run was started from a stream event"));

    /**
     * The result of resolving a workflow's declared inputs.
     *
     * @param context         the initial run context (declared and available inputs only)
     * @param missingRequired declared required inputs that are not available, in declaration order
     */
    public record Resolution(Map<String, Object> context, List<String> missingRequired) {
    }

    private StandardInputs() {
    }

    /**
     * Finds a standard input by name.
     *
     * @param name the input name
     * @return the input, or empty when the name is not a standard input
     */
    public static Optional<StandardInput> find(String name) {
        return ALL.stream().filter(i -> i.name().equals(name)).findFirst();
    }

    /**
     * Returns the standard input names.
     *
     * @return the names
     */
    public static Set<String> names() {
        return ALL.stream().map(StandardInput::name).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Builds the {@code subject} input value: exactly {@link #SUBJECT_FIELDS}, blank strings as null.
     *
     * @param subject the run's subject
     * @return the value (insertion-ordered, may contain nulls)
     */
    public static Map<String, Object> subjectValue(SubjectEntity subject) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", subject.id);
        value.put("name", blankToNull(subject.name));
        value.put("type", blankToNull(subject.type));
        value.put("ref", blankToNull(subject.ref));
        value.put("refSource", blankToNull(subject.refSource));
        value.put("repository", blankToNull(subject.repository));
        return value;
    }

    /**
     * Builds the {@code event} input value: the event envelope, with actor and payload parsed as JSON.
     *
     * @param event  the stream event
     * @param mapper the JSON mapper
     * @return the value
     */
    public static ObjectNode eventValue(StreamEventEntity event, ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", event.id.toString());
        node.put("type", event.type);
        node.put("source", event.source);
        node.put("eventSourceId", event.eventSourceId);
        node.put("ref", event.ref);
        node.put("timestamp", event.timestamp != null ? event.timestamp.toString() : null);
        try {
            if (event.actor != null) {
                node.set("actor", mapper.readTree(event.actor));
            }
            if (event.payload != null) {
                node.set("payload", mapper.readTree(event.payload));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON in stream event " + event.id + ": " + e.getMessage(), e);
        }
        return node;
    }

    /**
     * Resolves the Start node's declared inputs for a new run. Undeclared and unknown names are ignored here
     * (publish rejects unknown names).
     *
     * @param workflow the workflow being started
     * @param subject  the run's subject
     * @param event    the {@code event} value when started from a stream event, else null
     * @return the context to start with and any required inputs that are not available
     */
    public static Resolution resolve(Workflow workflow, SubjectEntity subject, JsonNode event) {
        Map<String, Object> context = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (NodeConfig.Field field : startInputs(workflow)) {
            String name = field.name();
            Object value = switch (name == null ? "" : name) {
                case SUBJECT -> subjectValue(subject);
                case EVENT -> event;
                default -> null;
            };
            if (find(name).isEmpty()) {
                continue;
            }
            if (value != null) {
                context.put(name, value);
            } else if (field.required()) {
                missing.add(name);
            }
        }
        return new Resolution(context, missing);
    }

    private static List<NodeConfig.Field> startInputs(Workflow workflow) {
        if (workflow.nodes() == null) {
            return List.of();
        }
        for (WorkflowNode node : workflow.nodes()) {
            if (node.type() == NodeType.START && node.typedConfig() instanceof NodeConfig.Start start) {
                return start.inputs();
            }
        }
        return List.of();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl app -am test -Dtest=StandardInputsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 6, Failures: 0, Errors: 0` in `app/target/surefire-reports/*StandardInputsTest.txt`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/StandardInputs.java app/src/test/java/io/apitomy/axiom/app/StandardInputsTest.java && git commit -m "feat(workflows): add the standard workflow inputs registry"
```

---

### Task 2: Run creation resolves declared standard inputs

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/RunRequest.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java` (constants ~line 66-70;
  overloads ~lines 115-172; `applyStartInputDefaults` ~175-200; `startRun` ~200-330)
- Modify: `app/src/main/java/io/apitomy/axiom/app/EventStreamOrchestrator.java` (`startRunFromEvent`
  ~770-810; `routeToCreateWorkflow` ~724)
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/SubjectsResourceImpl.java` (~390)
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/StreamEventsResourceImpl.java` (~215)
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/WorkflowDefinitionsResourceImpl.java` (`rerunWorkflowRun`
  ~505-528)
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowScheduleTriggerScheduler.java` (`startRun` ~166-176)
- Rewrite: `app/src/test/java/io/apitomy/axiom/app/WorkflowTriggerInputGuardTest.java`
- Modify tests: `WorkflowRunMetadataTest.java`, `WorkflowInstanceResourceTest.java`, and every test that no
  longer compiles (see Step 6)

**Interfaces:**
- Consumes: Task 1 `StandardInputs.resolve`, `StandardInputs.eventValue`, `StandardInputs.EVENT`.
- Produces:
  - `record RunRequest(String title, Map<String, String> labels)` with `RunRequest.EMPTY`.
  - `WorkflowExecutionService.triggerWorkflow(long subjectId, long definitionId, RunRequest request,
    EventOrigin origin)` — when `origin != null`, the `event` input is built from
    `StreamEventEntity.findById(origin.eventId())`.
  - `WorkflowExecutionService.triggerWorkflow(long subjectId, long definitionId)` (unchanged).
  - The `triggerWorkflow(..., Map<String, Object> extraContext, ...)` overloads are removed.
  - `EventStreamOrchestrator.startRunFromEvent(StreamEventEntity, long, RunRequest, Long)` (signature
    unchanged; no longer builds the event node).
  - Stored `workflow_run.inputs` = JSON of the resolved context **without** `event`; null when empty.

- [ ] **Step 1: Write the failing integration test** — replace the whole of
  `WorkflowTriggerInputGuardTest.java` with:

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A run's initial context holds exactly the declared standard inputs; required ones must be available. */
@QuarkusTest
class WorkflowTriggerInputGuardTest {

    @Inject
    WorkflowExecutionService service;

    @Inject
    EventStreamOrchestrator orchestrator;

    /** Start → wait (keeps the run active so its context can be read) → end, with the given Start inputs. */
    private static String content(String inputs) {
        return """
            {"id": "in-wf", "name": "Inputs WF",
             "nodes": [
               {"id": "s1", "type": "start", "name": "Start", "config": {"inputs": %s}, "position": {"x": 0, "y": 0}},
               {"id": "w1", "type": "wait", "name": "Hold", "config": {"duration": "PT1H"}, "position": {"x": 0, "y": 100}},
               {"id": "e1", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 200}}],
             "edges": [
               {"id": "x1", "source": "s1", "target": "w1", "priority": 0, "isDefault": false},
               {"id": "x2", "source": "w1", "target": "e1", "priority": 0, "isDefault": false}]}
            """.formatted(inputs);
    }

    private static final String SUBJECT_REQUIRED = "[{\"name\": \"subject\", \"type\": \"object\", \"required\": true}]";
    private static final String EVENT_REQUIRED = "[{\"name\": \"event\", \"type\": \"object\", \"required\": true}]";
    private static final String EVENT_OPTIONAL = "[{\"name\": \"event\", \"type\": \"object\", \"required\": false}]";

    private static UUID event() {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity e = new StreamEventEntity();
            e.id = id;
            e.sourceEventId = "inputs-" + id;
            e.source = "github";
            e.eventSourceId = "conn-inputs";
            e.type = "issue.created";
            e.ref = "test/inputs-event-" + id;
            e.timestamp = Instant.now();
            e.payload = "{\"n\": 5}";
            e.createdOn = Instant.now();
            e.persist();
        });
        return id;
    }

    @Test
    void contextHoldsOnlyTheDeclaredSubject() {
        long subjectId = TestFixtures.createSubject("inputs-subject-only");
        long defId = TestFixtures.createDefinition("Inputs Subject Only", content(SUBJECT_REQUIRED));
        given().contentType(ContentType.JSON).body("{\"workflowDefinitionId\": %d}".formatted(defId))
                .when().post("/api/v1/subjects/" + subjectId + "/workflow")
                .then().statusCode(200)
                .body("context.subject.id", equalTo((int) subjectId))
                .body("context.subject.name", equalTo("inputs-subject-only"))
                .body("context.subject.repository", nullValue())
                .body("context.ref", nullValue())
                .body("context.subjectId", nullValue())
                .body("context.event", nullValue())
                .body("inputs.subject.ref", equalTo("test/inputs-subject-only"));
    }

    @Test
    void noDeclaredInputsGivesAnEmptyContext() {
        long subjectId = TestFixtures.createSubject("inputs-none");
        long defId = TestFixtures.createWaitDefinition("Inputs None");
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                service.triggerWorkflow(subjectId, defId, RunRequest.EMPTY, null));
        given().when().get("/api/v1/workflow/runs/" + run.id)
                .then().statusCode(200).body("context.subject", nullValue()).body("inputs", nullValue());
    }

    @Test
    void requiredEventWithoutAnEventFailsWithNamedInput() {
        long subjectId = TestFixtures.createSubject("inputs-no-event");
        long defId = TestFixtures.createDefinition("Needs Event", content(EVENT_REQUIRED));
        given().contentType(ContentType.JSON).body("{\"workflowDefinitionId\": %d}".formatted(defId))
                .when().post("/api/v1/subjects/" + subjectId + "/workflow")
                .then().statusCode(400)
                .body("message", equalTo(
                        "Cannot start workflow \"Needs Event\": required input(s) not available: event"));
        long runs = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunEntity.count("subjectId = ?1 and definitionId = ?2", subjectId, defId));
        assertEquals(0, runs);
    }

    @Test
    void failureMessageIsTheExceptionMessage() {
        long subjectId = TestFixtures.createSubject("inputs-no-event-msg");
        long defId = TestFixtures.createDefinition("Needs Event Msg", content(EVENT_REQUIRED));
        WebApplicationException e = assertThrows(WebApplicationException.class, () ->
                QuarkusTransaction.requiringNew().call(() ->
                        service.triggerWorkflow(subjectId, defId, RunRequest.EMPTY, null)));
        assertTrue(e.getMessage().contains("required input(s) not available: event"), e.getMessage());
    }

    @Test
    void optionalEventIsAbsentWithoutAnEvent() {
        long subjectId = TestFixtures.createSubject("inputs-optional-event");
        long defId = TestFixtures.createDefinition("Optional Event", content(EVENT_OPTIONAL));
        given().contentType(ContentType.JSON).body("{\"workflowDefinitionId\": %d}".formatted(defId))
                .when().post("/api/v1/subjects/" + subjectId + "/workflow")
                .then().statusCode(200).body("context.event", nullValue());
    }

    @Test
    void eventStartedRunGetsTheEventButNotInItsStoredInputs() {
        UUID eventId = event();
        long defId = TestFixtures.createDefinition("Event Run",
                content("[{\"name\": \"subject\", \"type\": \"object\", \"required\": true},"
                        + " {\"name\": \"event\", \"type\": \"object\", \"required\": true}]"));
        given().contentType(ContentType.JSON).body("{\"workflowDefinitionId\": %d}".formatted(defId))
                .when().post("/api/v1/stream/events/" + eventId + "/runs")
                .then().statusCode(200)
                .body("context.event.type", equalTo("issue.created"))
                .body("context.event.payload.n", equalTo(5))
                .body("inputs.event", nullValue())
                .body("inputs.subject.id", org.hamcrest.Matchers.notNullValue());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl app -am test -Dtest=WorkflowTriggerInputGuardTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: failures (e.g. `context.subject` is null and `context.ref` is set; required `event` returns the old
engine message).

- [ ] **Step 3: Change `RunRequest`** — replace the record with:

```java
package io.apitomy.axiom.app;

import java.util.Map;

/**
 * Optional parameters for starting a workflow run. A run's inputs are not supplied: Axiom resolves the standard
 * inputs its Start node declares (see {@link StandardInputs}).
 *
 * @param title  run title; null for none
 * @param labels run labels; null for none
 */
public record RunRequest(String title, Map<String, String> labels) {

    /** An empty request. */
    public static final RunRequest EMPTY = new RunRequest(null, null);
}
```

- [ ] **Step 4: Rewrite context building in `WorkflowExecutionService`**

1. Delete the `PROTECTED_INPUTS` constant and the whole `applyStartInputDefaults` method (with its Javadoc).
2. Delete the two `triggerWorkflow` overloads that take `Map<String, Object> extraContext` (the ones at
   ~lines 139 and 157, with their Javadoc). Keep `triggerWorkflow(long, long, RunRequest, EventOrigin)` and
   `triggerWorkflow(long, long)`.
3. In `startRun`, replace everything from `Map<String, Object> context = new HashMap<>();` through
   `applyStartInputDefaults(workflow, context);` with:

```java
        JsonNode eventValue = null;
        if (origin != null && origin.eventId() != null) {
            StreamEventEntity event = StreamEventEntity.findById(origin.eventId());
            if (event != null) {
                eventValue = StandardInputs.eventValue(event, objectMapper);
            }
        }
        StandardInputs.Resolution resolution = StandardInputs.resolve(workflow, subject, eventValue);
        if (!resolution.missingRequired().isEmpty()) {
            String message = "Cannot start workflow \"" + definition.name + "\": required input(s) not available: "
                    + String.join(", ", resolution.missingRequired());
            // The message constructor keeps getMessage() meaningful for schedule trigger outcomes.
            throw new WebApplicationException(message,
                    Response.status(400).entity(Map.of("message", message)).build());
        }
        Map<String, Object> context = new HashMap<>(resolution.context());
```

4. Replace `entity.inputs = toJsonOrNull(request.inputs());` with:

```java
        Map<String, Object> storedInputs = new LinkedHashMap<>(resolution.context());
        storedInputs.remove(StandardInputs.EVENT); // the event is linked as the run's trigger
        entity.inputs = storedInputs.isEmpty() ? null : toJsonOrNull(storedInputs);
```

5. Add imports as needed: `com.fasterxml.jackson.databind.JsonNode`,
   `io.apitomy.axiom.core.entities.StreamEventEntity`, `java.util.LinkedHashMap`. Remove imports that become
   unused (`NodeConfig` only if no longer referenced elsewhere in the file — check with grep).

- [ ] **Step 5: Update the callers**

`EventStreamOrchestrator` — in the private `startRunFromEvent(StreamEventEntity, Long, long, RunRequest, Long)`
delete the event-node building (the `extraContext` map, the try/catch that builds `eventNode`, and the
`effective` request) so the method body becomes:

```java
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, definitionId, request,
                        new EventOrigin(event.id, ledgerId)));
        LOG.infof("Created workflow (definition %d) for event %s on subject %d",
                definitionId, event.id, subjectId);

        sseEvents.fire(SseEvent.subjectUpdated(subjectId));
        return run;
```

Update both Javadocs of `startRunFromEvent` (`@param request run title and labels`; "the run's `event` input
is the event when its Start node declares `event`"). In `routeToCreateWorkflow` replace
`new RunRequest(null, null, null, null)` with `RunRequest.EMPTY`.

`SubjectsResourceImpl.triggerSubjectWorkflow` — build the request without inputs:

```java
        RunRequest request = new RunRequest(data.getTitle(),
                toStringMap(data.getLabels() != null ? data.getLabels().getAdditionalProperties() : null));
```

`StreamEventsResourceImpl.triggerStreamEventWorkflow` — `RunRequest request = new RunRequest(data.getTitle(),
labels);`

`WorkflowDefinitionsResourceImpl.rerunWorkflowRun` — delete the `inputs` lines and build
`new io.apitomy.axiom.app.RunRequest(original.title, labels)`. Keep the event/no-event branching.

`WorkflowScheduleTriggerScheduler.startRun` — temporarily ignore `attempt.inputs()` (Task 3 removes it):

```java
    private void startRun(Attempt attempt) {
        workflowExecutionService.triggerWorkflow(attempt.subjectId(), attempt.definitionId(),
                new RunRequest(null, Map.of("trigger", "schedule")), null);
    }
```

(Remove the now-unused `TypeReference` import if nothing else uses it.)

- [ ] **Step 6: Fix the remaining tests**

Run `mvn -q -pl app -am test-compile 2>&1 | grep ERROR` and fix every compile error:
- `new RunRequest(a, b, c, d)` → `new RunRequest(a, b)` (drop inputs and extra context).
- Calls to the removed `triggerWorkflow(..., Map extraContext, ...)` overloads: if the test only needed an
  event, start the run through `orchestrator.startRunFromEvent(event, definitionId, RunRequest.EMPTY, null)`
  with a persisted `StreamEventEntity` (see `event()` in Step 1) and declare `event` on the Start node; if it
  passed other keys, the behaviour no longer exists — delete that test.

Then update behaviour tests:
- `WorkflowRunMetadataTest`: delete `contextContainsSubjectNameAndRepository`,
  `suppliedInputsCannotOverrideRepositoryOrSubjectName`, `extraContextCannotOverrideRepository` and
  `nullRepositoryYieldsEmptyString` (covered by Task 1 and Step 1); in `suppliedMetadataIsPersisted` drop the
  inputs part and assert only title and labels.
- `WorkflowInstanceResourceTest`: delete `appliesStartInputDefaultsForInputsNotSupplied` and any test that
  posts `inputs`.
- `WorkflowScheduleTriggerSchedulerTest.firstFiringCreatesSubjectAndStartsRun`: delete the line
  `assertTrue(runs.get(0).inputs.contains("high"), "static inputs are passed to the run");` (the trigger's
  inputs are now ignored; Task 3 removes them).
- Any test workflow whose nodes read `context.ref`, `context.subjectId`, `context.subjectName` or
  `context.repository` must declare `subject` on its Start node and read `context.subject.<field>` instead;
  `WorkflowRunTitleTest.workflowSdkNodeSetsItsOwnRunTitle` changes its title expression to
  `'Checking ' += context.subject.name` and declares `subject`.

- [ ] **Step 7: Run the full backend suite**

Run: `mvn -q -pl app -am test > /tmp/opencode/apptest.log 2>&1; grep -L "Failures: 0, Errors: 0" app/target/surefire-reports/*.txt`
Expected: no file names printed. Fix any failure caused by tests relying on the old always-injected keys
(same rule as Step 6).

- [ ] **Step 8: Commit**

```bash
git add app && git commit -m "feat(workflows): start runs with only the declared standard inputs"
```

---

### Task 3: Remove supplied run inputs from the API, database and UI

**Files:**
- Modify: `common/api/src/main/resources/openapi.json` (schemas `TriggerWorkflow`, `NewScheduleTrigger`,
  `ScheduleTrigger`; descriptions mentioning inputs on the rerun and run-creation operations)
- Modify: `app/src/main/resources/db/migration/V1__baseline.sql` (table `workflow_schedule_trigger`)
- Modify: `core/src/main/java/io/apitomy/axiom/core/entities/WorkflowScheduleTriggerEntity.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/WorkflowDefinitionsResourceImpl.java` (~280-307)
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowScheduleTriggerScheduler.java` (`Attempt` ~49, ~142)
- Modify: `ui/src/config/api.ts`, `ui/src/components/RunWorkflowFromEventModal.tsx`,
  `ui/src/components/WorkflowTriggersTab.tsx` and their `.test.tsx` files
- Test: `app/src/test/java/io/apitomy/axiom/app/rest/WorkflowScheduleTriggersResourceTest.java`

**Interfaces:**
- Consumes: Task 2's `RunRequest(title, labels)`.
- Produces: no `inputs` on `TriggerWorkflow`, `NewScheduleTrigger`, `ScheduleTrigger` (generated beans lose
  `getInputs`/`setInputs`); `WorkflowInstance.inputs` and `WorkflowRunSummary.inputs` stay (resolved inputs).

- [ ] **Step 1: Write failing tests**

Backend — in `app/src/test/java/io/apitomy/axiom/app/rest/WorkflowScheduleTriggersResourceTest.java`,
`createAndListTrigger`: keep the request body as is (it still sends `"inputs": {"priority": "high"}`, which
must now be ignored) and replace `.body("inputs.priority", equalTo("high"))` with
`.body("inputs", nullValue())`.

In `app/src/test/java/io/apitomy/axiom/app/WorkflowScheduleTriggerSchedulerTest.java` add (it already has
`scheduler`, `createTrigger`, `loadTrigger`, `TestFixtures`):

```java
    /** A trigger whose workflow requires an event cannot start a run: the outcome names the missing input. */
    @Test
    void requiredEventFailsTheFiring() {
        long definitionId = TestFixtures.createDefinition("sched-needs-event-" + UUID.randomUUID(), """
            {"id": "sched-ev", "name": "Needs Event",
             "nodes": [
               {"id": "s1", "type": "start", "name": "Start", "position": {"x": 0, "y": 0},
                "config": {"inputs": [{"name": "event", "type": "object", "required": true}]}},
               {"id": "e1", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 100}}],
             "edges": [{"id": "x1", "source": "s1", "target": "e1", "priority": 0, "isDefault": false}]}
            """);
        long triggerId = createTrigger(definitionId, "Needs event " + UUID.randomUUID(), true);

        scheduler.fire(triggerId, Instant.now());

        String outcome = loadTrigger(triggerId).lastOutcome;
        assertTrue(outcome.startsWith("failed: Cannot start workflow"), outcome);
        assertTrue(outcome.endsWith("required input(s) not available: event"), outcome);
    }
```

and change the helper to `createTrigger(long definitionId, String subjectName, boolean enabled)` (delete the
`inputs` parameter and the `trigger.inputs = inputs;` line), updating every call to drop its last argument.
Until Step 3 this does not compile — that is the expected failure.

UI — in `ui/src/components/RunWorkflowFromEventModal.test.tsx`:
- in "sends the workflow, title, labels and inputs on Run": rename to "sends the workflow, title and labels
  on Run", delete the `userEvent.type(screen.getByLabelText("Inputs"), ...)` line and the `inputs: { x: 1 }`
  property of the expected body, and add before the click:
  `expect(screen.queryByLabelText("Inputs")).toBeNull();`
- delete the test "blocks Run while the inputs are not a JSON object";
- in the `parseLabels` test delete the three `parseInputs` assertions and remove `parseInputs` from the
  import.

In `ui/src/components/WorkflowTriggersTab.test.tsx`, "lists triggers and opens the add modal", add at the end:

```tsx
        expect(within(dialog).queryByRole("textbox", { name: /Inputs/ })).toBeNull();
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl app -am test -Dtest=WorkflowScheduleTriggersResourceTest -Dsurefire.failIfNoSpecifiedTests=false`
and `cd ui && timeout 280 npx vitest run src/components/RunWorkflowFromEventModal.test.tsx src/components/WorkflowTriggersTab.test.tsx`
Expected: the new assertions fail.

- [ ] **Step 3: Contract and backend**

Edit `openapi.json` programmatically (load, change, dump with `indent=2, ensure_ascii=False`, keep the trailing
newline) and check `git diff --stat` shows only the intended lines:
- delete `properties.inputs` from `TriggerWorkflow`, `NewScheduleTrigger` and `ScheduleTrigger`;
- in the `rerunWorkflowRun` description replace "with the original title, labels (plus rerunOf=<runId>) and
  inputs" with "with the original title and labels (plus rerunOf=<runId>)";
- set `WorkflowInstance.properties.inputs.description` and `WorkflowRunSummary.properties.inputs.description`
  to `"The standard inputs the run was started with (excluding event)"`.

`V1__baseline.sql`: delete the `inputs TEXT,` line of `create table workflow_schedule_trigger`.
`WorkflowScheduleTriggerEntity`: delete the `inputs` field and its Javadoc.
`WorkflowDefinitionsResourceImpl`: delete the trigger-inputs write (~283-286, including its try/catch if it
only guarded inputs) and read (~298-307); remove the `Inputs` import if unused.
`WorkflowScheduleTriggerScheduler`: `private record Attempt(long subjectId, long definitionId)` and
`return new Attempt(subject.id, trigger.definitionId);`.

- [ ] **Step 4: UI**

`api.ts`: delete `inputs?` from `TriggerStreamEventWorkflowRequest` and `NewScheduleTrigger`; update the
`triggerStreamEventWorkflow` Javadoc (`@param data the workflow and optional title and labels`) and the
rerun Javadoc (~line 1755: "with the original title and labels (plus rerunOf)").
`RunWorkflowFromEventModal.tsx`: delete `parseInputs`, the `inputsText` state and its reset, the `inputs`
parse, its part of `canRun`, the `body.inputs` line and the Inputs `FormGroup`.
`WorkflowTriggersTab.tsx`: delete the `inputs` state, JSON parsing/validation, the `inputs` key in `onSave`
and the "Inputs (JSON)" `FormGroup`; if the table shows trigger inputs, remove that column too
(`grep -n "inputs" ui/src/components/WorkflowTriggersTab.tsx` must print nothing).

- [ ] **Step 5: Run the tests**

Run: `mvn -q -pl app -am test > /tmp/opencode/apptest.log 2>&1; grep -L "Failures: 0, Errors: 0" app/target/surefire-reports/*.txt`
Expected: nothing printed.
Run: `cd ui && timeout 250 npx tsc -b --noEmit && timeout 280 npx vitest run 2>&1 | grep -E "FAIL|Tests "`
Expected: `Tests  N passed (N)`, no FAIL.

- [ ] **Step 6: Commit**

```bash
git add common/api/src/main/resources/openapi.json app core ui && git commit -m "feat(workflows): remove supplied run inputs from runs, schedule triggers and the UI"
```

---

### Task 4: Start input publish rules and the new-workflow scaffold

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/validation/WorkflowContentRules.java`
  (`OPTIONAL_BUILT_IN_INPUTS`, `startInputViolation`, `BUILT_IN_CONTEXT_KEYS`)
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowTemplates.java`
- Create test: `app/src/test/java/io/apitomy/axiom/app/validation/WorkflowContentRulesStartInputsTest.java`
- Modify test: `app/src/test/java/io/apitomy/axiom/app/assistant/WorkbenchReferenceTest.java` (it holds the
  existing `emptyWorkflow` scaffold test)

**Interfaces:**
- Consumes: `StandardInputs.find`, `StandardInputs.names()`, `StandardInputs.ALL`.
- Produces: `WorkflowContentRules.startInputViolation(Workflow)` (same signature) with the new rules;
  `WorkflowContentRules.BUILT_IN_CONTEXT_KEYS` = `StandardInputs.names()`; `OPTIONAL_BUILT_IN_INPUTS` removed
  (Task 6 stops using it in the reference generator — do Task 6's one-line generator change here if the build
  breaks: replace the "Start inputs: names must match …" sentence with
  `.append("Start inputs: see Start inputs below.\n\n")`).

- [ ] **Step 1: Write the failing tests**

```java
package io.apitomy.axiom.app.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.flow.model.Workflow;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Start input publish rules: only standard inputs, with their types, and no defaults. */
class WorkflowContentRulesStartInputsTest {

    private static Optional<String> violation(String inputs) throws Exception {
        Workflow wf = new ObjectMapper().readValue("""
            {"id": "w", "name": "W",
             "nodes": [{"id": "s", "type": "start", "name": "Start", "config": {"inputs": %s}, "position": {"x": 0, "y": 0}},
                       {"id": "e", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 100}}],
             "edges": [{"id": "x", "source": "s", "target": "e", "priority": 0, "isDefault": false}]}
            """.formatted(inputs), Workflow.class);
        return WorkflowContentRules.startInputViolation(wf);
    }

    @Test
    void standardInputsAreValidAndEventMayBeRequired() throws Exception {
        assertTrue(violation("[{\"name\": \"subject\", \"type\": \"object\", \"required\": true},"
                + " {\"name\": \"event\", \"type\": \"object\", \"required\": true}]").isEmpty());
        assertTrue(violation("[{\"name\": \"event\", \"required\": false}]").isEmpty());
    }

    @Test
    void nonStandardNameIsRejected() throws Exception {
        assertEquals(Optional.of("Start node input 'ref' is not a standard input. A Start node may declare only: "
                + "subject, event."), violation("[{\"name\": \"ref\", \"type\": \"string\"}]"));
    }

    @Test
    void wrongTypeIsRejected() throws Exception {
        assertEquals(Optional.of("Start node input 'subject' must have type 'object', not 'string'."),
                violation("[{\"name\": \"subject\", \"type\": \"string\"}]"));
    }

    @Test
    void defaultValueIsRejected() throws Exception {
        assertEquals(Optional.of("Start node input 'event' cannot declare a defaultValue; Axiom provides "
                + "standard inputs itself."),
                violation("[{\"name\": \"event\", \"type\": \"object\", \"defaultValue\": {}}]"));
    }
}
```

In `WorkbenchReferenceTest`, add:

```java
    @Test
    void newWorkflowsDeclareSubjectAndEventBothRequired() {
        com.fasterxml.jackson.databind.node.ObjectNode wf =
                io.apitomy.axiom.app.WorkflowTemplates.emptyWorkflow("X", new com.fasterxml.jackson.databind.ObjectMapper());
        com.fasterxml.jackson.databind.JsonNode inputs = wf.path("nodes").get(0).path("config").path("inputs");
        assertEquals(2, inputs.size());
        assertEquals("subject", inputs.get(0).path("name").asText());
        assertEquals("object", inputs.get(0).path("type").asText());
        assertTrue(inputs.get(0).path("required").asBoolean());
        assertEquals("event", inputs.get(1).path("name").asText());
        assertTrue(inputs.get(1).path("required").asBoolean());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl app -am test -Dtest='WorkflowContentRulesStartInputsTest,WorkbenchReferenceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL (e.g. `ref` accepted; scaffold declares 3 inputs).

- [ ] **Step 3: Implement the rules** — in `WorkflowContentRules` delete `OPTIONAL_BUILT_IN_INPUTS` and replace
the body of `startInputViolation`'s loop (after the `INPUT_NAME_PATTERN` check, which stays) with:

```java
            Optional<StandardInputs.StandardInput> standard = StandardInputs.find(name);
            if (standard.isEmpty()) {
                return Optional.of("Start node input '" + name + "' is not a standard input. A Start node may "
                        + "declare only: " + StandardInputs.ALL.stream().map(StandardInputs.StandardInput::name)
                                .collect(java.util.stream.Collectors.joining(", ")) + ".");
            }
            Object type = input.get("type");
            if (type != null && !type.toString().isBlank() && !standard.get().type().equals(type.toString())) {
                return Optional.of("Start node input '" + name + "' must have type '" + standard.get().type()
                        + "', not '" + type + "'.");
            }
            if (input.containsKey("defaultValue") && input.get("defaultValue") != null) {
                return Optional.of("Start node input '" + name + "' cannot declare a defaultValue; Axiom "
                        + "provides standard inputs itself.");
            }
```

Update its Javadoc ("every name must be a standard input with the standard type and no defaultValue").
Replace `BUILT_IN_CONTEXT_KEYS` with:

```java
    /** Context keys a run can be given at start: the standard input names. */
    public static final Set<String> BUILT_IN_CONTEXT_KEYS = StandardInputs.names();
```

Import `io.apitomy.axiom.app.StandardInputs`. Fix any other user of `OPTIONAL_BUILT_IN_INPUTS`
(`grep -rn OPTIONAL_BUILT_IN_INPUTS app/src`) — the assistant reference generator: replace its
"Start inputs: names must match … may not be marked required: …" sentence as noted in Interfaces.

- [ ] **Step 4: Implement the scaffold** — in `WorkflowTemplates.emptyWorkflow` replace the three `input(...)`
calls with:

```java
        for (StandardInputs.StandardInput standard : StandardInputs.ALL) {
            input(inputs, standard.name(), standard.type(), true, standard.description());
        }
```

and update the method Javadoc ("declaring every standard input, all required"). Import
`io.apitomy.axiom.app.StandardInputs` if the class is in another package (it is in the same package —
`io.apitomy.axiom.app` — so no import).

- [ ] **Step 5: Run tests**

Run: `mvn -q -pl app -am test > /tmp/opencode/apptest.log 2>&1; grep -L "Failures: 0, Errors: 0" app/target/surefire-reports/*.txt`
Expected: nothing printed. Tests that published workflows declaring `subjectId`/`ref`/custom inputs or
`defaultValue` (e.g. `WorkflowDefinitionsResourceTest` around lines 301-379, `ActionResourceTest`) must be
updated to declare `subject`/`event` instead, or deleted when they tested custom inputs or defaults.

- [ ] **Step 6: Commit**

```bash
git add app && git commit -m "feat(workflows): allow only standard Start inputs and scaffold subject and event as required"
```

---

### Task 5: `SHADOWS_WORKFLOW_INPUT` warning

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/validation/ValidationContext.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/validation/ValidationContextFactory.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/validation/WorkflowContentRules.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/validation/ConfigItemValidationService.java`
  (`validateWorkflow`, after the `inputMappingProblems` block)
- Test: `app/src/test/java/io/apitomy/axiom/app/validation/ConfigItemValidationServiceTest.java`

**Interfaces:**
- Produces:
  - `ValidationContext` gains a 9th component `Map<String, Set<String>> actionTypeOutputs` (action type name
    → declared output names; null disables the action-type part of the check). Keep an 8-argument
    constructor that passes `null` for it so existing callers compile:

```java
    public ValidationContext(Set<String> toolNames, Set<String> toolsetNames, Set<String> actionTypeNames,
                             Set<String> workflowDefinitionNames, Set<Long> workflowDefinitionIds,
                             Set<String> eventSourceIds, Set<String> secretNames,
                             Map<String, Map<String, Boolean>> actionTypeInputs) {
        this(toolNames, toolsetNames, actionTypeNames, workflowDefinitionNames, workflowDefinitionIds,
                eventSourceIds, secretNames, actionTypeInputs, null);
    }
```

  - `record WorkflowContentRules.ShadowProblem(int nodeIndex, String key, String message)` with
    `String path()` returning `"content.nodes[" + nodeIndex + "].config"`.
  - `List<ShadowProblem> WorkflowContentRules.shadowProblems(Workflow workflow,
    Map<String, Set<String>> actionTypeOutputs)`.

- [ ] **Step 1: Write the failing test** — add to `ConfigItemValidationServiceTest` (it builds workflows as
JSON items and calls the service; follow the existing workflow tests in that file for the helper that builds
an item and the `service` field):

```java
    @Test
    void warnsWhenANodeOutputShadowsADeclaredInput() throws Exception {
        String content = """
            {"id": "w", "name": "W",
             "nodes": [
               {"id": "s", "type": "start", "name": "Start", "position": {"x": 0, "y": 0},
                "config": {"inputs": [{"name": "event", "type": "object", "required": true}]}},
               {"id": "r", "type": "receive-event", "name": "Await", "position": {"x": 0, "y": 100},
                "config": {"eventType": "issue.comment.created", "outputs": [{"contextKey": "event", "expression": "event"}]}},
               {"id": "h", "type": "human-task", "name": "Review", "position": {"x": 0, "y": 200},
                "config": {"outputs": [{"name": "subject", "type": "string"}]}},
               {"id": "e", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 300}}],
             "edges": [
               {"id": "x1", "source": "s", "target": "r", "priority": 0, "isDefault": false},
               {"id": "x2", "source": "r", "target": "h", "priority": 0, "isDefault": false},
               {"id": "x3", "source": "h", "target": "e", "priority": 0, "isDefault": false}]}
            """;
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW,
                json("{\"name\": \"W\", \"content\": " + content + "}"), ValidationContext.unchecked());
        List<ConfigProblem> shadows = problems.stream()
                .filter(p -> p.code().equals("SHADOWS_WORKFLOW_INPUT")).toList();
        assertEquals(1, shadows.size(), problems.toString()); // 'subject' is not declared, so only 'event'
        assertEquals("warning", shadows.get(0).severity());
        assertEquals("content.nodes[1].config", shadows.get(0).path());
        assertEquals("Output 'event' of node 'Await' overwrites the workflow input 'event' for later nodes.",
                shadows.get(0).message());
    }

    @Test
    void warnsWhenAnActionTypeOutputShadowsADeclaredInput() throws Exception {
        String content = """
            {"id": "w", "name": "W",
             "nodes": [
               {"id": "s", "type": "start", "name": "Start", "position": {"x": 0, "y": 0},
                "config": {"inputs": [{"name": "subject", "type": "object", "required": true}]}},
               {"id": "a", "type": "action", "name": "Lookup", "position": {"x": 0, "y": 100},
                "config": {"actionType": "lookup"}},
               {"id": "e", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 200}}],
             "edges": [
               {"id": "x1", "source": "s", "target": "a", "priority": 0, "isDefault": false},
               {"id": "x2", "source": "a", "target": "e", "priority": 0, "isDefault": false}]}
            """;
        ValidationContext ctx = new ValidationContext(Set.of(), Set.of(), Set.of("lookup"), Set.of(), Set.of(),
                Set.of(), Set.of(), Map.of("lookup", Map.of()), Map.of("lookup", Set.of("subject", "other")));
        List<ConfigProblem> shadows = service.validate(ConfigItemType.WORKFLOW,
                json("{\"name\": \"W\", \"content\": " + content + "}"), ctx).stream()
                .filter(p -> p.code().equals("SHADOWS_WORKFLOW_INPUT")).toList();
        assertEquals(1, shadows.size());
        assertEquals("Output 'subject' of node 'Lookup' overwrites the workflow input 'subject' for later nodes.",
                shadows.get(0).message());
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl app -am test -Dtest=ConfigItemValidationServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the two new tests fail (no `SHADOWS_WORKFLOW_INPUT` problems; or compile error for the 9-arg
constructor).

- [ ] **Step 3: Implement**

`ValidationContext`: add the 9th component and the 8-argument constructor above; update `unchecked()` to
pass nine nulls; Javadoc: "`actionTypeOutputs` maps action type name to declared output names; null disables
the action-type part of the shadowing check."

`ValidationContextFactory`: add, next to `actionTypeInputs`,

```java
    /**
     * Builds declared output names for the given action types.
     *
     * @param actionTypes the action type entities
     * @return action type name to output names
     */
    public static Map<String, Set<String>> actionTypeOutputs(List<ActionTypeEntity> actionTypes) {
        Map<String, Set<String>> result = new HashMap<>();
        for (ActionTypeEntity at : actionTypes) {
            Set<String> outputs = new HashSet<>();
            if (at.outputs != null) {
                at.outputs.forEach(f -> outputs.add(f.name));
            }
            result.put(at.name, outputs);
        }
        return result;
    }

    private static Map<String, Set<String>> pendingOutputs(Iterable<JsonNode> actionTypes) {
        Map<String, Set<String>> result = new HashMap<>();
        if (actionTypes == null) {
            return result;
        }
        for (JsonNode item : actionTypes) {
            String name = item.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            Set<String> outputs = new HashSet<>();
            item.path("outputs").forEach(o -> {
                String n = o.path("name").asText("");
                if (!n.isBlank()) {
                    outputs.add(n);
                }
            });
            result.put(name, outputs);
        }
        return result;
    }

    private static Map<String, Set<String>> overlayOutputs(Map<String, Set<String>> base,
                                                          Map<String, Set<String>> pending) {
        Map<String, Set<String>> result = new HashMap<>(base != null ? base : Map.of());
        result.putAll(pending);
        return result;
    }
```

and pass a 9th argument in every `new ValidationContext(...)` in the factory: `actionTypeOutputs(actionTypes)`
in `databaseOnly()`, and `overlayOutputs(db.actionTypeOutputs(), pendingOutputs(<same source as
pendingInputs>))` in `forPack`, `forItems` and `forDirectory`.

`WorkflowContentRules`: add

```java
    /**
     * A node output that overwrites a declared workflow input.
     *
     * @param nodeIndex the node's index in {@code content.nodes}
     * @param key       the context key written
     * @param message   the warning message
     */
    public record ShadowProblem(int nodeIndex, String key, String message) {

        /** @return the JSON path of the node's config relative to the workflow item */
        public String path() {
            return "content.nodes[" + nodeIndex + "].config";
        }
    }

    /**
     * Finds nodes whose statically known outputs would overwrite an input declared on the Start node: action
     * node outputs (renamed by their {@code contextKey}), the action type's declared outputs the node does not
     * rename, human-task outputs and receive-event output mappings. {@code sdk:} results are not declared and
     * are not checked.
     *
     * @param workflow          the workflow
     * @param actionTypeOutputs action type name to declared output names; null to skip action type outputs
     * @return one problem per node and overwritten input
     */
    public static List<ShadowProblem> shadowProblems(Workflow workflow, Map<String, Set<String>> actionTypeOutputs) {
        List<ShadowProblem> problems = new ArrayList<>();
        List<WorkflowNode> nodes = workflow.nodes() != null ? workflow.nodes() : List.of();
        Set<String> declared = new HashSet<>();
        for (WorkflowNode node : nodes) {
            if (node.type() == NodeType.START && node.config() != null) {
                try {
                    if (node.typedConfig() instanceof NodeConfig.Start start) {
                        start.inputs().forEach(f -> addKey(declared, f.name()));
                    }
                } catch (RuntimeException e) {
                    // Malformed config is reported by the engine validator.
                }
            }
        }
        if (declared.isEmpty()) {
            return problems;
        }
        for (int i = 0; i < nodes.size(); i++) {
            WorkflowNode node = nodes.get(i);
            Set<String> written = new java.util.LinkedHashSet<>();
            try {
                switch (node.typedConfig()) {
                    case NodeConfig.Action action -> {
                        Set<String> renamed = new HashSet<>();
                        action.outputs().forEach(f -> {
                            addKey(written, f.effectiveContextKey());
                            addKey(renamed, f.name());
                        });
                        if (actionTypeOutputs != null && action.actionType() != null) {
                            actionTypeOutputs.getOrDefault(action.actionType(), Set.of()).stream()
                                    .filter(o -> !renamed.contains(o)).forEach(o -> addKey(written, o));
                        }
                    }
                    case NodeConfig.HumanTask task -> task.outputs().forEach(f -> addKey(written, f.effectiveContextKey()));
                    case NodeConfig.ReceiveEvent receive -> receive.outputs().forEach(m -> addKey(written, m.contextKey()));
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                continue;
            }
            for (String key : written) {
                if (declared.contains(key)) {
                    problems.add(new ShadowProblem(i, key, "Output '" + key + "' of node '" + node.name()
                            + "' overwrites the workflow input '" + key + "' for later nodes."));
                }
            }
        }
        return problems;
    }
```

`ConfigItemValidationService.validateWorkflow`: after the `inputMappingProblems` block add

```java
        for (WorkflowContentRules.ShadowProblem p
                : WorkflowContentRules.shadowProblems(workflow, context.actionTypeOutputs())) {
            problems.add(ConfigProblem.warning(p.path(), "SHADOWS_WORKFLOW_INPUT", p.message()));
        }
```

- [ ] **Step 4: Run tests**

Run: `mvn -q -pl app -am test > /tmp/opencode/apptest.log 2>&1; grep -L "Failures: 0, Errors: 0" app/target/surefire-reports/*.txt`
Expected: nothing printed.

- [ ] **Step 5: Commit**

```bash
git add app && git commit -m "feat(validation): warn when a node output overwrites a declared workflow input"
```

---

### Task 6: Assistant reference, validator hints and bundled content

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantReferenceGenerator.java`
  (`NODE_CONFIG_DOCS` START/ACTION/HUMAN_TASK text ~lines 46-62; `appendWorkflows` ~194-232; SDK example
  ~262)
- Modify: `core/src/main/java/io/apitomy/axiom/core/services/ActionTypeValidator.java` (hints ~lines 30-40)
- Modify: `app/src/main/resources/templates/assistant-reference/examples/workflows/issue-triage.json`
- Modify: `app/src/main/resources/templates/assistant-templates/axiom-config-assistant.json`
- Modify: `app/src/main/resources/templates/assistant-templates/axiom-workbench.json` (one rule line)
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/AssistantReferenceTest.java`,
  `WorkbenchReferenceTest.java`, `app/src/test/java/io/apitomy/axiom/core/services/ActionTypeValidatorTest.java`

**Interfaces:**
- Consumes: `StandardInputs.ALL`, `StandardInputs.SUBJECT_FIELDS`.

- [ ] **Step 1: Write the failing tests** — add to `AssistantReferenceTest`:

```java
    @Test
    void referenceDocumentsStandardStartInputs() {
        String ref = generator.generate();
        assertTrue(ref.contains("### Start inputs"), ref);
        assertTrue(ref.contains("| `subject` | object | always |"), ref);
        assertTrue(ref.contains("| `event` | object | only when the run was started from a stream event |"), ref);
        assertTrue(ref.contains("`subject` fields: `id`, `name`, `type`, `ref`, `refSource`, `repository`"), ref);
        assertTrue(ref.contains("Declare only these inputs"), ref);
        assertTrue(ref.contains("SHADOWS_WORKFLOW_INPUT"), ref);
        assertFalse(ref.contains("defaultValue"), ref);
        assertFalse(ref.contains("context.ref"), ref);
        assertFalse(ref.contains("context.subjectName"), ref);
        assertTrue(ref.contains("context.subject.name"), ref);
    }
```

Add to `app/src/test/java/io/apitomy/axiom/core/services/ActionTypeValidatorTest.java`:

```java
    @Test
    void removedPlaceholderHintsUseTheSubjectInput() {
        assertEquals("Declare an input 'ref' and map it from context.subject.ref on the workflow node.",
                ActionTypeValidator.REMOVED_PLACEHOLDER_HINTS.get("ref"));
        assertEquals("Declare an input 'subjectId' and map it from context.subject.id on the workflow node.",
                ActionTypeValidator.REMOVED_PLACEHOLDER_HINTS.get("subjectId"));
        assertTrue(ActionTypeValidator.REMOVED_PLACEHOLDER_HINTS.values().stream()
                .noneMatch(h -> h.matches(".*context\\.(ref|repository|subjectName|subjectId)\\b.*")));
    }
```

(add `assertEquals`/`assertTrue` static imports if missing).

In `WorkbenchReferenceTest.promptCarriesEveryRule`, add this string to the `List.of(...)` of rules:

```java
                "- Declare only the standard Start inputs (subject, event); require event only when the workflow "
                        + "must be started from an event.",
```

The bundled examples are already covered by `AssistantReferenceTest.everyExampleValidatesWithoutProblems`,
which fails once Task 4's rules reject the old `issue-triage.json` Start inputs.

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl app -am test -Dtest='AssistantReferenceTest,WorkbenchReferenceTest,ActionTypeValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the new assertions fail.

- [ ] **Step 3: Implement**

`NODE_CONFIG_DOCS`:
- START: `"Exactly one per workflow. `config.inputs`: array of `{name, type, required, description}` declaring "
  + "which standard inputs the run receives (see Start inputs below)."`
- ACTION: replace `` `context.ref` `` with `` `context.subject.ref` ``.
- HUMAN_TASK: replace `` `'Review ' += context.ref` `` with `` `'Review ' += context.subject.ref` ``.

`appendWorkflows`: replace the "Start inputs: names must match …" sentence with a call to a new
`appendStartInputs(sb);` placed before `appendSdkActionNodes(sb);`:

```java
    /** Documents the standard Start inputs, generated from {@link StandardInputs}. */
    private static void appendStartInputs(StringBuilder sb) {
        sb.append("### Start inputs\n\n")
                .append("A run receives only the inputs its Start node declares. Declare only these inputs; no ")
                .append("other names and no `defaultValue`:\n\n")
                .append("| Input | Type | Available |\n|---|---|---|\n");
        for (StandardInputs.StandardInput input : StandardInputs.ALL) {
            sb.append("| `").append(input.name()).append("` | ").append(input.type()).append(" | ")
                    .append(input.availability()).append(" |\n");
        }
        sb.append("\n`subject` fields: ")
                .append(StandardInputs.SUBJECT_FIELDS.stream().map(f -> "`" + f + "`").collect(Collectors.joining(", ")))
                .append(" (missing values are null). Read them as `context.subject.<field>`, e.g. ")
                .append("`context.subject.ref`; read the event as `context.event.<field>`, e.g. ")
                .append("`context.event.payload`.\n\n")
                .append("- Mark `event` required only if the workflow must be started from an event; a ")
                .append("schedule-triggered workflow must not require it.\n")
                .append("- A required input that is not available stops the run from being created.\n")
                .append("- Do not give a node output the name of a declared input: it overwrites the input for ")
                .append("later nodes (validation warns with `SHADOWS_WORKFLOW_INPUT`).\n\n");
    }
```

SDK example (~line 262): `'Checking ' += context.subjectName` → `'Checking ' += context.subject.name`, and the
example node is preceded by a sentence noting the workflow declares `subject`.

`ActionTypeValidator` hints: `context.ref` → `context.subject.ref`, `context.repository` →
`context.subject.repository`, `context.subjectName` → `context.subject.name`, `context.subjectId` →
`context.subject.id`.

`issue-triage.json`: Start inputs become `subject` (object, required) and `event` (object, required) with no
`defaultValue`; every `context.ref` → `context.subject.ref`, `context.repository` →
`context.subject.repository`, `context.subjectName` → `context.subject.name`, `context.subjectId` →
`context.subject.id` (receive-event `correlationKey.subscriptionKey` included).

`axiom-config-assistant.json`: apply the same expression replacements to its `systemPrompt`; replace any text
about custom inputs/defaults with "Workflows declare only the standard Start inputs `subject` and `event`."
Use a JSON-aware edit (load, change the string, dump with the file's existing indentation) and check
`git diff` is limited to the changed text.

`axiom-workbench.json`: add the line `- Declare only the standard Start inputs (subject, event); require event
only when the workflow must be started from an event.` (one line, exactly the string asserted in Step 1) to the
`## Rules` list in `systemPrompt`, after the "Receive-event nodes must correlate" rule.

Then `grep -rnE "context\.(ref|subjectId|subjectName|repository)\b" app/src/main core/src/main` must print
nothing.

- [ ] **Step 4: Run tests**

Run: `mvn -q -pl app -am test > /tmp/opencode/apptest.log 2>&1; grep -L "Failures: 0, Errors: 0" app/target/surefire-reports/*.txt core/target/surefire-reports/*.txt`
Expected: nothing printed. (Core tests run as part of `-am`; if core reports are not produced, run
`mvn -q -pl core test -Dtest=ActionTypeValidatorTest -Dsurefire.failIfNoSpecifiedTests=false`.)

- [ ] **Step 5: Commit**

```bash
git add app core && git commit -m "feat(assistant): document the standard Start inputs and use context.subject in references"
```

---

### Task 7: Documentation

**Files:**
- Rewrite: `docs/developer-guide/workflow-input-contract.md`
- Modify: `docs/user-guide/workflows.md`, `docs/user-guide/concepts.md`, `docs/user-guide/usage-examples.md`,
  `docs/developer-guide/workflow-human-task-nodes.md`

- [ ] **Step 1: Rewrite `workflow-input-contract.md`** with these sections (wrap at 110):
  1. *Standard inputs* — table: `subject` (object, always; fields `id`, `name`, `type`, `ref`, `refSource`,
     `repository`, missing values null), `event` (object, only when started from a stream event: subscription
     `create-workflow`, **Run workflow…** on an event, re-run of an event-started run; envelope fields).
     Registry: `io.apitomy.axiom.app.StandardInputs`.
  2. *Declaring inputs* — only standard names, matching type, no `defaultValue`
     (`WorkflowContentRules.startInputViolation`, enforced at publish and in workbench validation). New
     workflows declare both, required (`WorkflowTemplates.emptyWorkflow`).
  3. *Run creation* — only declared inputs are put in the context; optional unavailable inputs are absent;
     required unavailable inputs stop the run with the exact message from Global Constraints; outcomes per
     trigger path (subject REST API and re-run: HTTP 400; schedule trigger: `lastOutcome` `failed: …`; event
     paths always have both inputs). The stored run `inputs` hold the resolved inputs without `event`.
  4. *Reading inputs* — `context.subject.ref`, `context.event.payload…`; undeclared reads are not checked
     because a node output may provide the key.
  5. *Shadowing warning* — `SHADOWS_WORKFLOW_INPUT`, the four output sources, `sdk:` results not checked.
  6. Keep the existing *Passing context to action types* and *Action type inputs and outputs at run time*
     sections, with examples changed to `context.subject.ref`.
  Remove all mentions of custom inputs, supplied inputs, protected inputs, `defaultValue` and the subject
  **Run Workflow** dialog.

- [ ] **Step 2: Update user docs**

`grep -nE "context\.(ref|subjectId|subjectName|repository)\b|defaultValue|[Ii]nputs field|static .?inputs|custom input|Inputs \(JSON\)" docs/user-guide/*.md docs/developer-guide/workflow-human-task-nodes.md`
and fix every hit: expressions → `context.subject.<field>`; remove the Inputs field from the **Run
workflow…** description and schedule trigger inputs from the triggers description; in `workflows.md`
"Run Title, Labels, and Inputs" describe stored inputs as the resolved standard inputs; in `concepts.md`
"Inputs, Placeholders and Mappings" list the run context as the declared standard inputs plus node outputs.

- [ ] **Step 3: Verify**

Run the grep from Step 2 again — expected: no output.

- [ ] **Step 4: Commit**

```bash
git add docs && git commit -m "docs: describe standard workflow inputs"
```

---

## After all tasks

- Full verification: backend suite, `node --test app/src/test/node/*.test.js`, UI `tsc` + `vitest`.
- Tell the user: delete the persistent database (`~/.axiom/data/axiom` and any other data dir) before
  restarting `dev.sh` — `V1__baseline.sql` changed (schedule trigger `inputs` column) — and existing
  published workflows must be re-created or re-declared with `subject`/`event` inputs.
