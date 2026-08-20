# Running Axiom

The fastest way to get started with Apitomy Axiom is to use the `run-axiom.sh` script, which
downloads and runs the latest release automatically.

## Prerequisites

- **Java** 25+
- **curl** and **jq** (for downloading releases)
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
3. Start the application on **http://localhost:9191**

Open **http://localhost:9191** in your browser to access the Axiom dashboard.

## Next Steps

- [Configuring Axiom](configuring-axiom.md) — AI engine settings, database profiles, and
  application properties
- [User Guide](../user-guide/concepts.md) — Core concepts, event sources, action types,
  and advanced usage
