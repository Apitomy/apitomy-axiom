# Upgrading and Backups

Axiom migrates its own database schema automatically on startup (see
[Database & Migrations](database-and-migrations.md)), but a schema migration is not the
same thing as a safe, reversible upgrade. This guide covers what changes across
versions, what to back up, and how to restore an instance.

---

## What a Version Upgrade Can Change

Until the first release, Axiom's schema is a single baseline migration
(`app/src/main/resources/db/migration/V1__baseline.sql`) that is **edited in place**. There is no data
migration and no backward compatibility between pre-release builds: when the baseline changes, an existing
persistent database cannot be upgraded and must be deleted (or moved aside) so Axiom can create a fresh
one. Export a [configuration pack](#configuration-packs-are-for-sharing-not-disaster-recovery) first if
you want to keep your configuration, then import it into the new database.

After the first release, schema changes will ship as incremental Flyway migrations. Because Flyway
migrations run automatically and are not designed to be rolled back, review the migration files between
your current version and the target version before upgrading a production instance, and take a database
backup (see below) first — there is no automatic downgrade path.

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

To fully back up and restore an Axiom instance, you need all of the following, taken
together at the same point in time:

| Item | Location | Notes |
|------|----------|-------|
| Database | `~/.axiom/data/axiom.mv.db` (and related H2 files) | Only meaningful with `persist` or `prod` profiles, which use a file-based database |
| Encryption key | `~/.axiom/secret.key` | Generated automatically on first startup. Secret values in the database are encrypted with this key — **without it, encrypted secrets cannot be decrypted, even by restoring the database.** |
| Subject workspaces | `~/.axiom/workspaces/` (or `axiom.workspace.root` if overridden) | Working directories (`subject-<id>`) backing each subject |
| Assistant session directories (optional) | `~/.axiom/assistant/sessions/` | Sessions are in-memory and do not survive a restart regardless of backup; this directory is cleaned up automatically at startup |

Stop the Axiom process before copying the database files to ensure a consistent
snapshot (H2 does not support online hot backups of the file store used here).

### Restoring

1. Install the target Axiom version.
2. Restore `~/.axiom/data/` and `~/.axiom/secret.key` from backup **together** — the
   encryption key must match the database it was used to encrypt secrets for.
3. Restore `~/.axiom/workspaces/` if subject workspaces are needed.
4. Start Axiom with the `persist` or `prod` profile. Before the first release, restore only into the
   same Axiom build that created the backup (the baseline may have changed since).

## Configuration Packs Are for Sharing, Not Disaster Recovery

Use configuration packs to move a known-good set of tools, action types, event sources,
subscriptions, and workflows between instances (e.g. staging to production), or to
version-control your configuration outside the database. Don't rely on them as your
only backup mechanism — they intentionally exclude runtime history and secret values.
