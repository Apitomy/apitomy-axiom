# Stack & Architecture

This guide provides a high-level overview of Axiom's technology stack, module structure,
and runtime architecture.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 25 |
| Framework | Quarkus 3.33 LTS |
| Database | H2 (in-memory for dev, file-based for prod) |
| ORM | Hibernate with Panache (active record pattern) |
| Migrations | Flyway |
| Frontend | TypeScript, React 19, PatternFly 6 |
| Build (frontend) | Vite 6.4 |
| Build (backend) | Maven 3.9+ |
| API | Contract-first OpenAPI with Apitomy Codegen |
| AI Engines | Claude Code CLI, OpenCode (pluggable) |

---

## Module Map

Axiom is a multi-module Maven project. Each module has a focused responsibility:

```
apitomy-axiom/
├── common/api/          OpenAPI contract + generated JAX-RS interfaces
├── core/                Domain entities, lifecycle state machine, services
├── engine/
│   ├── spi/             AI engine abstraction (AiEngine, AiEngineRegistry)
│   └── opencode/        OpenCode engine + actor implementation
├── manager/             AI Manager — event triage and decision-making
├── actors/
│   ├── spi/             Actor interface (Actor, ActorContext, TaskResult)
│   ├── claude-code/     Claude Code engine + actor implementation
│   └── human/           Human actor (notification-driven)
├── events/
│   ├── core/            Event queue and EventService
│   ├── github/          GitHub poller and API client
│   └── jira/            Jira poller and API client
├── notifications/
│   ├── spi/             Notification channel interface (stub)
│   ├── slack/           Slack notification channel
│   └── telegram/        Telegram notification channel
├── app/                 Quarkus application — assembles all modules
├── ui/                  React frontend (standalone Vite project)
└── ui-bundle/           Packages UI assets into the backend JAR
```

### Module Dependency Flow

```
common/api ◄── core ◄── manager
                 ▲        ▲
                 │        │
              engine/spi ◄┘
                 ▲
                 │
           ┌─────┴──────┐
     engine/opencode  actors/claude-code
                         ▲
                         │
                    actors/spi ◄── actors/human
                         ▲
                         │
events/core ◄── events/github
            ◄── events/jira

            All modules ──► app (assembles everything)
```

---

## Runtime Architecture

The `app` module wires everything together using Quarkus CDI. At runtime, several
scheduled pollers and services cooperate to process events and execute work.

### Event Ingestion

```
GitHubPoller / JiraPoller
  │  @Scheduled — polls external APIs at configurable intervals
  │
  ▼
EventService.ingestEvent()
  │  Persists EventEntity + creates EventQueueEntity (status: pending)
  │
  ▼
EventQueueEntity table (FIFO queue)
```

Event source pollers run on a tick interval (default 10 seconds) and check whether each
enabled event source's poll interval has elapsed. When it has, the poller fetches new
activity from the external API, normalizes it into events, and enqueues them.

### Event Processing Pipeline

```
PipelineOrchestrator
  │  @Scheduled — dequeues one pending event per tick
  │
  ▼
ManagerService.evaluate(event)
  │  Invokes AI engine with structured output schema
  │  Returns List<ManagerDecision>
  │
  ▼
Decision dispatch
  ├── create_task → find/create Project, create TaskEntity
  ├── ignore → mark event processed, log
  ├── script_action → trigger ScriptExecutionService
  └── escalate → create task for human review
```

### Task Execution

```
TaskQueuePoller
  │  @Scheduled — finds projects with pending tasks
  │
  ▼
TaskExecutionService.executeNextTask(projectId)
  │  Resolves Actor implementation via CDI
  │  Builds ActorContext (tools, prompt, env, MCP config)
  │  Enforces project-level serialization (one task at a time)
  │
  ▼
Actor.execute(task, context)
  │  Runs AI engine subprocess or sends notifications
  │  Returns TaskResult (output, cost, tokens, log)
  │
  ▼
TaskEntity updated with result
AiUsageEntity created with cost/token data
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
  │  Invokes AI engine with report prompt and tools
  │  Writes generated Markdown to ReportEntity
```

### AI Assistant Sessions

```
AssistantSessionManager
  │  Creates session from template
  │  Resolves MCP servers, allowed tools, working directory
  │  Runs optional init script
  │
  ▼
AssistantSession (Claude Code subprocess)
  │  ProcessBuilder with stream-json I/O
  │  Virtual threads: stdout reader, stderr reader, process monitor
  │  Bidirectional: JSON lines to stdin, NDJSON events from stdout
  │
  ▼
AssistantEventParser
  │  Normalizes NDJSON into typed SseEvent records
  │  Auto-approval rules intercept permission requests
  │
  ▼
JAX-RS SSE endpoint (per-session)
  │  Streams events to browser EventSource
  │  Full event history replay on reconnect
```

Sessions are in-memory — they do not survive server restarts. Cost and token usage
are accumulated per-session and persisted to `AiUsageEntity` on session destruction.

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

Extension points (engines, actors, event sources) use a consistent pattern:

1. An **SPI module** defines the interface (e.g. `AiEngine`, `Actor`)
2. **Implementation modules** provide concrete classes annotated with `@ApplicationScoped`
3. A **registry** or CDI `Instance<T>` discovers implementations at runtime
4. Selection is driven by configuration (e.g. `axiom.ai-engine=claude-code`)

See the [Extending Axiom](extending-axiom.md) guide for details.

### Panache Active Record

All entities extend `PanacheEntity` and use the active record pattern — queries are
static methods on the entity class:

```java
ProjectEntity project = ProjectEntity.findById(id);
List<TaskEntity> tasks = TaskEntity.find("projectId", projectId).list();
entity.persist();
```

### Builder Pattern

Configuration objects use builders for clean, immutable construction:

- `AiEngineConfig.Builder` — engine invocation settings
- `ActorContext.Builder` — task execution context
- `TaskResult.Builder` — execution results

### Async Execution

Actor execution and engine invocations return `CompletableFuture`, keeping the
scheduled pollers non-blocking.

### Tracing

Every event pipeline run and report generation produces a **trace** — a tree of
lightweight nodes recording each step. The tracing subsystem follows several key
design principles:

- **`TraceService` lives in `core`** — both the `app` module (PipelineOrchestrator,
  TaskExecutionService) and the `manager` module (ManagerService) inject it directly,
  avoiding circular dependencies
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
- **UUID primary key** — `TraceEntity` uses a UUID PK (the only entity in the project
  to do so). The trace ID doubles as the correlation identifier threaded through
  environment variables and API callbacks.

See the [Tracing](tracing.md) developer guide for the full data model, service API, and
REST endpoint reference.

### Scheduled Pollers

All background processing uses Quarkus `@Scheduled` with
`concurrentExecution = SKIP` to prevent overlapping executions:

| Poller | Responsibility |
|--------|---------------|
| `GitHubPoller` | Poll GitHub repos for new activity |
| `JiraPoller` | Poll Jira projects for new activity |
| `PipelineOrchestrator` | Dequeue and process events |
| `TaskQueuePoller` | Dispatch pending tasks to actors |
| `ReportScheduler` | Check for due report definitions |
