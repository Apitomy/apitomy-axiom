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
- The Workflow Workbench is an authoring workspace where you build a workflow together with its action
  types and tools, test them, and work with an AI Assistant that proposes changes
- A UI with Runs as its landing page provides visibility into runs, subjects, tasks, and configuration

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Backend | Java 25 / Quarkus 3.40 |
| Frontend | TypeScript / React / PatternFly |
| Database | H2 (in-memory dev, file-based prod) |
| AI | Pluggable: Claude Code CLI, OpenCode or GitHub Copilot CLI (configurable) |
| Workflow editor/engine | Apitomy Flow 3.0.2 (`@apitomy/flow-ui`, `apitomy-flow-engine`) |
| API | Contract-first OpenAPI + apitomy-codegen |

## AI Engine

Axiom's AI engine is pluggable. Set `axiom.agent.default-type` (for example with the
`AXIOM_AGENT_DEFAULT_TYPE` environment variable) or use **Settings > AI Engine** in the UI to select the
default engine:

| Engine | Config Value | Binary | Install |
|--------|-------------|--------|---------|
| Claude Code | `claude-code` (default) | `claude` | `npm install -g @anthropic-ai/claude-code` |
| OpenCode | `opencode` | `opencode` | `curl -fsSL https://opencode.ai/install \| bash` |
| GitHub Copilot CLI | `copilot` | `copilot` | `npm install -g @github/copilot` |

Only the selected engine's binary needs to be installed. Several engines can coexist.

## Prerequisites

- Java 25+
- Maven 3.9+
- Node.js 20.19+ or 22.12+ (for UI and MCP tool server)
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

Other options:

```bash
./dev.sh --skip-ui          # backend only (no UI)
./dev.sh --persist          # use the persist profile (file-based H2 database in ~/.axiom/data)
./dev.sh --dataDir=/path    # store the H2 database in /path (implies --persist)
./dev.sh --portOffset=100   # offset both ports (backend 9190, UI 9291)
```

Without `--persist` the backend uses the `dev` profile: an in-memory database that is recreated on every
restart.

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

`V1__baseline.sql` is frozen as of 3.0.0: every schema change is a new, incremental `V2__…` migration,
applied automatically at startup. `MigrationSchemaValidationTest` verifies that the migrations match the
entity model. Axiom 3 refuses to start on a database created by Axiom 2.x or older.
See [Database & Migrations](docs/developer-guide/database-and-migrations.md).

## Documentation

- [Running Axiom](docs/getting-started/running-axiom.md)
- [Configuring Axiom](docs/getting-started/configuring-axiom.md)
- [Concepts](docs/user-guide/concepts.md)
- [Navigating the UI](docs/user-guide/navigating-the-ui.md)
- [Workflows](docs/user-guide/workflows.md)
- [Stack & Architecture](docs/developer-guide/stack-and-architecture.md)

## License

[Apache License 2.0](LICENSE)

## Links

- [GitHub Repository](https://github.com/Apitomy/apitomy-axiom)
- [Apitomy Website](https://www.apitomy.io)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on how to contribute to this project.
