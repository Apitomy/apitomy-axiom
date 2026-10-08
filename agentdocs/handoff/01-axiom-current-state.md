# Apitomy Axiom: Current State (2026-10-08)

Axiom runs **workflows** (built with Apitomy Flow) over **subjects** such as GitHub or Jira issues. Workflow
`action` nodes run **action types**: an AI agent prompt, a Node.js module or a bash script, each with declared
inputs and outputs. Agents get **tools** (parameterized bash scripts, grouped into **toolsets**) through MCP.
**Event sources** poll GitHub and Jira; **subscriptions** filter the events and either start workflows or
deliver them to runs waiting at a `receive-event` node. Workflow runs are the primary object in the UI
(`/runs` is the landing page).

## 1. Modules, stack, build

**Maven modules** (root `pom.xml`):

| Module | Contents |
|--------|----------|
| `common/api` | `openapi.json` (the REST contract), JAX-RS interfaces and beans generated into `target/generated-sources/jaxrs/` |
| `core` | JPA entities, core services (validation, encryption, workspaces, tracing, SDK registry), `AxiomHome` |
| `agents/spi`, `agents/claude-code`, `agents/opencode`, `agents/copilot` | Agent SPI (`Agent`, `AgentRegistry`) and one implementation per engine |
| `events/core`, `events/github`, `events/jira` | Event-source polling and normalization |
| `notifications/spi`, `notifications/slack`, `notifications/telegram` | Notifications |
| `app` | The Quarkus application: REST implementations, workflow execution, the assistant, schedulers |
| `ui-bundle` (profile) | Packages the UI |

Other top-level folders: `ui/` (React app), `docs/` (mkdocs site), `agentdocs/` (design and agent notes),
`tools/`, `scripts/`, `event-examples/`.

**Stack:**
- Java 25 and Quarkus 3.40.1.
- H2 database with Flyway. There is a single migration,
  `app/src/main/resources/db/migration/V1__baseline.sql`, which we edit in place (nothing is released yet).
- React 19, PatternFly 6, Vite 8.
- Apitomy Flow **2.7.0**: `io.apitomy:apitomy-flow-engine` (Java) and `@apitomy/flow-ui` (React).

**Build and run:**

| Command | Notes |
|---------|-------|
| `mvn package` | Full build and tests, without `clean`. Safe while dev mode runs. |
| `mvn -q -pl app -am test -Dtest=SomeTest -Dsurefire.failIfNoSpecifiedTests=false` | Focused tests. Use `-am` so `core` changes are picked up. |
| `mvn -q -pl common/api install -DskipTests` | Regenerates the REST interfaces and beans after editing `openapi.json` |
| `./build.sh` | Runs `mvn clean`, which **kills a running `quarkus:dev`**. Warn the user first. |
| `cd ui && npx tsc -b --noEmit && npx vitest run` | UI type check and tests |

**Runtime:**
- Dev mode listens on port **9090** (`%prod` uses 9191). REST is under `/api/v1`. There is one global SSE
  channel, `/api/v1/sse`.
- Profiles `%persist` and `%prod` use a file-based H2 database at `~/.axiom/data/axiom`. Dev mode is not
  persistent.
- **`axiom.home`** (default `~/.axiom`) holds assistant sessions, task files, installed MCP servers,
  workspaces and `secret.key`. Surefire sets it to `target/axiom-test-home`, so **tests never touch the
  developer's `~/.axiom`**. Before this was fixed, test runs deleted live assistant sessions.

**API-first rule** (see `CLAUDE.md`): every REST change starts in
`common/api/src/main/resources/openapi.json`. Make targeted edits there, regenerate, then implement the
generated interface in `app/src/main/java/io/apitomy/axiom/app/rest/*ResourceImpl.java`. Never put `@Path` on
an implementation class, and use the generated beans (`io.apitomy.axiom.api.beans`) rather than raw
`JsonNode`.

## 2. Domain model (`core/src/main/java/io/apitomy/axiom/core/entities/`)

