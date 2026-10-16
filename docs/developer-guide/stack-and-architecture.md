# Stack & Architecture

This guide provides a high-level overview of Axiom's technology stack and runtime
architecture. For the current module layout and entity model, read the source under
`core/src/main/java/io/apitomy/axiom/core/entities/` and the root `pom.xml` — those are
the authoritative reference and change more often than documentation should chase.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 25 |
| Framework | Quarkus 3 |
| Database | H2 (in-memory for dev, file-based for prod) |
| ORM | Hibernate with Panache (active record pattern) |
| Migrations | Flyway |
| Frontend | TypeScript, React 19, PatternFly 6 |
| Workflow engine and editor | Apitomy Flow 3.0.2 (`apitomy-flow-engine`, `@apitomy/flow-ui`) |
| Build (frontend) | Vite |
| Build (backend) | Maven |
| API | Contract-first OpenAPI with Apitomy Codegen |
| AI Agents | Claude Code CLI, OpenCode, GitHub Copilot CLI (pluggable) |

---

## Module Layout

The root `pom.xml` lists these modules:

| Module | Contents |
|--------|----------|
| `common/api` | `openapi.json` and the JAX-RS interfaces and beans generated from it |
| `core` | Entities, SSE events, filters, tracing and other shared services |
| `agents/spi` | The `Agent` SPI |
| `agents/claude-code`, `agents/opencode`, `agents/copilot` | Agent implementations |
| `events/core` | Event stream persistence and the event source SPI |
| `events/github`, `events/jira` | Event source pollers |
| `notifications/spi` | The notification channel SPI |
| `notifications/slack`, `notifications/telegram` | Notification channels |
| `app` | The Quarkus application: REST resources, workflow execution, AI Assistant sessions and the workbench |
| `ui-bundle` | Packages the React UI from `ui/` into the JAR (only with the `ui` profile) |

---

## Runtime Architecture

The `app` module wires everything together using Quarkus CDI. At runtime, several
scheduled pollers and services cooperate to process events and execute work.

### Event Ingestion

```
GitHubEventSourcePoller / JiraEventSourcePoller
  │  @Scheduled — polls external APIs at each event source's configured interval
  │
  ▼
EventStreamService.persistEvent()
  │  Persists a normalized StreamEventEntity, deduplicated by source event ID
  │
  ▼
stream_event table
```

### Subscription Evaluation and Routing

```
EventStreamOrchestrator
  │  @Scheduled — evaluates unprocessed (event, subscription) pairs each tick
  │
  ▼
SubscriptionFilterEvaluator
  │  Evaluates the subscription's EL filter expression against the event
  │
  ▼
Routing dispatch (in order, per matched subscription)
  ├── create-workflow    → finds/creates the Subject from the event ref, starts a new workflow run
  │                        (skipped and recorded if the subject already has an active run of that definition)
  └── workflow-dispatch  → broadcasts the event to every waiting run parked at a matching receive-event node
```

A durable processing ledger records the outcome of every (event, subscription) pair
(`skipped`, `completed`, or `failed`), so processing survives restarts and failed
routing is retried automatically on each tick.

### Task Execution

```
TaskQueuePoller
  │  @Scheduled — finds subjects with pending tasks
  │
  ▼
TaskExecutionService.executeNextTask(subjectId)
  │  Requests a lease from AgentPool using a capability string (e.g. "action:Name")
  │  Builds an AgentRequest (tools, prompt, env, MCP config)
  │  Enforces subject-level serialization (one task at a time per subject)
  │
  ▼
Agent.execute(request)
  │  Runs the agent's subprocess/HTTP call, or completes via human input
  │  Returns AgentResult (output, cost, tokens, log)
  │
  ▼
TaskEntity updated with result
AiUsageEntity created with cost/token data
AgentPool lease released
```

Tasks are created only by workflow `action` and `human-task` nodes. Because tasks are serialized per subject,
concurrent runs of different definitions on the same subject take turns executing their tasks.

### Workflow Execution

