# Run a Workflow from a Stream Event Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** On the event detail page (`/events/stream/:id`), the user can choose **Run workflow…**, pick any
published workflow (workflows a subscription runs for this event listed first), optionally set a title, labels
and inputs, and start a run from that event exactly as a subscription would. Runs started by the event (by
subscriptions or manually) are listed in a new **Runs** tab.

**Architecture:** Extract the event → subject → run code from `EventStreamOrchestrator.routeToCreateWorkflow`
into one shared method used by both subscriptions and the new manual endpoint. Manual runs pass
`EventOrigin(event.id, null)` (no ledger entry) and get the label `trigger=manual`. Runs are listed by the
existing runs endpoint with a new `triggerEventId` filter. Suggestions come from subscriptions whose
`create-workflow` rules ran (ledger `completed`) for the event.

**Tech Stack:** Java 21/Quarkus/Panache, JUnit 5 + REST Assured; React 18 + PatternFly 6 + Vitest.

**Spec:** user decisions (2026-10-14): Run workflow… only on the event detail page; picker offers all
published workflows with suggested ones first; results shown in a new Runs tab (no change to the processing
trail).

## Global Constraints

- Contract-first REST (`common/api/src/main/resources/openapi.json`, edited programmatically, additions only);
  impl classes implement generated interfaces; generated beans for bodies.
- Never run `./build.sh`, `mvn clean`, `mvn install`. Backend tests:
  `mvn -q -pl app -am test -Dtest=<Classes> -Dsurefire.failIfNoSpecifiedTests=false`. UI (in `ui/`):
  `timeout 250 npx tsc -b --noEmit`, `timeout 280 npx vitest run [paths]`.
- Java: 4-space indent, Javadoc on public methods, explicit types, JUnit 5. Conventional commits on
  `feat/workflow-first`, no AI attribution, never push.
- Subscription-triggered behaviour must not change (same subject resolution, same `context.event`, same
  ledger/outcome records, same conflict handling).
- Manual runs: label `trigger=manual` (merged with user labels; the user cannot override `trigger`),
  `EventOrigin(event.id, null)`, no ledger entry. Conflict (an active run of the same workflow for the
  subject) → HTTP 409 with the existing conflict message; unknown event or workflow → 404; workflow without a
  published version or invalid inputs → 400 (existing behaviour of `triggerWorkflow`).

---

### Task 1: Backend — shared start-from-event, manual endpoint, runs filter, suggestions

**Files:** `app/src/main/java/io/apitomy/axiom/app/EventStreamOrchestrator.java`,
`app/src/main/java/io/apitomy/axiom/app/rest/StreamEventsResourceImpl.java`, the runs resource that implements
`listWorkflowRuns` (+ `WorkflowRunBeanMapper.search`), `common/api/src/main/resources/openapi.json`, tests.

1. **Extract** from `routeToCreateWorkflow` a public method on `EventStreamOrchestrator`:
   `WorkflowRunEntity startRunFromEvent(StreamEventEntity event, long definitionId, RunRequest request,
   Long ledgerId)` that resolves/creates the subject (`findOrCreateSubjectForEvent`), builds the `event`
   context node (merging it into `request.extraContext()`), calls `workflowExecutionService.triggerWorkflow(...,
   new EventOrigin(event.id, ledgerId))` in `QuarkusTransaction.requiringNew()`, fires `subjectUpdated`, and
   returns the run (letting `WorkflowRunConflictException` propagate). `routeToCreateWorkflow` calls it and
   keeps its own outcome/conflict bookkeeping. Reuse `buildEventMap` instead of the duplicated inline code if
   they are equivalent.
