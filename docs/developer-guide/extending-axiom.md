# Extending Axiom

Axiom is designed to be extended at several key points: AI agents, event connections,
and notification channels. Extension points generally follow the same pattern —
define an interface in an SPI module, implement it in a sibling module, and let CDI
discover it at runtime.

---

## The SPI Pattern

Extension points that use a formal SPI follow this structure:

```
component/
├── spi/          Interface definition + registry
└── impl-name/    Concrete implementation
```

Implementations are `@ApplicationScoped` CDI beans. A registry or CDI `Instance<T>`
discovers all implementations at startup. The active implementation is selected by
configuration or, for agent pool work, by capability matching.

---

## Adding an AI Agent

An agent provides the ability to invoke an LLM for Manager evaluations, task
execution, report generation, and agent-mode scheduled jobs. Axiom ships with three
agent implementations: Claude Code, OpenCode, and GitHub Copilot CLI.

### Interface: `Agent`

Location: `agents/spi/src/main/java/io/apitomy/axiom/agents/spi/Agent.java`

| Method | Purpose |
|--------|---------|
| `String getType()` | Agent type identifier (e.g. `"claude-code"`, `"opencode"`, `"copilot"`) |
| `String getLabel()` | Human-readable display label |
| `List<String> getAvailableModels()` | Models available for the UI model picker |
| `boolean supportsInteractiveSessions()` | Whether this agent can back AI Assistant sessions |
| `CompletableFuture<AgentResult> execute(AgentRequest request)` | Invoke the agent with a prompt |
| `CompletableFuture<AgentResult> executeWithSchema(AgentRequest request, String jsonSchema)` | Invoke with a JSON schema constraint for structured output |
| `void cancel(String executionId)` | Best-effort cancellation of a running execution |
| `List<AgentCheckResult> healthCheck()` | Startup health checks |

Implementations are `@ApplicationScoped` CDI beans discovered via CDI
`Instance<Agent>`.

### Discovery: `AgentRegistry`

`AgentRegistry` discovers all `Agent` beans via CDI `Instance<Agent>` and provides
type-based lookup. The default agent type is selected by the
`axiom.agent.default-type` configuration property, and can be changed at runtime
from **Settings > AI Engine** in the UI.

### Configuration Objects

- **`AgentRequest`** — builder-based configuration passed to every agent call:
  prompt, system prompt, model, allowed/disallowed tools, working directory,
  environment variables, timeout, max steps, budget, MCP config file
- **`AgentResult`** — returned from agent calls: output text, session ID, cost,
  tokens, success flag, execution log

### MCP Support (Optional)

If your agent supports MCP tool servers, implement `AgentMcpManager`:

| Method | Purpose |
|--------|---------|
| `Path configureMcpServers(Long workItemId, Map<String, String> environment, List<String> allowedTools)` | Generate MCP config file; return path or null |
| `void cleanup(Long workItemId)` | Clean up after work item completion |

`AgentRegistry.getMcpManager(agentType)` resolves the MCP manager for a given agent
type when the `Agent` implementation also implements `AgentMcpManager`.

### Reference Implementations

- **Claude Code**: `agents/claude-code/src/main/java/.../ClaudeCodeAgent.java` —
  subprocess-based, launches `claude` CLI
- **OpenCode**: `agents/opencode/src/main/java/.../OpenCodeAgent.java` —
  HTTP client against a running OpenCode server
- **GitHub Copilot CLI**: `agents/copilot/src/main/java/.../CopilotAgent.java` —
  subprocess-based, launches `copilot` CLI

### The Agent Pool

Registered agents don't self-select work — `AgentPool` leases an enabled, idle
`AgentEntity` to a caller based on a requested capability string (e.g.
`action:Auto-Label Issue`, `report:weekly-status`, `job:cleanup`). See
[AI Agents](../user-guide/concepts.md#ai-agents) for the capability-matching model
that all agent-consuming services (`TaskExecutionService`, `ReportExecutionService`,
`ScheduledJobExecutionService`) share.

### Interactive Assistant Session Drivers

The AI Assistant runtime uses `InteractiveSessionDriver` as an agent-neutral contract for
session lifecycle, prompt submission, permission handling, and interrupt/teardown behavior.
`InteractiveSessionDriverFactory` selects the concrete driver per session
(`ClaudeInteractiveSessionDriver` or `OpenCodeInteractiveSessionDriver`) based on the
session's resolved agent type, and enforces fail-closed OpenCode compatibility checks
before allowing session startup.

---

## Adding a Connection Type

A Connection polls an external system for activity and produces normalized events for
the event stream. Axiom ships with GitHub and Jira connection types.

### Pattern

Connection pollers are `@Scheduled` beans that use `EventStreamService` to persist
normalized events. There is no formal SPI interface — the pattern is convention-based,
built around a shared normalized event model (`NormalizedEvent` and its typed payload
records) defined in `core`.

### Key Service: `EventStreamService`

Location: `events/core/src/main/java/io/apitomy/axiom/events/core/EventStreamService.java`

| Method | Purpose |
|--------|---------|
| `boolean persistEvent(NormalizedEvent event)` | Persist a normalized event, deduplicated by source event ID; fires an SSE update on success |

### Implementation Pattern

1. Create a scheduled poller with `@Scheduled` and `concurrentExecution = SKIP`
2. Find enabled connections of your source type from `EventSourceConnectionEntity`
3. Check if the connection's poll interval has elapsed since `lastPolledAt`
4. Fetch activity from the external API
5. Normalize each item into a `NormalizedEvent` with a typed payload and call
   `eventStreamService.persistEvent()`
6. Update `lastPolledAt` on the connection
7. Record the poll result in `ConnectionPollLogEntity`

New connections do not need any corresponding Subscription configuration to start
recording events — subscriptions are what route recorded events onward to the Manager,
a workflow, or an action.

### Reference Implementations

- **GitHub**: `events/github/src/main/java/.../v2/GitHubConnectionPoller.java` —
  polls the GitHub Repository Events API, backfilling PR payloads as needed
- **Jira**: `events/jira/src/main/java/.../v2/JiraConnectionPoller.java` — polls the
  Jira REST API using JQL search with changelog expansion

See [Event System — Design Document](event-sourcing-design.md) for the full event
schema and per-source normalization details.

---

## Adding a Notification Channel

Notification channels deliver alerts to humans (e.g. when a task requires human
input).

### Current State

The notification SPI (`notifications/spi/`) is currently a stub — no formal interface
is defined yet, and the `notifications/slack/` and `notifications/telegram/` modules
are placeholders with no implemented delivery logic. Human-completed tasks are
currently surfaced through the Inbox UI regardless of whether a notification channel
is configured.

---

## Module Setup

When adding a new extension module:

1. **Create the Maven module** directory under the appropriate parent
   (e.g. `agents/my-agent/` or `events/my-source/`)

2. **Create `pom.xml`** with the parent reference and dependencies, following the
   pattern of an existing sibling module (e.g. `agents/opencode/pom.xml`)

3. **Add to root `pom.xml`** modules list and `dependencyManagement`

4. **Add the Jandex plugin** to your module's `pom.xml` — required for CDI bean
   discovery in Quarkus:

    ```xml
    <plugin>
        <groupId>io.smallrye</groupId>
        <artifactId>jandex-maven-plugin</artifactId>
    </plugin>
    ```

5. **Add as a dependency** in `app/pom.xml` so the app module includes your
   implementation
