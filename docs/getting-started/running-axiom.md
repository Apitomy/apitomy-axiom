# Running Axiom

The fastest way to get started with Apitomy Axiom is to use the `run-axiom.sh` script, which
downloads and runs the latest release automatically.

## Prerequisites

- **Java 25+**
- **curl** and **jq** (for downloading releases)
- **Node.js** (the `node` binary on the `PATH`; used by Axiom at runtime to run the MCP tool servers it
  gives to AI agents and the AI Assistant, and Node.js action types). The AI engine CLIs installed with
  `npm` also need **npm**.
- One of the supported AI engine CLIs:

| Engine | Config Value | Binary | Install |
|--------|-------------|--------|---------|
| Claude Code | `claude-code` (default) | `claude` | `npm install -g @anthropic-ai/claude-code` |
| OpenCode | `opencode` | `opencode` | `curl -fsSL https://opencode.ai/install \| bash` |
| GitHub Copilot CLI | `copilot` | `copilot` | `npm install -g @github/copilot` |

- An API key for your LLM provider (e.g., `ANTHROPIC_API_KEY` environment variable)

## Run Axiom

Download and run the script:

```bash
curl -fsSL https://raw.githubusercontent.com/Apitomy/apitomy-axiom/main/scripts/run-axiom.sh | bash
```

Or clone the repo and run it directly:

```bash
git clone https://github.com/Apitomy/apitomy-axiom.git
cd apitomy-axiom
./scripts/run-axiom.sh
```

The script will:

1. Query GitHub for the latest Apitomy Axiom release
2. Download the application JAR (cached in `$AXIOM_HOME/releases/`, default `~/.axiom/releases/`, for future
   runs)
3. Start the application on **http://localhost:9191** with the `prod` profile (file-based H2 database
   in `~/.axiom/data/`, or `<axiom.home>/data/` if `axiom.home` is set)

Open **http://localhost:9191** in your browser to access the Axiom UI. The REST API is served under
`http://localhost:9191/api/v1`. Arguments passed to the script are passed on to `java -jar`.

### Running a Release JAR Directly

Each GitHub release publishes a single uber-jar named `apitomy-axiom-<version>.jar`. You can download it
yourself and run it with:

```bash
java -jar apitomy-axiom-3.0.0.jar
```

The release JAR uses the `prod` profile: it listens on port **9191** and stores its H2 database in
`~/.axiom/data/` (`<axiom.home>/data/` if `axiom.home` is set).

!!! warning "Local access only"
    Axiom has no authentication, so the release build only accepts connections from the same machine
    (`127.0.0.1`). To make it reachable from other machines, set the bind address, for example
    `java -Dquarkus.http.host=0.0.0.0 -jar apitomy-axiom-3.0.0.jar` (or `QUARKUS_HTTP_HOST=0.0.0.0`), and
    protect access yourself, for example behind an authenticating reverse proxy.

!!! warning "Upgrading from Axiom 2.x"
    Axiom 3 cannot use a database created by Axiom 2.x (or older) and refuses to start on one. See
    [Upgrading From Axiom 2.x](../developer-guide/upgrading-and-backups.md#upgrading-from-axiom-2x).

## Running from Source

To run the current source code, clone the repository and run `./dev.sh` (requires Java 25+, Maven 3.9+,
Node.js 20.19+ or 22.12+ and npm). It builds all modules, then starts the backend on
**http://localhost:9090** and the Vite UI dev server on **http://localhost:9191**, which proxies API
requests to the backend.

| Option | Effect |
|--------|--------|
| `--skip-ui` | Start the backend only |
| `--persist` | Use the `persist` profile (file-based H2 database in `<axiom.home>/data/`, default `~/.axiom/data/`) instead of the in-memory `dev` database |
| `--dataDir=/path` | Store the H2 database in `/path` (implies `--persist`) |
| `--portOffset=N` | Add `N` to both ports (for example `100` gives backend 9190 and UI 9291) |

Press **Ctrl+C** to stop both processes.

To build a release JAR yourself, run `./build-release.sh` (backend and UI, `prod` profile). It produces
`app/target/apitomy-axiom-app-<version>-runner.jar`, which the release workflow publishes as
`apitomy-axiom-<version>.jar`. See [Building Axiom](../developer-guide/building-axiom.md).

## Next Steps

- [Configuring Axiom](configuring-axiom.md) — AI engine settings, database profiles, and
  application properties
- [User Guide](../user-guide/concepts.md) — Core concepts, event sources, action types,
  and advanced usage
