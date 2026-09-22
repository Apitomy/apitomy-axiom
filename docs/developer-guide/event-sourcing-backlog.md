# Event Sourcing Redesign — Remaining Work

Temporary backlog for tracking incomplete items on the `feat/event-sourcing-redesign` branch.

## High Priority

- [ ] **Manager decision processing** — The `routeToManager` path calls the Manager AI
  and gets decisions back, but decisions are only logged. Need to rebuild the decision
  processing logic (create_task, ignore, escalate, script_action) that was in the old
  `PipelineOrchestrator.processDecision()`.

- [ ] **Stream event retention cleanup** — No cleanup job exists for `stream_event` or
  `event_processing_ledger` tables. They grow unbounded. Need a scheduled cleanup job
  similar to the existing `EventCleanup`.

- [ ] **Connection error tracking** — The `ConnectionStatus` endpoint returns `lastError`
  and `lastErrorAt` fields, but pollers only log errors without persisting error state
  to the connection entity. Users can't diagnose polling failures.

## Medium Priority

- [ ] **Old entity and code cleanup** — Remove remaining references to old event system:
  - `EventEntity`, `EventQueueEntity`, `EventSourceEntity` still referenced by
    `TaskExecutionService`, `EventCleanup`, `EventQueueCleanup`, `ProjectDeletionService`,
    `ReportExecutionService`, `TraceCleanup`, `SeedDataInitializer`
  - `EventsResourceImpl` — old `/api/v1/events` endpoint still active
  - `EventService` — still used by `TaskExecutionService` for internal event emission
  - `GitHubApiClient`, `JiraApiClient` — dead code (old non-v2 clients)

- [ ] **Orphaned pending ledger entries** — If Axiom crashes between creating a `pending`
  ledger entry and completing routing, the entry stays pending forever. Need periodic
  cleanup or treat pending entries older than N minutes as retriable.

- [ ] **SSE notifications** — The new orchestrator doesn't fire SSE events when events
  arrive or when routing creates tasks/workflows. UI doesn't auto-refresh.

## Low Priority / Polish

- [ ] **Design document update** — `event-sourcing-design.md` doesn't reflect EL
  expression filtering, routing rules, or the processing ledger.

- [ ] **Test coverage** — Most tests are smoke tests. Need integration tests for ledger
  retry, retroactive subscription processing, and restart recovery.

- [ ] **Unused UI types** — `EventSourceFilterRule` still has `connection` and `ref` type
  options from the old include/exclude model, now irrelevant since we switched to EL
  expressions.

- [ ] **Drop old database tables** — `event_source`, `event_source_label`, `event`,
  `event_queue` tables can be dropped once all code references are removed (depends on
  old entity cleanup above).
