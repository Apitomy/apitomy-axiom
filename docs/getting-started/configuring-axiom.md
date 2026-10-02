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
| `axiom.manager.confidence-threshold` | `AXIOM_MANAGER_CONFIDENCE_THRESHOLD` | `-Daxiom.manager.confidence-threshold=...` |

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

### AI Agents

Axiom supports multiple pluggable AI agents (Claude Code, OpenCode, and GitHub Copilot
CLI). Set the default agent used for tasks, reports, and agent-mode scheduled jobs
(unless overridden per-item) with:

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.agent.default-type` | `claude-code` | Default agent type (`claude-code`, `opencode`, or `copilot`) |

#### Claude Code Settings

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.agent.claude-code.executable` | `claude` | Path to the Claude CLI binary |
| `axiom.agent.claude-code.model` | *(agent default)* | AI model override |
| `axiom.agent.claude-code.max-steps` | `50` | Maximum agentic steps per task |
| `axiom.agent.claude-code.max-budget-usd` | `5.0` | Maximum cost per task in USD |
| `axiom.agent.claude-code.timeout-seconds` | `600` | Subprocess timeout |
| `axiom.agent.claude-code.available-models` | *(empty)* | Comma-separated model list for the UI model picker |

#### OpenCode Settings

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.agent.opencode.executable` | `opencode` | Path to the OpenCode CLI binary |
| `axiom.agent.opencode.server.hostname` | `127.0.0.1` | OpenCode server hostname |
| `axiom.agent.opencode.server.port` | `4096` | OpenCode server port |
| `axiom.agent.opencode.model` | *(agent default)* | Model in `provider/model` format |
| `axiom.agent.opencode.max-steps` | `50` | Maximum agent steps per task |
| `axiom.agent.opencode.timeout-seconds` | `600` | HTTP request timeout |
| `axiom.agent.opencode.available-models` | *(empty)* | Comma-separated model list for the UI model picker |
| `axiom.agent.opencode.model-discovery.enabled` | `true` | Enable dynamic model discovery from the OpenCode server |
| `axiom.agent.opencode.model-discovery.timeout-seconds` | `8` | Timeout for a model discovery request |
| `axiom.agent.opencode.model-discovery.cache-seconds` | `86400` | How long discovered models are cached |

#### GitHub Copilot CLI Settings

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.agent.copilot.executable` | `copilot` | Path to the Copilot CLI binary |
| `axiom.agent.copilot.model` | *(agent default)* | AI model override |
| `axiom.agent.copilot.timeout-seconds` | `600` | Subprocess timeout |
| `axiom.agent.copilot.available-models` | *(empty)* | Comma-separated model list for the UI model picker |

### Manager

The AI Manager triages incoming stream events and decides what actions to take.

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.manager.confidence-threshold` | `0.7` | Minimum confidence for auto-execution (0.0–1.0) |
| `axiom.manager.timeout-seconds` | `120` | Manager subprocess timeout |
| `axiom.manager.max-turns` | `5` | Maximum agentic turns for the Manager |
| `axiom.manager.engine` | *(global default)* | Agent type override for the Manager |
| `axiom.manager.model` | *(agent default)* | Model override for the Manager |

Decisions below the confidence threshold are escalated to the Inbox for human review
rather than executed automatically.

### Event Stream Pipeline

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.stream-pipeline.poll-interval` | `5s` | How often the event stream orchestrator evaluates unprocessed (event, subscription) pairs |
| `axiom.stream-pipeline.max-attempts` | `3` | Maximum routing attempts (first try plus retries) for an (event, subscription) pair; when the last allowed attempt fails the ledger entry becomes `exhausted` and is no longer retried automatically (it can be retried manually, which allows one more attempt). Each Manager retry is a full AI call, so a higher value multiplies the cost of a failing Manager |
| `axiom.stream-pipeline.retry-initial-delay` | `30s` | Delay before the first retry of a failed entry; each later retry doubles it (exponential backoff). Quarkus duration format (`30s`, `5m`, `PT30S`) |
| `axiom.stream-pipeline.retry-max-delay` | `1h` | Upper bound of the delay between two attempts |

Startup fails with an error naming the property if `max-attempts` is less than 1, `retry-initial-delay` is
not greater than zero, or `retry-max-delay` is less than `retry-initial-delay` (values are not clamped).

**Upgrading:** the V69 migration cannot read the configuration, so it assumes the old default cap of 3:
ledger entries that had already failed 3 or more times become `exhausted`, even if `max-attempts` is
higher. They can be retried manually from the event detail page (one more attempt each) or with
`POST /api/v1/stream/events/{eventId}/processing/{ledgerId}/retry`.

### Scheduled Jobs

Scheduled Jobs run on a configurable CRON-style schedule.

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.scheduled-jobs.poll-interval` | `60s` | How often to check for due scheduled jobs |

### AI Assistant

The interactive AI Assistant allows users to create and update configuration items through
conversation.

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.assistant.max-sessions` | `3` | Maximum concurrent assistant sessions |
| `axiom.assistant.opencode.executable` | `opencode` | OpenCode CLI executable used for interactive sessions |
| `axiom.assistant.opencode.startup-timeout-seconds` | `30` | Per-session server startup timeout |

Session availability also depends on which registered agents support interactive
sessions — see [AI Assistant](../user-guide/ai-assistant.md).

### Workspaces

| Property | Default | Description |
|----------|---------|--------------|
| `axiom.workspace.root` | `~/.axiom/workspaces` | Root directory for project git clones |

## Data Retention

Retention periods for closed projects, execution traces, and stream events are stored
in the database (not as static properties) and can be changed at any time from
**Settings > Data Retention** in the UI. Defaults are:

| Setting | Default | What it controls |
|---------|---------|-------------------|
| Closed projects | 90 days | How long a `Completed` project (and its tasks, events, and thread) is kept before automatic deletion |
| Traces | 30 days | How long execution traces (and their nodes and tool execution records) are kept |
| Events | 90 days | How long stream events and their subscription processing ledger entries are kept |

Cleanup runs hourly in the background. Connection poll logs are always retained for a
fixed 3 days and are not configurable.

## Database Profiles

Axiom uses H2 with different profiles for different environments:

| Profile | Database | Usage |
|---------|----------|-------|
| `dev` (default) | H2 in-memory | Development — schema recreated on restart |
| `persist` | H2 file (`~/.axiom/data/axiom`) | Persistent dev — Flyway migrations |
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
