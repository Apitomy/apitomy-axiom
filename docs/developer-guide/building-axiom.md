# Building Axiom

This guide covers how to build, run, and develop Axiom locally.

---

## Prerequisites

| Requirement | Purpose |
|------------|---------|
| Java | Backend compilation and runtime |
| Maven | Build system |
| Node.js and npm | UI build (auto-installed by Maven in release builds) and runtime support for custom MCP tool servers and the AI Assistant's MCP server |
| AI agent CLI | One of `claude`, `opencode`, or `copilot` for AI features |
| API key | `ANTHROPIC_API_KEY` or provider-specific key |

---

## Build Scripts

Axiom provides several shell scripts in the project root for common workflows.

### `build.sh` — Standard Build

Builds the backend and runs unit tests. Does **not** build the UI.

```bash
./build.sh
```

Runs: `mvn clean package`

### `build-release.sh` — Release Build

Builds the complete application with the React UI bundled into a single runnable JAR.

```bash
./build-release.sh
```

Runs: `mvn clean package -Pui,prod`

The `-Pui` profile activates the `ui-bundle` module, which uses the
`frontend-maven-plugin` to install Node.js, run `npm install` and `npm run build`,
and package the resulting `dist/` folder into `META-INF/resources/` inside the JAR.

The `-Pprod` profile configures Quarkus to produce an uber-jar.

Output: `app/target/quarkus-app/quarkus-run.jar` (the release workflow republishes
this as `app/target/apitomy-axiom-app-<version>-runner.jar`)

The resulting application can be started with:

```bash
java -jar app/target/quarkus-app/quarkus-run.jar
```

### `build-all.sh` — Full Build with Integration Tests

Includes integration tests that require the Claude Code CLI to be installed and an
`ANTHROPIC_API_KEY` set.

```bash
./build-all.sh
```

Runs: `AXIOM_CLAUDE_TESTS=true mvn clean package`

### `dev.sh` — Development Mode

Builds all Maven modules, then launches the packaged backend JAR together with the
Vite UI dev server side by side.

```bash
./dev.sh
```

| Service | URL |
|---------|-----|
| Backend | http://localhost:9090 |
| Frontend (Vite dev server) | http://localhost:9191 |

The Vite dev server proxies `/api` requests to the backend, so you access the UI at
`http://localhost:9191` and API calls are forwarded automatically.

Both processes run in the foreground — **Ctrl+C** stops everything.

!!! note
    `dev.sh` runs the backend as a packaged application (`mvn clean install` followed
    by `java -jar`), not Quarkus's live-reload dev mode. Backend Java changes require
    re-running `dev.sh` to take effect. To get Quarkus hot reload while iterating on
    backend code, run `mvn quarkus:dev` directly from the `app` module instead.

**Flags:**

| Flag | Effect |
|------|--------|
| `--skip-ui` | Start backend only, no Vite dev server |
| `--persist` | Use file-based H2 database (survives restarts) |
| `--portOffset=N` | Offset both the backend and UI dev server ports by `N` |

```bash
./dev.sh --skip-ui              # Backend only
./dev.sh --persist              # Persistent database
./dev.sh --persist --skip-ui    # Both flags
./dev.sh --portOffset=100       # Backend on 9190, UI on 9291
```

---

## Maven Profiles

| Profile | What it does | Activated by |
|---------|-------------|-------------|
| `ui` | Includes the `ui-bundle` module (builds and packages the React UI) | `-Pui` |
| `prod` | Produces an uber-jar with production settings | `-Pprod` or auto in `build-release.sh` |

---

## UI Build Pipeline

When the `ui` profile is active, the `ui-bundle` module runs these steps:

1. **Install Node.js** — `frontend-maven-plugin` downloads Node.js to
   `ui-bundle/target/node-install/`
2. **Install dependencies** — runs `npm install` in the `ui/` directory
3. **Build UI** — runs `npm run build`, producing optimized assets in `ui/dist/`
4. **Package** — copies `ui/dist/` to `target/classes/META-INF/resources/`, where
   Quarkus serves them as static files

In production, the backend serves the bundled UI directly — no separate web server
needed.

---

## Development Workflow

A typical development loop:

1. Run `./dev.sh` to start both backend and frontend
2. Open `http://localhost:9191` in your browser
3. Edit frontend TypeScript/React code — Vite hot-reloads instantly
4. Edit backend Java code — re-run `./dev.sh` (or use `mvn quarkus:dev` from the `app`
   module for hot reload)
5. Run `mvn test` in a module directory to run unit tests for that module

---

## Port Reference

| Port | Service | Mode |
|------|---------|------|
| 9090 | Backend | Development (`./dev.sh` or `mvn quarkus:dev`) |
| 9191 | Vite UI dev server | Development (`npm run dev`) |
| 9191 | Backend | Production (release JAR) |

---

## Database Profiles

| Profile | Database | Schema Management |
|---------|----------|-------------------|
| `dev` (default) | H2 in-memory | Hibernate `drop-and-create` — recreated on restart |
| `persist` | H2 file (`~/.axiom/data/axiom`) | Flyway migrations |
| `prod` | H2 file (`~/.axiom/data/axiom`) | Flyway migrations |

Use `--persist` with `dev.sh` or `-Dquarkus.profile=persist` with Maven to keep data
between restarts during development.

See the [Database & Migrations](database-and-migrations.md) guide for details on
schema management, and [Upgrading and Backups](upgrading-and-backups.md) for what to
back up alongside the database.
