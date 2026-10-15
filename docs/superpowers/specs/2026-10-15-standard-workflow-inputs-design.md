# Standard Workflow Inputs — Design

Date: 2026-10-15
Status: Approved in discussion; awaiting spec review

## Goal

Make a workflow's Start node the complete, explicit contract for what a run receives. A workflow declares
which of a small set of **standard inputs** it needs; when Axiom creates a run it satisfies the declared
inputs and nothing else. A run whose required inputs cannot be satisfied is not created.

## Decisions

| Topic | Decision |
|-------|----------|
| Standard inputs | Exactly two: `subject` (object) and `event` (object) |
| `subject` fields | `id`, `name`, `type`, `ref`, `refSource`, `repository` — nothing else |
| Undeclared inputs | Not provided: a run's initial context contains only the declared standard inputs |
| Custom inputs | Removed: a Start node may declare only standard inputs |
| Supplied run inputs | Removed everywhere (dialog, REST, schedule triggers, re-runs) |
| `defaultValue` on Start inputs | Removed (rejected at publish) |
| Required but unsatisfiable | Run creation fails; no run is created |
| New-workflow scaffold | Declares `subject` and `event`, both required |

## Current State (for reference)

- Axiom always puts `subjectId`, `subjectName`, `ref`, `repository` (and `event` when started from an event)
  into every run's context, declared or not (`WorkflowExecutionService.triggerWorkflow`).
- Start inputs may be custom; supplied run inputs (`RunRequest.inputs`) join the context; protected names are
  ignored; `applyStartInputDefaults` applies `defaultValue`s.
- The Flow engine (`NodeValueResolver.validateStartInputs`) only checks that required inputs are present and
  non-null; it does not check types or reject undeclared keys.
- Publish rules (`WorkflowContentRules.startInputViolation`): valid identifier names; `event`, `subjectName`
  and `repository` may not be required.

## 1. Standard Inputs Registry

A new class `io.apitomy.axiom.app.StandardInputs` (next to `WorkflowExecutionService`, its main user) is
the single source of truth. Each entry has a name, a type, a description and a
resolver:

| Input | Type | Value | Satisfied when |
|-------|------|-------|----------------|
| `subject` | `object` | `{id, name, type, ref, refSource, repository}` snapshot of the run's subject at run creation; absent values are `null` | always (every run has a subject) |
| `event` | `object` | the triggering event envelope (`id`, `type`, `source`, `eventSourceId`, `ref`, `timestamp`, `actor`, `payload`) — unchanged from today | the run was started from a stream event: subscription `create-workflow`, **Run workflow…** on an event, or a re-run of an event-started run |

`subject.id` is a number; the other `subject` fields are strings or `null`.

The registry is used by: run creation (resolution), publish/validation rules, the new-workflow scaffold, and
the assistant reference generator. Adding a standard input or a `subject` field later is a registry change.

## 2. Run Creation

`WorkflowExecutionService.triggerWorkflow` builds the initial context from the Start node's declared inputs:

1. For each declared input, ask the registry to resolve it for this run (subject + optional origin event).
2. Satisfied → put it in the context under its name.
3. Not satisfied and optional → leave it out (no key).
4. Not satisfied and required → collect it.
5. If any were collected, fail **before** creating the run with HTTP 400 and the message
   `Cannot start workflow "<name>": required input(s) not available: <names>`.

Undeclared standard inputs are never put in the context. `PROTECTED_INPUTS` and `applyStartInputDefaults` are
removed. The `event` value continues to come from `EventStreamOrchestrator.startRunFromEvent`, but as the
event origin passed to resolution rather than as free-form `extraContext`; `RunRequest.extraContext` is
removed if nothing else needs it.

Failure handling per trigger path (existing mechanisms, new message):

| Path | Outcome on unsatisfied required input |
|------|---------------------------------------|
| **Run workflow…** on an event (`POST /stream/events/{eventId}/runs`) | HTTP 400; message shown in the dialog |
| `POST /subjects/{subjectId}/workflow` (API only) | HTTP 400 |
| Re-run (`POST /workflow/runs/{runId}/rerun`) | HTTP 400; shown by the re-run UI |
| Schedule trigger | trigger `lastOutcome` = `failed: <message>` |
| Subscription `create-workflow` routing | recorded as a failed routing outcome via the existing failure path |

Runs that need subject data an action requires but the subject lacks (e.g. no repository) start normally and
fail at that node with the existing `Missing required input(s): …` task error. This is an accepted trade-off
of a single `subject` object.

## 3. Removing Supplied Inputs

- **REST/OpenAPI:** remove `inputs` from `TriggerWorkflow` (subject and event run creation bodies) and from
  `NewScheduleTrigger` / `ScheduleTrigger`.
