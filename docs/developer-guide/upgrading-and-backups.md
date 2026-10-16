# Upgrading and Backups

Axiom migrates its own database schema automatically on startup (see
[Database & Migrations](database-and-migrations.md)), but a schema migration is not the
same thing as a safe, reversible upgrade. This guide covers what changes across
versions, what to back up, and how to restore an instance.

---

## What a Version Upgrade Can Change

Schema changes ship as incremental Flyway migrations (see
[Database & Migrations](database-and-migrations.md#migrations)) that run automatically at startup. They are
not designed to be rolled back: review the migration files between your current version and the target
version before upgrading a production instance, and take a backup (see below) first. There is no automatic
downgrade path.

## Upgrading From Axiom 2.x

Axiom 3.0 is a clean break from 2.x: it starts from a new database schema and **cannot use a 2.x database**.
If Axiom 3 finds a database created by 2.x (or older) it refuses to start, with a message telling you to
remove the old data. To upgrade:

1. Stop Axiom 2.x.
2. Back up `~/.axiom` (or move it aside) if you want to keep the old data for reference.
3. Delete the database directory, `~/.axiom/data/`.
4. Start Axiom 3. It creates a new, empty database.
5. Recreate your configuration: event sources, secrets, action types, tools, workflows and subscriptions.
   Many 2.x concepts were removed or reworked (see the 3.0.0 release notes), so 2.x configuration packs are
   not expected to import cleanly; the [Workflow Workbench](../user-guide/workbench.md) is the quickest way
   to rebuild workflows.

Secrets are stored in the database, so they must be re-entered. The rest of `~/.axiom` (the encryption key,
workspaces and session directories) can stay; old 2.x subject workspaces are no longer referenced and can be
removed.

## What Makes Up a Full Backup

A configuration pack (**Settings > Configuration Packs**) exports action types, tools,
toolsets, MCP servers, session templates,
event sources, subscriptions, and workflow definitions as JSON. **It is not a full
instance backup.** A configuration pack does not include:

- Subjects, tasks, thread entries, or activity history
- Workflow runs and their state
- The stream event history and processing ledger
- Secrets (secret values are never exported by the API)
- The AI usage/cost history
- Workbench compositions and their unpublished drafts (only published workflows, action types and tools can
  be exported)

To fully back up and restore an Axiom instance, you need all of the following, taken
together at the same point in time. `~/.axiom` is the default Axiom home (`axiom.home`):

| Item | Location | Notes |
|------|----------|-------|
| Database | `~/.axiom/data/axiom.mv.db` (and related H2 files) | Only meaningful with `persist` or `prod` profiles, which use a file-based database |
| Encryption key | `~/.axiom/secret.key` | Generated automatically on first startup. Secret values in the database are encrypted with this key — **without it, encrypted secrets cannot be decrypted, even by restoring the database.** |
| Subject workspaces | `~/.axiom/workspaces/` (or `axiom.workspace.root` if overridden) | Working directories (`subject-<id>`) backing each subject |
| Assistant session directories (optional) | `~/.axiom/assistant/sessions/` | Running agent processes do not survive a restart. Workbench composition session directories are kept and hold the conversation history that the workbench restores after a restart; other stale session directories are removed at startup |

Stop the Axiom process before copying the database files to ensure a consistent
snapshot (H2 does not support online hot backups of the file store used here).

### Restoring

1. Install the target Axiom version.
2. Restore `~/.axiom/data/` and `~/.axiom/secret.key` from backup **together** — the
   encryption key must match the database it was used to encrypt secrets for.
3. Restore `~/.axiom/workspaces/` if subject workspaces are needed.
4. Start Axiom with the `persist` or `prod` profile. Restore into the same or a newer 3.x version than the
   one that created the backup; newer versions migrate the restored database at startup.

## Configuration Packs Are for Sharing, Not Disaster Recovery

Use configuration packs to move a known-good set of tools, action types, event sources,
subscriptions, and workflows between instances (e.g. staging to production), or to
version-control your configuration outside the database. Don't rely on them as your
only backup mechanism — they intentionally exclude runtime history and secret values.
