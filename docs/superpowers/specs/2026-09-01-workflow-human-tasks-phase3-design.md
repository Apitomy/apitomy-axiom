# Workflow Human Tasks (Phase 3) — Design

**Status:** Approved for planning (2026-09-01)

**GitHub issue:** #228 (workflow epic); Flow-side prerequisite: apitomy-flow#55 / PR #57 (merged)

## Summary

Phase 3 makes **human-task** nodes executable in Axiom workflows. When a running workflow reaches a
`HUMAN_TASK` node, the Flow engine parks the instance in `WAITING`; Axiom turns that parked node into
an **inbox task** (`status = "AwaitingInput"`) whose human-facing context and completion form are
derived directly from the node's Flow configuration. A user completes the task through the existing,
unified inbox completion flow; Axiom coerces the answers to the node's declared semantic types and
resumes the engine via `completeCurrentNode`, merging the answers into workflow context so downstream
edge conditions and nodes can consume them.

Scope is **human-task only**. `wait` and `receive-event` nodes remain rejected at trigger time and are
deferred to a later phase.

## Background — the two sides

**Flow engine (1.0.3+, PR #57 merged).** The engine is stateless and synchronous. Reaching a
`HUMAN_TASK` node sets `InstanceStatus.WAITING` and fires `onNodeEntered`; no `NodeExecutor` runs.
While parked, `engine.getHumanTaskInfo(workflow, instance)` returns a `HumanTaskInfo`:

```
HumanTaskInfo(String nodeId, String nodeName, String description,
              Map<String,Object> inputs,              // display-only, EL already resolved
              List<OutputDefinition> outputs)          // the form fields the human fills in

OutputDefinition(String name, String type, boolean required,
                 String label, String description, String widget,
                 Object defaultValue, List<OutputOption> options)

OutputOption(String label, String value)
```

The engine applies server-side defaults in `getHumanTaskInfo`: `label` defaults to `name`, and
`widget` is inferred from the semantic `type` when absent — `string→text`, `number→number`,
`boolean→checkbox`, `object→textarea`. So Axiom always receives a concrete `widget` from the set
`{text, textarea, select, number, checkbox}`, and the semantic `type` stays available separately
(`string | number | boolean | object`). The engine does **not** validate the human's submitted answers
against `outputs` — that is the host's responsibility.

Resume is `engine.completeCurrentNode(workflow, instance, new NodeResult(status, output))`;
`COMPLETED` merges `output` into instance context, `FAILED` routes to error handling.

**Axiom inbox (current).** The inbox is a view over `TaskEntity` rows where `status =
"AwaitingInput"`. A task carries two optional JSON columns: `humanContext`
(`{title, description, references[]}`) and `outputSchema` (`{fields[]}`, each field
`{name, type, label, description, required, defaultValue, options[]}`, `type` ∈
`text|textarea|boolean|select|number`). `TaskEntity` already has `nodeId` and `workflowRunId` columns.
Task completion already advances workflows: `TaskExecutionService.onTaskCompleted` calls
`workflowExecutionService.onTaskCompleted(taskId)` when `workflowRunId != null`.

Two problems this phase fixes: (1) `markTaskAwaitingInput` is only reachable from `POST /inbox`, so
workflow-created human tasks would never surface; (2) two divergent completion paths exist —
`POST /inbox/{id}/complete` (structured, schema-validated) and
`POST /projects/{id}/tasks/{taskId}/respond` (free-form text, ignores schema).

## Design

### 1. Enable human-task execution

`WorkflowExecutionService.SUPPORTED_NODE_TYPES` gains `NodeType.HUMAN_TASK`. The rejection message
becomes: *"Workflow contains unsupported node types: … Supported: start, end, action, human-task."*
`wait` and `receive-event` still 400 at trigger time.

### 2. Create the inbox task inline (decision: inline in ExecutionService)

`createTaskForCurrentNode` branches by asking the engine which kind of parked node it is:

```
HumanTaskInfo hti = workflowEngine.getHumanTaskInfo(workflow, instance);
if (hti != null) { createHumanTaskForNode(entity, instance, hti); return; }
ActionInfo ai = workflowEngine.getActionInfo(workflow, instance);
... existing action-task path ...
```

`createHumanTaskForNode` builds a `TaskEntity`:

- `projectId`, `workflowRunId = run.id`, `nodeId = instance.currentNodeId()`, `traceId = run.traceId`
- `createdBy = "workflow"`, `createdOn = now()`, `status = "Pending"` (flipped below)
- `actionType = hti.nodeName()` when non-blank, else `"Human Task"` (column is `NOT NULL`; also the
  inbox/UI display label)
- `humanContext` = JSON of `WorkflowHumanTaskMapper.toHumanContext(hti)`
- `outputSchema` = JSON of `WorkflowHumanTaskMapper.toOutputSchema(hti.outputs())` (null when the node
  declares no outputs — the inbox then falls back to a free-form response)

After persist + trace-node creation, it calls `taskExecutionService.markTaskAwaitingInput(task.id)` to
flip the task to `AwaitingInput` and fire the inbox SSE events. `WorkflowExecutionService` gains an
`@Inject TaskExecutionService` (CDL circular reference with the existing
`TaskExecutionService → WorkflowExecutionService` injection is resolved by CDI client proxies; both are
`@ApplicationScoped`).

### 3. Mapping (`WorkflowHumanTaskMapper`, new)

A cohesive, unit-tested helper in the `app` module.

**`HumanContext toHumanContext(HumanTaskInfo hti)`**
- `title` = `hti.nodeName()` (fallback `"Human Task"`)
- `description` = `hti.description()` (nullable)
- `details` = one `{label, value}` per entry of `hti.inputs()` (display-only resolved values;
  `value = String.valueOf(v)`, `null → ""`). Requires a new optional `details` field on `HumanContext`
  (see §5).

**`OutputSchema toOutputSchema(List<OutputDefinition> outputs)`** — `null` when `outputs` is null/empty.
Per output:
- `name` = `name`
- `type` = `widgetToFieldType(widget)`: `checkbox → boolean`; `text|textarea|select|number` pass through
  verbatim (they already match the `OutputSchemaField` enum). Unknown/null widget → `text`.
- `label` = `label` (engine already defaults it to `name`)
- `description` = `description`
- `required` = `required`
- `defaultValue` = `defaultValue`
- `options` = `[{label, value}]` from `options` (only meaningful for `select`)

**`Map<String,Object> coerceAnswers(List<OutputDefinition> outputs, Map<String,Object> answers)`** —
coerces each submitted value to the node's **semantic** `type` so context comparisons in edge
conditions behave (e.g. `context.approved == true`, `context.score > 700`). For each output, `v =
answers.get(name)`; skip when `v == null`. Then:
- `number`: `Number` as-is; `String` → `Long` if integral else `Double`; else keep
- `boolean`: `Boolean` as-is; `String` → `Boolean.parseBoolean`; else keep
- `object`: `Map`/`List` as-is; `String` → JSON-parse (fallback: keep original string)
- default (`string`/unknown): `String.valueOf(v)`

Answers whose keys are not declared outputs are passed through unchanged (defensive; the engine merges
them into context regardless).

### 4. Resume the workflow on completion

`WorkflowExecutionService.onTaskCompleted` branches the same way. After loading `workflow` and the
still-`WAITING` `instance`:

```
HumanTaskInfo hti = workflowEngine.getHumanTaskInfo(workflow, instance);
if (hti != null) {
    NodeResult result = "Completed".equals(task.status)
        ? new NodeResult(COMPLETED,
              WorkflowHumanTaskMapper.coerceAnswers(hti.outputs(), parseOutputMap(task.output)))
        : new NodeResult(FAILED, Map.of());
    ... completeCurrentNode + advance (shared with action path) ...
} else {
    ... existing action path (ActionTypeIoValidator etc.) ...
}
```

Human-task completion deliberately **does not** run `ActionTypeIoValidator` (that validates action-type
output contracts). Required-field enforcement happens earlier in `completeInboxItem` via
`InboxResponseValidator`.

### 5. Unify completion (decision: unify now)

- **Single completion entry point:** `POST /inbox/{taskId}/complete` (`completeInboxItem`). It already
  validates against `outputSchema`, stores the response JSON in `task.output`, and calls
  `onTaskCompleted`, which advances the workflow. No new endpoint is needed.
- **Remove** `POST /projects/{projectId}/tasks/{taskId}/respond` and the `TaskResponse` bean from the
  OpenAPI spec, delete `ProjectsResourceImpl.respondToTask`, and remove `respondToTask` from the UI API
  client.
- **Project-detail UI:** the "Respond" affordance fetches the structured item via `fetchInboxItem`
  (`GET /inbox/{taskId}`) and renders `DynamicFormRenderer` bound to its `outputSchema`, submitting via
  `completeInboxItem`. This is identical to `InboxPage`, so schema-validated completion is the only path
  regardless of where the user responds.
- **New OpenAPI surface:** add optional `details: HumanContextDetail[]` to `HumanContext`, where
  `HumanContextDetail = {label: string, value: string}` (display-only context resolved from the node's
  Flow `inputs`). `InboxPage` and the project-detail responder render `details` read-only above the
  form.

### 6. Fixes folded in

- `InboxResourceImpl.parseOutputSchema` currently drops `defaultValue`; fix it to round-trip
  `defaultValue` so pre-filled human-task forms work.

### 7. Dependency bumps (prerequisite)

- `app/pom.xml`: `apitomy-flow-engine` `1.0.2 → 1.0.3` (the release containing PR #57; installed
  locally or published).
- `ui/package.json`: `@apitomy/flow-ui` `1.0.2 → 1.0.3` (so the embedded WorkflowEditor authors the
  rich human-task output metadata).

## Out of scope

- `wait` and `receive-event` nodes (still rejected at trigger).
- Engine-side validation of submitted human answers (host validates).
- Auto-triggering of workflows; assignment/reassignment of inbox tasks.

## Testing strategy

- **Unit** (`WorkflowHumanTaskMapper`): `toHumanContext` (title fallback, details from inputs),
  `toOutputSchema` (widget→type incl. `checkbox→boolean`, options, defaultValue, null-when-empty),
  `coerceAnswers` (number/boolean/object/string coercion from both native and string inputs).
- **Integration** (`@QuarkusTest`, RestAssured, model on `WorkflowInstanceResourceTest`): trigger a
  workflow whose Start→human-task→End reaches the human node → run `waiting`, an `AwaitingInput` inbox
  task exists with mapped `humanContext`/`outputSchema`; complete it via `POST /inbox/{id}/complete` →
  run advances to `completed` and a downstream edge keyed on an answer is followed.
- **Regression:** rewrite `testTriggerWithUnsupportedNodeTypesReturns400` to use a `receive-event`
  node (still unsupported) instead of `human-task`.