```
WorkflowExecutionService.triggerWorkflow(subjectId, definitionId, RunRequest)
  │  A subscription's "create-workflow" rule, a manual start from an event (Event Stream),
  │  a schedule trigger, or a re-run of a failed run
  │  RunRequest carries only the optional title and labels; runs cannot be started with supplied inputs
  │  StandardInputs resolves the inputs the Start node declares (subject, event) into the initial context;
  │  the run stores the resolved inputs (subject only; the event is linked as the run's trigger event)
  │  Rejects a second active (running/waiting) run of the same definition on the subject
  │  (WorkflowRunConflictException → HTTP 409, or a recorded "skipped" for routing and schedules)
  │
  ▼
WorkflowEngine (from the shared flow-engine library)
  │  Advances the workflow instance through its node graph
  │  action / human-task nodes create TaskEntity rows via TaskExecutionService
  │  wait nodes park the instance until a resume time
  │  receive-event nodes park the instance until a matching event is dispatched
  │
  ▼
WorkflowRunEntity updated with instance state, current node, and status
```

### Schedule Triggers

```
WorkflowScheduleTriggerScheduler
  │  @Scheduled every axiom.workflow.schedule-poll-interval (default 30s)
  │  Claims due, enabled WorkflowScheduleTriggerEntity rows (nextRunAt <= now)
  │  Computes the next firing from the 5-field Unix cron expression (UTC)
  │
  ▼
Find or create the fixed Subject (ref "schedule:<subjectName>")
  │
  ▼
WorkflowExecutionService.triggerWorkflow(subjectId, definitionId, inputs, label trigger=schedule)
  │  lastOutcome = started | skipped (active run exists) | failed: <reason>
```

Schedule firing assumes a single Axiom node; there is no cross-node locking.

See [Workflows](../user-guide/workflows.md) for the authoring and run-time reference.

### AI Assistant Sessions

```
AssistantSessionManager
  │  Creates session from template
  │  Resolves agent type, MCP servers, allowed tools, working directory
  │  Runs optional init script
  │
  ▼
InteractiveSessionDriver (ClaudeInteractiveSessionDriver or OpenCodeInteractiveSessionDriver)
  │  ProcessBuilder or HTTP session with stream-json / SSE I/O
  │  Virtual threads: stdout reader, stderr reader, process monitor
  │
  ▼
AssistantEventParser
  │  Normalizes engine-specific events into typed SseEvent records
  │  Auto-approval rules intercept permission requests
  │
  ▼
JAX-RS SSE endpoint (per-session)
  │  Streams events to browser EventSource
  │  Full event history replay on reconnect
```

Live sessions are held in memory, and the agent process does not survive a server restart. Each session's
events are also appended to a JSON Lines history journal (`HistoryJournal`), so a conversation can be shown
again after a restart. Cost and token usage are accumulated per session and persisted to `AiUsageEntity` when
the session is destroyed (only if the session had at least one turn).

Each session gets an MCP token. REST routes that only a session's own MCP server may call check the
`X-Axiom-Session-Token` header with `SessionTokenGuard` and return 401 if it is missing or wrong. The token
keeps sessions apart; it does not protect Axiom from the agent itself, which can read the token from its
environment.

### Workflow Workbench

```
CompositionsResourceImpl (REST, /compositions)
  │
  ▼
CompositionService          → saves composition items (workflow, action types, tools) as drafts,
  │                           checks the base revision, records revisions, fires composition-item-changed
  ├── WorkbenchSessions     → binds each composition to one agent session (template "axiom-workbench")
  ├── CompositionTurns      → runs AI turns; collects workflow change sets, fires composition-turn and
  │                           composition-changeset
  ├── TurnUndo              → undoes an AI turn
  ├── CompositionValidator  → validates drafts with the same rules as publishing
  ├── CompositionTestRuns   → runs node/script action type and tool drafts, fires composition-test-run
  └── CompositionPublisher  → publishes selected drafts as one configuration pack in one transaction
```

The workbench agent reaches Axiom through the built-in `@axiom-workbench` MCP server
(`app/src/main/resources/templates/axiom-workbench-mcp/server.js`). Its tools read the composition, write
action types and tools (`upsert_item`, `remove_item`), propose workflow changes
(`propose_workflow_changes`), validate, suggest tests (`suggest_test`), and look up events, runs, traces and
existing components. The agent cannot run tests or change the workflow directly: workflow change sets are
applied by the user's editor when the turn ends, and only the user runs tests. Calls from the MCP server to
the `/compositions/{compositionId}/agent/...` routes are checked by `SessionTokenGuard`.

