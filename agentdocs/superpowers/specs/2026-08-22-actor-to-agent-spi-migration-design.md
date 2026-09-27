# Actor-to-Agent SPI Migration

Unify the Actor SPI and AiEngine SPI into a single Agent SPI, enabling all AI workloads
(tasks, reports, scheduled jobs) to share a common agent pool with centralized resource
governance.

## Problem Statement

Axiom currently has two parallel SPIs for AI execution:

1. **Actor SPI** (`actors/spi/`) — task-shaped interface (`TaskEntity` in, `TaskResult` out).
   Used only by `TaskExecutionService` for task execution. Concurrency is managed via per-actor
   busy checks against the `actor` database table.

2. **AiEngine SPI** (`engine/spi/`) — prompt-shaped interface (`AiEngineConfig` + prompt in,
   `AiEngineResult` out). Used directly by `ReportExecutionService`, `ScheduledJobExecutionService`,
   `ManagerService`, and several utility services (e.g., `ScriptAiService`, `ToolAiService`,
   `AssistantSessionManager`).

This creates several problems:

- **No unified AI resource governance.** Tasks go through the actor pool (with busy checks), but
  reports and scheduled jobs call `AiEngine.prompt()` directly with no concurrency control. A task,
  a report, and a scheduled job can all hit the AI engine simultaneously with nothing governing the
  total.

- **Duplicated abstractions.** Each AI provider module (claude-code, opencode, copilot) implements
  both an `Actor` and an `AiEngine`, with significant overlap. `ClaudeCodeActor` and
  `ClaudeCodeEngine` live in the same module and use the same subprocess machinery.

- **Duplicated result types.** `TaskResult` and `AiEngineResult` have nearly identical fields
  (output, cost, tokens, session ID, execution log, success/failure).

- **Duplicated config types.** `ActorContext` and `AiEngineConfig` have nearly identical fields
  (model, system prompt, tools, working directory, environment, MCP config, timeout, max steps,
  max budget).

- **Awkward HumanActor.** The `HumanActor` implements `Actor` but doesn't execute anything — it
  creates a pending future and waits for REST input. This will be replaced by human task nodes
  in the workflow engine.

- **Indirection layers.** The `AiEngineProvider` and `AiEngineProducer` exist solely to work around
  CDI ambiguity. The `AiEngine.getActorType()` bridge method exists solely to connect the two SPIs.

## Goals

1. Replace the Actor SPI and AiEngine SPI with a single **Agent SPI** that represents an AI
   provider capable of executing arbitrary prompts.
2. Introduce an **Agent Pool** that governs concurrency across all AI workloads (tasks, reports,
   scheduled jobs).