| Entity | Purpose |
|--------|---------|
| `SubjectEntity` | The thing being worked on (issue, PR, repository), with a workspace directory |
| `TaskEntity` | One execution of an action type, or a human task created by a workflow |
| `ActionTypeEntity` | A reusable unit of work (`agent`, `script` or `node`) with declared inputs and outputs |
| `ToolDefinitionEntity`, `ToolsetEntity`, `McpServerEntity` | Bash tools, named tool groups, external MCP servers |
| `AgentEntity` (+ capability, lease) | Agents in the pool, each bound to one engine |
| `WorkflowDefinitionEntity`, `WorkflowDefinitionVersionEntity` | Flow workflow definitions; runs use a published version |
| `WorkflowRunEntity`, `WorkflowRunResumeEntity` | Runs (the Flow instance is stored as JSON in `instanceState`) and which event resumed them |
| `WorkflowWaitEntity` | A run parked at a `wait` (timer) node |
| `WorkflowEventSubscriptionEntity` | A run parked at a `receive-event` node: `correlation_key`, `timeout_at`, `lookback_from` |
| `WorkflowScheduleTriggerEntity` | Cron triggers |
| `StreamEventEntity` | A normalized incoming event |
| `EventSourceEntity` (+ poll log) | GitHub and Jira sources |
| `EventSubscriptionEntity` | A filter expression plus routing rules |
| `EventProcessingLedgerEntity`, `RoutingOutcomeEntity` (+ items) | Per event × subscription processing, retries and results |
| `SessionTemplateEntity` | User-defined assistant session templates (built-ins come from the classpath) |
| `SecretEntity`, `TraceEntity`/`TraceNodeEntity`, `AiUsageEntity`, `ActivityLogEntity`, `DashboardEntity`, `SystemConfigEntity` | Secrets, execution traces, AI cost, activity, dashboards, settings |

## 3. Action types

There are three execution modes, run through `TaskExecutionService` and `TaskQueuePoller`.

**Data contract:** an action receives data **only through its declared inputs**, which the workflow's `action`
node maps from EL expressions (`context.*`, earlier outputs, quoted literals). It never relies on ambient
subject data or environment variables. Blank required inputs count as missing. Object-typed fields also accept
JSON arrays. Outputs are declared and validated, and are merged into the run context under their names.

| Mode | Field | How it runs |
|------|-------|-------------|
| `agent` | `promptTemplate` (+ `allowedTools`, `engine`, `model`) | The only placeholder is `{{inputs.<name>}}`. Axiom appends an Inputs section and a "Required output" contract, so prompts must not describe the output format. Runs on a pool agent whose engine matches the action type's engine. |
| `node` (newest, preferred for non-AI work) | `codeTemplate` | An ES module exporting `async function run(inputs, ctx)`, executed by `app/src/main/resources/node-runner/axiom-node-runner.mjs` via `NodeExecutionService`. `ctx.exec(cmd, args)` (no shell), `ctx.log`. Only Node built-in modules can be imported (`require` is not defined). Checked with `node --check`, with results cached. |
| `script` | `scriptTemplate` | Bash, run by `ScriptExecutionService` in the subject workspace. `{{inputs.x}}` is substituted (rejected inside quoted heredocs). stderr is captured separately. Outputs come from the **last JSON object printed to stdout**. |

- **Engines:** `claude-code`, `opencode` and `copilot`, one per `agents/*` module. Seeded agents are Blinky
  and Clyde (claude-code) and Pinky and Inky (opencode); see `SeedDataInitializer`. The UI explains the agent
  pool in `AgentPoolHelpModal`.
- **Validation:** `core/.../services/ActionTypeValidator` plus `app/.../validation/*`.
- **UI:** `ActionTypeDetailPage.tsx` has tabs for Prompt, Script or Code (`NodeCodeTab`) depending on the
  mode, plus fields and validation problems. A bug fixed on 2026-10-08: the page did not load `codeTemplate`
  into its form.

## 4. Tools, toolsets, SDK

- A **tool** is a bash script with declared parameters. **Toolsets** group tools and are referenced in
  `allowedTools` as `@Toolset Name`.
- Agents reach tools through the `axiom-tools` MCP server, with tool names like `mcp__axiom-tools__<tool>`.
  `app/.../McpConfigGenerator.java` installs the Node MCP servers into `~/.axiom/mcp-server/` and writes the
  per-run config and `tools.json`.
- **SDK functions** are registered in `core/.../SdkFunctionRegistry.java`. Agents reach them through the
  `axiom-sdk` MCP server; workflows call them with an `action` node whose `actionType` is `sdk:<function>`,
  run by `SdkCallService` with no AI involved.

## 5. Workflows (Apitomy Flow 2.7.0)

- **Engine integration:** `app/.../WorkflowExecutionService.java`. Supported node types are `start`, `end`,
  `action`, `human-task`, `wait` and `receive-event`. The engine itself is stateless; Axiom persists the
  instance JSON and the parked state.
