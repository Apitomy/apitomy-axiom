# Workbench Subscriptions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Compositions can hold `subscription` items that start (create-workflow) or feed (workflow-dispatch)
their own workflow; publishing creates them disabled and an explicit Enabled switch turns automation on.

**Architecture:** `subscription` joins the composition item types (contract enums, `CompositionItems`,
`CompositionService`). `CompositionValidator` adds composition-specific subscription rules. `CompositionPublisher`
adds the subscription→workflow dependency and controls `enabled`/`processEventsFrom` before the pack import. A new
endpoint toggles `enabled`. The existing `POST /subscriptions/preview` gains error info and backs a new AI tool and
a UI panel. The workbench UI gets a Subscriptions group and a subscription editor.

**Tech Stack:** Java 25, Quarkus, Panache, JUnit 5 + RestAssured; OpenAPI 3.1 contract-first (generated JAX-RS
interfaces/beans in `io.apitomy.axiom.api.beans`); Node (workbench MCP server, `node:test`); React 19 + PatternFly 6
+ Vitest.

**Spec:** `docs/superpowers/specs/2026-10-15-workbench-subscriptions-design.md`

## Global Constraints

- Item type JSON name: `subscription`. Draft content = pack form `{name, description, filterExpression, routing,
  labels}`.
- Validation codes, verbatim: `FOREIGN_WORKFLOW_ROUTE` (error), `ENABLED_IGNORED` (warning),
  `OVERLAPPING_SUBSCRIPTION` (warning).
- `ENABLED_IGNORED` message, verbatim: `Subscriptions are published disabled; enable them in the workbench after
  publishing.`
- Publish never changes an existing subscription's `enabled` or `processEventsFrom`; new ones are created with
  `enabled = false`.
- Enable endpoint: `PUT /compositions/{compositionId}/items/{itemId}/enabled`, body `{"enabled": boolean}`; 404
  unknown item; 409 not a subscription or not published; enabling sets `processEventsFrom = now`.
- Preview: existing `POST /subscriptions/preview` with `limit: 200`; response gains `errorCount` and `firstError`.
- New workbench tool name: `preview_subscription`; allowed tool `mcp__workbench__preview_subscription`.
- New prompt rule, verbatim: `- Never say the automation is live: published subscriptions stay disabled until the
  user enables them in the workbench.`
- Contract-first REST: edit `common/api/src/main/resources/openapi.json` with minimal textual diffs (no
  reformatting, no `nullable`); implement generated interfaces; no `@Path` on impl classes.
- Never run `./build.sh`, `mvn clean` or `mvn install`. Backend: `mvn -q -pl app -am test -Dtest=<Classes>
  -Dsurefire.failIfNoSpecifiedTests=false`; full suite: `mvn -q -pl app -am test > /tmp/opencode/apptest.log 2>&1;
  grep -L "Failures: 0, Errors: 0" app/target/surefire-reports/*.txt` (expect no output; delete stale reports
  first). UI (cwd `ui/`): `timeout 250 npx tsc -b --noEmit`, `timeout 280 npx vitest run`. Node:
  `node --test app/src/test/node/*.test.js`.
- `LineageTest.queryCountDoesNotGrowWithFanOut` is known-flaky: if it alone fails, re-run once.
- Commit only when the task's suites pass; chain with `&&`. Conventional commits; no AI attribution.
- Java: 4-space indent, Javadoc on public methods, explicit types. TS: JSDoc on exported functions.
- Markdown docs wrapped at 110 characters (except tables).

---

### Task 1: `subscription` as a composition item type

**Files:**
- Modify: `common/api/src/main/resources/openapi.json` (enums of `CompositionItem.type`,
  `NewCompositionItem.type`, `CompositionItemProblems.type`, `AgentItemUpsert.type`)
- Modify: `app/src/main/java/io/apitomy/axiom/app/composition/CompositionItems.java` (`SUPPORTED`, `blank`,
  `packArray`; new `referencedWorkflows`)
- Modify: `app/src/main/java/io/apitomy/axiom/app/composition/CompositionService.java` (`typeOrder`,
  `addExisting`, `publishedName`, `publishedContent`)
- Modify: `app/src/main/java/io/apitomy/axiom/app/ImportExportService.java` (`serializeSubscription` → public,
  Javadoc)
- Modify: `app/src/main/java/io/apitomy/axiom/app/composition/CompositionTurns.java` (`agentType` message)
- Modify: `core/src/main/java/io/apitomy/axiom/core/entities/CompositionItemEntity.java` (Javadoc of `itemType`)
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/CompositionItemsTest.java`,
  `app/src/test/java/io/apitomy/axiom/app/composition/CompositionsResourceTest.java`

**Interfaces:**
- Produces: `CompositionItems.SUPPORTED` includes `ConfigItemType.SUBSCRIPTION`;
  `CompositionItems.packArray(SUBSCRIPTION)` = `"subscriptions"`;
  `static Set<String> CompositionItems.referencedWorkflows(JsonNode subscription)` (names of `create-workflow`
  rules' `workflowDefinitionName`); `CompositionService.publishedName(SUBSCRIPTION, id)`;
  `ImportExportService.serializeSubscription(EventSubscriptionEntity)` public. Generated bean enums gain
  `SUBSCRIPTION` (`CompositionItem.Type`, `NewCompositionItem.Type`, `CompositionItemProblems.Type`,
  `AgentItemUpsert.Type`).

- [ ] **Step 1: Write failing tests**

In `CompositionItemsTest` add:

```java
    @Test
    void subscriptionIsSupportedWithABlankItemAndPackArray() {
        assertEquals(ConfigItemType.SUBSCRIPTION, CompositionItems.supportedType("subscription"));
        assertEquals("subscriptions", CompositionItems.packArray(ConfigItemType.SUBSCRIPTION));
        ObjectNode blank = CompositionItems.blank(ConfigItemType.SUBSCRIPTION, "on-pr", new ObjectMapper());
        assertEquals("on-pr", blank.path("name").asText());
        assertEquals("", blank.path("description").asText());
        assertEquals("", blank.path("filterExpression").asText());
        assertTrue(blank.path("routing").isArray());
        assertEquals(0, blank.path("routing").size());
        assertFalse(blank.has("enabled"));
        assertFalse(blank.has("scriptTemplate"));
    }

    @Test
    void referencedWorkflowsAreTheCreateWorkflowNames() throws Exception {
        JsonNode sub = new ObjectMapper().readTree("""
            {"name": "s", "routing": [
              {"type": "create-workflow", "workflowDefinitionName": "wf-a"},
              {"type": "workflow-dispatch"},
              {"type": "create-workflow", "workflowDefinitionId": 7}]}
            """);
        assertEquals(Set.of("wf-a"), CompositionItems.referencedWorkflows(sub));
    }
```

(add imports `com.fasterxml.jackson.databind.JsonNode`, `ObjectMapper`, `node.ObjectNode`, `java.util.Set`,
`assertFalse`, `assertTrue` if missing; read the file first and follow its existing structure).

In `CompositionsResourceTest` (it has `static long createComposition()`, `static JsonPath addItem(long
compositionId, String type, String name)` and a `BASE` constant for `/api/v1/compositions`) add:

```java
    @Test
    void addsABlankSubscriptionItem() {
        long compositionId = createComposition();
        JsonPath item = addItem(compositionId, "subscription", "on-pr");
        assertEquals("subscription", item.getString("type"));
        assertEquals("on-pr", item.getString("name"));
        assertEquals(0, item.getList("content.routing").size());
        given().get(BASE + "/{id}/items", compositionId).then().statusCode(200).body("type", hasItem("subscription"));
    }

    @Test
    void existingSubscriptionsCannotBeAdded() {
        long compositionId = createComposition();
        given().contentType(ContentType.JSON).body(Map.of("type", "subscription", "existingItemId", 1))
                .post(BASE + "/{id}/items", compositionId).then().statusCode(400);
    }
```

(add static imports `assertEquals`, `hasItem` if missing).

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl app -am test -Dtest='CompositionItemsTest,CompositionsResourceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: failures (`Item type not supported in compositions: subscription`; 400 on the POST).

- [ ] **Step 3: Contract** — in `openapi.json`, add `"subscription"` as the last value of each of these four enums
(textual edit of each enum array only): `#/components/schemas/CompositionItem/properties/type/enum`,
`#/components/schemas/NewCompositionItem/properties/type/enum`,
`#/components/schemas/CompositionItemProblems/properties/type/enum`,
`#/components/schemas/AgentItemUpsert/properties/type/enum`. Do not touch `AgentTestSuggestion` or
`CompositionTestRun` enums. `git diff --stat` must show only these 4 lines (+ commas).

