# Stack & Architecture

This guide provides a high-level overview of Axiom's technology stack and runtime
architecture. For the current module layout and entity model, read the source under
`core/src/main/java/io/apitomy/axiom/core/entities/` and the root `pom.xml` — those are
the authoritative reference and change more often than documentation should chase.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java |
| Framework | Quarkus |
| Database | H2 (in-memory for dev, file-based for prod) |
| ORM | Hibernate with Panache (active record pattern) |
| Migrations | Flyway |
| Frontend | TypeScript, React, PatternFly |
| Build (frontend) | Vite |
| Build (backend) | Maven |
| API | Contract-first OpenAPI with Apitomy Codegen |
| AI Agents | Claude Code CLI, OpenCode, GitHub Copilot CLI (pluggable) |

---

## Runtime Architecture

The `app` module wires everything together using Quarkus CDI. At runtime, several
scheduled pollers and services cooperate to process events and execute work.

### Event Ingestion

```
GitHubConnectionPoller / JiraConnectionPoller
  │  @Scheduled — polls external APIs at each connection's configured interval
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
  ├── manager           → ManagerService.evaluateStreamEvent()
  ├── workflow-dispatch  → offers the event to parked receive-event workflow nodes
  ├── create-workflow    → finds/creates a project, starts a new workflow instance
  └── invoke-action      → finds/creates a project, creates a TaskEntity directly
```

A durable processing ledger records the outcome of every (event, subscription) pair
(`skipped`, `completed`, or `failed`), so processing survives restarts and failed
routing is retried automatically on each tick.

### Manager Evaluation

```
ManagerService.evaluateStreamEvent(event)
  │  Invokes an AI agent with a structured output schema
  │  Returns List<ManagerDecision>
  │
  ▼
Decision dispatch
  ├── create_task / script_action → find/create Project, create TaskEntity
  ├── ignore                       → mark processed, log
  └── escalate                     → create an Inbox task for human review
```

Decisions below the configured confidence threshold are automatically escalated
regardless of their decision type.

### Task Execution

```
TaskQueuePoller
  │  @Scheduled — finds projects with pending tasks
  │
  ▼
TaskExecutionService.executeNextTask(projectId)
  │  Requests a lease from AgentPool using a capability string (e.g. "action:Name")
  │  Builds an AgentRequest (tools, prompt, env, MCP config)
  │  Enforces project-level serialization (one task at a time)
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

### Report Generation

```
ReportScheduler
  │  @Scheduled — checks for due report definitions
  │  Creates ReportEntity (status: Pending)
  │
  ▼
ReportQueueConsumer
  │  Sequential FIFO queue (one report at a time)
  │  Daemon thread blocks on BlockingQueue.take()
  │
  ▼
ReportExecutionService
  │  Requests a lease from AgentPool using capability "report:<slug>"
  │  Invokes the leased agent with the report prompt and tools
  │  Writes generated Markdown to ReportEntity
```

### Scheduled Job Execution

```
ScheduledJobScheduler
  │  @Scheduled — checks for due scheduled jobs
  │  Creates ScheduledJobRunEntity (status: Pending)
  │
  ▼
ScheduledJobQueueConsumer
  │  Sequential FIFO queue (one job at a time)
  │  Daemon thread blocks on BlockingQueue.take()
  │
  ▼
ScheduledJobExecutionService
  │  Agent mode: requests a lease using capability "job:<slug>", invokes the agent
  │  Script mode: runs bash script via ProcessBuilder
  │  Writes output to ScheduledJobRunEntity
```

### Workflow Execution

```
WorkflowExecutionService.triggerWorkflow(projectId, definitionId)
  │  Manual trigger, or a subscription's "create-workflow"/"workflow-dispatch" rule
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

See [Workflows](../user-guide/workflows.md) for the authoring and run-time reference.

### AI Assistant Sessions

```
AssistantSessionManager
  │  Creates session from template
  │  Resolves agent type, MCP servers, allowed tools, working directory
  │  Runs optional init script
  │
  ▼
InteractiveSessionDriver (Claude Code or OpenCode)
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

Sessions are in-memory — they do not survive server restarts. Cost and token usage
are accumulated per-session and persisted to `AiUsageEntity` on session destruction.

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

```
Any service fires CDI event: Event<SseEvent>
  │
  ▼
SseResource (@Observes SseEvent)
  │  Broadcasts to all connected clients
  │
  ▼
SseClient (browser)
  │  EventSource with auto-reconnect
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
ProjectEntity project = ProjectEntity.findById(id);
List<TaskEntity> tasks = TaskEntity.find("projectId", projectId).list();
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

Every manager evaluation, workflow run, scheduled job run, and report generation
produces a **trace** — a tree of lightweight nodes recording each step. The tracing
subsystem follows several key design principles:

- **`TraceService` lives in `core`** — all producers of traces (`app` and `manager`
  modules) inject it directly, avoiding circular dependencies
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
connection pollers, the event stream orchestrator, the task queue poller, and the
report and scheduled job schedulers.
