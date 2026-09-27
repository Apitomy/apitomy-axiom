# Upgrading and Backups

Axiom migrates its own database schema automatically on startup (see
[Database & Migrations](database-and-migrations.md)), but a schema migration is not the
same thing as a safe, reversible upgrade. This guide covers what changes across
versions, what to back up, and how to restore an instance.

---

## What a Version Upgrade Can Change

Because Flyway migrations run automatically and are not designed to be rolled back,
review the migration files in
`app/src/main/resources/db/migration/` between your current version and the version
you're upgrading to before upgrading a production instance. Migrations can:

- **Rename or restructure tables and columns.** For example, the actor-to-agent
  rename dropped the `human` agent type, renamed `assigned_actor` to `assigned_agent`,
  and changed the `actor`/`agent` execution-mode value used by action types and
  scheduled jobs.
- **Drop tables outright.** For example, the legacy event/event-queue/event-source
  tables were dropped once fully replaced by the event stream pipeline
  (connections, stream events, and subscriptions).
- **Delete data as part of the migration**, not just restructure it (as in the actor
  removal above).

There is no automatic downgrade path. If you need the ability to roll back, take a
database backup (see below) before upgrading.

## What Makes Up a Full Backup

A configuration pack (**Settings > Configuration Packs**) exports action types, tools,
toolsets, MCP servers, report definitions, scheduled jobs, session templates,
connections, subscriptions, and workflow definitions as JSON. **It is not a full
instance backup.** A configuration pack does not include:

- Projects, tasks, thread entries, or activity history
- Generated reports, scheduled job runs, or workflow run state
- The stream event history and processing ledger
- Secrets (secret values are never exported by the API)
- The AI usage/cost history

To fully back up and restore an Axiom instance, you need all of the following, taken
together at the same point in time:

| Item | Location | Notes |
|------|----------|-------|
| Database | `~/.axiom/data/axiom.mv.db` (and related H2 files) | Only meaningful with `persist` or `prod` profiles, which use a file-based database |
| Encryption key | `~/.axiom/secret.key` | Generated automatically on first startup. Secret values in the database are encrypted with this key — **without it, encrypted secrets cannot be decrypted, even by restoring the database.** |
| Project workspaces | `~/.axiom/workspaces/` (or `axiom.workspace.root` if overridden) | Git clones and working directories backing each project |
| Assistant session directories (optional) | `~/.axiom/assistant/sessions/` | Sessions are in-memory and do not survive a restart regardless of backup; this directory is cleaned up automatically at startup |

Stop the Axiom process before copying the database files to ensure a consistent
snapshot (H2 does not support online hot backups of the file store used here).

### Restoring

1. Install the target Axiom version.
2. Restore `~/.axiom/data/` and `~/.axiom/secret.key` from backup **together** — the
   encryption key must match the database it was used to encrypt secrets for.
3. Restore `~/.axiom/workspaces/` if project working directories are needed.
4. Start Axiom with the `persist` or `prod` profile so Flyway applies any migrations
   needed to bring the restored schema up to the target version.

## Configuration Packs Are for Sharing, Not Disaster Recovery

Use configuration packs to move a known-good set of tools, action types, connections,
subscriptions, and workflows between instances (e.g. staging to production), or to
version-control your configuration outside the database. Don't rely on them as your
only backup mechanism — they intentionally exclude runtime history and secret values.
