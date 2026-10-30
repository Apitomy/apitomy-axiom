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

Optional, for external MCP servers that run locally:

- **npx** (comes with Node.js) for servers distributed as npm packages
- **uvx** ([uv](https://docs.astral.sh/uv/getting-started/installation/)) for servers distributed as Python
  packages, such as Jira & Confluence
- **Docker** (client and a running daemon) for servers distributed as container images

Axiom checks for these when it starts and shows the results under **Prerequisites** on **Settings → AI
Engine**. A missing optional tool is only a warning: MCP servers that need it won't start. Restart Axiom after
installing one.

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

1. Query GitHub for the latest Apitomy Axiom release (or the release pinned with `AXIOM_VERSION`)
2. Download the application JAR (cached in `$AXIOM_HOME/releases/`, default `~/.axiom/releases/`, for future
   runs). The JAR is downloaded to a temporary `.part` file and only moved into place once complete.
3. Check for Java 25+, `curl`, `jq` and Node.js (`node` is required for Axiom 3.x; for older versions a
   missing `node` only produces a warning)
4. Start the application on **http://localhost:9191** with the `prod` profile (file-based H2 database
   in `$AXIOM_HOME/data/`, default `~/.axiom/data/`)

Open **http://localhost:9191** in your browser to access the Axiom UI. The REST API is served under
`http://localhost:9191/api/v1`. Arguments passed to the script are passed on to `java -jar`.

The script reads these environment variables:

| Variable | Effect |
|----------|--------|
| `AXIOM_VERSION` | Release to run, without the leading `v` (for example `AXIOM_VERSION=2.1.2`). Default: the latest release |
| `AXIOM_HOME` | Axiom home directory holding the JAR cache (`releases/`) and the database (`data/`). Default: `~/.axiom` |

For example, to stay on Axiom 2.1.2:

```bash
AXIOM_VERSION=2.1.2 ./scripts/run-axiom.sh
```

**Offline use.** If GitHub cannot be reached (no network, API rate limit, HTTP error), the script falls back
to the cache with a warning: the newest cached JAR when no version is pinned, or the cached JAR of the pinned
`AXIOM_VERSION`. It fails only if no suitable JAR is cached.

**Upgrade warning.** The script records the last version it ran in `$AXIOM_HOME/releases/last-run-version`.
Before starting Axiom 3.x it checks whether `$AXIOM_HOME/data/axiom.mv.db` exists and appears to come from
Axiom 2.x (the recorded version is 2.x, or there is no record but a 2.x JAR is cached). If so, it explains
that Axiom 3 cannot use a 2.x database and that you can either back up and delete `$AXIOM_HOME/data` or pin
`AXIOM_VERSION=2.1.2`, and links to the
[3.0.0 release notes](https://github.com/Apitomy/apitomy-axiom/releases/tag/v3.0.0). In an interactive
terminal it asks for confirmation; otherwise it continues and Axiom itself refuses to start on the old
database. This check is a heuristic and can miss databases created outside the script.

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