- [ ] **Step 4: `CompositionItems`**

- `SUPPORTED` = `EnumSet.of(ConfigItemType.WORKFLOW, ConfigItemType.ACTION_TYPE, ConfigItemType.TOOL,
  ConfigItemType.SUBSCRIPTION)`.
- In `blank(type, name, executionMode, mapper)`, before the action-type/tool branches (after the common `name`/
  `description`), add:

```java
        if (type == ConfigItemType.SUBSCRIPTION) {
            item.put("filterExpression", "");
            item.putArray("routing");
            return item;
        }
```

  and change the final `else` (tool) branch to `else if (type == ConfigItemType.TOOL)` only if the method's
  structure makes that clearer; behaviour for tools is unchanged.
- `packArray`: add `case SUBSCRIPTION -> "subscriptions";`.
- Add:

```java
    /**
     * Returns the workflow names a subscription starts: the {@code workflowDefinitionName} of each
     * {@code create-workflow} routing rule.
     *
     * @param subscription the subscription item content
     * @return the referenced workflow names
     */
    public static Set<String> referencedWorkflows(JsonNode subscription) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode rule : subscription.path("routing")) {
            String name = rule.path("workflowDefinitionName").asText("");
            if ("create-workflow".equals(rule.path("type").asText()) && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }
```

  (use `RoutingRule.TYPE_CREATE_WORKFLOW` if that constant is importable here; check its package with grep).

- [ ] **Step 5: `CompositionService` and `ImportExportService`**

- `typeOrder`: `case "workflow" -> 0; case "action-type" -> 1; case "tool" -> 2; default -> 3;`.
- `addExisting`: at the top, after `requireSupported(type)`:

```java
        if (type == ConfigItemType.SUBSCRIPTION) {
            throw new IllegalArgumentException("Existing subscriptions cannot be added to a composition; "
                    + "create a new subscription instead");
        }
```

- `publishedName`: add `case SUBSCRIPTION -> { EventSubscriptionEntity e = EventSubscriptionEntity.findById(id);
  yield e == null ? null : e.name; }`.
- `publishedContent`: add

```java
            case SUBSCRIPTION -> {
                // enabled and processEventsFrom are runtime state, changed by the Enabled switch, not content.
                ObjectNode content = importExportService.serializeSubscription(
                        EventSubscriptionEntity.findById(id));
                content.remove("enabled");
                content.remove("processEventsFrom");
                yield content;
            }
```

