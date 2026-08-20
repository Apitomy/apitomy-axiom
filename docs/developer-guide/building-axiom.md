# Building Axiom

This guide covers how to build, run, and develop Axiom locally.

---

## Prerequisites

| Requirement | Version | Purpose |
|------------|---------|---------|
| Java | 25+ | Backend compilation and runtime |
| Maven | 3.9+ | Build system |
| Node.js | 22+ | UI build (auto-installed by Maven in release builds) |
| AI engine CLI | Latest | One of `claude`, `opencode`, or `copilot` for AI features |
| API key | — | `ANTHROPIC_API_KEY` or provider-specific key |

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

Builds the complete application with the React UI bundled into an uber-jar.

```bash
./build-release.sh
```

Runs: `mvn clean package -Pui,prod`

The `-Pui` profile activates the `ui-bundle` module, which uses the
`frontend-maven-plugin` to install Node.js, run `npm install` and `npm run build`,
and package the resulting `dist/` folder into `META-INF/resources/` inside the JAR.

The `-Pprod` profile configures Quarkus to produce an uber-jar.

Output: `app/target/quarkus-app/quarkus-run.jar`

### `build-all.sh` — Full Build with Integration Tests

Includes integration tests that require the Claude Code CLI to be installed and an
`ANTHROPIC_API_KEY` set.

```bash
./build-all.sh
```

Runs: `AXIOM_CLAUDE_TESTS=true mvn clean package`

### `dev.sh` — Development Mode

Starts the Quarkus backend (with hot reload) and the Vite UI dev server side by side.

```bash
./dev.sh
```

| Service | URL |
|---------|-----|
| Backend (Quarkus dev mode) | http://localhost:9090 |
| Frontend (Vite dev server) | http://localhost:9191 |

The Vite dev server proxies `/api` requests to the backend, so you access the UI at
`http://localhost:9191` and API calls are forwarded automatically.

Both processes run in the foreground — **Ctrl+C** stops everything.

**Flags:**

| Flag | Effect |
|------|--------|
| `--skip-ui` | Start backend only, no Vite dev server |
| `--persist` | Use file-based H2 database (survives restarts) |

```bash
./dev.sh --skip-ui              # Backend only
./dev.sh --persist              # Persistent database
./dev.sh --persist --skip-ui    # Both flags
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

1. **Install Node.js** — `frontend-maven-plugin` downloads Node.js 22 to
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
3. Edit backend Java code — Quarkus hot-reloads on the next request
4. Edit frontend TypeScript/React code — Vite hot-reloads instantly
5. Run `mvn test` in a module directory to run unit tests for that module

---

## Port Reference

| Port | Service | Mode |
|------|---------|------|
| 9090 | Quarkus backend | Development (`mvn quarkus:dev`) |
| 9191 | Vite UI dev server | Development (`npm run dev`) |
| 9191 | Quarkus backend | Production (uber-jar) |

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
schema management.
