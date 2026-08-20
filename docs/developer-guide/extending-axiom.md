# Extending Axiom

Axiom is designed to be extended at several key points: AI engines, actors, event
sources, and notification channels. All extension points follow the same pattern —
define an interface in an SPI module, implement it in a sibling module, and let CDI
discover it at runtime.

---

## The SPI Pattern

Every extension point in Axiom follows this structure:

```
component/
├── spi/          Interface definition + registry
└── impl-name/    Concrete implementation
```

Implementations are `@ApplicationScoped` CDI beans. A registry or CDI `Instance<T>`
discovers all implementations at startup. The active implementation is selected by
configuration.

---

## Adding an AI Engine

An AI engine provides the ability to invoke an LLM for manager evaluations and task
execution. Axiom ships with three engines: Claude Code, OpenCode, and GitHub Copilot CLI.

### Interface: `AiEngine`

Location: `engine/spi/src/main/java/io/apitomy/axiom/engine/spi/AiEngine.java`

| Method | Purpose |
|--------|---------|
| `String getType()` | Engine identifier (e.g. `"claude-code"`, `"opencode"`, `"copilot"`) |
| `CompletableFuture<AiEngineResult> prompt(AiEngineConfig config, String prompt)` | Invoke the AI with a text prompt |
| `CompletableFuture<AiEngineResult> promptWithSchema(AiEngineConfig config, String prompt, String jsonSchema)` | Invoke with structured output (JSON schema constraint) |
| `List<AiEngineCheckResult> healthCheck()` | Startup health checks |

### Registration: `AiEngineProvider`

Rather than registering `AiEngine` directly as a CDI bean (which can cause type
conflicts), engines implement `AiEngineProvider`:

```java
@ApplicationScoped
@Typed({MyEngine.class, AiEngineProvider.class})
public class MyEngine implements AiEngine, AiEngineProvider {

    @Override
    public String getType() {
        return "my-engine";
    }

    @Override
    public AiEngine getEngine() {
        return this;
    }

    // ... implement prompt(), promptWithSchema(), healthCheck()
}
```

The `@Typed` annotation limits CDI visibility to avoid conflicts with other engine
implementations.

### Discovery

`AiEngineRegistry` discovers all `AiEngineProvider` beans via CDI
`Instance<AiEngineProvider>`. The active engine is selected by the
`axiom.ai-engine` configuration property (default: `claude-code`).

### Configuration Objects

- **`AiEngineConfig`** — builder-based configuration passed to every engine call:
  model, system prompt, allowed tools, working directory, environment variables,
  timeout, max steps, budget, MCP config file
- **`AiEngineResult`** — returned from engine calls: result text, session ID, cost,
  tokens, success flag, execution log

### MCP Support (Optional)

If your engine supports MCP tool servers, implement `AiEngineMcpManager`:

| Method | Purpose |
|--------|---------|
| `Path configureMcpServers(Long taskId, Map<String, String> environment, List<String> allowedTools)` | Generate MCP config file; return path or null |
| `void cleanup(Long taskId)` | Clean up after task completion |

Register it via `AiEngineProvider.getMcpManager()`.

### Reference Implementations

- **Claude Code**: `actors/claude-code/src/main/java/.../ClaudeCodeEngine.java` —
  subprocess-based, launches `claude` CLI
- **OpenCode**: `engine/opencode/src/main/java/.../OpenCodeEngine.java` —
  HTTP client against a running OpenCode server
- **GitHub Copilot CLI**: `engine/copilot/src/main/java/.../CopilotEngine.java` —
  subprocess-based, launches `copilot` CLI

---

## Adding an Actor

An actor executes tasks — typically by invoking an AI engine, but it can also send
notifications or perform any other work.

### Interface: `Actor`

Location: `actors/spi/src/main/java/io/apitomy/axiom/actors/spi/Actor.java`

| Method | Purpose |
|--------|---------|
| `String getType()` | Actor identifier (e.g. `"claude-code"`, `"human"`) |
| `CompletableFuture<TaskResult> execute(TaskEntity task, ActorContext context)` | Execute a task asynchronously |
| `void cancel(TaskEntity task)` | Cancel a running task |

### Context and Results

**`ActorContext`** — provided to the actor for each task:

- `workingDirectory` — the project's workspace directory (`~/.axiom/workspaces/project-{id}`)
- `allowedTools` / `disallowedTools` — tool access control
- `systemPrompt` — system-level instructions
- `promptTemplate` — the action type's prompt (with placeholders substituted)
- `mcpConfigFile` — path to MCP configuration
- `environment` — environment variables (including decrypted secrets)
- `model` — model override (if set on the action type)

**`TaskResult`** — returned after execution:

- `success` — whether the task completed successfully
- `output` — result text
- `errorMessage` — failure reason (if failed)
- `costUsd` / `inputTokens` / `outputTokens` — usage tracking
- `executionLog` — full transcript for debugging
- `sessionId` — for engine session resumption