- `ImportExportService.serializeSubscription`: make it `public` and add Javadoc ("Serializes a subscription in
  configuration-pack form.").
- `CompositionTurns.agentType`: message `"'type' must be workflow, action-type, tool or subscription"`.
- `CompositionItemEntity.itemType` Javadoc: "workflow, action-type, tool or subscription".

- [ ] **Step 6: Run tests, then the full suite**

Run focused tests (Step 2 command) — expected PASS. Then the full backend suite (Global Constraints) — expected no
failing reports.

- [ ] **Step 7: Commit**

```bash
git add common/api/src/main/resources/openapi.json app core && git commit -m "feat(workbench): allow subscription items in compositions"
```

---

### Task 2: Composition rules for subscriptions

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/composition/CompositionValidator.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/composition/CompositionPublisher.java` (validation loop uses
  the new method)
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/CompositionValidatorTest.java`

**Interfaces:**
- Consumes: Task 1 (`SUBSCRIPTION` supported; `addBlank(compositionId, ConfigItemType.SUBSCRIPTION, name)`).
- Produces: `public List<ConfigProblem> CompositionValidator.validateItem(long compositionId, ConfigItemType type,
  ObjectNode packItem, ValidationContext context)` — generic validation plus composition rules. `validateAll`,
  `validateContent` and `CompositionPublisher.publish` all use it.

- [ ] **Step 1: Write failing tests** — add to `CompositionValidatorTest` (read it first; it injects
`CompositionService service` and `CompositionValidator validator` or similar — use its existing helpers and
cleanup; add cleanup of `EventSubscriptionEntity` rows named `cvt-%` and workflow definitions it creates):

```java
    private CompositionItemEntity subscription(long compositionId, String name, String routingJson,
                                               boolean withEnabled) throws Exception {
        CompositionItemEntity item = service.addBlank(compositionId, ConfigItemType.SUBSCRIPTION, name);
        ObjectNode content = (ObjectNode) service.contentOf(item);
        content.put("filterExpression", "event.type == 'pr.created'");
        content.set("routing", new ObjectMapper().readTree(routingJson));
        if (withEnabled) {
            content.put("enabled", true);
        }
        return service.save(compositionId, item.id, content, item.contentRevision, CompositionService.AUTHOR_USER, null);
    }

    private static List<ConfigProblem> codes(List<ConfigProblem> problems, String code) {
        return problems.stream().filter(p -> p.code().equals(code)).toList();
    }

    @Test
    void createWorkflowMustTargetTheCompositionWorkflowByName() throws Exception {
        CompositionEntity composition = service.create("cvt-c1", null, null, null, null);
        String wf = service.items(composition.id).stream()
                .filter(i -> i.itemType.equals("workflow")).findFirst().orElseThrow().name;
        CompositionItemEntity good = subscription(composition.id, "cvt-good",
                "[{\"type\": \"create-workflow\", \"workflowDefinitionName\": \"" + wf + "\"},"
                        + " {\"type\": \"workflow-dispatch\"}]", false);
        CompositionItemEntity other = subscription(composition.id, "cvt-other",
                "[{\"type\": \"create-workflow\", \"workflowDefinitionName\": \"someone-else\"}]", false);
        CompositionItemEntity byId = subscription(composition.id, "cvt-id",
                "[{\"type\": \"create-workflow\", \"workflowDefinitionId\": 1}]", false);

        Map<Long, List<ConfigProblem>> all = validator.validateAll(composition.id);
        assertTrue(codes(all.get(good.id), "FOREIGN_WORKFLOW_ROUTE").isEmpty(), all.get(good.id).toString());
        assertEquals(1, codes(all.get(other.id), "FOREIGN_WORKFLOW_ROUTE").size());
        assertEquals("error", codes(all.get(other.id), "FOREIGN_WORKFLOW_ROUTE").get(0).severity());
        assertEquals("routing[0]", codes(all.get(other.id), "FOREIGN_WORKFLOW_ROUTE").get(0).path());
        assertEquals(1, codes(all.get(byId.id), "FOREIGN_WORKFLOW_ROUTE").size());
    }

    @Test
    void enabledInADraftIsIgnoredWithAWarning() throws Exception {
        CompositionEntity composition = service.create("cvt-c2", null, null, null, null);
        CompositionItemEntity sub = subscription(composition.id, "cvt-en", "[]", true);
        List<ConfigProblem> problems = codes(validator.validateAll(composition.id).get(sub.id), "ENABLED_IGNORED");
        assertEquals(1, problems.size());
        assertEquals("warning", problems.get(0).severity());
        assertEquals("Subscriptions are published disabled; enable them in the workbench after publishing.",
                problems.get(0).message());
    }

    @Test
    void warnsWhenAnotherEnabledSubscriptionStartsTheWorkflow() throws Exception {
        CompositionEntity composition = service.create("cvt-c3", null, null, null, null);
        CompositionItemEntity wfItem = service.items(composition.id).stream()
                .filter(i -> i.itemType.equals("workflow")).findFirst().orElseThrow();
        long definitionId = TestFixtures.createWaitDefinition(wfItem.name);
        QuarkusTransaction.requiringNew().run(() -> {
            EventSubscriptionEntity s = new EventSubscriptionEntity();
            s.name = "cvt-outside";
            s.enabled = true;
            s.routing = "[{\"type\":\"create-workflow\",\"workflowDefinitionId\":" + definitionId + "}]";
            s.createdOn = Instant.now();
            s.modifiedOn = Instant.now();
            s.persist();
        });
        CompositionItemEntity sub = subscription(composition.id, "cvt-mine",
                "[{\"type\": \"create-workflow\", \"workflowDefinitionName\": \"" + wfItem.name + "\"}]", false);
        List<ConfigProblem> problems = codes(validator.validateAll(composition.id).get(sub.id),
                "OVERLAPPING_SUBSCRIPTION");
        assertEquals(1, problems.size());
        assertEquals("warning", problems.get(0).severity());
        assertTrue(problems.get(0).message().contains("cvt-outside"), problems.get(0).message());
    }
```

Notes for the implementer: `service.create(...)` seeds one workflow item (confirm by reading
`CompositionService.create`); if the workflow item's name collides with an existing definition in the test DB,
use a unique composition name. `TestFixtures.createWaitDefinition(String)` exists in
`app/src/test/java/io/apitomy/axiom/app/TestFixtures.java` (package `io.apitomy.axiom.app`; import it).
`ConfigProblem` is a record `(severity, path, code, message)`.

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl app -am test -Dtest=CompositionValidatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the three new tests fail.

- [ ] **Step 3: Implement** — in `CompositionValidator` add:

```java
    /**
     * Validates one item in configuration-pack form: the generic configuration checks plus the composition's own
     * rules (for subscriptions: routes only to this composition's workflow, no draft {@code enabled}, and a
     * warning when another enabled subscription already starts the workflow).
     *
     * @param compositionId the composition id
     * @param type the item type
     * @param packItem the item in configuration-pack form
     * @param context the validation context
     * @return the problems
     */
    public List<ConfigProblem> validateItem(long compositionId, ConfigItemType type, ObjectNode packItem,
                                            ValidationContext context) {
        List<ConfigProblem> problems = new ArrayList<>(validationService.validate(type, packItem, context));
        if (type == ConfigItemType.SUBSCRIPTION) {
            problems.addAll(subscriptionProblems(compositionId, packItem));
        }
        return problems;
    }

    private List<ConfigProblem> subscriptionProblems(long compositionId, JsonNode subscription) {
        List<ConfigProblem> problems = new ArrayList<>();
        List<CompositionItemEntity> items = service.items(compositionId);
        String workflowName = items.stream().filter(i -> ConfigItemType.WORKFLOW.jsonName().equals(i.itemType))
                .map(i -> i.name).findFirst().orElse(null);
        if (subscription.has("enabled")) {
            problems.add(ConfigProblem.warning("enabled", "ENABLED_IGNORED",
                    "Subscriptions are published disabled; enable them in the workbench after publishing."));
        }
        JsonNode routing = subscription.path("routing");
        for (int i = 0; i < routing.size(); i++) {
            JsonNode rule = routing.get(i);
            if (!"create-workflow".equals(rule.path("type").asText())) {
                continue;
            }
            boolean own = !rule.has("workflowDefinitionId") && workflowName != null
                    && workflowName.equals(rule.path("workflowDefinitionName").asText());
            if (!own) {
                problems.add(ConfigProblem.error("routing[" + i + "]", "FOREIGN_WORKFLOW_ROUTE",
                        "A create-workflow rule must start this composition's workflow: set "
                                + "workflowDefinitionName to '" + workflowName + "'."));
            }
        }
        if (workflowName != null && !CompositionItems.referencedWorkflows(subscription).isEmpty()) {
            WorkflowDefinitionEntity definition = WorkflowDefinitionEntity.find("name", workflowName).firstResult();
            if (definition != null) {
                Set<Long> owned = items.stream()
                        .filter(i -> ConfigItemType.SUBSCRIPTION.jsonName().equals(i.itemType) && i.baseItemId != null)
                        .map(i -> i.baseItemId).collect(Collectors.toSet());
                for (EventSubscriptionEntity other : EventSubscriptionEntity.<EventSubscriptionEntity>list("enabled", true)) {
                    if (!owned.contains(other.id) && startsWorkflow(other, definition.id)) {
                        problems.add(ConfigProblem.warning("routing", "OVERLAPPING_SUBSCRIPTION",
                                "Enabled subscription '" + other.name + "' already starts workflow '"
                                        + workflowName + "'."));
                    }
                }
            }
        }
        return problems;
    }

    private boolean startsWorkflow(EventSubscriptionEntity subscription, long definitionId) {
        if (subscription.routing == null) {
            return false;
        }
        try {
            for (JsonNode rule : mapper.readTree(subscription.routing)) {
                if ("create-workflow".equals(rule.path("type").asText())
                        && rule.path("workflowDefinitionId").asLong(-1) == definitionId) {
                    return true;
                }
            }
        } catch (JsonProcessingException e) {
            return false;
        }
        return false;
    }
```

Use `RoutingRule.TYPE_CREATE_WORKFLOW` instead of the literal if importable. Then make `validateAll` call
`validateItem(compositionId, type, packItem(item), context)` and `validateContent` call `validateItem(compositionId,
type, CompositionItems.toPackItem(type, name, content, mapper), context(compositionId))`. In
`CompositionPublisher.publish`, replace `validationService.validate(ConfigItemType.fromName(item.itemType),
validator.packItem(item), context)` with `validator.validateItem(compositionId,
ConfigItemType.fromName(item.itemType), validator.packItem(item), context)` (remove the now-unused
`validationService` field from the publisher if nothing else uses it).

- [ ] **Step 4: Run tests** — focused (Step 2) then the full backend suite. Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add app && git commit -m "feat(workbench): validate composition subscriptions against the composition's workflow"
```

---

### Task 3: Publishing subscriptions and the Enabled switch endpoint

**Files:**
- Modify: `common/api/src/main/resources/openapi.json` (new path + schema; `CompositionItem.enabled`)
- Modify: `app/src/main/java/io/apitomy/axiom/app/composition/CompositionPublisher.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/composition/CompositionService.java` (new `setEnabled`)
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java` (`toBean`, new method)
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/CompositionPublisherTest.java`,
  `app/src/test/java/io/apitomy/axiom/app/composition/CompositionsResourceTest.java`

**Interfaces:**
- Consumes: Tasks 1-2 (`referencedWorkflows`, `validateItem`).
- Produces: `CompositionItem.enabled` (boolean; set only for published subscription items); endpoint
  `setCompositionItemEnabled` (`PUT /compositions/{compositionId}/items/{itemId}/enabled`, body schema
  `CompositionItemEnabled {enabled: boolean, required}`, returns `CompositionItem`);
  `CompositionItem CompositionService.setEnabled(long compositionId, long itemId, boolean enabled)` returning the
  entity (throws `NotFoundException`-style 404 per existing `item(...)`, and `IllegalStateException` → 409).

- [ ] **Step 1: Write failing tests** — in `CompositionPublisherTest` (read it; reuse its helpers `workflowUsing`,
`validActionType`, cleanup; add cleanup of `EventSubscriptionEntity` named `cpt-%`):

```java
    private CompositionItemEntity subscriptionFor(long compositionId, String name, String workflowName) throws Exception {
        CompositionItemEntity item = service.addBlank(compositionId, ConfigItemType.SUBSCRIPTION, name);
        ObjectNode content = (ObjectNode) service.contentOf(item);
        content.put("filterExpression", "event.type == 'pr.created'");
        content.set("routing", new ObjectMapper().readTree(
                "[{\"type\": \"create-workflow\", \"workflowDefinitionName\": \"" + workflowName + "\"}]"));
        return service.save(compositionId, item.id, content, item.contentRevision, CompositionService.AUTHOR_USER, null);
    }

    @Test
    void publishesSubscriptionDisabledAndKeepsEnabledStateOnRepublish() throws Exception {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity actionType = validActionType(composition.id, "cpt-sat");
        CompositionItemEntity workflow = workflowUsing(composition.id, "cpt-swf", "cpt-sat");
        CompositionItemEntity sub = subscriptionFor(composition.id, "cpt-sub", "cpt-swf");

        CompositionPublisher.Outcome outcome = publisher.publish(composition.id,
                List.of(workflow.id, actionType.id, sub.id), false);
        assertEquals(3, outcome.created());
        EventSubscriptionEntity published = QuarkusTransaction.requiringNew().call(() ->
                EventSubscriptionEntity.<EventSubscriptionEntity>find("name", "cpt-sub").firstResult());
        assertFalse(published.enabled);

        CompositionItemEntity enabled = QuarkusTransaction.requiringNew().call(() ->
                service.setEnabled(composition.id, sub.id, true));
        Instant from = QuarkusTransaction.requiringNew().call(() ->
                EventSubscriptionEntity.<EventSubscriptionEntity>findById(published.id).processEventsFrom);

        CompositionItemEntity edited = QuarkusTransaction.requiringNew().call(() -> {
            CompositionItemEntity current = service.item(composition.id, sub.id);
            ObjectNode content = (ObjectNode) service.contentOf(current);
            content.put("description", "changed");
            return service.save(composition.id, current.id, content, current.contentRevision,
                    CompositionService.AUTHOR_USER, null);
        });
        publisher.publish(composition.id, List.of(edited.id), false);   // not a conflict: enabling is not content
        EventSubscriptionEntity republished = QuarkusTransaction.requiringNew().call(() ->
                EventSubscriptionEntity.<EventSubscriptionEntity>findById(published.id));
        assertTrue(republished.enabled, "republish keeps the subscription enabled");
        assertEquals(from, republished.processEventsFrom);
        assertEquals("changed", republished.description);
    }

    @Test
    void blocksSubscriptionWhoseWorkflowIsNotPublishedOrSelected() throws Exception {
        CompositionEntity composition = service.create("c", null, null, null, null);
        workflowUsing(composition.id, "cpt-bwf", "cpt-bat");
        CompositionItemEntity sub = subscriptionFor(composition.id, "cpt-bsub", "cpt-bwf");
        PublishBlockedException e = assertThrows(PublishBlockedException.class,
                () -> publisher.publish(composition.id, List.of(sub.id), false));
        assertTrue(e.problemsByItem().get("subscription/cpt-bsub").stream()
                .anyMatch(p -> p.code().equals("missing-dependency")), e.problemsByItem().toString());
    }
```

Adapt to the real APIs as you read them: the accessor on `PublishBlockedException` (check its name, e.g.
`problems()`/`problemsByItem()`), whether `service.item(...)` is public (use the public lookup the class offers),
and how `workflowUsing` names the composition's workflow (the composition must contain exactly one workflow —
if `service.create` seeds one, `workflowUsing` probably saves into it; read it). Keep the assertions.

In `CompositionsResourceTest` add:

```java
    @Test
    void enabledEndpointRejectsUnpublishedAndNonSubscriptionItems() {
        long compositionId = createComposition();
        long subscriptionId = addItem(compositionId, "subscription", "en-sub").getLong("id");
        long toolId = addItem(compositionId, "tool", "en-tool").getLong("id");
        given().contentType(ContentType.JSON).body("{\"enabled\": true}")
                .when().put("/api/v1/compositions/" + compositionId + "/items/" + subscriptionId + "/enabled")
                .then().statusCode(409);
        given().contentType(ContentType.JSON).body("{\"enabled\": true}")
                .when().put("/api/v1/compositions/" + compositionId + "/items/" + toolId + "/enabled")
                .then().statusCode(409);
        given().contentType(ContentType.JSON).body("{\"enabled\": true}")
                .when().put("/api/v1/compositions/" + compositionId + "/items/999999999/enabled")
                .then().statusCode(404);
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl app -am test -Dtest='CompositionPublisherTest,CompositionsResourceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile errors / failures (no `setEnabled`, no endpoint, subscription published enabled).

- [ ] **Step 3: Contract** — in `openapi.json`:
- add property `"enabled": {"type": "boolean", "description": "For a published subscription item: whether the
  published subscription is enabled"}` to `CompositionItem.properties`;
- add schema `CompositionItemEnabled`: `{"type": "object", "required": ["enabled"], "properties": {"enabled":
  {"type": "boolean", "description": "Whether the published subscription is enabled"}}}`;
- add path `/compositions/{compositionId}/items/{itemId}/enabled` with `put`: tags as the other composition item
  operations, `operationId` `setCompositionItemEnabled`, summary "Enable or disable a published subscription",
  description "Enabling also sets processEventsFrom to now, so earlier events are not processed.", requestBody
  `CompositionItemEnabled` (required), responses `200` → `CompositionItem`, `404` → existing NotFound response ref,
  `409` "The item is not a subscription or is not published"; path parameters copied from the sibling
  `/compositions/{compositionId}/items/{itemId}` path.
Insert textually next to the sibling path; keep the file's formatting.

- [ ] **Step 4: Implement**

`CompositionService`:

```java
    /**
     * Enables or disables the published subscription of a subscription item. Enabling sets
     * {@code processEventsFrom} to now, so events before this moment are not processed.
     *
     * @param compositionId the composition id
     * @param itemId the item id
     * @param enabled the new state
     * @return the item
     * @throws IllegalStateException when the item is not a published subscription
     */
    @Transactional
    public CompositionItemEntity setEnabled(long compositionId, long itemId, boolean enabled) {
        CompositionItemEntity item = item(compositionId, itemId);
        if (!ConfigItemType.SUBSCRIPTION.jsonName().equals(item.itemType) || item.baseItemId == null) {
            throw new IllegalStateException("Only a published subscription can be enabled or disabled");
        }
        EventSubscriptionEntity subscription = EventSubscriptionEntity.findById(item.baseItemId);
        if (subscription == null) {
            throw new IllegalStateException("The published subscription no longer exists");
        }
        subscription.enabled = enabled;
        if (enabled) {
            subscription.processEventsFrom = Instant.now();
        }
        subscription.modifiedOn = Instant.now();
        return item;
    }
```

(`item(compositionId, itemId)` is the existing lookup that 404s; if it is private, call it from within the class
as shown.)

`CompositionsResourceImpl`: implement the generated `setCompositionItemEnabled(long compositionId, long itemId,
CompositionItemEnabled data)`: call `service.setEnabled(...)`, map `IllegalStateException` to a 409 the same way the
class maps conflicts (read `mapBadRequest`/existing conflict mappings; use `WebApplicationException` with status 409
and `{"message": ...}` if no helper exists), return `toBean(item, false)`. In `toBean`, after the existing fields:

```java
        if (ConfigItemType.SUBSCRIPTION.jsonName().equals(entity.itemType) && entity.baseItemId != null) {
            EventSubscriptionEntity subscription = EventSubscriptionEntity.findById(entity.baseItemId);
            if (subscription != null) {
                bean.setEnabled(subscription.enabled);
            }
        }
```

`CompositionPublisher`:
- `checkDependencies`: add a branch

```java
        } else if (ConfigItemType.SUBSCRIPTION.jsonName().equals(item.itemType)) {
            for (String name : CompositionItems.referencedWorkflows(content)) {
                requireDependency(item, ConfigItemType.WORKFLOW, name,
                        WorkflowDefinitionEntity.count("name", name) > 0, selectedKeys, draftKeys, blocked);
            }
        }
```

- `publishedIdOrNull`: add `case SUBSCRIPTION -> idOf(EventSubscriptionEntity.<EventSubscriptionEntity>find("name",
  name).firstResult());`.
- Pack building: replace the loop body adding items with

```java
            ObjectNode packItem = validator.packItem(item);
            if (type == ConfigItemType.SUBSCRIPTION) {
                applyPublishedState(packItem);
            }
            pack.withArray(CompositionItems.packArray(type)).add(packItem);
```

  and add

```java
    /**
     * Publishing never changes whether a subscription runs: a new one is created disabled, and an existing one
     * keeps its enabled state and processEventsFrom (the pack import would otherwise reset them).
     */
    private static void applyPublishedState(ObjectNode packItem) {
        packItem.remove("enabled");
        packItem.remove("processEventsFrom");
        EventSubscriptionEntity existing = EventSubscriptionEntity.<EventSubscriptionEntity>find("name",
                packItem.path("name").asText()).firstResult();
        if (existing == null) {
            packItem.put("enabled", false);
            return;
        }
        packItem.put("enabled", existing.enabled);
        if (existing.processEventsFrom != null) {
            packItem.put("processEventsFrom", existing.processEventsFrom.toString());
        }
    }
```

- counts: `int created = ... + result.subscriptionsCreated();` and `int updated = ... + result.subscriptionsUpdated();`.

- [ ] **Step 5: Run tests** — focused (Step 2), then the full backend suite. Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add common/api/src/main/resources/openapi.json app && git commit -m "feat(workbench): publish subscriptions disabled and enable them explicitly"
```

---

### Task 4: Filter preview errors, `preview_subscription` tool and the workbench AI

**Files:**
- Modify: `common/api/src/main/resources/openapi.json` (`SubscriptionPreviewResponse` gains `errorCount`,
  `firstError`)
- Modify: `core/src/main/java/io/apitomy/axiom/core/filters/SubscriptionFilterEvaluator.java` (new
  `evaluate`)
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/SubscriptionsResourceImpl.java`
  (`previewSubscriptionFilter`)
- Modify: `app/src/main/resources/templates/axiom-workbench-mcp/server.js` (`preview_subscription`,
  `formatSubscriptionPreview`, `upsert_item` description)
- Modify: `app/src/main/resources/templates/assistant-templates/axiom-workbench.json` (allowedTools, rule)
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantReferenceGenerator.java`
  (`generateChangeSetReference`)
- Test: `app/src/test/java/io/apitomy/axiom/app/SubscriptionsResourceTest.java`,
  `app/src/test/node/workbench-mcp.test.js`, `app/src/test/java/io/apitomy/axiom/app/assistant/WorkbenchReferenceTest.java`

**Interfaces:**
- Produces: `boolean SubscriptionFilterEvaluator.evaluate(String filterExpression, Map<String, Object> eventMap)`
  (true for blank; throws on evaluation errors); `SubscriptionPreviewResponse.errorCount` (int32),
  `firstError` (string); JS helper `formatSubscriptionPreview(response)` exported.

- [ ] **Step 1: Write failing tests**

`SubscriptionsResourceTest` (read it for its event-creation helper):

```java
    @Test
    void previewReportsEvaluationErrors() {
        QuarkusTransaction.requiringNew().run(() ->
                createStreamEvent("preview-err-" + UUID.randomUUID(), "github", "issue.created", Instant.now()));
        given().contentType(ContentType.JSON)
                .body("{\"filterExpression\": \"event.payload.missing.deeper == 1\", \"limit\": 200}")
                .when().post("/api/v1/subscriptions/preview")
                .then().statusCode(200)
                .body("errorCount", greaterThan(0))
                .body("firstError", notNullValue());
        given().contentType(ContentType.JSON)
                .body("{\"filterExpression\": \"event.type != ''\", \"limit\": 200}")
                .when().post("/api/v1/subscriptions/preview")
                .then().statusCode(200).body("errorCount", equalTo(0));
    }
```

(`createStreamEvent(String sourceEventId, String source, String type, Instant timestamp)` is the class's
existing private helper; its event has `payload` `{}`.) If `event.payload.missing.deeper` does not throw with the EL
resolver in use (check with a quick run), use an expression that does, e.g. `event.payload.x.y.z()` or
`1 / ''` — the test needs an expression that throws per event.

`workbench-mcp.test.js`:

```js
test("formatSubscriptionPreview summarises matches and errors", () => {
    const text = helpers.formatSubscriptionPreview({
        results: [
            { matched: true, event: { id: "e1", type: "pr.created", ref: "https://x/pull/1", actor: { login: "renovate[bot]" },
                timestamp: "2026-10-15T10:00:00Z" } },
            { matched: false, event: { id: "e2", type: "issue.created", ref: "https://x/issues/2", timestamp: "t" } },
        ],
        totalMatched: 7, errorCount: 1, firstError: "boom",
    });
    assert.strictEqual(text, "1 of 2 recent events match (7 in all events).\n"
        + "1 event(s) could not be evaluated; first error: boom\n"
        + "- e1 pr.created https://x/pull/1 by renovate[bot] at 2026-10-15T10:00:00Z");
});

test("formatSubscriptionPreview with no matches", () => {
    assert.strictEqual(helpers.formatSubscriptionPreview({ results: [], totalMatched: 0, errorCount: 0 }),
        "0 of 0 recent events match (0 in all events).");
});
```

`WorkbenchReferenceTest`: add to `promptCarriesEveryRule`'s list the rule string from Global Constraints (without
the leading `- ` if the list's other entries omit it — follow the list's style), add
`"mcp__workbench__preview_subscription"` to the allowed-tools assertion (follow `templateAllowsReadingRuns`), and:

```java
    @Test
    void referenceExplainsWorkbenchSubscriptions() {
        String reference = new AssistantReferenceGenerator().generateChangeSetReference();
        assertTrue(reference.contains("# Subscriptions in the workbench"), reference);
        assertTrue(reference.contains("upsert_item"), reference);
        assertTrue(reference.contains("workflowDefinitionName"), reference);
        assertTrue(reference.contains("workflow-dispatch"), reference);
        assertTrue(reference.contains("preview_subscription"), reference);
        assertTrue(reference.contains("Do not set `enabled`"), reference);
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl app -am test -Dtest='SubscriptionsResourceTest,WorkbenchReferenceTest,WorkbenchMcpServerScriptTest' -Dsurefire.failIfNoSpecifiedTests=false`
and `node --test app/src/test/node/*.test.js`. Expected: failures.

- [ ] **Step 3: Implement**

Contract: add to `SubscriptionPreviewResponse.properties`: `"errorCount": {"format": "int32", "type": "integer",
"description": "Events on this page whose filter evaluation failed (counted as not matched)"}` and
`"firstError": {"type": "string", "description": "The first evaluation error on this page"}`.

`SubscriptionFilterEvaluator`:

```java
    /**
     * Evaluates a filter expression against an event map, reporting errors instead of treating them as no
     * match. Used by previews; live matching uses {@link #matches}.
     *
     * @param filterExpression the EL expression (null or blank = match all)
     * @param eventMap the event data, exposed as the "event" variable
     * @return whether the event matches
     * @throws RuntimeException when the expression cannot be evaluated
     */
    public boolean evaluate(String filterExpression, Map<String, Object> eventMap) {
        if (filterExpression == null || filterExpression.isBlank()) {
            return true;
        }
        return conditionEvaluator.evaluate(filterExpression, Map.of(), eventMap);
    }
```

`SubscriptionsResourceImpl.previewSubscriptionFilter`: replace the `matched` computation with

```java
            boolean matched = false;
            if (!beforeCutoff) {
                try {
                    matched = filterEvaluator.evaluate(filterExpression, eventMap);
                } catch (RuntimeException e) {
                    errorCount++;
                    if (firstError == null) {
                        firstError = e.getMessage() != null ? e.getMessage() : e.toString();
                    }
                }
            }
```

(declare `int errorCount = 0; String firstError = null;` before the loop) and set
`response.setErrorCount(errorCount); response.setFirstError(firstError);`.

`server.js`: add a top-level helper and export it:

```js
/**
 * Formats a subscription filter preview: counts, errors and the matched events (at most 20).
 * @param {object} r the SubscriptionPreviewResponse
 * @returns {string} the summary
 */
function formatSubscriptionPreview(r) {
    const results = r.results || [];
    const matches = results.filter(x => x.matched);
    const lines = [`${matches.length} of ${results.length} recent events match (${r.totalMatched ?? 0} in all events).`];
    if (r.errorCount) {
        lines.push(`${r.errorCount} event(s) could not be evaluated; first error: ${r.firstError}`);
    }
    for (const m of matches.slice(0, 20)) {
        const e = m.event || {};
        const actor = e.actor && e.actor.login ? ` by ${e.actor.login}` : "";
        lines.push(`- ${e.id} ${e.type} ${e.ref}${actor} at ${e.timestamp}`);
    }
    return lines.join("\n");
}
```

and a tool after `evaluate_expression`:

```js
    {
        name: "preview_subscription",
        description: "Show which of the 200 most recent stream events a subscription filter expression matches, "
            + "evaluated exactly as subscription matching does (evaluation errors are reported). Read-only.",
        parameters: [
            { name: "filterExpression", type: "string", description: "The subscription filterExpression", required: true },
        ],
        handler: async (args) => formatSubscriptionPreview(JSON.parse(await axiomApi("POST", "/subscriptions/preview",
            { filterExpression: args.filterExpression, limit: 200 }))),
    },
```

Change `upsert_item`'s description to `"Create or replace a composition item (action-type, tool or subscription). item is the full JSON object."`.

`axiom-workbench.json` (JSON-aware edit preserving formatting; diff limited to the change): add
`"mcp__workbench__preview_subscription"` to `allowedTools` after `"mcp__workbench__evaluate_expression"`, and add the
Global Constraints rule line to the `## Rules` list in `systemPrompt`, after the rule starting "- Declare only the
standard Start inputs".

`AssistantReferenceGenerator.generateChangeSetReference`: return
`CHANGE_SET_REFERENCE + "\n" + expressionScopesReference() + "\n" + WORKBENCH_SUBSCRIPTIONS_REFERENCE` with

```java
    private static final String WORKBENCH_SUBSCRIPTIONS_REFERENCE = """
            # Subscriptions in the workbench

            A composition may define subscriptions that start or feed its workflow. Create and change them with
            `upsert_item` (type `subscription`): `{name, description, filterExpression, routing, labels}`.

            - A `create-workflow` rule must start this composition's workflow: set `workflowDefinitionName` to the
              workflow's name (never `workflowDefinitionId`).
            - Add a `workflow-dispatch` subscription whenever the workflow has `receive-event` nodes; without one,
              waiting runs never receive events.
            - Check a filter with `preview_subscription` before relying on it, and single events with
              `evaluate_expression` (scope `subscription-filter`).
            - Do not set `enabled`: publishing leaves subscriptions disabled, and the user enables each one in the
              workbench.
            """;
```

- [ ] **Step 4: Run tests** — Step 2 commands, then the full backend suite. Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add common/api/src/main/resources/openapi.json core app && git commit -m "feat(workbench): preview subscription filters and teach the workbench AI about subscriptions"
```

---

### Task 5: UI data layer and workbench logic for subscriptions

**Files:**
- Modify: `ui/src/config/compositions.ts` (type union, `CompositionItem.enabled`, `setItemEnabled`)
- Modify: `ui/src/config/api.ts` (`SubscriptionPreviewResponse` gains `errorCount?`, `firstError?`)
- Modify: `ui/src/pages/workbench/workbenchLogic.ts` (`itemKindLabel`, `publishSelection` subscription deps)
- Modify: `ui/src/pages/workbench/useWorkbench.ts` (load subscription contents like action types, ~l.262-275)
- Modify: `ui/src/pages/workbench/toolCards.ts` (`ItemType`/`isItemType`/`kindLabel` include subscription)
- Modify: `ui/src/pages/workbench/WorkbenchToolCards.tsx` (`openItem` type union)
- Test: `ui/src/config/compositions.test.ts`, `ui/src/pages/workbench/workbenchLogic.test.ts`,
  `ui/src/pages/workbench/toolCards.test.ts`

**Interfaces:**
- Produces: `type CompositionItemType = "workflow" | "action-type" | "tool" | "subscription"`;
  `CompositionItem.enabled?: boolean`; `setItemEnabled(id: number, itemId: number, enabled: boolean):
  Promise<CompositionItem>` (PUT `${c(id)}/items/${itemId}/enabled`); `itemKindLabel(type: CompositionItemType):
  string` returning `"workflow" | "action type" | "tool" | "subscription"`.

- [ ] **Step 1: Write failing tests**

`compositions.test.ts` (follow its fetch-mock style):

```ts
    it("enables a subscription item", async () => {
        const fetchSpy = mockFetch(200, { id: 5, name: "s", type: "subscription", contentRevision: "r",
            lastAuthor: "user", changed: false, enabled: true });
        const item = await setItemEnabled(3, 5, true);
        const [url, init] = fetchSpy.mock.calls[0];
        expect(String(url)).toMatch(/\/api\/v1\/compositions\/3\/items\/5\/enabled$/);
        expect((init as RequestInit).method).toBe("PUT");
        expect(JSON.parse(String((init as RequestInit).body))).toEqual({ enabled: true });
        expect(item.enabled).toBe(true);
    });
```

(`mockFetch(status, body)` is the file's existing helper; add `setItemEnabled` to its import.)

`workbenchLogic.test.ts`:

```ts
    it("labels every item kind", () => {
        expect(itemKindLabel("workflow")).toBe("workflow");
        expect(itemKindLabel("action-type")).toBe("action type");
        expect(itemKindLabel("tool")).toBe("tool");
        expect(itemKindLabel("subscription")).toBe("subscription");
    });

    it("publishing a subscription also selects its changed workflow", () => {
        const items = [
            { id: 1, type: "workflow", name: "wf", changed: true, contentRevision: "r", lastAuthor: "user" },
            { id: 2, type: "subscription", name: "on-pr", changed: true, contentRevision: "r", lastAuthor: "user" },
        ] as CompositionItem[];
        const contents = {
            1: { id: "wf", name: "wf", nodes: [], edges: [] },
            2: { name: "on-pr", routing: [{ type: "create-workflow", workflowDefinitionName: "wf" }, { type: "workflow-dispatch" }] },
        };
        expect(publishSelection(items, contents, [2])).toEqual([1, 2]);
    });
```

`toolCards.test.ts`: assert `isItemType("subscription")` is true (follow the file's existing tests for
`isItemType`/`kindLabel`; add `kindLabel("subscription") === "subscription"` if `kindLabel` is exported).

- [ ] **Step 2: Run to verify failure** — `cd ui && timeout 280 npx vitest run src/config/compositions.test.ts src/pages/workbench/workbenchLogic.test.ts src/pages/workbench/toolCards.test.ts`. Expected: failures.

- [ ] **Step 3: Implement**

`compositions.ts`: extend `CompositionItemType`; add `enabled?: boolean;` with JSDoc "For a published subscription
item: whether the published subscription is enabled." to `CompositionItem`; add

```ts
/** Enables or disables a published subscription item; enabling skips events before now. */
export function setItemEnabled(id: number, itemId: number, enabled: boolean): Promise<CompositionItem> {
    return request("PUT", `${c(id)}/items/${itemId}/enabled`, { enabled });
}
```

`api.ts`: add `errorCount?: number;` and `firstError?: string;` to `SubscriptionPreviewResponse`.

`workbenchLogic.ts`:

```ts
/**
 * Human-readable kind of a composition item, for messages and dialogs.
 * @param type the item type
 * @returns e.g. "action type"
 */
export function itemKindLabel(type: CompositionItemType): string {
    return type === "action-type" ? "action type" : type;
}
```

and in `publishSelection` add a branch:

```ts
        } else if (item.type === "subscription") {
            const routing = (content.routing as { type?: string; workflowDefinitionName?: string }[] | undefined) ?? [];
            deps = routing.filter((r) => r.type === "create-workflow" && !!r.workflowDefinitionName)
                .map((r) => findChanged("workflow", r.workflowDefinitionName as string))
                .filter((d: CompositionItem | undefined): d is CompositionItem => !!d);
        }
```

`useWorkbench.ts`: where action-type contents are loaded (~l.262-275), load contents for items of type
`"action-type"` **or** `"subscription"` (so `publishSelection` sees subscription routing).

`toolCards.ts` / `WorkbenchToolCards.tsx`: add `"subscription"` to the `ItemType` union, `isItemType`, and
`kindLabel` (label "subscription"); widen `openItem`'s type parameter.

- [ ] **Step 4: Run** — `cd ui && timeout 250 npx tsc -b --noEmit && timeout 280 npx vitest run`. Expected: all
pass (fix any exhaustive `switch`/`Record<CompositionItemType, ...>` the type change breaks).

- [ ] **Step 5: Commit**

```bash
git add ui && git commit -m "feat(workbench-ui): subscription item type in the data layer and workbench logic"
```

---

### Task 6: UI — Subscriptions group, subscription editor, Matching events and Enabled switch

**Files:**
- Create: `ui/src/components/SubscriptionItemEditor.tsx`, `ui/src/components/SubscriptionItemEditor.test.tsx`
- Modify: `ui/src/pages/workbench/ItemTab.tsx` (editor switch; new props), `ui/src/pages/workbench/WorkbenchPage.tsx`
  (Create menu, ItemTab props, no Test for subscriptions), `ui/src/pages/workbench/CompositionTree.tsx` (GROUPS,
  labels via `itemKindLabel`, AddItemDialog new-only for subscriptions, `onTest` only for action types/tools)
- Test: `ui/src/pages/workbench/ItemTab.test.tsx`, `ui/src/pages/workbench/CompositionTree.test.tsx`

**Interfaces:**
- Consumes: Task 5 (`CompositionItemType` incl. subscription, `CompositionItem.enabled`, `setItemEnabled`,
  `itemKindLabel`), `previewSubscriptionFilter` from `config/api` (`{filterExpression, limit}` →
  `{results: {event: StreamEvent, matched}[], totalMatched, errorCount?, firstError?}`).
- Produces: `SubscriptionItemEditor` props:

```ts
export interface SubscriptionItemEditorProps {
    value: Record<string, unknown>;
    onChange(value: Record<string, unknown>): void;
    readOnly?: boolean;
    problems?: ValidationMessage[];
    /** Name of the composition's workflow; create-workflow rules always target it. */
    workflowName: string;
    /** Whether the item has been published (the Enabled switch is shown only then). */
    published: boolean;
    /** Whether the composition's workflow is published (the switch is disabled otherwise). */
    workflowPublished: boolean;
    enabled: boolean;
    onToggleEnabled(enabled: boolean): void;
}
```

  `ItemTabProps` gains `workflowName?: string; workflowPublished?: boolean; onItemChanged?: (item: CompositionItem) => void`.

- [ ] **Step 1: Write failing tests** — `SubscriptionItemEditor.test.tsx`:

```tsx
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { SubscriptionItemEditor } from "./SubscriptionItemEditor";
import * as api from "../config/api";

vi.mock("../config/api", () => ({ previewSubscriptionFilter: vi.fn() }));

const base = { name: "on-pr", description: "", filterExpression: "event.type == 'pr.created'",
    routing: [{ type: "create-workflow", workflowDefinitionName: "wf" }] };

function renderEditor(over: Partial<Parameters<typeof SubscriptionItemEditor>[0]> = {}) {
    const props = { value: base, onChange: vi.fn(), workflowName: "wf", published: false, workflowPublished: false,
        enabled: false, onToggleEnabled: vi.fn(), ...over };
    render(<MemoryRouter><SubscriptionItemEditor {...props} /></MemoryRouter>);
    return props;
}

beforeEach(() => {
    vi.mocked(api.previewSubscriptionFilter).mockReset().mockResolvedValue({
        results: [
            { matched: true, event: { id: "e1", type: "pr.created", ref: "https://x/pull/1", timestamp: "2026-10-15T10:00:00Z" } },
            { matched: false, event: { id: "e2", type: "issue.created", ref: "https://x/issues/2", timestamp: "2026-10-15T09:00:00Z" } },
        ], totalMatched: 1, totalCount: 2, page: 1, limit: 200, errorCount: 0,
    } as never);
});

describe("SubscriptionItemEditor", () => {
    it("shows the fields and that start rules target the composition's workflow", () => {
        renderEditor();
        expect(screen.getByLabelText("Name")).toHaveValue("on-pr");
        expect(screen.getByLabelText("Filter expression")).toHaveValue("event.type == 'pr.created'");
        expect(screen.getByText(/Starts workflow/)).toHaveTextContent("Starts workflow wf");
    });

    it("previews matching recent events", async () => {
        renderEditor();
        expect(await screen.findByText("1 of 2 recent events match")).toBeInTheDocument();
        expect(screen.getByRole("link", { name: /https:\/\/x\/pull\/1/ })).toHaveAttribute("href", "/events/stream/e1");
        expect(api.previewSubscriptionFilter).toHaveBeenCalledWith({ filterExpression: base.filterExpression, limit: 200 });
    });

    it("adds a dispatch rule and a start rule bound to the workflow", async () => {
        const props = renderEditor({ value: { ...base, routing: [] } });
        await userEvent.click(screen.getByRole("button", { name: "Add start rule" }));
        expect(props.onChange).toHaveBeenLastCalledWith(expect.objectContaining({
            routing: [{ type: "create-workflow", workflowDefinitionName: "wf" }] }));
        await userEvent.click(screen.getByRole("button", { name: "Add dispatch rule" }));
        expect(props.onChange).toHaveBeenCalledWith(expect.objectContaining({
            routing: [{ type: "workflow-dispatch" }] }));
    });

    it("hides the Enabled switch until published and disables it while the workflow is unpublished", () => {
        renderEditor();
        expect(screen.queryByRole("switch", { name: "Enabled" })).toBeNull();
    });

    it("toggles enabled when published", async () => {
        const props = renderEditor({ published: true, workflowPublished: true });
        await userEvent.click(screen.getByRole("switch", { name: "Enabled" }));
        expect(props.onToggleEnabled).toHaveBeenCalledWith(true);
    });

    it("keeps the switch disabled while the workflow is unpublished", () => {
        renderEditor({ published: true, workflowPublished: false });
        expect(screen.getByRole("switch", { name: "Enabled" })).toBeDisabled();
    });
});
```

Add to `CompositionTree.test.tsx` (follow its render helper): a subscription item renders under a "Subscriptions"
group and its row has no Test button (`queryByRole("button", { name: /Test/ })` within that row is null). Add to
`ItemTab.test.tsx` (follow its mocks): a subscription item renders the subscription editor (`getByLabelText("Filter
expression")`) instead of the action-type editor.

- [ ] **Step 2: Run to verify failure** — `cd ui && timeout 280 npx vitest run src/components/SubscriptionItemEditor.test.tsx src/pages/workbench/ItemTab.test.tsx src/pages/workbench/CompositionTree.test.tsx`.

- [ ] **Step 3: Implement `SubscriptionItemEditor.tsx`**

```tsx
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
    Alert, Button, Flex, FlexItem, Form, FormGroup, HelperText, HelperTextItem, Switch, TextArea, TextInput,
    Title, Tooltip,
} from "@patternfly/react-core";
import { TrashIcon } from "@patternfly/react-icons";
import { previewSubscriptionFilter, type SubscriptionPreviewResponse } from "../config/api";
import type { ValidationMessage } from "./ActionTypeEditor";

const PREVIEW_DEBOUNCE_MS: number = 500;
const PREVIEW_LIMIT: number = 200;

type Rule = { type: string; workflowDefinitionName?: string };

/** Props of {@link SubscriptionItemEditor}. */
export interface SubscriptionItemEditorProps {
    /** The item content: `{name, description, filterExpression, routing, labels}`. */
    value: Record<string, unknown>;
    /** Called with the whole new content on every change. */
    onChange(value: Record<string, unknown>): void;
    /** Disables editing (e.g. while the AI edits the item). */
    readOnly?: boolean;
    /** Server validation problems to show above the form. */
    problems?: ValidationMessage[];
    /** Name of the composition's workflow; create-workflow rules always target it. */
    workflowName: string;
    /** Whether the item has been published (the Enabled switch is shown only then). */
    published: boolean;
    /** Whether the composition's workflow is published (the switch is disabled otherwise). */
    workflowPublished: boolean;
    /** Whether the published subscription is enabled. */
    enabled: boolean;
    /** Called when the user flips the Enabled switch. */
    onToggleEnabled(enabled: boolean): void;
}

/**
 * Editor for a workbench subscription item: fields, routing rules bound to the composition's workflow, a preview
 * of the recent events the filter matches, and (once published) the Enabled switch.
 */
export function SubscriptionItemEditor(props: SubscriptionItemEditorProps) {
    const { value, onChange, readOnly, problems, workflowName } = props;
    const routing: Rule[] = (value.routing as Rule[] | undefined) ?? [];
    const filter: string = (value.filterExpression as string | undefined) ?? "";
    const [preview, setPreview] = useState<SubscriptionPreviewResponse | null>(null);
    const [previewError, setPreviewError] = useState<string | null>(null);

    useEffect(() => {
        const timer = setTimeout(() => {
            previewSubscriptionFilter({ filterExpression: filter, limit: PREVIEW_LIMIT })
                .then((r) => { setPreview(r); setPreviewError(null); })
                .catch((e: unknown) => setPreviewError(e instanceof Error ? e.message : String(e)));
        }, PREVIEW_DEBOUNCE_MS);
        return () => clearTimeout(timer);
    }, [filter]);

    const set = (field: string, v: unknown) => onChange({ ...value, [field]: v });
    const setRouting = (rules: Rule[]) => set("routing", rules);
    const matches = (preview?.results ?? []).filter((r) => r.matched);

    return (
        <div className="axiom-subscription-item-editor">
            {props.published && (
                <Flex justifyContent={{ default: "justifyContentFlexEnd" }}>
                    <FlexItem>
                        <Tooltip content="Publish the workflow before enabling this subscription"
                            trigger={props.workflowPublished ? "manual" : "mouseenter focus"}>
                            <Switch id="subscription-enabled" label="Enabled" aria-label="Enabled"
                                isChecked={props.enabled} isDisabled={!props.workflowPublished}
                                onChange={(_e, checked) => props.onToggleEnabled(checked)} />
                        </Tooltip>
                    </FlexItem>
                </Flex>
            )}
            {(problems ?? []).length > 0 && (
                <Alert isInline variant="warning" title="Problems">
                    <ul>{(problems ?? []).map((p, i) => <li key={i}>{p.field ? `${p.field}: ` : ""}{p.message}</li>)}</ul>
                </Alert>
            )}
            <Form onSubmit={(e) => e.preventDefault()}>
                <FormGroup label="Name" fieldId="subscription-name">
                    <TextInput id="subscription-name" value={(value.name as string) ?? ""} isDisabled={readOnly}
                        onChange={(_e, v) => set("name", v)} />
                </FormGroup>
                <FormGroup label="Description" fieldId="subscription-description">
                    <TextArea id="subscription-description" value={(value.description as string) ?? ""}
                        isDisabled={readOnly} rows={2} onChange={(_e, v) => set("description", v)} />
                </FormGroup>
                <FormGroup label="Filter expression" fieldId="subscription-filter">
                    <TextArea id="subscription-filter" className="axiom-monospace" value={filter} rows={4}
                        isDisabled={readOnly} onChange={(_e, v) => set("filterExpression", v)} />
                </FormGroup>
                <FormGroup label="Routing" fieldId="subscription-routing">
                    {routing.map((rule, i) => (
                        <Flex key={i} alignItems={{ default: "alignItemsCenter" }}>
                            <FlexItem>
                                {rule.type === "create-workflow" ? `Starts workflow ${workflowName}`
                                    : "Delivers events to waiting runs (receive-event nodes)"}
                            </FlexItem>
                            <FlexItem>
                                <Button variant="plain" aria-label={`Remove rule ${i + 1}`} isDisabled={readOnly}
                                    icon={<TrashIcon />} onClick={() => setRouting(routing.filter((_, j) => j !== i))} />
                            </FlexItem>
                        </Flex>
                    ))}
                    <Flex>
                        <Button variant="link" isInline isDisabled={readOnly}
                            onClick={() => setRouting([...routing,
                                { type: "create-workflow", workflowDefinitionName: workflowName }])}>
                            Add start rule
                        </Button>
                        <Button variant="link" isInline isDisabled={readOnly}
                            onClick={() => setRouting([...routing, { type: "workflow-dispatch" }])}>
                            Add dispatch rule
                        </Button>
                    </Flex>
                </FormGroup>
            </Form>
            <Title headingLevel="h3" size="md">Matching events</Title>
            {previewError && <Alert isInline variant="danger" title={`Could not preview: ${previewError}`} />}
            {preview && (
                <>
                    <p>{`${matches.length} of ${preview.results.length} recent events match`}</p>
                    {(preview.errorCount ?? 0) > 0 && (
                        <HelperText><HelperTextItem variant="warning">
                            {`${preview.errorCount} event(s) could not be evaluated: ${preview.firstError}`}
                        </HelperTextItem></HelperText>
                    )}
                    <ul>
                        {matches.map((m) => (
                            <li key={m.event.id}>
                                <Link to={`/events/stream/${m.event.id}`}>{`${m.event.type} ${m.event.ref}`}</Link>
                                {` ${new Date(m.event.timestamp).toLocaleString()}`}
                            </li>
                        ))}
                    </ul>
                </>
            )}
        </div>
    );
}
```

If
`SubscriptionPreviewResponse`'s field types in `api.ts` differ (e.g. `results` optional, `event.timestamp`
type), adapt the accessors. If the `Tooltip` `trigger` prop is not accepted, wrap the switch in a `Tooltip` only
when `!props.workflowPublished`. Add `.axiom-monospace { font-family: var(--pf-t--global--font--family--mono); }`
to `ui/src/axiom-theme.css` if no monospace utility class exists (grep first).

- [ ] **Step 4: Wire it in**

`ItemTab.tsx`: add the three new optional props; replace the editor ternary with

```tsx
            {item.type === "subscription" ? (
                <SubscriptionItemEditor value={value} readOnly={locked} problems={messages}
                    onChange={onChange} workflowName={props.workflowName ?? ""}
                    published={item.baseItemId != null} workflowPublished={props.workflowPublished ?? false}
                    enabled={item.enabled ?? false}
                    onToggleEnabled={(enabled: boolean) => {
                        setItemEnabled(compositionId, item.id, enabled)
                            .then((updated) => props.onItemChanged?.(updated))
                            .catch((e: unknown) => setSaveError({ kind: "error",
                                message: e instanceof Error ? e.message : String(e) }));
                    }} />
            ) : item.type === "tool" ? (
                // No toolId: the workbench tests tools through the Test output strip, not the editor's Test tab.
                <ToolEditor value={value as unknown as NewToolDefinition} readOnly={locked} problems={messages}
                    onAskAiAboutProblem={askAiAboutProblem} askAiDisabled={aiWorking} fillHeight
                    onChange={(v: NewToolDefinition) => onChange(v as unknown as Record<string, unknown>)} />
            ) : (
                <ActionTypeEditor value={value as unknown as NewActionType} readOnly={locked} problems={messages}
                    onAskAiAboutProblem={askAiAboutProblem} askAiDisabled={aiWorking} fillHeight
                    onChange={(v: NewActionType) => onChange(v as unknown as Record<string, unknown>)} />
            )}
```

(import `SubscriptionItemEditor` and `setItemEnabled`; `onChange` is ItemTab's existing change handler). Use `itemKindLabel(item.type)` in
`problemPrefill`. Update the component JSDoc ("action type, tool or subscription").

`WorkbenchPage.tsx`: add `<DropdownItem value="subscription">Subscription</DropdownItem>` to the Create menu; pass
to every `ItemTab` `workflowName={workflowItem?.name}` and `workflowPublished={workflowItem?.baseItemId != null}`
(compute `workflowItem = wb.items.find((i) => i.type === "workflow")`) and `onItemChanged` that replaces the item in
the workbench state (use the same updater `onSaved` uses for item metadata — read `useWorkbench` for the setter, e.g.
`wb.updateItem` / `setItems`; add a small `replaceItem(item)` to `useWorkbench` if none exists, with a test in
`useWorkbench.test.tsx`).

`CompositionTree.tsx`: add `{ type: "subscription", title: "Subscriptions" }` to `GROUPS`; pass `onTest` only when
`item.type === "action-type" || item.type === "tool"`; replace the three `type === "tool" ? "tool" : "action type"`
ternaries with `itemKindLabel(...)`; in `AddItemDialog`, for `type === "subscription"` hide the New/Existing toggle
and the existing-items list (do not call `fetchTools`/`fetchActionTypes`), submitting `{ type, name }`.

- [ ] **Step 5: Run** — `cd ui && timeout 250 npx tsc -b --noEmit && timeout 280 npx vitest run`. Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add ui && git commit -m "feat(workbench-ui): subscriptions group, subscription editor with matching events and Enabled switch"
```

---

### Task 7: Documentation

**Files:**
- Modify: `docs/user-guide/workbench.md`, `docs/user-guide/events.md` (or whichever user-guide page documents
  subscriptions — `grep -ln "Subscriptions" docs/user-guide/*.md`)

- [ ] **Step 1: Write the docs** (wrap at 110):
  - `workbench.md`: a "Subscriptions" section — what they are for in a composition (start runs with a
    `create-workflow` rule to the composition's workflow; feed receive-event nodes with a `workflow-dispatch`
    rule); creating one (Create → Subscription, or ask the AI); the editor (fields, routing rules, Matching events
    preview over the 200 most recent events); validation (`FOREIGN_WORKFLOW_ROUTE`, `ENABLED_IGNORED`,
    `OVERLAPPING_SUBSCRIPTION`); publishing (created disabled; republishing keeps the enabled state and
    `processEventsFrom`; needs the workflow published or published with it); the **Enabled** switch (appears once
    published; disabled until the workflow is published; enabling ignores earlier events); removing a draft or
    deleting the composition leaves the published subscription as it is. Mention the AI's `preview_subscription`
    tool in the AI tools list if the page has one.
  - The subscriptions user-guide page: one paragraph noting that workbench compositions can own subscriptions and
    that the preview reports evaluation errors.
- [ ] **Step 2: Verify** — `grep -n "subscription" docs/user-guide/workbench.md | head` shows the new section; no
  line of prose over 110 characters (`awk 'length > 110 && !/^\|/' docs/user-guide/workbench.md` prints nothing new).
- [ ] **Step 3: Commit**

```bash
git add docs && git commit -m "docs: workbench subscriptions"
```
