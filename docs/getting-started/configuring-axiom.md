# Configuring Axiom

Axiom is configured through environment variables or Java system properties.

## Application Properties

Application properties should be set using **environment variables** or **Java system
properties** (`-D` flags), not by editing `application.properties` directly.

Axiom uses the standard
[Quarkus configuration](https://quarkus.io/guides/config-reference) conventions. To
convert a property name to an environment variable: replace dots and hyphens with
underscores and uppercase everything. For example:

| Property | Environment Variable | Java System Property |
|----------|---------------------|---------------------|
| `axiom.agent.default-type` | `AXIOM_AGENT_DEFAULT_TYPE` | `-Daxiom.agent.default-type=...` |
| `axiom.agent.claude-code.max-steps` | `AXIOM_AGENT_CLAUDE_CODE_MAX_STEPS` | `-Daxiom.agent.claude-code.max-steps=...` |
| `axiom.workflow.schedule-poll-interval` | `AXIOM_WORKFLOW_SCHEDULE_POLL_INTERVAL` | `-Daxiom.workflow.schedule-poll-interval=...` |

For example, to run Axiom with OpenCode as the default agent and a custom timeout:

```bash
export AXIOM_AGENT_DEFAULT_TYPE=opencode
export AXIOM_AGENT_OPENCODE_TIMEOUT_SECONDS=300
java -jar apitomy-axiom-3.0.0.jar
```

Or using system properties:

```bash
java -Daxiom.agent.default-type=opencode -Daxiom.agent.opencode.timeout-seconds=300 -jar apitomy-axiom-3.0.0.jar
```

See the [Quarkus Configuration Reference](https://quarkus.io/guides/config-reference) for
full details on property sources and precedence.

The active default agent can also be changed at runtime from **Settings > AI Engine** in
the UI, without restarting the server. The UI setting takes precedence over
`axiom.agent.default-type` once it has been set.

### Server

| Property | Default | Description |
|----------|---------|--------------|
| `quarkus.http.port` | `9090` (`9191` in the `prod` profile) | HTTP port of the UI and REST API (`/api/v1`) |
| `quarkus.http.host` | `127.0.0.1` in the `prod` profile | Address to listen on. Axiom has no authentication, so it accepts local connections only by default; set `0.0.0.0` to listen on all interfaces and protect access yourself |

### AI Agents

Axiom supports multiple pluggable AI agents (Claude Code, OpenCode, and GitHub Copilot
CLI). Set the default agent used for tasks
(unless overridden per action type) with:

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.home` | `~/.axiom` | Home for the database (`data/`), assistant sessions, task files, MCP servers, workspaces and the encryption key. Override with `-Daxiom.home=...` or the `AXIOM_HOME` environment variable |
| `axiom.agent.default-type` | `claude-code` | Default agent type (`claude-code`, `opencode`, or `copilot`) |

#### Claude Code Settings

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.agent.claude-code.executable` | `claude` | Path to the Claude CLI binary |
| `axiom.agent.claude-code.model` | *(agent default)* | AI model override |
| `axiom.agent.claude-code.max-steps` | `50` | Maximum agentic turns per task (passed as `--max-turns`) |
| `axiom.agent.claude-code.max-budget-usd` | `5.0` | Maximum cost per task in USD |
| `axiom.agent.claude-code.timeout-seconds` | `600` | Subprocess timeout |
| `axiom.agent.claude-code.available-models` | *(see `application.properties`)* | Comma-separated model list for the UI model picker |

#### OpenCode Settings

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.agent.opencode.executable` | `opencode` | Path to the OpenCode CLI binary |
| `axiom.agent.opencode.server.hostname` | `127.0.0.1` | OpenCode server hostname |
| `axiom.agent.opencode.server.port` | `4096` | OpenCode server port |
| `axiom.agent.opencode.model` | *(see `application.properties`)* | Model in `provider/model` format |
| `axiom.agent.opencode.max-steps` | `50` | Maximum agent steps per task |
| `axiom.agent.opencode.timeout-seconds` | `600` | HTTP request timeout |
| `axiom.agent.opencode.available-models` | *(see `application.properties`)* | Comma-separated model list for the UI model picker |
| `axiom.agent.opencode.model-discovery.enabled` | `true` | Enable dynamic model discovery from the OpenCode server |
| `axiom.agent.opencode.model-discovery.timeout-seconds` | `8` | Timeout for a model discovery request |
| `axiom.agent.opencode.model-discovery.cache-seconds` | `86400` | How long discovered models are cached |

#### GitHub Copilot CLI Settings

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.agent.copilot.executable` | `copilot` | Path to the Copilot CLI binary |
| `axiom.agent.copilot.model` | *(agent default)* | AI model override |
| `axiom.agent.copilot.timeout-seconds` | `600` | Subprocess timeout |
| `axiom.agent.copilot.available-models` | *(see `application.properties`)* | Comma-separated model list for the UI model picker |

### Event Stream Pipeline

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.stream-pipeline.poll-interval` | `5s` | How often the event stream orchestrator evaluates unprocessed (event, subscription) pairs |
| `axiom.stream-pipeline.max-attempts` | `3` | Maximum routing attempts (first try plus retries) for an (event, subscription) pair; when the last allowed attempt fails the ledger entry becomes `exhausted` and is no longer retried automatically (it can be retried manually, which allows one more attempt). |
| `axiom.stream-pipeline.retry-initial-delay` | `30s` | Delay before the first retry of a failed entry; each later retry doubles it (exponential backoff). Quarkus duration format (`30s`, `5m`, `PT30S`) |
| `axiom.stream-pipeline.retry-max-delay` | `1h` | Upper bound of the delay between two attempts |

Startup fails with an error naming the property if `max-attempts` is less than 1, `retry-initial-delay` is
not greater than zero, or `retry-max-delay` is less than `retry-initial-delay` (values are not clamped).

### Workflows

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.workflow.schedule-poll-interval` | `30s` | How often to check for due workflow schedule triggers. Schedule triggers fire on a single node only; running several Axiom nodes against one database is not supported for schedule triggers. |
| `axiom.workflow.wait-poll-interval` | `10s` | How often to resume workflow runs whose `wait` node has elapsed |

### Scripts

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.script.timeout-seconds` | `60` | Maximum run time, in seconds, of a Script or Node.js action type; output gets a `[Script timed out after Ns]` note (Script) or a `[Node action timed out after Ns]` note (Node.js) when it is exceeded. Tools are not affected: they time out after a fixed 30 seconds. |

### AI Assistant

The interactive AI Assistant allows users to create and update configuration items through
conversation.

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.assistant.max-sessions` | `12` | Maximum concurrent assistant sessions |
| `axiom.assistant.opencode.executable` | `opencode` | OpenCode CLI executable used for interactive sessions |
| `axiom.assistant.opencode.startup-timeout-seconds` | `30` | Per-session server startup timeout |
| `axiom.assistant.opencode.server.port` | *(unset)* | Port for each interactive OpenCode server; when unset, port `0` is used |

### Workflow Workbench

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.workbench.interrupt-timeout-seconds` | `30` | When the user stops an AI turn in the workbench and the session has not ended it after this many seconds, the turn is finished as failed |

Session availability also depends on which registered agents support interactive
sessions — see [AI Assistant](../user-guide/ai-assistant.md).

### Workspaces

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.workspace.root` | `~/.axiom/workspaces` | Root directory for subject workspaces (one `subject-<id>` directory per subject) |

## Data Retention

Retention periods are stored in the database (not as static properties) and can be changed at any time from
**Settings > Data Retention** in the UI or with `PUT /api/v1/system/retention`. Defaults are:

| Setting | Default | What it controls |
|---------|---------|-------------------|
| Inactive subjects | 90 days | How long an inactive subject (no running or waiting run) is kept after its most recent run finished, before it is deleted with its workspace, tasks, thread, workflow runs, activity and AI usage. Subjects that have never had a run are not deleted automatically. Traces and event routing outcomes of the subject are kept. |
| Traces | 30 days | How long execution traces (and their nodes and tool execution records) are kept |
| Events | 90 days | How long stream events, their subscription processing ledger entries and routing outcomes are kept. Events that a running or waiting workflow run depends on are kept until the run finishes. |
| Workflow runs | 0 (forever) | How long finished workflow runs are kept, measured from when they finished. Running and waiting runs are never deleted. |
| Activity log | 0 (forever) | How long activity log entries are kept |
| AI usage | 0 (forever) | How long AI usage (cost) records are kept |

Inactive subjects, traces and events must be kept for at least 1 day. For the history settings, `0` means
"keep forever". They default to `0` so that upgrading Axiom never deletes
data you did not ask it to delete.

Cleanup runs hourly in the background. Event source poll logs are always retained for a
fixed 3 days and are not configurable.

## Database Profiles

Axiom uses H2 with different profiles for different environments:

| Profile | Database | Usage |
|---------|----------|-------|
| `dev` (default) | H2 in-memory | Development — schema recreated on restart |
| `persist` | H2 file (`<axiom.home>/data/axiom`, default `~/.axiom/data/axiom`) | Persistent dev — Flyway migrations |
| `prod` | H2 file | Production — release JAR with Flyway |

When running from the release JAR, the `prod` profile is active automatically. For
development with persistent data, activate the `persist` profile:

```bash
mvn quarkus:dev -Dquarkus.profile=persist
```

Database schema migrations are managed by Flyway and run automatically on startup when
using the `persist` or `prod` profiles.

## Secrets and Encryption

Secret values (API tokens, credentials) are encrypted at rest using a key that Axiom
generates automatically on first startup and stores at `~/.axiom/secret.key`. This key
is separate from the database — see
[Upgrading and Backups](../developer-guide/upgrading-and-backups.md) for what to back up
and restore together.

Axiom loads the key when it starts. If the key file cannot be read or created, or is not a valid
256-bit key (for example, truncated), Axiom logs the problem and exits instead of starting. Restore the
key from a backup; deleting it makes Axiom generate a new key, and secrets encrypted with the old key can
no longer be decrypted.