### Discovery

`TaskExecutionService` discovers actors via CDI `Instance<Actor>`. The actor type
is resolved from the engine type via `AiEngineRegistry.getActorType()`.

### Implementation Example

```java
@ApplicationScoped
public class MyActor implements Actor {

    @Override
    public String getType() {
        return "my-engine";
    }

    @Override
    public CompletableFuture<TaskResult> execute(TaskEntity task, ActorContext context) {
        return CompletableFuture.supplyAsync(() -> {
            // Use context.promptTemplate(), context.workingDirectory(), etc.
            // Invoke your engine or perform work
            return TaskResult.success("Task completed successfully")
                    .costUsd(0.05)
                    .inputTokens(1000L)
                    .outputTokens(500L)
                    .executionLog("Full transcript here")
                    .build();
        });
    }

    @Override
    public void cancel(TaskEntity task) {
        // Terminate the running process/session
    }
}
```

### Reference Implementations

- **Claude Code**: `actors/claude-code/src/main/java/.../ClaudeCodeActor.java` —
  launches `claude` CLI as a subprocess
- **OpenCode**: `engine/opencode/src/main/java/.../OpenCodeActor.java` — invokes
  OpenCode server via HTTP
- **GitHub Copilot CLI**: `engine/copilot/src/main/java/.../CopilotActor.java` —
  launches `copilot` CLI as a subprocess
- **Human**: `actors/human/` — sends notifications, waits for human response

---

## Adding an Event Source

An event source polls an external system for activity and creates normalized events.

### Pattern

Event sources are `@Scheduled` pollers that use `EventService` to ingest events.
There is no formal SPI interface — the pattern is convention-based.

### Key Service: `EventService`

Location: `events/core/src/main/java/io/apitomy/axiom/events/core/EventService.java`

| Method | Purpose |
|--------|---------|
| `ingestEvent(Long eventSourceId, String source, String eventType, String issueRef, String repository, String payload)` | Create an event and enqueue it for processing |
| `recordPollLog(Long eventSourceId, String status, String message, String detail, int eventsIngested)` | Log the result of a poll cycle |

### Implementation Pattern

1. Create a scheduled poller with `@Scheduled` and `concurrentExecution = SKIP`
2. Find enabled event sources of your type from `EventSourceEntity`
3. Check if the poll interval has elapsed since `lastPolledAt`
4. Fetch activity from the external API
5. Normalize events and call `eventService.ingestEvent()` for each
6. Update `lastPolledAt` on the event source
7. Call `eventService.recordPollLog()` with the result

### Event Type Conventions

Events use a normalized type string. Existing conventions:

| Event Type | Meaning |
|------------|---------|
| `issue-created` | A new issue was opened |
| `issue-updated` | An issue was modified |
| `issue-closed` | An issue was closed |
| `issue-reopened` | A closed issue was reopened |
| `comment-added` | A comment was added to an issue |
| `pr-opened` | A pull request was opened |
| `pr-merged` | A pull request was merged |
| `pr-closed` | A pull request was closed without merging |
| `review-comment-added` | A review comment was added to a PR |

### Reference Implementations

- **GitHub**: `events/github/src/main/java/.../GitHubPoller.java` — polls GitHub
  REST API for issues, comments, PRs, and review comments
- **Jira**: `events/jira/src/main/java/.../JiraPoller.java` — polls Jira REST API
  using JQL search

---

## Adding a Notification Channel

Notification channels deliver alerts to humans (e.g. when a task is assigned to a
human actor).

### Current State

The notification SPI (`notifications/spi/`) is currently a stub — no formal interface
is defined yet. Slack and Telegram implementations exist but follow their own patterns.

### Reference Implementations

- **Slack**: `notifications/slack/`
- **Telegram**: `notifications/telegram/`

---

## Module Setup

When adding a new extension module:

1. **Create the Maven module** directory under the appropriate parent
   (e.g. `engine/my-engine/` or `events/my-source/`)

2. **Create `pom.xml`** with the parent reference and dependencies:

    ```xml
    <parent>
        <groupId>io.apitomy</groupId>
        <artifactId>apitomy-axiom</artifactId>
        <version>${revision}</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>apitomy-axiom-engine-my-engine</artifactId>
    ```

3. **Add to root `pom.xml`** modules list:

    ```xml
    <module>engine/my-engine</module>
    ```

4. **Add Jandex plugin** to your module's `pom.xml` — required for CDI bean discovery
   in Quarkus:

    ```xml
    <plugin>
        <groupId>io.smallrye</groupId>
        <artifactId>jandex-maven-plugin</artifactId>
    </plugin>
    ```

5. **Add as dependency** in `app/pom.xml` so the app module includes your implementation

6. **Add to `dependencyManagement`** in the root `pom.xml` for version consistency
