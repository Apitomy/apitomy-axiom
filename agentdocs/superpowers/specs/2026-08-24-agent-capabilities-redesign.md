# Agent Capabilities Redesign

**Issue:** Follows from #231 (Agent SPI unification)
**Date:** 2026-08-24

## Summary

Replace the current free-form, comma-separated capabilities string on agents with a
structured, glob-pattern-based capability system that governs agent routing across all
AI workloads: action type tasks, reports, and scheduled jobs.

## Problem

After the Agent SPI unification (#231), all AI workloads route through the Agent Pool.
However, capabilities are still rudimentary:

- Free-form strings stored as a comma-separated TEXT column
- Only action type tasks use capability matching; reports and scheduled jobs pass `null`
  and grab any idle agent
- No structured format or validation
- The Manager AI sees capabilities in its prompt but can't meaningfully act on them

## Design

### Capability String Format

Capabilities follow a `<flow>:<pattern>` convention where:

- `flow` is one of: `action`, `report`, `job`
- `pattern` is a glob string supporting `*` as a wildcard
- The bare `*` is the global catch-all (matches any requirement)

Examples:

| Capability        | Matches                                 |
|-------------------|-----------------------------------------|
| `*`               | everything                              |
| `action:*`        | any action type                         |
| `action:auto-tag` | only the `auto-tag` action type         |
| `action:git-*`    | action types starting with `git-`       |
| `report:*`        | any report                              |
| `report:daily-*`  | reports with slugs starting with `daily-` |
| `job:*`           | any scheduled job                       |
| `job:github-*`    | scheduled jobs with slugs starting with `github-` |

Matching is case-insensitive. The `*` character matches any sequence of characters; no
other glob special characters are supported.

### Capability Requirement Strings

When routing a workload, the system constructs a requirement string:

| Flow             | Requirement string                | Example                        |
|------------------|-----------------------------------|--------------------------------|
| Action type task | `action:<actionType.name>`        | `action:auto-tag`              |
| Report           | `report:<definition.slug>`        | `report:daily-github-activity` |
| Scheduled job    | `job:<job.slug>`                  | `job:test-scheduled-job`       |

An agent matches if any of its capability patterns glob-match the requirement string.

### Slug Field for Reports & Scheduled Jobs

Report definitions and scheduled jobs gain a `slug` field — a stable, URL-safe identifier
used in capability requirement strings.

- Optional — if not set, the system generates one by slugifying the name (lowercase,
  spaces to hyphens, strip special characters)
- Must be unique within its entity type
- Validated: lowercase alphanumeric and hyphens only, no spaces
- Exposed in the REST API and editable in the UI on the Info tab

Action types do not need a slug — their existing name is already used as the identifier.

### Storage: `agent_capability` Table

Replace the comma-separated `TEXT` column with a dedicated join table:

```sql
CREATE TABLE agent_capability (
    agent_id BIGINT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    capability VARCHAR(255) NOT NULL,
    PRIMARY KEY (agent_id, capability)
);
```

Each row is one capability pattern for one agent. The composite primary key ensures
uniqueness. `ON DELETE CASCADE` cleans up capabilities when an agent is deleted.

### AgentPool Matching

`AgentPool.tryLease()` matching changes:

1. **Explicit agent assignment** — if a specific agent ID is provided by the caller
2. **Glob-match against capabilities** — first idle, enabled agent with a capability
   pattern that matches the requirement string
3. **No fallback** — if no agent matches, the workload fails with a clear error
   indicating the missing capability

The previous behavior of falling back to any idle agent (with a warning) is removed.
Strict matching is enforced.

### Caller Changes

| Caller                         | Current behavior       | New behavior                          |
|--------------------------------|------------------------|---------------------------------------|
| `TaskExecutionService`         | passes `actionType`    | passes `action:<actionType.name>`     |
| `ReportExecutionService`       | passes `null`          | passes `report:<definition.slug>`     |
| `ScheduledJobExecutionService` | passes `null`          | passes `job:<job.slug>`               |
| `ManagerService`               | direct SPI call        | unchanged (no pooling)                |
| Utility AI services            | direct SPI call        | unchanged (no pooling)                |

### Manager Prompt

Remove agent capabilities from `ManagerPromptBuilder.formatAgents()`. The Manager
decides *what* action to take; the Agent Pool decides *who* does it. These concerns are
separated — the Manager should not reason about agent capability patterns.

### Seed Data

Both default agents receive a single capability row: `*` (global catch-all). This
ensures everything works out of the box without configuration.

### DB Migration: `V46__agent_capabilities_and_slugs.sql`

1. Create `agent_capability` table
2. Migrate existing comma-separated `capabilities` values into rows:
   - `*` stays as `*`
   - Other values (e.g. `auto-tag`) become `action:<value>`
3. Drop `capabilities` column from `agent` table
4. Add `slug VARCHAR(255)` to `report_definition` and `scheduled_job` tables
5. Backfill slugs from existing names
6. Add `UNIQUE(slug)` constraints on both tables

### OpenAPI Spec Changes

- Agent schema: `capabilities` remains `string[]` in request/response (backed by join
  table instead of comma-separated column)
- Report definition schema: add `slug` string field
- Scheduled job schema: add `slug` string field

### UI Changes

**Agent Detail Page — Capabilities tab:**
- Free-form input accepting `flow:pattern` strings
- Helper text explaining the format
- Autocomplete suggestions organized by prefix:
  - `action:` — existing action type names
  - `report:` — existing report definition slugs
  - `job:` — existing scheduled job slugs
  - Plus wildcard variants (`action:*`, `report:*`, `job:*`, `*`)
- Validation: warn (not block) if a pattern doesn't match `flow:pattern` format or `*`

**Agents list page:**
- Capabilities column renders patterns as chips (no structural change)

**Report Definition & Scheduled Job detail pages:**
- Add `Slug` field on the Info tab, below the name
- Text input with auto-generated default from the name
- Helper text: "Stable identifier used for agent capability matching"

## Scope

**In scope:**
- `agent_capability` table and entity
- Slug field on report definitions and scheduled jobs
- Glob matching logic in AgentPool
- Caller migration (TaskExecutionService, ReportExecutionService,
  ScheduledJobExecutionService)
- Remove capabilities from Manager prompt
- UI updates for capabilities editor and slug fields
- DB migration with data migration
- OpenAPI spec updates

**Out of scope:**
- Capability-based filtering in the Manager's decision logic
- Priority/preference weighting on capabilities
- Capability descriptions or metadata beyond the pattern string
- Changes to script-mode scheduled job execution
- Changes to the event pipeline or scheduling