3. Route task execution, report generation, and AI-mode scheduled jobs through the same pool.
4. Remove the `HumanActor` — human-in-the-loop will be handled by workflow human task nodes and
   the existing task inbox (which doesn't need an Actor/Agent abstraction).
5. Eliminate the duplicated types (`TaskResult`/`AiEngineResult`, `ActorContext`/`AiEngineConfig`).
6. Simplify the module structure by collapsing `actors/` and `engine/` into `agents/`.

## Non-Goals

- Changing how the **Manager** evaluates events. The Manager makes quick, synchronous
  `promptWithSchema` calls that don't need pool management. It will call the Agent SPI directly.
- Changing how **script-mode** execution works. Script-based tasks and scheduled jobs run via
  `ProcessBuilder` and don't need an AI agent.
- Changing how **utility AI services** (`ScriptAiService`, `ActionTypeAiService`, `ToolAiService`,
  `AssistantSessionManager`) invoke AI. These make short, direct AI calls and will call the
  Agent SPI directly (not through the pool).
- Redesigning the event pipeline, report scheduling, or scheduled job scheduling. Only the AI
  execution path changes.

## Design Overview

The new architecture has three layers:

```
                  +-----------------------+
                  |      Agent Pool       |  <-- concurrency governance
                  |  (lease/release/busy) |
                  +-----------+-----------+
                              |
                  +-----------+-----------+
                  |      Agent SPI        |  <-- provider abstraction
                  |  (execute / cancel)   |
                  +-----------+-----------+
                              |
            +-----------------+------------------+
            |                 |                  |
   +--------+-------+ +------+--------+ +-------+--------+
   | ClaudeCodeAgent | | OpenCodeAgent | | CopilotAgent   |
   +----------------+ +---------------+ +----------------+
```

**Callers fall into two categories:**

- **Pool callers** — long-running AI work that needs resource governance:
  `TaskExecutionService`, `ReportExecutionService`, `ScheduledJobExecutionService`.
  These lease an agent from the pool, execute, and release.

- **Direct callers** — short AI calls that don't need pooling:
  `ManagerService`, `ScriptAiService`, `ActionTypeAiService`, `ToolAiService`,
  `AssistantSessionManager`, `StartupCheckService`.
  These call the Agent SPI directly via `AgentRegistry`.

## Agent SPI

### Agent Interface

Replaces both `Actor` and `AiEngine`:

```java
package io.apitomy.axiom.agents.spi;

public interface Agent {

    /** Provider identifier (e.g. "claude-code", "opencode", "copilot"). */
    String getType();

    /** Execute a prompt and return the result asynchronously. */
    CompletableFuture<AgentResult> execute(AgentRequest request);

    /** Execute a prompt with a JSON schema constraint for structured output. */
    CompletableFuture<AgentResult> executeWithSchema(AgentRequest request, String jsonSchema);

    /** Best-effort cancellation of a running execution. */
    void cancel(String executionId);

    /** Startup health checks. */
    List<AgentCheckResult> healthCheck();
}
```

### AgentRequest

Replaces both `ActorContext` and `AiEngineConfig`. This is the unified work description for any
AI invocation — task, report, scheduled job, or utility call.

```java
package io.apitomy.axiom.agents.spi;

public class AgentRequest {

    // What to do
    private String prompt;
    private String systemPrompt;

    // AI configuration
    private String model;
    private int timeoutSeconds;       // default 120
    private int maxSteps;             // default 50
    private Double maxBudgetUsd;

    // Tool configuration
    private List<String> allowedTools;
    private List<String> disallowedTools;
    private Path mcpConfigFile;

    // Execution environment
    private Path workingDirectory;
    private Map<String, String> environment;

    // Tracking
    private String executionId;       // for cancellation (caller-assigned)
    private String sessionId;         // for session resumption (engine-specific)

    // Builder pattern (same as existing AiEngineConfig)
}
```

Key difference from `ActorContext`: the prompt is part of the request, not external. Key difference
from `AiEngineConfig`: includes `executionId` for cancellation tracking.

### AgentResult

Replaces both `TaskResult` and `AiEngineResult`:

```java
package io.apitomy.axiom.agents.spi;

public record AgentResult(
    boolean success,
    String output,
    String errorMessage,
    String executionLog,
    String sessionId,
    Double costUsd,
    Long inputTokens,
    Long outputTokens
) {
    public static AgentResult success(String output) { ... }
    public static AgentResult failure(String errorMessage) { ... }
}
```

### AgentCheckResult

Replaces `AiEngineCheckResult` (identical shape):

```java
package io.apitomy.axiom.agents.spi;

public record AgentCheckResult(String name, String status, String message) {}
```

### AgentMcpManager

Replaces `AiEngineMcpManager` (identical shape):

```java
package io.apitomy.axiom.agents.spi;

public interface AgentMcpManager {
    Path configureMcpServers(Long workItemId, Map<String, String> environment,
                              List<String> allowedTools);
    default void cleanup(Long workItemId) {}
}
```

## Agent Registry

Replaces `AiEngineRegistry`, `AiEngineProducer`, `AiEngineProvider`, and CDI `Instance<Actor>`
discovery. Single class that provides access to agents by type.

```java
package io.apitomy.axiom.agents.spi;

@ApplicationScoped
public class AgentRegistry {

    @ConfigProperty(name = "axiom.agent.default-type", defaultValue = "claude-code")
    String defaultAgentType;

    // Discovers all Agent CDI beans at startup
    @Inject Instance<Agent> agents;

    /** Get agent by type, falling back to default. */
    public Agent getAgent(String type) { ... }

    /** Get the default agent. */
    public Agent getDefaultAgent() { ... }

    /** Get the MCP manager for a given agent type. */
    public AgentMcpManager getMcpManager(String agentType) { ... }

    /** List available agent type identifiers. */
    public List<String> getAvailableTypes() { ... }
}
```

This eliminates the `AiEngineProvider` indirection. Agent implementations are `@ApplicationScoped`
CDI beans directly. The registry iterates `Instance<Agent>` to find them by type — the same
pattern `TaskExecutionService` already uses for `Instance<Actor>`.

The config property name changes from `axiom.ai-engine` to `axiom.agent.default-type`.

## Agent Pool

New concept that manages concurrency across all AI workloads. The pool controls how many
AI agents can run simultaneously.

### AgentEntity (database)

Replaces `ActorEntity`. Represents a configured agent slot in the pool.

```java
package io.apitomy.axiom.core.entities;

@Entity
@Table(name = "agent")
public class AgentEntity extends PanacheEntity {
    public String name;            // display name
    public String description;
    public String agentType;       // AI provider: "claude-code", "opencode", "copilot"
    public String capabilities;    // comma-separated action types, or "*"
    public String configuration;   // provider-specific config overrides (JSON)
    public boolean enabled;        // whether this agent is available for work
}
```

Changes from `ActorEntity`:
- `type` (was `"ai-agent"` or `"human"`) becomes `agentType` (always an AI provider type).
- `permissions` field removed (unused).
- `enabled` field added.
- No more `"human"` type — human tasks are handled by the workflow engine / inbox.

### AgentPool

New service that manages agent leasing and concurrency:

```java
package io.apitomy.axiom.app;

@ApplicationScoped
public class AgentPool {

    @Inject AgentRegistry agentRegistry;

    /**
     * Leases an available agent for the given work.  Returns null if all
     * agents are busy.
     *
     * Resolution priority:
     * 1. Explicit agent assignment (if agentEntityId is provided)
     * 2. Capability match — first non-busy agent whose capabilities include
     *    the requested capability
     * 3. Fallback — first non-busy agent of any type
     *
     * @param capability     the action type / work type (nullable for reports/jobs)
     * @param agentEntityId  explicit agent assignment (nullable)
     * @param agentType      preferred provider type override (nullable)
     * @return a lease, or null if no agent is available
     */
    public AgentLease tryLease(String capability, Long agentEntityId, String agentType) { ... }

    /**
     * Releases a lease, making the agent available for new work.
     */
    public void release(AgentLease lease) { ... }

    /**
     * Checks whether a specific agent entity is currently busy.
     */
    public boolean isBusy(Long agentEntityId) { ... }
}
```

### AgentLease

Value object returned by `AgentPool.tryLease()`:

```java
package io.apitomy.axiom.app;

public record AgentLease(
    Long agentEntityId,       // the database agent record
    String agentEntityName,   // display name for logging
    Agent agent               // the resolved Agent SPI implementation
) {}
```

### Busy-State Tracking

The current system tracks busy state by querying `TaskEntity` for in-progress tasks assigned
to an actor. With the unified pool, we need to track busy state across work types.

Approach: add a lightweight `agent_lease` table (or use an in-memory `ConcurrentHashMap`
with startup recovery). The table tracks which agent entity is currently executing what:

```sql
CREATE TABLE agent_lease (
    id          BIGINT PRIMARY KEY,
    agent_id    BIGINT NOT NULL REFERENCES agent(id),
    work_type   VARCHAR(32) NOT NULL,   -- 'task', 'report', 'scheduled-job'
    work_id     BIGINT NOT NULL,        -- task ID, report ID, or run ID
    leased_at   TIMESTAMP NOT NULL
);
```

On startup, orphaned leases (from a crash) are cleaned up. On work completion, the lease is
deleted. `isBusy()` checks for an existing lease row.

Alternative: keep the existing pattern of querying entity status columns (`TaskEntity.status`,
`ReportEntity.status`, `ScheduledJobRunEntity.status`). This avoids a new table but couples
the pool to the entity schemas. The `agent_lease` table is cleaner and decoupled.

## Caller Migration

### TaskExecutionService

Currently:
1. Resolves `ActorEntity` (capability match + busy check)
2. Maps to `Actor` SPI bean via `AiEngineRegistry.getActorType()`
3. Builds `ActorContext`
4. Calls `actor.execute(task, context)`

After migration:
1. Calls `agentPool.tryLease(task.actionType, task.assignedAgent, engineType)`
2. If no lease available, task stays pending
3. Builds `AgentRequest` (prompt from task + action type template, system prompt, tools, etc.)
4. Calls `lease.agent().execute(request)`
5. On completion, calls `agentPool.release(lease)` and processes result

The task lifecycle management (status updates, activity logging, tracing, SSE events) stays
in `TaskExecutionService`. Only the AI invocation path changes.

### ReportExecutionService

Currently:
1. `ReportQueueConsumer` (single daemon thread) picks up pending reports
2. `ReportExecutionService.generateReport()` calls `aiEngine.prompt()` directly
3. Consumer blocks on `waitForCompletion()` polling DB every 5s

After migration:
1. `ReportQueueConsumer` picks up pending reports (same)
2. Calls `agentPool.tryLease(null, null, null)` — reports have no capability/assignment
3. If no lease available, report is re-enqueued (back of queue)
4. Builds `AgentRequest` with the report prompt, system prompt, tools, etc.
5. Calls `lease.agent().execute(request)`
6. On completion callback, calls `agentPool.release(lease)` and processes result
7. No more blocking `waitForCompletion()` — the `CompletableFuture` callback handles it

### ScheduledJobExecutionService

Currently:
1. `ScheduledJobQueueConsumer` (single daemon thread) picks up pending runs
2. For actor mode: calls `aiEngine.prompt()` directly, blocks on `waitForCompletion()`
3. For script mode: runs via `ProcessBuilder`

After migration:
1. Queue consumer picks up pending runs (same)
2. For AI mode: calls `agentPool.tryLease(null, null, null)`
3. If no lease available, run is re-enqueued
4. Builds `AgentRequest` with the job prompt, system prompt, tools, etc.
5. Calls `lease.agent().execute(request)`
6. On completion callback, releases lease and processes result
7. For script mode: unchanged (no agent needed)

### ManagerService (no change to pooling)

The Manager makes quick, synchronous `promptWithSchema` calls. It will switch from:
```java
@Inject AiEngine aiEngine;
...
aiEngine.promptWithSchema(config, prompt, schema).join();
```
to:
```java
@Inject AgentRegistry agentRegistry;
...
agentRegistry.getDefaultAgent().executeWithSchema(request, schema).join();
```

No pool involvement. The `AiEngineConfig` ➝ `AgentRequest` mapping is straightforward.

### Utility AI Services (no change to pooling)

`ScriptAiService`, `ActionTypeAiService`, `ToolAiService`, `AssistantSessionManager`:
same pattern as the Manager — switch from `AiEngine` to `AgentRegistry.getDefaultAgent()`.

## Agent Implementations

### ClaudeCodeAgent

Replaces both `ClaudeCodeActor` and `ClaudeCodeEngine`. Lives in a single module.

```java
package io.apitomy.axiom.agents.claudecode;

@ApplicationScoped
public class ClaudeCodeAgent implements Agent {
    public String getType() { return "claude-code"; }
    // Uses ClaudeCodeSubprocess (existing) for both execute() and executeWithSchema()
    // Merges the logic from ClaudeCodeActor.execute() and ClaudeCodeEngine.prompt()
}
```

Supporting classes (`ClaudeCodeCommandBuilder`, `ClaudeCodeSubprocess`, `ClaudeCodeResult`,
`ExecutionLogBuilder`, `ClaudeCodeMcpManager`) stay largely unchanged — they already do the
real work.

### OpenCodeAgent

Replaces both `OpenCodeActor` and `OpenCodeEngine`. Currently split across two modules
(`actors/` doesn't have opencode, but `engine/opencode/` has both `OpenCodeEngine` and
`OpenCodeActor`).

```java
package io.apitomy.axiom.agents.opencode;

@ApplicationScoped
public class OpenCodeAgent implements Agent {
    public String getType() { return "opencode"; }
    // Delegates to OpenCodeClient (existing HTTP client)
}
```

### CopilotAgent

Replaces both `CopilotActor` and `CopilotEngine` from `engine/copilot/`.

```java
package io.apitomy.axiom.agents.copilot;

@ApplicationScoped
public class CopilotAgent implements Agent {
    public String getType() { return "copilot"; }
    // Uses CopilotSubprocess (existing)
}
```

## Module Structure Changes

### Before

```
actors/
  spi/           → Actor, ActorContext, TaskResult
  claude-code/   → ClaudeCodeActor, ClaudeCodeEngine, ClaudeCodeMcpManager, ...
  human/         → HumanActor
engine/
  spi/           → AiEngine, AiEngineConfig, AiEngineResult, AiEngineRegistry, ...
  opencode/      → OpenCodeActor, OpenCodeEngine, OpenCodeMcpManager, ...
  copilot/       → CopilotActor, CopilotEngine, CopilotMcpManager, ...
```

### After

```
agents/
  spi/           → Agent, AgentRequest, AgentResult, AgentRegistry, AgentMcpManager, ...
  claude-code/   → ClaudeCodeAgent, ClaudeCodeMcpManager, ...
  opencode/      → OpenCodeAgent, OpenCodeMcpManager, ...
  copilot/       → CopilotAgent, CopilotMcpManager, ...
```

The `actors/` and `engine/` top-level directories are removed. The `actors/human/` module is
removed entirely. All provider modules consolidate into `agents/`.

The Java package changes from `io.apitomy.axiom.actors.*` and `io.apitomy.axiom.engine.*`
to `io.apitomy.axiom.agents.*`.

## Database Migration

### Rename `actor` table to `agent`

```sql
ALTER TABLE actor RENAME TO agent;
ALTER TABLE agent RENAME COLUMN type TO agent_type;
ALTER TABLE agent DROP COLUMN permissions;
ALTER TABLE agent ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- Remove human actor records
DELETE FROM agent WHERE agent_type = 'human';

-- Update type values: "ai-agent" -> actual provider type
-- (requires knowing which engine is configured; may need a data migration script)
UPDATE agent SET agent_type = 'claude-code' WHERE agent_type = 'ai-agent';
```

### Create `agent_lease` table

```sql
CREATE TABLE agent_lease (
    id          BIGINT PRIMARY KEY,
    agent_id    BIGINT NOT NULL,
    work_type   VARCHAR(32) NOT NULL,
    work_id     BIGINT NOT NULL,
    leased_at   TIMESTAMP NOT NULL,
    CONSTRAINT fk_agent_lease_agent FOREIGN KEY (agent_id) REFERENCES agent(id)
);
```

### Update `task` table foreign key

```sql
ALTER TABLE task RENAME COLUMN assigned_actor TO assigned_agent;
```

### Update `ai_usage` table

```sql
ALTER TABLE ai_usage RENAME COLUMN actor_id TO agent_id;
```

## REST API Changes

### Rename Actors endpoints to Agents

| Before | After |
|--------|-------|
| `GET /api/v1/actors` | `GET /api/v1/agents` |
| `POST /api/v1/actors` | `POST /api/v1/agents` |
| `GET /api/v1/actors/{id}` | `GET /api/v1/agents/{id}` |
| `PUT /api/v1/actors/{id}` | `PUT /api/v1/agents/{id}` |
| `DELETE /api/v1/actors/{id}` | `DELETE /api/v1/agents/{id}` |

The request/response beans change accordingly (`ActorBean` ➝ `AgentBean`). The `type` field
changes from `"ai-agent"` / `"human"` to the provider type (`"claude-code"`, `"opencode"`,
`"copilot"`). The `permissions` field is removed. The `enabled` field is added.

### Update OpenAPI spec

Update `common/api/src/main/resources/openapi.json`:
- Rename `Actor` schema to `Agent`, update fields
- Rename `Actors` tag to `Agents`
- Update all `/actors` paths to `/agents`
- Update any references in other schemas (e.g., task beans referencing actor ID/name)

## Configuration Changes

| Before | After |
|--------|-------|
| `axiom.ai-engine` | `axiom.agent.default-type` |
| `axiom.claude-code.model` | `axiom.agent.claude-code.model` |
| `axiom.claude-code.max-turns` | `axiom.agent.claude-code.max-steps` |
| `axiom.claude-code.max-budget-usd` | `axiom.agent.claude-code.max-budget-usd` |
| `axiom.claude-code.timeout-seconds` | `axiom.agent.claude-code.timeout-seconds` |
| `axiom.copilot.*` | `axiom.agent.copilot.*` |
| `axiom.opencode.*` | `axiom.agent.opencode.*` |

## UI Changes

- Rename "Actors" page/section to "Agents" throughout the UI
- Update the actor management page to reflect the new schema (remove type selector showing
  "AI Agent" / "Human", replace with provider type selector; remove permissions field;
  add enabled toggle)
- Update any references to "actor" in task detail views, activity logs, etc.

## Migration Strategy

This is a breaking change to internal SPIs, database schema, and REST API. Recommended approach:

1. **Create the new `agents/` modules** with the new SPI interfaces and implementations.
   Initially, have them delegate to the existing Actor/AiEngine implementations to validate
   the interface design.
2. **Migrate callers one at a time** — start with the simplest (utility services), then
   reports, then scheduled jobs, then tasks.
3. **Introduce the AgentPool** once all three pool callers are migrated.
4. **Database migration** — single Flyway migration that renames tables/columns and migrates data.
5. **Remove old modules** — delete `actors/`, `engine/` directories and all old SPI types.
6. **Update OpenAPI spec and UI** — rename actors to agents in the API and frontend.

Since this is an internal application (not a library with external consumers), the migration can
be done in a single release without backwards compatibility concerns.

## Future: Workflow Integration

The upcoming workflow engine (apitomy-flow) will need to execute action nodes. When a workflow
reaches an `ACTION` node, the `NodeExecutor` implementation for that action type will:

1. Build an `AgentRequest` from the action node's configuration
2. Call `agentPool.tryLease(...)` to get an agent
3. Call `agent.execute(request)` and await the result
4. Release the lease and return the `NodeResult` to the workflow engine

This means workflow action nodes automatically participate in the same agent pool as tasks,
reports, and scheduled jobs — unified concurrency governance with zero additional infrastructure.

Human task nodes in workflows go directly to the human task inbox. They don't need an Agent.