- **Java:** remove `RunRequest.inputs`; re-runs no longer copy inputs (they re-resolve from the subject and
  original event); the schedule scheduler no longer passes static inputs.
- **Database (`V1__baseline.sql`, edited in place):** drop the schedule trigger `inputs` column. The run's
  `inputs` column stays (see below). A persistent database reset is required.
- **UI:** remove the Inputs field from **Run workflow…** (`RunWorkflowFromEventModal`) and the schedule
  trigger inputs from `WorkflowTriggersTab`; remove now-unused parsing helpers.
- **Stored run inputs:** `workflow_run.inputs` and the `inputs` field on `WorkflowInstance` /
  `WorkflowRunSummary` now hold the resolved standard inputs **excluding `event`** (the event is already
  linked as *Triggered by*), i.e. `{"subject": {...}}` when declared. The run Overview's **Inputs** field shows
  it as JSON unchanged.
- **SDK:** `axiom_get_workflow_run` keeps returning `inputs` (now the resolved inputs).

## 4. Publish and Validation Rules

`WorkflowContentRules.startInputViolation` (used by `WorkflowPublisher` and `ConfigItemValidationService`, so
it covers publish and the workbench's `propose_workflow_changes`) rejects a Start node input when:

1. its name is not a standard input name (message lists the standard inputs);
2. its declared `type` is set and differs from the standard type;
3. it declares a `defaultValue`.

The old rule forbidding `required` on `event`/`subjectName`/`repository` is removed; requiring `event` is how a
workflow says it must be started from an event.

4. an EL expression anywhere in the workflow (edge conditions, node input mappings, human-task
   title/description, receive-event correlation/match/output mappings) reads `context.subject` or
   `context.event` while the Start node does not declare that input. Detection is a token match on
   `context.subject` / `context.event` not followed by an identifier character, so `context.subjectX` does not
   match. Such a read can never succeed, so it blocks publish like the other rules.

## 5. New-Workflow Scaffold

`WorkflowTemplates.emptyWorkflow` declares:

```json
[
  {"name": "subject", "type": "object", "required": true,  "description": "The subject the run is about"},
  {"name": "event",   "type": "object", "required": true,  "description": "The event that started the run"}
]
```

Used by the workbench and by **Components → Workflows → new**. A schedule-triggered workflow must remove
`event` or make it optional.

## 6. Expressions and Built-in Content

`context.ref` → `context.subject.ref`, `context.subjectId` → `context.subject.id`, `context.subjectName` →
`context.subject.name`, `context.repository` → `context.subject.repository`. Update:

- `AssistantReferenceGenerator` text and the SDK node example;
- `ActionTypeValidator` hints (e.g. "map it from `context.subject.ref`");
- `assistant-reference/examples/workflows/issue-triage.json` and any other bundled workflows;
- `axiom-config-assistant.json` prompt text;
- user and developer docs.

Engine-supplied values that do not come from the context are unaffected: `sdk:` nodes still get `subjectId` and
`runId` from the run, and tasks still get the run's subject.

## 7. Workbench AI

The generated reference gains a **Start inputs** section, generated from the registry: a table of the
standard inputs with type, availability and (for `subject`) its fields, followed by the rules:

- declare only standard inputs; no other inputs and no `defaultValue`;
- mark `event` required only if the workflow must be started from an event (schedule-triggered workflows must
  not require it);
- read only declared inputs (`context.subject.<field>`, `context.event.<field>`).

The workbench prompt rule list gets a matching one-line rule.

## 8. Documentation

Rewrite `docs/developer-guide/workflow-input-contract.md` around the registry (and drop the stale subject
**Run Workflow** dialog text). Update `docs/user-guide/workflows.md` (inputs, triggers, schedule inputs, Run
workflow… dialog), `concepts.md`, `usage-examples.md` and `workflow-human-task-nodes.md` for the new
expressions.

## 9. Testing

- **Unit:** registry resolution (subject snapshot with nulls; event present/absent); publish rules (unknown
  name, wrong type, `defaultValue`, required `event` now allowed); undeclared-use detection; scaffold content;
  reference section content.
- **Integration (`@QuarkusTest`):** context contains only declared inputs; optional unsatisfied `event` is
  absent; required unsatisfied `event` fails for each event-less trigger path (subject REST API, schedule
  trigger outcome, re-run of a run that was not started from an event); stored run `inputs` excludes `event`; removed `inputs` fields are gone from the API.
- **UI (Vitest):** Run workflow… has no Inputs field; schedule trigger form has no inputs.
- Existing tests that rely on `context.ref`-style keys or supplied inputs are updated.

## Out of Scope

- Per-field requirements on `subject`.
- Any additional standard inputs or `subject` fields (`labels`, `body`, `metadata`, `trigger`).
- Changes to the Flow engine.
