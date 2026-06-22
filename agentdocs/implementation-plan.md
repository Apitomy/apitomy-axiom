# Apitomy Axiom v2 — Phased Implementation Plan

**Status:** Draft
**Last Updated:** 2026-04-02

This document defines the phased implementation plan for Apitomy Axiom v2. Each phase builds
on the previous one and delivers a usable increment of functionality. Phases are ordered to
maximize early feedback: we get the core loop working first, then layer on intelligence, UI
polish, and integrations.

---

## Phase Overview

| Phase | Name                        | Goal                                                    |
|-------|-----------------------------|---------------------------------------------------------|
| 1     | Foundation                  | Project scaffolding, database, REST API, basic UI shell |
| 2     | Core Domain                 | Projects, Tasks, Actors, Action Types — full CRUD       |
| 3     | Event Ingestion (GitHub)    | Receive GitHub webhooks, normalize events, event queue  |
| 4     | Manager (AI Triage)         | AI Manager evaluates events and creates tasks           |
| 5     | Claude Code Actor           | Execute tasks via Claude Code CLI subprocess            |
| 6     | Pipeline Integration        | End-to-end event → manager → task → actor flow          |
| 7     | UI: Project Experience      | Project detail, activity timeline, conversation thread  |
| 8     | User-Initiated Actions      | Manual task triggering from the UI                      |
| 9     | Notifications               | In-app notifications + SSE real-time updates            |
| 10    | Human Actor                 | Task assignment and response collection for humans      |
| 11    | Policies & Configuration    | Policy management UI, action type management            |
| 12    | Jira Integration            | Jira webhook/polling event source                       |
| 13    | External Notifications      | Slack and Telegram notification channels                |
| 14    | MCP Server Extensibility    | MCP server registry, per-task MCP config generation     |
| 15    | Production Hardening        | Docker Compose, Flyway migrations, security, monitoring |

---

## Phase 1: Foundation

**Goal:** Establish the project skeleton, build tooling, and a running application with an
empty UI shell.

### Deliverables
- Maven multi-module project structure (all modules created, mostly empty)
- Parent POM with Quarkus 3.33 LTS BOM, Java 25 compiler settings
- `common/api` module with initial OpenAPI document (minimal — system health endpoint)
- apitomy-codegen Maven plugin configured to generate JAX-RS interfaces
- `core` module with Panache Next entity base classes (empty entities, just the setup)
- `app` module as Quarkus entry point, H2 configured for dev profile
- `ui` module initialized with Vite + React + TypeScript + PatternFly
- TypeScript SDK generation via Kiota (initial, from minimal OpenAPI)
- `GET /api/v1/system/health` endpoint working
- UI shell: app layout with PatternFly navigation (empty pages for Dashboard, Projects,
  Actors, Policies, Activity Log, Repositories)
- UI loads API URL from config, calls system health on startup

### Key Decisions to Make
- Final Maven module naming conventions (groupId, artifactId patterns)
- PatternFly version to target
- Vite proxy configuration for local development

### Done When
- `mvn clean install` succeeds from the root
- `mvn quarkus:dev` in `app/` starts the backend on port 9090
- `npm run dev` in `ui/` starts the frontend with PatternFly navigation
- The UI can call the backend health endpoint and display the result


## Phase 2: Core Domain

**Goal:** Implement the domain model with full CRUD REST APIs for all core entities.

### Deliverables
- **Entities (Panache Next):** Project, Task, Actor, ActionType, Policy, Event, Repository,
  ActivityLog, ThreadEntry, EventQueue
- **OpenAPI:** Full CRUD endpoints for Projects, Actors, Action Types, Policies, Repositories
- **Generated code:** JAX-RS interfaces and beans via apitomy-codegen
- **REST implementations:** CRUD operations for all entities
- **TypeScript SDK:** Regenerated from the expanded OpenAPI document
- **Seed data:** Built-in action types (analyze, auto-tag, implement, propose, review,
  respond, answer-question, close-project, reopen-project)
- **Project lifecycle state machine:** Status transitions enforced in the service layer

### Scope Notes
- No event processing yet — just data management
- No AI integration yet
- Tasks can be created via the API but not executed
- Activity log entries are created as side effects of CRUD operations

### Done When
- All entities persist correctly in H2
- All CRUD endpoints work via curl / Swagger UI
- Project status transitions are enforced (Created → In Progress → Idle → Completed)
- TypeScript SDK compiles and can call all endpoints


