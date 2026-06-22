# Apitomy Axiom v2 — Architecture Document

**Status:** Draft
**Last Updated:** 2026-04-02

This document describes the technical architecture for Apitomy Axiom v2. It builds on the
functional design document (`design-v2.md`) and specifies the technology choices, component
structure, and implementation approach.

---

## 1. Technology Stack

| Layer          | Technology                    | Notes                                       |
|----------------|-------------------------------|---------------------------------------------|
| Backend        | Java 25 / Quarkus 3.33 LTS    | Modular monolith                             |
| Frontend       | TypeScript / React            | Vite for dev, nginx for production           |
| Database       | H2 (dev) / PostgreSQL (prod)  | Configurable via Quarkus profiles            |
| ORM            | Hibernate ORM with Panache Next |                                            |
| REST API       | JAX-RS (RESTEasy Reactive)    | Contract-first via OpenAPI + apitomy-codegen|
| Real-time      | Server-Sent Events (SSE)      | RESTEasy Reactive SSE support                |
| AI (Manager)   | Claude Code CLI subprocess    | Specialized MCP tools for triage decisions   |
| AI (Actors)    | Claude Code CLI subprocess    | Scoped to project working directory          |
| Containerization | Docker Compose              | Backend, PostgreSQL, nginx (UI)              |


### 1.1 Version Details

- **Java 25:** Targeted Java version, supported since Quarkus 3.31. Enables the latest language
  features (pattern matching, virtual threads, etc.).
- **Quarkus 3.33 LTS:** Long-term support release. Provides stability guarantees and extended
  maintenance. The parent POM should pin `quarkus.platform.version` to the 3.33.x LTS stream.
