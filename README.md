[![Verify Build Workflow](https://github.com/Apitomy/apitomy-axiom/actions/workflows/verify.yaml/badge.svg)](https://github.com/Apitomy/apitomy-axiom/actions/workflows/verify.yaml?query=branch%3Amain)

# Apitomy Axiom

Workflow-first, event-driven orchestration platform that monitors GitHub and Jira activity and runs
versioned workflows that delegate work to AI agents, scripts, and humans.

## What It Does

- Polls GitHub/Jira for activity and records it in a normalized event stream
- Subscriptions route matching events to workflows: `create-workflow` starts a run on the event's subject,
  `workflow-dispatch` delivers the event to runs waiting for it
- Schedule triggers start workflow runs on a cron schedule against a fixed subject
- Workflow action nodes run action types through a pool of AI agents (or as scripts); human-task nodes
  wait in the Inbox
- Subjects (issues, pull requests, named subjects) hold the workspace and thread shared by their runs
- A UI with Runs as its landing page provides visibility into runs, subjects, tasks, and configuration

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Backend | Java 25 / Quarkus 3.33 LTS |
| Frontend | TypeScript / React / PatternFly |
| Database | H2 (in-memory dev, file-based prod) |
| AI | Pluggable: Claude Code CLI or OpenCode (configurable) |
| API | Contract-first OpenAPI + apitomy-codegen |

## AI Engine

Axiom's AI engine is pluggable. Set `axiom.ai-engine` in `application.properties` to
select which engine to use:

| Engine | Config Value | Binary | Install |
|--------|-------------|--------|---------|
| Claude Code | `claude-code` (default) | `claude` | `npm install -g @anthropic-ai/claude-code` |
| OpenCode | `opencode` | `opencode` | `curl -fsSL https://opencode.ai/install \| bash` |

Only the selected engine's binary needs to be installed. Both can coexist for testing.

## Prerequisites

- Java 25+
- Maven 3.9+
- Node.js 20+ (for UI and MCP tool server)
- One of the AI engine CLIs (see table above)
- API key for your LLM provider (e.g. `ANTHROPIC_API_KEY`)

## Build

```bash
# Standard build (backend only, no UI)
./build.sh

# Backend + UI build, including the UI unit tests (Vitest)
./build.sh -Pui

# UI unit tests only
cd ui && npm test

# Release build (backend + UI bundled into uber-jar)
./build-release.sh
```

### Live CLI tests

Some tests launch a real `opencode` CLI and may consume LLM tokens. These are tagged
`@Tag("live-cli")` and are **excluded from the default build**. To run them, enable the
`live-cli-tests` profile (requires the `opencode` CLI installed and authenticated):

```bash
mvn test -Plive-cli-tests
```

When adding a test that invokes a real `opencode` or `claude` CLI, annotate it with
`@Tag("live-cli")` so it stays out of the standard build.

## Development

The `dev.sh` script builds all modules and starts both the Quarkus backend and the Vite UI
dev server side by side:

```bash
./dev.sh
```

- Backend starts on **http://localhost:9090**
- UI starts on **http://localhost:9191** (proxies API requests to the backend)
- Both run in the foreground — **Ctrl+C** stops everything

To run the backend only (no UI):

```bash
./dev.sh --skip-ui
```

## Project Structure

```
common/api/          OpenAPI contract + generated JAX-RS interfaces
core/                Domain entities, workspace management, SDK function registry
agents/spi/          Pluggable AI agent abstraction (agent pool)
agents/claude-code/  Claude Code CLI agent
agents/opencode/     OpenCode agent
agents/copilot/      GitHub Copilot CLI agent
events/core/         Event stream and pipeline support
events/github/       GitHub event source poller
events/jira/         Jira event source poller
notifications/       Notification channels (Slack, Telegram)
app/                 Quarkus application (assembles everything)
ui/                  React frontend
ui-bundle/           Maven module that builds and tests the UI for release builds
```

## Database Migrations (Flyway)

Schema migrations are managed by [Flyway](https://flywaydb.org/) and run automatically on
startup for persistent databases (the `persist` and `prod` profiles). The schema is a single baseline
file, `app/src/main/resources/db/migration/V1__baseline.sql`.

- **Dev mode** (`quarkus:dev`): Uses Hibernate's `drop-and-create` — no Flyway, schema is
  recreated on every restart.
- **Persist/Prod mode**: Flyway runs migrations at startup. Hibernate schema generation is
  set to `none` — Flyway owns the schema.

Until the first release, schema changes are made by **editing `V1__baseline.sql` in place** — do not add
`V2__…` files. There is no data migration: an existing persistent database must be deleted when the
baseline changes. `MigrationSchemaValidationTest` verifies that the baseline matches the entity model.
See [Database & Migrations](docs/developer-guide/database-and-migrations.md).

## Documentation

- [Concepts](docs/user-guide/concepts.md)
- [Workflows](docs/user-guide/workflows.md)
- [Stack & Architecture](docs/developer-guide/stack-and-architecture.md)

## License

[Apache License 2.0](LICENSE)

## Links

- [GitHub Repository](https://github.com/Apitomy/apitomy-axiom)
- [Apitomy Website](https://www.apitomy.io)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on how to contribute to this project.