## Phase 3: Event Ingestion (GitHub)

**Goal:** Receive GitHub events, normalize them, and enqueue them for processing.

### Deliverables
- **`events/github` module:**
  - Webhook endpoint: `POST /api/v1/webhooks/github`
  - Webhook signature validation (HMAC-SHA256)
  - Event normalization: GitHub payload → internal `Event` entity
  - Supported event types: `issue-created`, `issue-updated`, `issue-closed`,
    `issue-reopened`, `comment-added`
- **`events/core` module:**
  - Event queue: insert events into `event_queue` table with status `pending`
  - Queue poller: background service that detects pending events (does not process yet)
- **Repository configuration:**
  - CRUD for monitored repositories (already from Phase 2)
  - Webhook secret stored per repository
- **Activity log:** Events received are logged in the global activity log

### Scope Notes
- PR events (`pr-opened`, `pr-updated`, `review-submitted`, `checks-completed`) are deferred
  to a later iteration — they require PR-to-issue linkage logic
- Polling mode (GitHub API polling as alternative to webhooks) is deferred
- The queue poller detects events but does not process them yet (no Manager)

### Testing Approach
- Use a tool like `ngrok` or GitHub's webhook testing to send real payloads
- Write integration tests with sample GitHub payloads

### Done When
- A GitHub webhook payload sent to the endpoint creates an `Event` and `EventQueue` entry
- Invalid signatures are rejected with 401
- The queue poller logs pending events


## Phase 4: Manager (AI Triage)

**Goal:** Implement the AI Manager that evaluates events and produces decisions.

### Deliverables
- **`manager` module:**
  - Claude Code subprocess launcher (reuses the same subprocess pattern as the Claude Code
    actor in Phase 5)
  - Prompt builder: assembles event payload, project summary, policies, action types, actors
    into a system prompt for the Manager
  - Axiom Manager MCP server: exposes decision tools (`create_task`, `ignore_event`,
    `execute_system_action`, `escalate`) and query tools (`get_project_summary`,
    `get_task_history`, `get_task_detail`, `get_thread_entries`, `get_related_events`)
  - JSON schema for structured output: enforces consistent decision format
  - Response parser: extracts structured decisions from the Claude Code JSON output
  - Confidence threshold: configurable, decisions below threshold are held for user review
- **Manager invocation (not yet wired into pipeline):**
  - Can be called directly via a test/debug REST endpoint
  - Accepts an event ID, evaluates it, returns decisions as JSON
- **Escalation:** Low-confidence decisions create a notification (in-app only for now)

### Scope Notes
- The Manager is not yet wired into the event processing pipeline — that's Phase 6
- Escalation notifications are stored in the database but not yet displayed in the UI
- The Manager uses Claude Code CLI (same as actors), configured with `--bare`,
  `--output-format json`, `--json-schema`, and `--mcp-config` for decision/query tools
- The Anthropic API key (used by Claude Code) is provided via environment variable

### Testing Approach
- Create sample events via the API, then call the Manager debug endpoint
- Verify that decisions match expected behavior for different event types and policies

### Done When
- The Manager can evaluate a sample `issue-created` event and return a `create_task` decision
- The Manager can evaluate a sample `comment-added` event and decide to ignore or act
- Query tools return correct data from the database
- Low-confidence decisions are flagged


## Phase 5: Claude Code Actor

**Goal:** Execute tasks by launching Claude Code as a CLI subprocess.

### Deliverables
- **`actors/spi` module:**
  - `Actor` interface, `TaskHandle`, `ActorContext`, `TaskResult`, `TaskStatus` types
  - `Question` and `Answer` types (for future use)
- **`actors/claude-code` module:**
  - Command builder: constructs the `claude -p ...` command line from task context
  - Subprocess launcher: spawns process via `ProcessBuilder`
  - NDJSON parser: reads `stream-json` output line by line
  - Result parser: extracts final result, cost, token usage, session ID
  - Timeout enforcement: kills process after configurable duration
  - System prompt generation: writes task-specific instructions to a temp file
  - Effective tool set computation: intersection of actor permissions and action-level
    constraints
- **Git workspace management:**
  - Clone a repository into the workspace directory when a Project is created
  - `--cwd` set to the project's workspace directory
  - Git authentication via SSH key or PAT (per repository configuration)
- **Task execution service (in `core`):**
  - Accepts a Task, resolves the assigned Actor, calls `Actor.assignTask()`
  - Enforces project-level task serialization (only one active task per project)
  - Updates Task status through its lifecycle (Pending → In Progress → Completed/Failed)
  - Records cost and token usage on the Task entity
  - Emits internal events when `emitsEvent` is true on the action type

