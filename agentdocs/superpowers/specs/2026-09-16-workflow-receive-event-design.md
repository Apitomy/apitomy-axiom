# Workflow Receive Event Node Support — Design

**Date:** 2026-09-16
**Epic:** Custom Workflow Feature for Axiom (Phase 4 — Event Correlation)
**Prerequisites:** Phase 2 (Workflow Execution), Phase 3 (Human Tasks), Wait node support
(PR #324) — all complete

## Summary

Implement execution support for the `receive-event` node type. A workflow branch that enters a
receive-event node parks until a matching Axiom event arrives, then resumes automatically with
the event's data merged into the workflow context. This is the second (and final) node type from
the deferred "Phase 4" scope in the Phase 2 design
(`2026-08-26-workflow-execution-phase2-design.md`).

## Engine semantics (apitomy-flow-engine 2.0.2)

A receive-event node's config contains:

- **`eventType`** (string, required) — matched via string equality against `event.get("type")`
  in the candidate event map.
- **`match`** (list of EL expression strings, optional) — each evaluated via
  `ConditionEvaluator.evaluate(expr, instance.context(), event)`; all must pass.

The engine provides:

- `getReceiveEventInfo(workflow, instance, nodeId)` →
  `ReceiveEventInfo(nodeId, nodeName, eventType, matchExpressions)` — introspection at park
  time, analogous to `getWaitInfo` / `getHumanTaskInfo`.
- `matchesEvent(workflow, instance, nodeId, Map<String,Object> event)` — returns true only if
  the instance is WAITING, the node is a receive-event node, `config.eventType` equals
  `event["type"]`, and every `match` expression evaluates true. Expression evaluation failures
  are logged by the engine and treated as non-matches.
- Resumption uses the same `completeNode(workflow, instance, nodeId, result)` call as all other
  node types; the `NodeResult` outputs merge into workflow context.

## Design decisions

These were settled during brainstorming:

1. **Hybrid event scoping** — events that correlate to a project (via
   `project.ref == event.issueRef`, else `event.projectId`) are offered only to that project's
   waiting runs; events with no project correlation are broadcast to all waiting receive-event
   subscriptions.
2. **Orthogonal to the pipeline** — receive-event matching is an additional step in event
   processing. The event still flows through source filters and AI Manager evaluation as usual.
   A matched event is not "consumed."
3. **Live-only matching** — a receive-event node only matches events processed after its branch
   parks. No backfill of earlier events. A stuck run can be cancelled and re-triggered.
4. **Curated event map** — a stable, documented event shape (below) is used both for `match`
   expression evaluation and as the node's output merged into workflow context.
5. **Subscription table (Approach A)** — parked receive-event branches are tracked in a
   dedicated table with a denormalized `event_type` for cheap prefiltering, mirroring the
   `workflow_wait` pattern. Stale rows are harmless because the engine's `matchesEvent`
   re-validates against actual instance state.

## Data model

New table `workflow_event_subscription` (migration `V59__create_workflow_event_subscription.sql`)
and `WorkflowEventSubscriptionEntity` in the core module:

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | `bigint` | PK | sequence `workflow_event_subscription_SEQ` (start 1, increment 50) |
| `run_id` | `bigint` | FK → workflow_run, NOT NULL | Owning run |
| `node_id` | `varchar(255)` | NOT NULL | The parked receive-event node |
| `event_type` | `varchar(255)` | NOT NULL | Denormalized from node config for prefiltering |
| `project_id` | `bigint` | NOT NULL | Denormalized from the run for scoped lookup |
| `created_on` | `timestamp` | NOT NULL | |

Indexes: `(event_type)`, `(run_id)`.

Row lifecycle matches `workflow_wait`: created when a branch parks on a receive-event node,
deleted when the branch resumes or the run is cancelled.

## WorkflowExecutionService changes

- Add `NodeType.RECEIVE_EVENT` to `SUPPORTED_NODE_TYPES` and update the 400 error message in
  `validateNodeTypes`.
- `createTaskForNode`: check `getReceiveEventInfo(workflow, instance, nodeId)` after the wait
  check and before the action fallback. If non-null, call
  `createEventSubscriptionForNode(entity, receiveEventInfo)`:
  - persist a `WorkflowEventSubscriptionEntity`,
  - add a trace node (`"Awaiting event: <eventType>"`),
  - log and fire the workflow-updated SSE event,
  - create no `TaskEntity` (no user action is involved).
- New `onEventReceived(long runId, String nodeId, Map<String,Object> eventMap)`:
  - loads the run, workflow content, and instance state,
  - builds `NodeResult(COMPLETED, Map.of("event", eventMap))`,
  - delegates to the existing `advanceWorkflow(...)` helper (shared with `onTaskCompleted` and
    `onWaitElapsed`).
  - The nested `event` key gives downstream nodes context access such as
    `event.payload.number`.
- `cancelWorkflow`: also delete the run's subscriptions
  (`WorkflowEventSubscriptionEntity.delete("runId", entity.id)`).

## Event map shape

Built once per event by a new `WorkflowEventMapper` (app module):

```java
{
  "type":       event.eventType,      // engine matches config.eventType against "type"
  "source":     event.source,
  "issueRef":   event.issueRef,       // nullable
  "repository": event.repository,     // nullable
  "receivedAt": event.receivedAt.toString(),
  "payload":    <EventEntity.payload parsed to Map; Map.of() if null or unparseable>
}
```

This exact map is passed to `matchesEvent` (so `match` expressions can reference `payload.*`,
`issueRef`, etc.) and, on match, becomes the node's output under the `event` key.

## Pipeline hook

New `@ApplicationScoped WorkflowEventDispatcher` (app module) with one entry point
`dispatchEvent(EventEntity event)`, called from `PipelineOrchestrator.processEvent` immediately
after the event is marked `filterStatus = "allowed"` and before Manager evaluation. The call is
wrapped so a dispatch failure is logged and never blocks normal pipeline processing.

Dispatch logic:

1. Prefilter: `WorkflowEventSubscriptionEntity.list("eventType", event.eventType)` — usually
   empty; exit fast.
2. Resolve the project using the same correlation as the orchestrator
   (`project.ref == event.issueRef`, else `event.projectId`). Apply hybrid scoping:
   - project resolved → keep only subscriptions with that `projectId`;
   - no project → keep all candidates (broadcast).
3. For each candidate subscription: load the run (skip if missing or terminal), load workflow
   content and instance state, call
   `workflowEngine.matchesEvent(workflow, instance, sub.nodeId, eventMap)`.
4. On match, in one transaction: delete the subscription row and call
   `workflowExecutionService.onEventReceived(runId, nodeId, eventMap)`. One event may resume
   multiple runs; each resumption is independent, and a per-run failure is logged without
   stopping the others or the event.

No scheduler/polling is needed — dispatch is synchronous within event processing, which is
already asynchronous via the event queue.

## Error handling

- `dispatchEvent` never propagates exceptions into the pipeline (log + continue to Manager).
- Per-subscription try/catch: one bad run (corrupt instance state, missing definition version)
  does not block other matches or the event.
- `match` expression failures are handled inside the engine (logged, non-match) — no Axiom-side
  handling required.
- Stale subscription rows (e.g. run advanced through another path) are safe: `matchesEvent`
  returns false for non-WAITING instances; the row is cleaned up on run cancellation and can
  also be deleted opportunistically when detected against a terminal run.

## Testing

- `WorkflowExecutionServiceTest`:
  - parking on a receive-event node creates a subscription and no task;
  - `onEventReceived` advances the run to completed with `event` present in context;
  - cancelling a run deletes its subscriptions.
- New `WorkflowEventDispatcherTest`:
  - eventType prefilter — a non-matching event type resumes nothing;
  - project scoping — an event correlated to project A does not resume project B's run;
  - broadcast — an event with no project correlation resumes any matching run;
  - `match` expression filtering — a false expression leaves the run parked and the
    subscription retained;
  - end-to-end — an event resuming a run through to completion via the dispatcher path.
- `WorkflowEventMapper` unit tests: payload parsing, null/unparseable payload safety.

## Out of scope

- Backfill matching of events processed before a branch parks (live-only for this iteration).
- Declared-outputs extraction for receive-event nodes (mapping selected event fields to named
  node outputs) — possible follow-up.
- Consume-on-match pipeline semantics (suppressing Manager evaluation for matched events).
- UI changes — `WorkflowViewer` (`@apitomy/flow-ui`) already renders receive-event nodes
  generically from instance/history data, and the editor already supports configuring
  `eventType` and `match`.