- **Panache Next:** The new Panache programming model introduced in Quarkus 3.x
  ([quarkusio/quarkus#50058](https://github.com/quarkusio/quarkus/pull/50058)). Replaces the
  legacy Panache API with a cleaner, more flexible approach to repository and active record
  patterns. All entity and repository code should use Panache Next exclusively.


## 2. Module Structure

```
apitomy-axiom/
├── pom.xml                           # Parent POM (Maven multi-module)
│
├── common/
│   └── api/                          # OpenAPI document + generated JAX-RS interfaces and beans
│
├── app/                              # Quarkus application (main entry point, config, bootstrap)
│
├── core/                             # Domain model, entities, repositories, lifecycle logic
│
├── manager/                          # AI Manager (Claude Code integration, policy evaluation)
│
├── actors/
│   ├── spi/                          # Actor interface, TaskHandle, lifecycle contracts
│   ├── claude-code/                  # Claude Code CLI subprocess actor implementation
│   └── human/                        # Human actor (notification-driven)
│
├── events/
│   ├── core/                         # Event queue, pipeline orchestrator, event normalization
│   ├── github/                       # GitHub webhook receiver + polling
│   └── jira/                         # Jira webhook receiver + polling
│
├── notifications/
│   ├── spi/                          # Notification channel interface
│   ├── slack/                        # Slack channel implementation
│   └── telegram/                     # Telegram channel implementation
│
├── typescript-sdk/                    # Generated TypeScript SDK (Kiota, from OpenAPI)
│   └── package.json
│
├── ui/                               # React frontend (separate build, served by nginx)
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│
├── docker-compose.yml                # Local deployment (backend, postgres, nginx)
└── docker/
    └── nginx/                        # nginx configuration for serving UI
```

### Module Dependency Graph

```
common/api ◄──── core ◄──── manager
                  ▲            │
                  │            │
                  ├──── events/core ◄──── events/github
                  │                 ◄──── events/jira
                  │
                  ├──── actors/spi ◄──── actors/claude-code
                  │                ◄──── actors/human
                  │
                  ├──── notifications/spi ◄──── notifications/slack
                  │                       ◄──── notifications/telegram
                  │
                  └──── app (assembles everything)
```

All modules depend on `common/api` for the generated REST API types. `core` defines the domain
model and is the central dependency. Implementation modules (`events/github`, `actors/claude-code`,
`notifications/slack`, etc.) depend on their respective SPI modules and `core`, but not on each
other.


## 3. Contract-First REST API

The REST API is defined **contract-first** using an OpenAPI 3.x document. Implementation follows
this workflow:

1. **Define** the API in `common/api/src/main/resources/openapi.json`
2. **Generate** JAX-RS interfaces and request/response beans using `apitomy-codegen` (Maven plugin)
3. **Implement** the generated interfaces in the `common/api` module

### API Resource Groups

| Resource             | Base Path              | Description                                |
|----------------------|------------------------|--------------------------------------------|
| Projects             | `/api/v1/projects`     | CRUD, lifecycle, manual actions             |
| Tasks                | `/api/v1/projects/{id}/tasks` | Task history, status, output          |
| Actors               | `/api/v1/actors`       | CRUD, health status                        |
| Action Types         | `/api/v1/action-types` | Registry CRUD                              |
| Policies             | `/api/v1/policies`     | CRUD, policy testing                       |
| Events               | `/api/v1/events`       | Event log, replay                          |
| Activity Log         | `/api/v1/activity`     | Global activity log                        |
| Conversation Threads | `/api/v1/projects/{id}/thread` | Thread entries, post answers         |
| Repositories         | `/api/v1/repositories` | Monitored repositories CRUD                |
| MCP Servers          | `/api/v1/mcp-servers`  | External MCP server registry CRUD          |
| Notifications        | `/api/v1/notifications`| Notification preferences                   |
| System               | `/api/v1/system`       | Configuration, health, UI bootstrap config |
| SSE                  | `/api/v1/sse`          | Real-time event stream                     |

### UI Configuration Bootstrap

The UI container is configured with **only the backend API URL** (e.g. via an environment variable
injected into the nginx-served `index.html` or a small `/config.js` file). All other configuration
the UI needs (available action types, actors, notification channels, feature flags, etc.) is loaded
at startup by calling `GET /api/v1/system/config`.


## 4. Database Design

### 4.1 Configuration

The database is configurable via Quarkus profiles:

- **`dev` profile:** H2 in-memory or file-based database. Enabled automatically by Quarkus Dev
  Services. Zero setup required for development.
- **`prod` profile:** PostgreSQL, provided by the Docker Compose stack.

Hibernate ORM with Panache Next manages schema generation in dev mode. For production, **Flyway** is
used for controlled schema migrations.

### 4.2 Entity Tables

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   project    │     │     task     │     │    actor     │
├──────────────┤     ├──────────────┤     ├──────────────┤
│ id           │◄────│ project_id   │     │ id           │
│ name         │     │ action_type  │────▶│ name         │
│ description  │     │ created_by   │     │ description  │
│ type         │     │ assigned_actor│────▶│ type         │
│ status       │     │ status       │     │ capabilities │
│ issue_source │     │ input        │     │ permissions  │
│ issue_ref    │     │ output       │     │ configuration│
│ repository   │     │ created_on   │     └──────────────┘
│ created_on   │     │ completed_on │
│ updated_on   │     └──────────────┘
│ metadata     │
└──────────────┘

┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│    event     │     │ action_type  │     │    policy    │
├──────────────┤     ├──────────────┤     ├──────────────┤
│ id           │     │ id           │     │ id           │
│ source       │     │ name         │     │ name         │
│ event_type   │     │ description  │     │ guideline    │
│ issue_ref    │     │ execution_mode│    │ action_type  │
│ repository   │     │ user_trigger │     │ actor_hint   │
│ project_id   │────▶│ input_schema │     └──────────────┘
│ task_id      │     │ tool_constr  │
│ payload      │     │ emits_event  │
│ received_at  │     └──────────────┘
└──────────────┘

┌──────────────┐     ┌──────────────┐
│ activity_log │     │ thread_entry │
├──────────────┤     ├──────────────┤
│ id           │     │ id           │
│ project_id   │     │ project_id   │
│ task_id      │     │ author_type  │  (manager, actor, user, system)
│ event_id     │     │ author_id    │
│ entry_type   │     │ entry_type   │  (decision, update, question, answer, result, message)
│ summary      │     │ content      │
│ details      │     │ created_on   │
│ created_on   │     └──────────────┘
└──────────────┘

┌──────────────┐     ┌──────────────┐
│  repository  │     │  event_queue │
├──────────────┤     ├──────────────┤
│ id           │     │ id           │
│ name         │     │ event_id     │
│ owner        │     │ status       │  (pending, processing, completed, failed)
│ source       │     │ enqueued_at  │
│ url          │     │ processed_at │
│ poll_interval│     └──────────────┘
│ webhook_secret│
│ configuration│
└──────────────┘
```

### 4.3 Event Queue Table

The event queue is a database table rather than a message broker. The pipeline orchestrator polls
the `event_queue` table for `pending` entries, processes them sequentially, and updates their
status. This is sufficient for the expected event volume and avoids an additional infrastructure
dependency.

Processing flow:
1. Event ingestion inserts a row into `event` and a corresponding row into `event_queue` with
   status `pending`
2. The pipeline orchestrator selects the oldest `pending` entry (ordered by `enqueued_at`)
3. Status is set to `processing`
4. The Manager evaluates the event and creates tasks as needed
5. Status is set to `completed` (or `failed` with error details)

### 4.4 Task Queue

Tasks within a Project are serialized. This is enforced by the task executor: before starting a
new task for a project, it checks that no other task for the same project is currently `In Progress`
or `Awaiting Input`. If one is, the new task remains `Pending` until the active task completes.

Tasks across different projects may execute in parallel, limited by the number of available actor
instances (configurable).


## 5. Component Details

### 5.1 Event Ingestion (`events/`)

**GitHub (`events/github`):**
- Exposes a webhook endpoint (`POST /api/v1/webhooks/github`) that receives GitHub webhook
  payloads
- Validates webhook signatures using the configured secret
- Also supports a polling mode: a Quarkus `@Scheduled` job that periodically queries the GitHub
  API for new events on monitored repositories
- Normalizes GitHub payloads into the internal `Event` entity

**Jira (`events/jira`):**
- Exposes a webhook endpoint (`POST /api/v1/webhooks/jira`)
- Also supports polling via the Jira REST API
- Normalizes Jira payloads into the internal `Event` entity

**Internal events:**
- When a task completes and its action type has `emitsEvent: true`, the task executor creates an
  internal `Event` and enqueues it

### 5.2 Pipeline Orchestrator (`events/core`)

The central event processing loop. Runs as a Quarkus background service:

```
while running:
    event = dequeue next pending event
    if event is null: sleep briefly, continue

    project = lookup project by event.issueRef
    decisions = manager.evaluate(event, project, policies, actionTypes, actors)

    for each decision:
        if decision is "ignore": log reason, continue
        if decision is "create task":
            if project is null: create project from event metadata
            create task, enqueue in project's task queue
        if decision is "system action":
            execute system action (close-project, reopen-project)

    mark event as completed
```

### 5.3 Manager (`manager/`)

The Manager is an AI agent implemented using **Claude Code CLI**, the same subprocess
technology used for AI Agent actors (see Section 5.5). This unifies the AI integration
approach — both the Manager and actors use Claude Code, simplifying the architecture and
reducing the number of distinct AI integration points.

**Why Claude Code for the Manager:** Using Claude Code for both the Manager and actors means
a single subprocess management implementation, a single MCP integration pattern, and
consistent behavior across all AI components. The Manager's decision-making tools are
provided via MCP servers, just like actor tools. This also gives the Manager access to
Claude Code's built-in capabilities (reasoning, structured output) without building a
separate Anthropic API integration layer.

**Implementation approach:**

The Manager is launched as a Claude Code subprocess with:
- A specialized system prompt containing the event payload, project summary, policies,
  available action types, and available actors
- A `--mcp-config` pointing to the Axiom MCP server for decision and query tools
- `--output-format json` with `--json-schema` to enforce structured decision output
- `--bare` mode for predictable, fast execution
- `--max-turns` to limit reasoning depth

**MCP tools provided to the Manager:**

   **Decision tools** — used to communicate the Manager's decision:
   - `create_task(action_type, actor_hint, input_context, confidence)` — create a task for the
     project
   - `ignore_event(reason, confidence)` — skip this event with an explanation
   - `execute_system_action(action_type, confidence)` — run a system action (close/reopen
     project)
   - `escalate(reason)` — flag for user review

   **Query tools** — used to drill down into project details before making a decision. The
   Manager starts with a summary view and can progressively request more detail as needed.
   This keeps the initial prompt lean while giving the Manager access to full context when
   the situation is ambiguous.
   - `get_project_summary(project_id)` — project metadata, current status, issue ref, task
     counts by status (e.g. "3 completed, 1 pending"), and the most recent activity entry
   - `get_task_history(project_id, limit?)` — list of tasks for the project with action type,
     assigned actor, status, and completion timestamps. Supports a `limit` parameter to fetch
     the most recent N tasks (default: 10)
   - `get_task_detail(task_id)` — full detail of a specific task including input context and
     output/result
   - `get_thread_entries(project_id, limit?)` — recent conversation thread entries (questions,
     answers, decisions, actor updates). Supports a `limit` parameter (default: 20)
   - `get_related_events(project_id, limit?)` — recent events associated with this project,
     useful for understanding the sequence of changes that led to the current state

**Output parsing:**

The Manager's output is structured via `--json-schema`, ensuring a consistent response
format that the pipeline orchestrator can parse reliably. The schema enforces that the
Manager returns one or more decisions, each with an action type, confidence level, and
reasoning.

Each decision includes a `confidence` field. If confidence is below a configurable
threshold, the decision is held for user confirmation via the notification system.

> **Note on token efficiency:** For most events (e.g. a new issue with no existing project),
> the Manager won't need to call any query tools — the event payload and policies are
> sufficient. The query tools are primarily useful when the Manager is evaluating an event
> on a project with existing history, where understanding prior work is important for
> making a good decision.

### 5.4 Actor SPI (`actors/spi`)

```java
public interface Actor {

    TaskHandle assignTask(Task task, ActorContext context);

    TaskStatus getStatus(TaskHandle handle);

    void cancelTask(TaskHandle handle);

    void provideAnswer(TaskHandle handle, Answer answer);

    void onTaskComplete(TaskHandle handle, Consumer<TaskResult> callback);

    void onQuestionAsked(TaskHandle handle, Consumer<Question> callback);
}
```

`ActorContext` provides the actor with:
- The project's working directory (git clone path)
- The effective tool set (intersection of actor permissions and action-level constraints)
- The task input (event data, issue content, prior task outputs)

### 5.5 Claude Code Actor (`actors/claude-code`)

Launches Claude Code as a **CLI subprocess** in non-interactive (`-p`) mode, scoped to the
project's working directory. See `research-claude-code-integration.md` for the full research
findings that informed this design.

**Command construction:**

```
claude -p "<constructed prompt>" \
  --bare \
  --model <configured-model> \
  --allowedTools <effective-tool-set> \
  --disallowedTools <blocked-tools> \
  --cwd <project-working-directory> \
  --output-format stream-json \
  --include-partial-messages \
  --permission-mode acceptEdits \
  --max-turns <configured-limit> \
  --max-budget-usd <configured-limit> \
  --session-id axiom-task-<taskId> \
  --append-system-prompt-file <task-instructions-file> \
  --mcp-config <project-mcp-config>
```

**Key flags and their purpose:**

| Flag | Purpose |
|------|---------|
| `-p` | Non-interactive mode — runs prompt and exits |
| `--bare` | Skip auto-discovery of hooks, plugins, CLAUDE.md — predictable execution |
| `--allowedTools` | Effective tool set (intersection of actor + action constraints) |
| `--disallowedTools` | Explicit blocklist as safety net |
| `--cwd` | Scoped to the project's git clone directory |
| `--output-format stream-json` | NDJSON streaming for real-time progress |
| `--include-partial-messages` | Token-level streaming for UI progress display |
| `--permission-mode acceptEdits` | Auto-accept file edits (pre-approved via allowedTools) |
| `--max-turns` | Prevent runaway agent loops |
| `--max-budget-usd` | Cost guardrail per task |
| `--session-id` | Deterministic session ID for potential resumption |
| `--append-system-prompt` | Task-specific instructions appended to default prompt |
| `--mcp-config` | Inject project-aware MCP tools (e.g. query project metadata) |

**Output handling:**

The `stream-json` format emits one JSON object per line. The actor reads stdout line by line:
- Lines with `type: "stream_event"` contain partial token updates — forwarded to the UI via
  SSE for real-time progress display
- The final line with `type: "result"` contains the complete result, session ID, cost, and
  token usage

The result JSON structure:
```json
{
  "result": "the final text output",
  "session_id": "uuid",
  "total_cost_usd": 0.05,
  "usage": { "input_tokens": 1200, "output_tokens": 800 }
}
```

Cost and token usage from the result are recorded on the Task entity for budgeting and
reporting.

**Session management:**

Each task is assigned a deterministic session ID (`axiom-task-{taskId}`). If a task needs to
be resumed (e.g., after a clarifying answer is provided), the actor uses `--resume {session-id}`
to continue the conversation with full context preserved.

**Clarifying questions:**

The CLI subprocess model does **not** natively support interactive clarifying questions. In
Phase 1, this is mitigated by:
- Providing comprehensive context in the prompt (issue body, prior task outputs, conversation
  thread history) to minimize the need for questions
- Using `--permission-mode acceptEdits` to avoid tool-approval prompts
- If a task produces incomplete results, the Manager can detect this via the internal event
  and create a follow-up task with additional context

A future Phase 2 may introduce a **Node.js sidecar** using the Claude Agent SDK
(`@anthropic-ai/claude-agent-sdk`), which provides a `canUseTool` callback for intercepting
tool calls and a multi-turn `ClaudeSDKClient` for stateful conversations with programmatic
control. See `research-claude-code-integration.md` Section "Phase 2" for details.

**MCP server integration:**

The `--mcp-config` flag allows us to provide MCP server configurations to the Claude Code
process. This is the primary extension mechanism for enhancing agent capabilities over time.
The MCP config is generated per-task and can include multiple MCP servers from two categories:

1. **Axiom project context server** — an MCP server exposed by the Quarkus backend that gives
   the agent access to project-aware tools:
   - Query the project's conversation thread for prior context
   - Look up related task outputs
   - Post status updates to the activity log

2. **External tool servers** — third-party or custom MCP servers that extend the agent's
   capabilities. These are registered in the actor configuration and can be selectively
   included based on the action type. Examples:
   - **Serena** — semantic code analysis, symbol-based navigation, and intelligent code
     editing. Significantly improves the agent's ability to understand and modify large
     codebases compared to raw file read/write operations.
   - **Database tools** — query or migrate database schemas
   - **Documentation tools** — search and fetch library documentation
   - **Custom domain tools** — any MCP server relevant to the project's domain

**MCP server registry:**

External MCP servers are configured at the application level and associated with actors
and/or action types:

| Field              | Description                                                 |
|--------------------|-------------------------------------------------------------|
| `id`               | Internal unique identifier                                  |
| `name`             | Human-readable name (e.g. "Serena", "Context7")             |
| `description`      | What this MCP server provides                               |
| `serverConfig`     | MCP server launch configuration (command, args, env)        |
| `applicableActions`| (Optional) Action types this server is relevant for — if empty, available for all action types |

The MCP config file generated for each task includes:
- The Axiom project context server (always included)
- External MCP servers that are (a) associated with the assigned actor and (b) applicable to
  the task's action type

This allows the system to evolve: as new MCP servers become available (or as custom ones are
built), they can be registered and made available to agents without changing the actor
implementation. The user manages the MCP server registry via the UI.

**Safety constraint enforcement:**

| Constraint Level | Mechanism |
|------------------|-----------|
| Actor-level | `--allowedTools` with the actor's full permission set |
| Action-level | Compute intersection of actor + action tools, pass as `--allowedTools` |
| Project-level | `--cwd` scoped to the project's git clone |
| Runaway prevention | `--max-turns` and `--max-budget-usd` |
| Isolation | `--bare` prevents loading external hooks/plugins |

**Subprocess lifecycle:**

The actor implementation must:
1. Build the command line from task context and safety constraints
2. Write the task-specific system prompt to a temp file
3. Generate the MCP config file for project-specific tools
4. Spawn the subprocess via `ProcessBuilder`, capturing stdout and stderr
5. Read NDJSON lines from stdout, forwarding progress to SSE
6. Monitor for timeout (configurable per-actor) and kill if exceeded
7. Parse the final result JSON
8. Record cost, token usage, session ID, and output on the Task entity
9. Clean up temp files

**Concurrency:** Multiple Claude Code subprocesses may run in parallel for different projects.
A configurable maximum limits the number of concurrent instances.

### 5.6 Human Actor (`actors/human`)

Delivers task assignments via the notification system and collects responses.

1. Formats the task into a human-readable description
2. Sends it via the user's configured notification channel (Slack, Telegram, or UI)
3. Listens for a response (channel-specific: Slack thread reply, Telegram message, UI form submit)
4. Translates the response into a `TaskResult`

The Human Actor is inherently asynchronous — the task remains `In Progress` until the human
responds.

### 5.7 Notification Service (`notifications/`)

**SPI:**

```java
public interface NotificationChannel {

    void send(Notification notification);

    void onResponse(String notificationId, Consumer<String> callback);
}
```

**Channels:**
- **Slack:** Uses the Slack API (Bot token) to send messages and listen for thread replies
- **Telegram:** Uses the Telegram Bot API to send messages and listen for replies
- **UI (in-app):** Stores notifications in the database; the frontend polls or receives them
  via SSE

### 5.8 SSE Real-Time Updates (`app/`)

A single SSE endpoint (`GET /api/v1/sse`) streams events to the frontend:

```java
@GET
@Path("/sse")
@Produces(MediaType.SERVER_SENT_EVENTS)
public Multi<SseEvent> stream() { ... }
```

Event types pushed to the client:
- `project-updated` — project status or metadata changed
- `task-updated` — task status changed (started, completed, failed)
- `thread-entry` — new conversation thread entry
- `notification` — alert for the user (clarifying question, escalation)
- `activity` — new activity log entry


## 6. Frontend Architecture

### 6.1 Deployment Model

- **Development:** Vite dev server with hot module replacement. Proxies API requests to the
  Quarkus backend.
- **Production:** Built as static assets, served by an **nginx container**. The nginx container
  is configured with the backend API URL as its only configuration.

### 6.2 Configuration Bootstrap

The nginx container injects the API URL into the served application via one of:
- A `/config.js` file generated at container startup from environment variables, loaded by
  `index.html` before the React app initializes
- Or a template substitution in `index.html` at container startup

On load, the React app calls `GET /api/v1/system/config` to fetch all runtime configuration
(available action types, actors, notification settings, feature flags, etc.). No other
configuration is baked into the UI container.

### 6.3 Key UI Libraries

| Concern          | Technology                                                      |
|------------------|-----------------------------------------------------------------|
| UI components    | PatternFly + apicurio-common-ui-components                      |
| State management | React Query (TanStack Query) for server state                   |
| Routing          | React Router                                                    |
| SSE client       | Native EventSource API or a lightweight wrapper                 |
| Forms            | React Hook Form                                                 |
| HTTP client      | Generated TypeScript SDK (see Section 6.4)                      |

**PatternFly** is the primary UI component library. Additional reusable components from
[apicurio-common-ui-components](https://github.com/Apitomy/apicurio-common-ui-components) are
used where applicable.

### 6.4 TypeScript SDK (API Client)

A TypeScript SDK is generated from the OpenAPI document using **Kiota**. This provides type-safe
API calls in the frontend and keeps the client in sync with the backend contract automatically.

The SDK follows the same pattern used in
[apicurio-registry/typescript-sdk](https://github.com/Apitomy/apicurio-registry/tree/main/typescript-sdk):
- Generated from the same `openapi.json` used by the backend
- Published as a package within the monorepo (consumed by the `ui/` module)
- Regenerated as part of the build whenever the OpenAPI document changes


## 7. Docker Compose Stack

```yaml
services:
  axiom-api:
    build: .
    ports:
      - "9090:9090"
    environment:
      QUARKUS_PROFILE: prod
      QUARKUS_DATASOURCE_JDBC_URL: jdbc:postgresql://axiom-db:5432/axiom
      QUARKUS_DATASOURCE_USERNAME: axiom
      QUARKUS_DATASOURCE_PASSWORD: axiom
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
    volumes:
      - axiom-workspaces:/var/axiom/workspaces    # Git clone working directories
    depends_on:
      - axiom-db

  axiom-db:
    image: postgres:16
    environment:
      POSTGRES_DB: axiom
      POSTGRES_USER: axiom
      POSTGRES_PASSWORD: axiom
    volumes:
      - axiom-pgdata:/var/lib/postgresql/data
    ports:
      - "5432:5432"

  axiom-ui:
    build: ./ui
    ports:
      - "9191:9090"
    environment:
      AXIOM_API_URL: http://localhost:9090

volumes:
  axiom-pgdata:
  axiom-workspaces:
```


## 8. Development Workflow

### 8.1 Local Development

```bash
# Terminal 1: Start Quarkus in dev mode (auto-provisions H2, live reload)
cd app/
mvn quarkus:dev

# Terminal 2: Start React dev server (proxies API to Quarkus)
cd ui/
npm run dev
```

Quarkus Dev Services automatically provides an H2 database in dev mode. No Docker required for
development.

### 8.2 Contract-First API Workflow

1. Edit `common/api/src/main/resources/openapi.json`
2. Run `mvn generate-sources -pl common/api` to regenerate JAX-RS interfaces and beans
3. Implement or update the generated interfaces in the backend
4. Regenerate the TypeScript SDK: run Kiota against the same OpenAPI document to update
   `typescript-sdk/`
5. The frontend consumes the updated SDK automatically (workspace dependency)

### 8.3 Production Build

```bash
# Build backend
mvn clean package -Pprod

# Build frontend
cd ui/
npm run build

# Start everything
docker compose up --build
```


## 9. Project Working Directories

Each Project gets an independent git clone of its associated repository, stored under a
configurable workspace root (e.g. `/var/axiom/workspaces/` in Docker, or
`~/.axiom/workspaces/` for local dev).

```
workspaces/
├── project-001/                  # Git clone for project 001
│   └── <repository contents>
├── project-002/                  # Git clone for project 002 (may be same repo as 001)
│   └── <repository contents>
└── project-003/
    └── <repository contents>
```

The git clone is created when the Project is created. It is deleted when the Project is archived
or purged. Each clone operates on its own branch to avoid conflicts.

### 9.1 Git Authentication

Repository cloning and push operations support two authentication methods, configurable per
repository:

- **SSH keys:** The application is configured with a path to an SSH private key. Git operations
  use this key via `GIT_SSH_COMMAND`. Suitable for repositories accessed via `git@github.com:...`
  URLs.
- **Personal Access Tokens (PATs):** The token is stored in the repository configuration. Git
  operations use the token via HTTPS URL embedding (`https://<token>@github.com/...`) or via a
  credential helper. Suitable for HTTPS-based repository access.

Both methods can coexist — different repositories can use different authentication methods.


## 10. Security Considerations

### 10.1 Secrets Management

- **Anthropic API key:** Required by Claude Code; provided via environment variable, never
  stored in the database
- **GitHub/Jira API tokens:** Stored encrypted in the database or provided via environment variables
- **Git SSH keys:** Path to private key provided via configuration (not stored in database)
- **Git PATs:** Stored encrypted in the repository configuration
- **Webhook secrets:** Stored in the repository configuration, used to validate incoming webhooks
- **Slack/Telegram bot tokens:** Stored encrypted in notification channel configuration

### 10.2 AI Agent Sandboxing

- Claude Code subprocesses run with `--cwd` restricted to the project's working directory
- The `--allowedTools` flag limits what tools the agent can use
- The application monitors subprocess activity and can kill runaway processes
- File system access outside the workspace root should be restricted (container-level enforcement
  via Docker volume mounts)

### 10.3 Webhook Validation

- GitHub webhooks are validated using HMAC-SHA256 signatures
- Jira webhooks are validated using shared secrets or JWT (depending on Jira deployment type)


## 11. Open Technical Questions

1. **Clarifying questions from AI agents:** The CLI subprocess model does not support
   interactive Q&A. Phase 1 works around this with comprehensive prompting. If this proves
   insufficient, a Node.js sidecar using the Claude Agent SDK will be needed (Phase 2). The
   trigger for this decision is: how often do tasks fail or produce incomplete results due to
   missing context?

2. **MCP server for Claude Code agents:** The architecture calls for injecting a custom MCP
   server config so agents can query project context. The details of this MCP server (endpoints,
   authentication, protocol) need to be designed.

3. **Monitoring and observability:** Should we add structured logging, metrics (Micrometer), or
   health checks beyond what Quarkus provides by default?
