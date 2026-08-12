# Apitomy Axiom

An event-driven project orchestration platform that monitors GitHub and Jira issues, creates
long-lived projects around them, and delegates work to human and AI actors.

## Key Features

- **AI-Powered Triage** — an AI Manager triages incoming events and decides what actions to take
- **Human + AI Actors** — pluggable actor system supports Claude Code, OpenCode, and human actors
- **Project Lifecycle** — long-lived projects track work from triage through completion
- **Event Sources** — watches GitHub and Jira repositories via webhooks or polling
- **Scheduled Jobs** — CRON-style automation with AI actor or script execution modes
- **Web Dashboard** — React + PatternFly UI with real-time SSE updates
- **AI Assistant** — interactive conversational interface with customizable session templates,
  real-time tool permission management, and plan mode
- **Pluggable AI Engines** — swap between Claude Code CLI and OpenCode without code changes

## Quick Links

- [Running Axiom](getting-started/running-axiom.md) — Prerequisites and running Axiom
- [User Guide](user-guide/concepts.md) — Core concepts, configuration, and extending Axiom
- [GitHub Repository](https://github.com/Apitomy/apitomy-axiom) — Source code and issues

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Backend | Java 25 / Quarkus 3.33 LTS |
| Frontend | TypeScript / React 19 / PatternFly 6 |
| Database | H2 (in-memory dev, file-based prod) |
| Migrations | Flyway (automatic on startup) |
| AI Engines | Claude Code CLI, OpenCode (pluggable) |
| API | Contract-first OpenAPI + Apitomy Codegen |

## Community

All Apitomy projects are open source under the Apache License 2.0. We welcome contributions,
feedback, and ideas.

- **Issues**: Report bugs and request features on
  [GitHub Issues](https://github.com/Apitomy/apitomy-axiom/issues)
- **Contributing**: See the
  [Contributing Guide](https://github.com/Apitomy/apitomy-axiom/blob/main/CONTRIBUTING.md)