See [Workflow Workbench](../user-guide/workbench.md) for the user-facing description.

#### Init Script Security

Session templates support optional init scripts (bash or Node.js) that run once when a
session is created. These scripts run via `ProcessBuilder` with **no sandboxing** — the
script runs as the same OS user as the Axiom server process, with full filesystem and
network access. The only safeguard is a 60-second timeout.

**Trust model:** The init script content comes from session templates stored in the
database (user-defined) or in built-in JSON files bundled with the application. There
are currently no authentication or role-based access controls on the template CRUD
endpoints. Any user with network access to Axiom can create a template containing an
arbitrary init script, which will execute with server privileges when a session is
created from that template. Template authors are therefore implicitly trusted with
server-level code execution.

**Future hardening options** (not currently implemented):

- Add role-based access control to the template CRUD endpoints so only administrators
  can create or modify templates with init scripts
- Run init scripts in a container or as a restricted OS user to limit their blast
  radius
- Validate or restrict init script content (e.g., command allowlists or static
  analysis)

### Real-Time Updates (SSE)

Event types include `subject-updated`, `task-updated`, `workflow-updated`, `stream-event`, `trace-updated`,
`inbox-updated`, `notification`, `activity`, `assistant-session-event`, the workbench events
`composition-item-changed`, `composition-turn`, `composition-changeset` and `composition-test-run`, and
`heartbeat`. See `core/src/main/java/io/apitomy/axiom/core/events/SseEvent.java` for the full list.

```
Any service fires CDI event: Event<SseEvent>
  │
  ▼
SseResource (@Observes SseEvent)
  │  Broadcasts to all connected clients
  │
  ▼
ui/src/config/sse.ts (browser)
  │  Shared connection (sse-worker.ts) with auto-reconnect
  │  UI components update reactively
```

---

## Key Design Patterns

### SPI / Provider Pattern

Extension points (AI agents, notification channels) use a consistent pattern:

1. An **SPI module** defines the interface (e.g. `Agent`)
2. **Implementation modules** provide concrete classes annotated with `@ApplicationScoped`
3. A **registry** or CDI `Instance<T>` discovers implementations at runtime
4. Selection is driven by configuration (e.g. `axiom.agent.default-type=claude-code`)
   or, for agent pool leasing, by capability matching

See the [Extending Axiom](extending-axiom.md) guide for details.

### Panache Active Record

All entities extend `PanacheEntity` (or `PanacheEntityBase` for entities with
non-Long primary keys) and use the active record pattern — queries are static methods
on the entity class:

```java
SubjectEntity subject = SubjectEntity.findById(id);
List<TaskEntity> tasks = TaskEntity.find("subjectId", subjectId).list();
entity.persist();
```

### Builder Pattern

Configuration objects use builders for clean, immutable construction:

- `AgentRequest.Builder` — agent invocation settings
- `AgentResult.Builder` — execution results

### Async Execution

Agent execution returns `CompletableFuture`, keeping the scheduled pollers
non-blocking.

### Tracing

Every workflow run produces a **trace** — a tree of lightweight nodes recording each step. The tracing
subsystem follows several key design principles:

- **`TraceService` lives in `core`** — producers of traces inject it directly, avoiding circular
  dependencies
- **Stack-based context** — `TraceContext` maintains a mutable node stack. Call
  `push(nodeId)` when descending into a child scope and `pop()` when returning. The
  current top of the stack is the parent for new nodes.
- **Independent transactions** — all trace writes use
  `QuarkusTransaction.requiringNew()`, so trace data persists even if the caller's
  transaction rolls back
- **Non-fatal** — all trace operations are wrapped in try/catch. Tracing failures never
  interrupt the main pipeline
- **SSE broadcast** — every trace mutation fires `SseEvent.traceUpdated(traceId)` for
  real-time UI updates
- **UUID primary key** — `TraceEntity` uses a UUID PK. The trace ID doubles as the
  correlation identifier threaded through environment variables and API callbacks.

See the [Tracing](tracing.md) developer guide for the full data model, service API, and
REST endpoint reference.

### Scheduled Pollers

All background processing uses Quarkus `@Scheduled` with
`concurrentExecution = SKIP` to prevent overlapping executions, including the
event source pollers, the event stream orchestrator, the task queue poller, the workflow
wait scheduler, and the workflow schedule trigger scheduler.
