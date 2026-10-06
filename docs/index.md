# Apitomy Axiom

A workflow-first, event-driven orchestration platform that monitors GitHub and Jira activity and runs
versioned workflows that delegate work to AI agents, scripts, and humans.

## Key Features

- **Workflows** — visually authored, versioned automations with action, human-task, wait, and
  receive-event nodes; workflow runs are the primary object in the UI
- **Event Sources & Subscriptions** — polls GitHub and Jira for activity, then routes matched events
  to workflows: `create-workflow` starts a run, `workflow-dispatch` resumes waiting runs
- **Schedule Triggers** — cron schedules that start workflow runs on a fixed subject
- **Subjects** — the issue, pull request, or named subject a run is about, with a shared workspace and
  thread
- **Human + AI Agents** — pluggable agent pool supports Claude Code, OpenCode, GitHub Copilot CLI, and
  human-completed tasks
- **Web UI** — React + PatternFly UI with real-time SSE updates
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