2. **Endpoint** `POST /stream/events/{eventId}/runs`, operationId `triggerStreamEventWorkflow`, tag as the
   other stream event operations, body the existing `TriggerWorkflow` schema (`workflowDefinitionId` required,
   `title`, `labels`, `inputs`), response `200` with the same run bean `triggerSubjectWorkflow` returns
   (check `openapi.json:562` and reuse its response schema), `404`, `409`, `400`. Implementation in
   `StreamEventsResourceImpl`: load the event (404), build `RunRequest(title, labels + trigger=manual,
   inputs, null)`, call `startRunFromEvent(event, defId, request, null)`, map the conflict exception to 409.
3. **Runs filter:** add optional query parameter `triggerEventId` (int64) to `listWorkflowRuns`
   (`GET /workflow/runs`) and filter on `WorkflowRunEntity.triggerEventId`.
4. **Suggestions:** `GET /stream/events/{eventId}/workflow-suggestions`, operationId
   `listStreamEventWorkflowSuggestions`, returns `StreamEventWorkflowSuggestion[]`
   (`{workflowDefinitionId: int64, workflowName: string, subscriptionId: int64, subscriptionName: string}`):
   for each ledger entry of the event with status `completed`, each `create-workflow` routing rule of its
   subscription (look at how `toProcessingEntries` reads rules) → one suggestion; de-duplicate by
   (definition, subscription); skip definitions that no longer exist.
5. **Tests** (`@QuarkusTest`, follow existing stream-event tests for fixtures): manual trigger starts a run on
   the event's subject with `triggerEventId` = event id, label `trigger=manual`, `context.event.id` = event id,
   and no new ledger entry; second manual trigger while the first is active → 409; unknown event → 404;
   `GET /workflow/runs?triggerEventId=` returns it; suggestions return the subscription's workflow for a
   completed ledger entry and nothing for a skipped one; existing orchestrator/subscription tests still pass
   (run them).
- [ ] RED → implement → GREEN; commit `feat(events): start a workflow run from a stream event`.

### Task 2: UI — Run workflow… on the event detail page, Runs tab

**Files:** `ui/src/pages/EventDetailPage.tsx` (+ test), `ui/src/config/api.ts`, new
`ui/src/components/RunWorkflowFromEventModal.tsx` (+ test).

- `api.ts`: `triggerStreamEventWorkflow(eventId, data)`, `fetchStreamEventWorkflowSuggestions(eventId)`, and
  `triggerEventId` in `WorkflowRunFilters`/`fetchWorkflowRuns`.
- Header: a **Run workflow…** primary button next to the page `Title`.
- Modal (PatternFly basic form): **Workflow** select listing published workflows (from
  `fetchWorkflowDefinitions`), suggested ones first in a "Suggested" group with the description "Used by
  subscription <name>", then "All workflows" alphabetically (skip unpublished ones if the definition bean shows
  that); **Title** (optional), **Labels** (optional `key:value, key:value`), **Inputs** (optional JSON object,
  validated). A note: "The run starts on the event's subject, with the event in context.event."
  **Run** → calls the API; on success close, switch to the Runs tab, refresh it, and show an inline success
  alert with a link to the run (`/runs/<id>`). Errors (409 with its message, 400, 404) show inline in the modal.
- **Runs** tab (after Processing): table of runs with `triggerEventId` = this event (status label, title link
  to `/runs/<id>`, workflow, started, a `manual` label when the run's labels contain `trigger=manual`);
  empty state "No runs were started from this event."; refreshes on the `workflow-updated` SSE event like the
  Runs page.
- Tests: suggestions listed first; Run sends `{ workflowDefinitionId, title?, labels?, inputs? }`; invalid
  inputs JSON blocks Run; 409 message shown; Runs tab lists runs and shows the manual label.
- [ ] RED → implement → GREEN (`tsc` + full vitest); commit `feat(events): run a workflow from the event detail page`.

### Task 3: Live verification

With `dev.sh` restarted: open an event in the Event Stream, Run workflow… with a suggested workflow, check the
run appears in the Runs tab and on `/runs`, and that a second immediate run of the same workflow shows the 409
message.
