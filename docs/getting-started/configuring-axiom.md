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
| `axiom.ai-engine` | `AXIOM_AI_ENGINE` | `-Daxiom.ai-engine=...` |
| `axiom.claude-code.max-turns` | `AXIOM_CLAUDE_CODE_MAX_TURNS` | `-Daxiom.claude-code.max-turns=...` |
| `axiom.manager.confidence-threshold` | `AXIOM_MANAGER_CONFIDENCE_THRESHOLD` | `-Daxiom.manager.confidence-threshold=...` |

For example, to run Axiom with OpenCode as the AI engine and a custom timeout:

```bash
export AXIOM_AI_ENGINE=opencode
export AXIOM_OPENCODE_TIMEOUT_SECONDS=300
java -jar apitomy-axiom-2.1.0.jar
```

Or using system properties:

```bash
java -Daxiom.ai-engine=opencode -Daxiom.opencode.timeout-seconds=300 -jar apitomy-axiom-2.1.0.jar
```

See the [Quarkus Configuration Reference](https://quarkus.io/guides/config-reference) for
full details on property sources and precedence.

### AI Engine

Axiom supports multiple AI engines. Set the active engine with:

| Property | Default | Description |
|----------|---------|-------------|
| `axiom.ai-engine` | `claude-code` | AI engine to use (`claude-code` or `opencode`) |

#### Claude Code Settings

| Property | Default | Description |
|----------|---------|-------------|
| `axiom.claude-code.executable` | `claude` | Path to the Claude CLI binary |
| `axiom.claude-code.model` | *(engine default)* | AI model override |
| `axiom.claude-code.max-turns` | `50` | Maximum agentic turns per task |
| `axiom.claude-code.max-budget-usd` | `5.0` | Maximum cost per task in USD |
| `axiom.claude-code.timeout-seconds` | `600` | Subprocess timeout |

#### OpenCode Settings

| Property | Default | Description |
|----------|---------|-------------|
| `axiom.opencode.server.hostname` | `127.0.0.1` | OpenCode server hostname |
| `axiom.opencode.server.port` | `4096` | OpenCode server port |
| `axiom.opencode.max-steps` | `50` | Maximum agent steps per task |
| `axiom.opencode.timeout-seconds` | `600` | HTTP request timeout |

### Manager

The AI Manager triages incoming events and decides what actions to take.

| Property | Default | Description |
|----------|---------|-------------|
| `axiom.manager.confidence-threshold` | `0.7` | Minimum confidence for auto-execution (0.0–1.0) |
| `axiom.manager.timeout-seconds` | `120` | Manager subprocess timeout |
| `axiom.manager.max-turns` | `5` | Maximum agentic turns for the Manager |

### AI Assistant

The interactive AI Assistant allows users to create configuration items through conversation.

| Property | Default | Description |
|----------|---------|-------------|
| `axiom.assistant.max-sessions` | `3` | Maximum concurrent assistant sessions |
| `axiom.assistant.idle-timeout-seconds` | `3600` | Auto-destroy idle sessions after this duration |

### Workspaces

| Property | Default | Description |
|----------|---------|-------------|
| `axiom.workspace.root` | `~/.axiom/workspaces` | Root directory for project git clones |

## Database Profiles

Axiom uses H2 with different profiles for different environments:

| Profile | Database | Usage |
|---------|----------|-------|
| `dev` (default) | H2 in-memory | Development — schema recreated on restart |
| `persist` | H2 file (`~/.axiom/data/axiom`) | Persistent dev — Flyway migrations |
| `prod` | H2 file | Production — uber-jar with Flyway |

When running from the release JAR, the `prod` profile is active automatically. For
development with persistent data, activate the `persist` profile:

```bash
mvn quarkus:dev -Dquarkus.profile=persist
```

Database schema migrations are managed by Flyway and run automatically on startup when
using the `persist` or `prod` profiles.
