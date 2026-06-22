[![Verify Build Workflow](https://github.com/Apitomy/apitomy-axiom/actions/workflows/verify.yaml/badge.svg)](https://github.com/Apitomy/apitomy-axiom/actions/workflows/verify.yaml?query=branch%3Amain)

# Apitomy Axiom

Event-driven project orchestration platform that monitors GitHub and Jira issues, creates
long-lived projects around them, and delegates work to human and AI actors.

## What It Does

- Watches GitHub/Jira repositories for issue activity (webhooks or polling)
- An AI Manager triages events and decides what actions to take
- Tasks are assigned to Actors (AI agents or humans)
- Projects track the full lifecycle of work tied to each issue
- A UI provides visibility into projects, tasks, activity, and configuration

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

# Release build (backend + UI bundled into uber-jar)
./build-release.sh
```

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
core/                Domain entities, lifecycle, workspace management
engine/spi/          Pluggable AI engine abstraction layer
engine/opencode/     OpenCode engine implementation
manager/             AI Manager (event triage and decision-making)
actors/spi/          Actor interface
actors/claude-code/  Claude Code CLI subprocess actor + engine
actors/human/        Human actor (notification-driven)
events/core/         Event queue and pipeline orchestrator
events/github/       GitHub webhooks + API polling
events/jira/         Jira integration (planned)
notifications/       Slack, Telegram channels (planned)
app/                 Quarkus application (assembles everything)
ui/                  React frontend
```

## Database Migrations (Flyway)

Schema migrations are managed by [Flyway](https://flywaydb.org/) and run automatically on
startup for persistent databases (the `persist` and `prod` profiles). Migration files are
located in `app/src/main/resources/db/migration/`.

### How it works

- **Dev mode** (`quarkus:dev`): Uses Hibernate's `drop-and-create` — no Flyway, schema is
  recreated on every restart. Fast for development.
- **Persist/Prod mode**: Flyway runs migrations at startup. Hibernate schema generation is
  set to `none` — Flyway owns the schema.

### Adding a new migration

1. Create a new SQL file in `app/src/main/resources/db/migration/` following the naming
   convention: `V<number>__<description>.sql` (e.g. `V3__add_priority_to_task.sql`)
2. Write standard SQL DDL statements. Use `IF NOT EXISTS` / `IF EXISTS` guards for safety.
3. The migration runs automatically on the next startup of a persistent database.

### Existing databases

Flyway is configured with `baseline-on-migrate=true` and `baseline-version=1`. This means
existing databases that were created before Flyway was introduced will be automatically
baselined — Flyway skips V1 (the full schema creation) and applies only new migrations
(V2+).

### Example migration

```sql
-- V3__add_priority_to_task.sql
ALTER TABLE task ADD COLUMN IF NOT EXISTS priority VARCHAR(255) DEFAULT 'normal';
```

## Documentation

- [Functional Design](docs/design-v2.md)
- [Architecture](docs/architecture-v2.md)
- [Implementation Plan](docs/implementation-plan.md)
- [OpenCode Migration Plan](docs/opencode-migration-plan.md)
- [Claude Code Integration Research](docs/research-claude-code-integration.md)

## License

[Apache License 2.0](LICENSE)

## Links

- [GitHub Repository](https://github.com/Apitomy/apitomy-axiom)
- [Apitomy Website](https://www.apitomy.io)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on how to contribute to this project.
