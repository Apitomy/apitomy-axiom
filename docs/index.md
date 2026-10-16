# Apitomy Axiom

A workflow-first, event-driven orchestration platform that monitors GitHub and Jira activity and runs
versioned workflows that delegate work to AI agents, scripts, and humans.

## Key Features

- **Workflows** — visually authored, versioned automations with action, human-task, wait, and
  receive-event nodes; workflow runs are the primary object in the UI
- **Standard Workflow Inputs** — a workflow's Start node declares the standard inputs it needs
  (`subject`, `event`), and Axiom supplies them when a run starts
- **Event Sources & Subscriptions** — polls GitHub and Jira for activity, then routes matched events
  to workflows: `create-workflow` starts a run, `workflow-dispatch` resumes waiting runs
- **Schedule Triggers** — cron schedules that start workflow runs on a fixed subject
- **Workflow Workbench** — an authoring workspace for a workflow and its action types, tools and
  subscriptions, with a workflow canvas, editors, test runs, run history and an AI Assistant that proposes
  changes you can review or apply live
- **Subjects** — the issue, pull request, or named subject a run is about, with a shared workspace and
  thread
- **Human + AI Agents** — pluggable agent pool supports Claude Code, OpenCode, GitHub Copilot CLI, and
  human-completed tasks
- **Inbox & Dashboards** — human tasks waiting for a person, and configurable dashboards
- **Web UI** — React + PatternFly UI with real-time SSE updates
- **AI Assistant** — interactive conversational interface with customizable session templates,
  real-time tool permission management, and plan mode
- **Pluggable AI Agents** — swap between Claude Code CLI, OpenCode, and GitHub Copilot CLI without code
  changes

## Quick Links

- [Running Axiom](getting-started/running-axiom.md) — Prerequisites and running Axiom
- [Configuring Axiom](getting-started/configuring-axiom.md) — AI engines, profiles, and properties
- [User Guide](user-guide/concepts.md) — Core concepts, configuration, and extending Axiom
- [Workflow Workbench](user-guide/workbench.md) — Author workflows with their action types, tools and
  subscriptions
- [Upgrading and Backups](developer-guide/upgrading-and-backups.md) — Axiom 3 cannot use a 2.x database
- [GitHub Repository](https://github.com/Apitomy/apitomy-axiom) — Source code and issues

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Backend | Java 25 / Quarkus |
| Frontend | TypeScript / React / PatternFly |
| Database | H2 (in-memory dev, file-based prod) |
| Migrations | Flyway (automatic on startup) |
| AI Agents | Claude Code CLI, OpenCode, GitHub Copilot CLI (pluggable) |
| Workflows | Apitomy Flow (editor and engine) |
| API | Contract-first OpenAPI + Apitomy Codegen |

## Community

All Apitomy projects are open source under the Apache License 2.0. We welcome contributions,
feedback, and ideas.

- **Issues**: Report bugs and request features on
  [GitHub Issues](https://github.com/Apitomy/apitomy-axiom/issues)
- **Contributing**: See the
  [Contributing Guide](https://github.com/Apitomy/apitomy-axiom/blob/main/CONTRIBUTING.md)
