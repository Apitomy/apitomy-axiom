# Running Axiom

The fastest way to get started with Apitomy Axiom is to use the `run-axiom.sh` script, which
downloads and runs the latest release automatically.

## Prerequisites

- **Java 25+**
- **curl** and **jq** (for downloading releases)
- **Node.js** and **npm** (used by Axiom at runtime for custom MCP tool servers and the
  AI Assistant's MCP server)
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
2. Download the application JAR (cached in `~/.axiom/releases/` for future runs)
3. Start the application on **http://localhost:9191** with the `prod` profile (file-based H2 database
   in `~/.axiom/data/`)

Open **http://localhost:9191** in your browser to access the Axiom UI. The REST API is served under
`http://localhost:9191/api/v1`. Arguments passed to the script are passed on to `java -jar`.

## Running from Source

To run the current source code, clone the repository and run `./dev.sh` (requires Java 25+, Maven 3.9+,
Node.js 20.19+ or 22.12+ and npm). It builds all modules, then starts the backend on
**http://localhost:9090** and the Vite UI dev server on **http://localhost:9191**, which proxies API requests to the backend.

| Option | Effect |
|--------|--------|
| `--skip-ui` | Start the backend only |
| `--persist` | Use the `persist` profile (file-based H2 database in `~/.axiom/data/`) instead of the in-memory `dev` database |
| `--dataDir=/path` | Store the H2 database in `/path` (implies `--persist`) |
| `--portOffset=N` | Add `N` to both ports (for example `100` gives backend 9190 and UI 9291) |

Press **Ctrl+C** to stop both processes.

## Next Steps

- [Configuring Axiom](configuring-axiom.md) — AI engine settings, database profiles, and
  application properties
- [User Guide](../user-guide/concepts.md) — Core concepts, event sources, action types,
  and advanced usage