### Scope Notes
- No MCP config injection yet — just basic Claude Code with `--bare`
- No clarifying question handling — comprehensive prompts only
- The actor is not yet wired into the pipeline — tasks are executed via direct API call

### Testing Approach
- Create a Project linked to a test repository (manually via API)
- Create a Task (manually via API) and verify Claude Code executes correctly
- Verify NDJSON output is parsed and cost is recorded
- Verify timeout kills the process

### Done When
- A Task with action type `analyze` runs Claude Code against a cloned repository
- The agent's output is captured and stored on the Task entity
- Cost and token usage are recorded
- The process is killed if it exceeds the timeout


## Phase 6: Pipeline Integration

**Goal:** Wire everything together into the end-to-end event processing loop.

### Deliverables
- **Pipeline orchestrator (`events/core`):**
  - Background service that dequeues events, calls the Manager, and dispatches tasks
  - Project auto-creation: if the Manager creates a task and no project exists, create one
    (including git clone)
  - System action execution: `close-project` and `reopen-project` update project status
  - Task enqueueing: created tasks go into the project's task queue
  - Task executor integration: pending tasks are picked up and dispatched to actors
  - Internal event emission: task completion optionally creates a new event
  - Event queue status updates: `pending` → `processing` → `completed`/`failed`
- **End-to-end flow:**
  - GitHub webhook → Event → Queue → Manager → Task → Actor → Result → Internal Event → ...
- **Activity logging:** All pipeline steps are recorded in the activity log
- **Conversation thread:** Manager decisions and task results are posted to the project's
  thread