- **Human tasks:** a run parked at a `human-task` node creates an Inbox task (`WorkflowHumanTaskMapper`,
  `InboxPage`). The task subject is the node's EL `title` (Flow 2.7), falling back to the node name.
- **Wait nodes:** handled by `WorkflowWaitScheduler`, which also fires receive-event timeouts and recovers
  look-backs.
- **Receive-event nodes:** `WorkflowEventDispatcher` handles these:
  - **Correlation key:** the evaluated `subscriptionKey` is stored in an indexed column and used to prefilter
    events.
  - **Look-back:** default `run-start`. When a run parks, stored events in the window are replayed, but only
    those that matched a subscription with a `workflow-dispatch` rule (ledger entry not `skipped`) and that
    have not already resumed this run. Replay runs after the parking transaction commits
    (`EventLookbackRequested`). Choosing events by ledger entry rather than routing outcome closes the race
    with routing that is in flight. The subscription row is locked while an event is offered.
  - **Timeout:** stored in `timeout_at`. When it fires, the run follows the node's `isTimeout` edge.
- **Schedule triggers:** `WorkflowScheduleTriggerScheduler` (cron).
- **REST and UI:**
  - History entries expose per-visit `input` and `title` (Flow 2.7).
  - `WorkflowDefinitionDetailPage` embeds the Flow `WorkflowEditor` with an `EditorSpi`. The SPI is shared
    through `ui/src/hooks/useWorkflowEditorSpi.ts`; it supplies action types plus `sdk:` functions for the
    action-type picker.
  - Run views (`WorkflowRunDetailPage`, `WorkflowTab`) use the Flow `WorkflowViewer`.
- **Docs:** `docs/user-guide/workflows.md`, `docs/developer-guide/workflow-*.md`.

## 6. Events pipeline

1. Sources (`events/github`, `events/jira`) poll on an interval. Every poll is logged.
2. Events are normalized into `StreamEventEntity` with a `type` (for example `issue.comment.created`), `ref`,
   `actor` and `payload`.
3. `EventStreamOrchestrator` evaluates each enabled subscription's filter for each event:
   - If the filter does not match, the ledger entry is `skipped`.
   - Otherwise a `pending` ledger entry is committed and the routing rules run:
     - `create-workflow` starts a run.
     - `workflow-dispatch` offers the event to waiting runs.
4. Routing records routing outcomes and items. Failures are retried with backoff, up to a cap.
5. Retention cleanup jobs remove old events, traces and history.

Design doc: `docs/developer-guide/event-sourcing-design.md`.

## 7. UI (`ui/src`)

- **Pages:**
  - Work: Runs, Subjects, Tasks, Inbox.
  - Configuration: Workflow definitions, Action types, Agents, Tools, Toolsets, MCP servers, Secrets, Event
    sources, Event stream, Subscriptions, Session templates, Configuration packs.
  - Assistant: Assistant, Assistant session.
  - Operations: Traces, Dashboards, AI usage, Activity log, data retention, disk usage, engine settings.
- **Navigation:** `components/AppSidebar.tsx`. **API client:** `config/api.ts`. **SSE client:**
  `config/sse.ts`.
- **One-shot AI edit helpers** already exist (relevant to the DX):
  - `ActionTypeAiModal`: `POST /action-types/ai-edit-prompt` (`ActionTypeAiService`)
  - `ScriptAiModal`: `POST /action-types/ai-edit-script` (`ScriptAiService`)
  - `ToolAiModal`: `POST /tools/ai-edit` (`ToolAiService`)

  They share `AiEditModal.tsx`. There is **no AI helper for node `codeTemplate`** yet.

## 8. Recent history (themes, newest first)

- Flow 2.7.0 upgrade: receive-event correlation, look-back and timeouts; human-task titles; history inputs;
  read-only editor in the assistant preview; assistant taught the new options.
- Configuration Assistant: tests isolated from `~/.axiom`; Apply refuses empty or missing work directories;
  the preview shows workflow inputs and event-source properties; the apply summary covers every item type.
- Node.js action types: design, runner, validation, editor and previews. The assistant prefers `node` for
  non-AI steps.
- Agents: seeded agents, engine-matched agent selection, agent pool help.
- Script correctness: heredocs, workspace directory, separate stderr, outputs from the last JSON object.
- Workflow-first refactor and the event-source rename (earlier specs in `docs/superpowers/specs/`).
