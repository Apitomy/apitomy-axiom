# Apitomy Axiom

An event-driven project orchestration platform that monitors GitHub and Jira issues, creates
long-lived projects around them, and delegates work to human and AI agents.

## Key Features

- **AI-Powered Triage** — an AI Manager triages incoming stream events and decides what actions to take
- **Human + AI Agents** — pluggable agent pool supports Claude Code, OpenCode, GitHub Copilot CLI, and human-completed tasks
- **Project Lifecycle** — long-lived projects track work from triage through completion
- **Event Connections & Subscriptions** — polls GitHub and Jira for activity, then routes matched events to the Manager, a workflow, or an action via filterable subscriptions
- **Workflows** — visually authored, versioned automations with human-task, wait, and event-triggered nodes
- **Scheduled Jobs** — CRON-style automation with agent or script execution modes
- **Web Dashboard** — React + PatternFly UI with real-time SSE updates
- **AI Assistant** — interactive conversational interface with customizable session templates,
  real-time tool permission management, and plan mode
- **Pluggable AI Agents** — swap between Claude Code CLI, OpenCode, and GitHub Copilot CLI without code changes

## Quick Links

- [Running Axiom](getting-started/running-axiom.md) — Prerequisites and running Axiom
- [User Guide](user-guide/concepts.md) — Core concepts, configuration, and extending Axiom
- [GitHub Repository](https://github.com/Apitomy/apitomy-axiom) — Source code and issues

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Backend | Java / Quarkus |
| Frontend | TypeScript / React / PatternFly |
| Database | H2 (in-memory dev, file-based prod) |
| Migrations | Flyway (automatic on startup) |
| AI Agents | Claude Code CLI, OpenCode, GitHub Copilot CLI (pluggable) |
| API | Contract-first OpenAPI + Apitomy Codegen |

## Community

All Apitomy projects are open source under the Apache License 2.0. We welcome contributions,
feedback, and ideas.

- **Issues**: Report bugs and request features on
  [GitHub Issues](https://github.com/Apitomy/apitomy-axiom/issues)
- **Contributing**: See the
  [Contributing Guide](https://github.com/Apitomy/apitomy-axiom/blob/main/CONTRIBUTING.md)