### Scope Notes
- This is the critical integration phase — most bugs will surface here
- The UI does not yet show any of this in real time (that's Phase 7 and 9)
- Error handling and retry logic for the pipeline

### Testing Approach
- End-to-end test: create a GitHub issue on a test repo, verify the full pipeline executes
- Verify project auto-creation
- Verify internal event chaining (task-completed → Manager re-evaluates)
- Verify task serialization within a project

### Done When
- Creating a GitHub issue triggers the full pipeline: event → manager → task → actor → result
- The project is auto-created with a git clone
- The activity log shows the complete chain of events
- Internal events trigger follow-up Manager evaluation


## Phase 7: UI — Project Experience

**Goal:** Build the primary user-facing views for interacting with projects.

### Deliverables
- **Dashboard:**
  - Project list with status indicators, type badges, issue links
  - Project count summary (by status)
  - Create Project form (manual project creation)
- **Project Detail View:**
  - Project metadata display (name, description, type, status, issue link)
  - Activity Timeline: chronological list of events, tasks, and decisions
  - Conversation Thread: chat-style view of thread entries
  - Task History: table of tasks with status, actor, action type, timestamps
  - Active task indicator (in-progress task with status)
- **Global Activity Log:**
  - Filterable list of all activity log entries
  - Manager decisions with reasoning
  - Events that were ignored (with reason)

### Scope Notes
- No real-time updates yet (polling only) — SSE comes in Phase 9
- No user-initiated actions yet — that's Phase 8
- Focus on read-only views first, then add interactive controls

### Done When
- The dashboard shows all projects with correct status
- The project detail view shows timeline, thread, and task history
- The global activity log is browsable and filterable
- Manual project creation works via the UI form


## Phase 8: User-Initiated Actions

**Goal:** Allow users to manually trigger actions on projects from the UI.

### Deliverables
- **Project Detail View additions:**
  - "Trigger Action" button/dropdown showing user-triggerable action types
  - Dynamic input form generated from the action type's `inputSchema`
  - Actor selection (optional — can be auto-selected based on capabilities)
- **Backend:**
  - `POST /api/v1/projects/{id}/tasks` endpoint for user-created tasks
  - Task created with `createdBy: user`, bypasses the Manager
  - Task enters the project's task queue and follows normal execution flow
- **Activity log and thread:** User-initiated tasks are recorded like event-driven ones

### Done When
- User can trigger an `analyze` action on a project from the UI
- The task executes via the Claude Code actor
- The result appears in the project's timeline and thread


## Phase 9: Notifications & Real-Time Updates

**Goal:** SSE-based real-time UI updates and in-app notification system.

### Deliverables
- **SSE endpoint:** `GET /api/v1/sse`
  - Pushes events: `project-updated`, `task-updated`, `thread-entry`, `notification`,
    `activity`
  - Backend emits SSE events whenever relevant state changes occur
- **UI SSE client:**
  - Connects on app startup, reconnects on failure
  - Updates React Query cache in response to SSE events (invalidation or optimistic update)
  - Real-time task progress display (streaming Claude Code output)
- **In-app notifications:**
  - Notification bell/badge in the UI header
  - Notification center: list of alerts (escalations, failed tasks, completed tasks)
  - Mark as read/dismiss
- **Manager escalation UI:**
  - When the Manager escalates (low confidence), a notification appears
  - User can approve, modify, or reject the Manager's proposed decision

### Done When
- Project status changes appear in the UI without page refresh
- Task progress streams in real time as Claude Code produces output
- Notifications appear for escalations, failures, and completions
- User can approve/reject escalated Manager decisions


## Phase 10: Human Actor

**Goal:** Implement the Human Actor so tasks can be assigned to people.

### Deliverables
- **`actors/human` module:**
  - Implements the `Actor` SPI
  - Sends task assignment via in-app notification (UI only — external channels in Phase 13)
  - Collects response via a UI form on the task detail view
  - Translates response into `TaskResult`
- **UI additions:**
  - "Assigned to you" task list (or notification)
  - Task response form: free-text response, optional file attachment
  - Task status: shows "Awaiting human response"

### Done When
- A task assigned to a human actor appears as a notification in the UI
- The user can respond via the UI
- The response is recorded as the task output and the task completes


## Phase 11: Policies & Configuration Management

**Goal:** Full UI for managing policies, action types, actors, and repositories.

### Deliverables
- **Policy Management UI (Section 8.4):**
  - List, create, edit, delete policies
  - Policy editor with guideline text, action type selection, actor hint
  - Policy testing: simulate an event and see the Manager's decision
- **Action Type Management UI (Section 8.5):**
  - List, create, edit, delete action types
  - Configure execution mode, user-triggerable flag, input schema, tool constraints,
    emitsEvent
- **Actor Management UI (Section 8.3):**
  - List, create, edit, delete actors
  - Configure type, capabilities, permissions, type-specific configuration
  - Actor health/status display
- **Repository Management UI (Section 8.7):**
  - List, create, edit, delete monitored repositories
  - Configure event types to watch, polling interval, webhook secret
  - Git authentication configuration (SSH key path or PAT)

### Done When
- All configuration entities can be managed through the UI
- Policy simulation works: user can test how the Manager would handle a sample event
- Changes take effect immediately (no restart required)


## Phase 12: Jira Integration

**Goal:** Add Jira as a second event source.

### Deliverables
- **`events/jira` module:**
  - Webhook endpoint: `POST /api/v1/webhooks/jira`
  - Webhook validation
  - Event normalization: Jira payload → internal `Event` entity
  - Supported event types: `issue-created`, `issue-updated`, `issue-transitioned`,
    `comment-added`
- **Repository configuration:**
  - Jira project as a "repository" concept (reuse the repository entity with
    `source: jira`)
  - Jira API authentication (API token or OAuth)

### Scope Notes
- Jira integration follows the same patterns as GitHub — normalize to the same internal
  Event format, reuse the entire pipeline
- Polling mode (Jira REST API) can be added as a follow-up

### Done When
- A Jira issue event triggers the full pipeline (same as GitHub)
- Projects can be linked to Jira issues
- Jira-sourced events appear in the activity log and timeline


## Phase 13: External Notification Channels

**Goal:** Slack and Telegram integration for notifications and human actor interaction.

### Deliverables
- **`notifications/slack` module:**
  - Slack Bot API integration
  - Send notifications (task assignments, escalations, alerts) as Slack messages
  - Receive responses via Slack thread replies
- **`notifications/telegram` module:**
  - Telegram Bot API integration
  - Send notifications as Telegram messages
  - Receive responses via Telegram replies
- **User preferences:**
  - UI for selecting preferred notification channel(s)
  - Channel-specific configuration (Slack workspace/channel, Telegram chat ID)
- **Human Actor update:**
  - Human Actor can deliver tasks and collect responses via Slack or Telegram
  - Clarifying question routing via external channels

### Done When
- Notifications appear in Slack/Telegram
- User can respond to human actor tasks via Slack thread
- Clarifying questions (future) can be routed to external channels


## Phase 14: MCP Server Extensibility

**Goal:** Enable registration of external MCP servers to extend agent capabilities.

### Deliverables
- **MCP Server Registry:**
  - Database entity for MCP server configurations
  - REST API: `POST/GET/PUT/DELETE /api/v1/mcp-servers`
  - UI: MCP Server Management page (list, add, edit, remove)
- **Axiom Project Context MCP Server:**
  - MCP server exposed by the Quarkus backend
  - Tools: query conversation thread, get task outputs, post activity updates
  - Authentication: token-based, scoped to the project
- **Per-task MCP config generation:**
  - When launching Claude Code, generate an MCP config JSON file that includes:
    - The Axiom project context server (always)
    - Applicable external MCP servers (based on actor + action type)
  - Pass via `--mcp-config` flag
- **Example integration:** Document and test Serena as the first external MCP server

### Done When
- An MCP server (e.g. Serena) can be registered via the UI
- Tasks executed by Claude Code include the registered MCP servers
- The Axiom context MCP server provides project-aware tools to the agent


## Phase 15: Production Hardening

**Goal:** Prepare for production deployment with Docker Compose, migrations, and security.

### Deliverables
- **Docker Compose stack:**
  - Backend container (Quarkus native or JVM)
  - PostgreSQL container
  - nginx container for UI
  - Volume mounts for workspaces and database
  - Environment variable configuration
- **Flyway migrations:**
  - Migration scripts for all database tables
  - Baseline migration from the current schema
  - Migration testing (fresh install and upgrade scenarios)
- **Security:**
  - Secrets encryption for stored tokens (GitHub, Jira, PATs, bot tokens)
  - Webhook signature validation (already done in Phase 3, verify for Jira)
  - Docker volume restrictions for AI agent sandboxing
- **Rate limiting:**
  - Maximum concurrent agent instances (configurable)
  - Per-task timeout enforcement (already done in Phase 5, verify)
  - Maximum task budget per project (configurable)
  - Cost tracking dashboard in the UI
- **Monitoring:**
  - Quarkus health checks (`/q/health`)
  - Structured logging (JSON format for production)
  - Micrometer metrics for key operations (events processed, tasks executed, agent cost)
- **Documentation:**
  - Installation guide
  - Configuration reference
  - User guide

### Done When
- `docker compose up` starts a fully functional system from scratch
- Flyway migrations run cleanly on a fresh PostgreSQL database
- All secrets are stored encrypted
- Rate limiting prevents runaway agent costs
- Health checks and metrics are exposed


---

## Dependency Graph

```
Phase 1 (Foundation)
   │
   ▼
Phase 2 (Core Domain)
   │
   ├───────────────────┐
   ▼                   ▼
Phase 3 (GitHub)    Phase 4 (Manager)    Phase 5 (Claude Code Actor)
   │                   │                    │
   └────────┬──────────┘                    │
            │                               │
            ▼                               │
         Phase 6 (Pipeline Integration) ◄───┘
            │
            ├──────────────────────┐
            ▼                      ▼
         Phase 7 (UI: Projects)  Phase 10 (Human Actor)
            │                      │
            ▼                      │
         Phase 8 (User Actions)    │
            │                      │
            ▼                      │
         Phase 9 (Notifications) ◄─┘
            │
            ├──────────────────────┬─────────────────┐
            ▼                      ▼                  ▼
         Phase 11 (Policies UI)  Phase 12 (Jira)   Phase 13 (Slack/Telegram)
            │                      │                  │
            └──────────┬───────────┘──────────────────┘
                       ▼
                    Phase 14 (MCP Extensibility)
                       │
                       ▼
                    Phase 15 (Production Hardening)
```

**Parallelizable phases:**
- Phases 3, 4, and 5 can be developed in parallel after Phase 2 (they are independent)
- Phases 7 and 10 can be developed in parallel after Phase 6
- Phases 11, 12, and 13 can be developed in parallel after Phase 9


## Risk Areas

1. **Phase 5 (Claude Code Actor)** carries the most technical risk — subprocess management,
   NDJSON parsing, and timeout handling are complex. Budget extra time here.

2. **Phase 6 (Pipeline Integration)** is where all the components meet. Integration bugs,
   race conditions, and edge cases will surface here. Plan for significant debugging time.

3. **Phase 4 (Manager)** depends on prompt engineering quality. The Manager's effectiveness
   depends on how well the prompt, tools, and policies are structured. Expect iteration.

4. **Git clone management** (Phase 5) has edge cases: large repos, authentication failures,
   branch conflicts, disk space. Start with small test repos.
