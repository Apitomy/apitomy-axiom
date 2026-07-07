# AI Assistant Session Templates — Design Specification

**Date:** 2026-07-07
**Status:** Approved

## Overview

The AI Assistant is currently hardcoded for a single use case: creating Axiom configuration items (Tools,
Action Types, Report Definitions). This design introduces **Session Templates** — configurable definitions
that control an AI Assistant session's persona, tools, and working environment. Templates enable the
assistant to support arbitrary tasks beyond Axiom configuration while preserving the existing Config
Assistant experience as a built-in template.

### Goals

- Allow the user to create AI Assistant sessions for arbitrary tasks, not just Axiom configuration
- Introduce a template model that defines the session's system prompt, tools, MCP servers, and working
  directory
- Ship built-in templates as immutable classpath resources; support user-defined templates in the database
- Preserve all existing Config Assistant functionality (sidebar, Apply, validation) as template-specific
  behavior
- Reuse the existing chat infrastructure (SSE, Claude Code subprocess, permission prompts, tool use
  blocks) without modification

### Non-Goals

- Multi-user support (Axiom is single-user)
- Generalizing the sidebar/items panel for other templates (future iteration)
- Template setup scripts or lifecycle hooks (future iteration)
- Message history persistence across page refreshes (separate concern)

## Data Model

### Session Template

A Session Template defines the configuration for an AI Assistant session. Templates come from two sources:

**Built-in templates** are JSON files bundled in `src/main/resources/templates/assistant-templates/`. They
are loaded at application startup, always appear in the template list, and cannot be modified or deleted.
Each has a stable `id` that persists across application versions.

**User-defined templates** are stored in the database as `SessionTemplateEntity` records. They support
full CRUD and can be created from scratch or cloned from a built-in template.

### Template Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Yes | Unique identifier. Stable slug for built-ins (e.g., `axiom-config-assistant`), generated UUID for user-defined. |
| `name` | String | Yes | Display name (e.g., "Configuration Assistant"). |
| `description` | String | Yes | Brief description shown in the template picker. |
| `builtIn` | boolean | — | Derived field, true for classpath templates. Not stored in the database. |
| `systemPrompt` | String (TEXT) | Yes | Markdown content written to `CLAUDE.md` in the session's working directory. |
| `welcomeMessage` | String (TEXT) | No | First message shown in the chat UI, attributed to the assistant. If empty, no welcome message is displayed. |
| `workingDirectory` | String | No | Absolute path to an existing directory. If null, Axiom creates a temporary directory under `~/.axiom/assistant-sessions/{sessionId}/`. |
| `mcpServers` | List\<String\> | No | Names of `McpServerEntity` records to include in the session's MCP config. Resolved at session creation time. |
| `toolsets` | List\<String\> | No | Names of `ToolsetEntity` records. All tools in the referenced toolsets are added to `--allowedTools`. |
| `allowedTools` | List\<String\> | No | Additional explicit tool patterns for `--allowedTools` (e.g., `Read(*)`, `Write(*)`, `Bash(ls *)`). |

### Built-in Template: Configuration Assistant

The existing hardcoded Config Assistant becomes the first built-in template with ID
`axiom-config-assistant`. Its `systemPrompt` field contains the current `CLAUDE.md` content from
`AssistantContextBuilder.buildClaudeMd()`. Its `allowedTools` contains the current auto-approved tool
list.

**Special MCP server handling:** The Config Assistant uses a purpose-built MCP server that is installed
from classpath resources to `~/.axiom/assistant-mcp-server/` (not an `McpServerEntity` in the database).
This server is referenced in the built-in template's JSON file via a special marker (e.g.,
`"mcpServers": ["@axiom-assistant"]`). The `AssistantSessionManager` recognizes this marker and handles
the installation and MCP config generation for this built-in server, while regular `mcpServers` entries
are resolved from `McpServerEntity` records. This keeps the existing installation logic intact without
requiring the user to manually configure the Axiom assistant MCP server as a database entity.

The sidebar, Apply button, validation listener, and item management features are **not** template fields.
They are feature-flagged in both the backend and frontend by checking whether the session's `templateId`
equals `axiom-config-assistant`.

### Database Schema

**Table: `session_template`**

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | BIGINT | PK (Panache-managed sequence) |
| `template_id` | VARCHAR | NOT NULL, UNIQUE |
| `name` | VARCHAR | NOT NULL |
| `description` | TEXT | NOT NULL |
| `system_prompt` | TEXT | NOT NULL |
| `welcome_message` | TEXT | |
| `working_directory` | VARCHAR | |

**Table: `session_template_mcp_server`** (collection table)

| Column | Type | Constraints |
|--------|------|-------------|
| `session_template_id` | BIGINT | FK → session_template.id |
| `mcp_server_name` | VARCHAR | NOT NULL |

**Table: `session_template_toolset`** (collection table)

| Column | Type | Constraints |
|--------|------|-------------|
| `session_template_id` | BIGINT | FK → session_template.id |
| `toolset_name` | VARCHAR | NOT NULL |

**Table: `session_template_allowed_tool`** (collection table)

| Column | Type | Constraints |
|--------|------|-------------|
| `session_template_id` | BIGINT | FK → session_template.id |
| `tool_pattern` | VARCHAR | NOT NULL |

Flyway migration: `V24__create_session_template.sql`

## REST API

### Template Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/assistant/templates` | List all templates (built-in + user-defined). |
| `GET` | `/api/v1/assistant/templates/{templateId}` | Get a single template by its template ID. |
| `POST` | `/api/v1/assistant/templates` | Create a user-defined template. Returns 201. |
| `PUT` | `/api/v1/assistant/templates/{templateId}` | Update a user-defined template. Returns 403 for built-ins. |
| `DELETE` | `/api/v1/assistant/templates/{templateId}` | Delete a user-defined template. Returns 403 for built-ins. |

All endpoints return template objects with the `builtIn` field set accordingly.

### Session Creation Changes

`POST /api/v1/assistant/sessions` request body changes from:

```json
{ "name": "session-name" }
```

to:

```json
{ "name": "session-name", "templateId": "axiom-config-assistant" }
```

`templateId` is **required**. The backend resolves the template and uses its configuration to set up the
session.

### Session Info Changes

`AssistantSessionInfo` gains a `templateId` field so the frontend can determine which template a session
is running and adjust the UI accordingly.

## Backend Architecture

### New Components

**`SessionTemplateService`** (`@ApplicationScoped`)

Central service for template lifecycle:
- On startup, loads built-in templates from `src/main/resources/templates/assistant-templates/*.json`
  into an in-memory map
- For list operations, merges built-in templates with `SessionTemplateEntity` records from the database
- For lookups by ID, checks built-ins first, then database
- CRUD operations for user-defined templates (rejects modifications to built-ins)
- Resolves `mcpServers` names to `McpServerEntity` records and `toolsets` names to `ToolsetEntity`
  records at session creation time

**`SessionTemplateEntity`** (Panache entity)

Database entity for user-defined templates. Fields mirror the template data model. List fields
(`mcpServers`, `toolsets`, `allowedTools`) use `@ElementCollection` with `@CollectionTable`.

**`SessionTemplateResource`** (JAX-RS, `@RunOnVirtualThread`)

REST resource for template CRUD. Delegates to `SessionTemplateService`. Returns 403 for PUT/DELETE on
built-in templates.

**Built-in template files**

JSON files in `src/main/resources/templates/assistant-templates/`. Example:
`axiom-config-assistant.json` containing the current hardcoded Config Assistant configuration (system
prompt, welcome message, MCP servers, allowed tools).

### Changes to Existing Components

**`AssistantSessionManager`**

- `createSession(String name, String templateId)` — Now takes a `templateId` parameter. Resolves the
  template via `SessionTemplateService`, then uses its fields to configure the session.
- `buildCommand(Path workDir, ResolvedTemplate template)` — Allowed tools come from the template's
  resolved toolsets + explicit `allowedTools` list instead of a hardcoded list.
- MCP config is built from the template's `mcpServers` references (resolved to actual entity records
  with their command/args/env) instead of hardcoding the Axiom assistant MCP server.
- Working directory uses the template's `workingDirectory` if set, otherwise creates a temp dir under
  `~/.axiom/assistant-sessions/`.
- The validation listener, item listing, item content retrieval, and apply logic become conditional —
  only activated when `templateId` is `axiom-config-assistant`.
- The Axiom assistant MCP server installation (`ensureAssistantMcpServerInstalled`) only runs when the
  template references it.

**`AssistantContextBuilder`**

- The hardcoded `buildClaudeMd()` method is removed. Replaced by writing the template's `systemPrompt`
  field directly to `CLAUDE.md`.
- The `buildMcpConfig(Path mcpServerDir)` method is generalized to accept a list of resolved MCP server
  configurations and build the config from them.
- The hardcoded subdirectory creation (`tools/`, `action-types/`, `report-definitions/`) becomes
  conditional on the template being `axiom-config-assistant`.

**`AssistantSession`**

- Gains a `templateId` field, set at construction time and returned in session info responses.

**`AssistantSessionResource`**

- `createSession` reads `templateId` from the request body and passes it to
  `AssistantSessionManager.createSession()`.
- `toSessionInfo()` includes the `templateId` in the response.

### Flyway Migration

`V24__create_session_template.sql` creates the `session_template` table and its three collection tables.

## Frontend Architecture

### Template Management UI

Located in the **Configuration** section of the main Axiom navigation, under a new **AI Assistant**
subsection. This follows the same pattern as the existing Configuration pages for Tools, Action Types,
MCP Servers, etc.

**Template list page:**
- Shows all templates (built-in + user-defined) in a table or card layout
- Each template shows name, description, and a badge indicating built-in vs. custom
- Actions: Create (for new user-defined templates), Edit (user-defined only), Clone (for built-ins),
  Delete (user-defined only, with confirmation)

**Template detail/edit page:**
- Fields: name, description, system prompt (code editor component with markdown mode), welcome message
  (textarea), working directory (text input, optional), MCP servers (multi-select from existing
  entities), toolsets (multi-select from existing entities), allowed tools (editable list of text
  entries)
- For built-in templates: all fields are read-only, with a "Clone" button to create an editable copy

### Session Creation Flow

The current flow (click "New Session" → name modal → create) changes to:

1. Click "New Session" on the Assistant page
2. **Template picker** appears — a modal or inline selection showing available templates as cards with
   name + description
3. User selects a template
4. **Name modal** appears with a pre-generated session name (random words, as today)
5. On "Create", `createAssistantSession(name, templateId)` is called
6. Navigate to the session page

### Conditional UI in Session Page

`AssistantSessionPage` fetches the session info (which now includes `templateId`) and conditionally
renders:

- **If `templateId === "axiom-config-assistant"`**: Two-panel layout with chat (70%) +
  `AssistantGeneratedItems` sidebar (30%), "Apply All" button in the header, "End Session" button
- **Otherwise**: Full-width chat panel, "End Session" button only (no sidebar, no Apply)

### Welcome Message

The hardcoded welcome message in `AssistantChatPanel` is replaced by the template's `welcomeMessage`
field. The frontend fetches this from the template data (either embedded in the session info response or
fetched separately via the template API). If the template has no welcome message, the chat starts empty.

### API Client Updates

New functions in `api.ts`:
- `fetchAssistantTemplates(): Promise<SessionTemplate[]>`
- `fetchAssistantTemplate(templateId: string): Promise<SessionTemplate>`
- `createAssistantTemplate(data: CreateSessionTemplateRequest): Promise<SessionTemplate>`
- `updateAssistantTemplate(templateId: string, data: UpdateSessionTemplateRequest): Promise<SessionTemplate>`
- `deleteAssistantTemplate(templateId: string): Promise<void>`

Updated function:
- `createAssistantSession(name: string, templateId: string): Promise<AssistantSessionInfo>`

### Routing

New routes:
- `/configuration/ai-assistant` — Template list page
- `/configuration/ai-assistant/:templateId` — Template detail/edit page

Existing routes unchanged:
- `/assistant` — Session list page
- `/assistant/:sessionId` — Active session page

## Migration Path

The existing hardcoded Config Assistant behavior is preserved exactly:

1. The current `CLAUDE.md` content moves from `AssistantContextBuilder.buildClaudeMd()` into the
   built-in template file `axiom-config-assistant.json`
2. The current hardcoded MCP server setup and allowed tools move into the same template file
3. The sidebar, Apply, validation, and item management code stays in place but becomes conditional on
   `templateId === "axiom-config-assistant"`
4. Existing sessions (if any are running) would not survive a server restart anyway (they're in-memory),
   so no data migration is needed for sessions
5. No existing database tables are modified — only new tables are added

## Future Iterations

These are explicitly out of scope for this design but noted as natural next steps:

- **Template setup scripts** — Run bash scripts in the session working directory when a session is
  created (e.g., clone a repo, install dependencies)
- **Generalizable sidebar** — Configurable sidebar panels for templates beyond the Config Assistant
- **Message history persistence** — Store conversation history in the database so it survives page
  refreshes
- **Session resume** — Allow reconnecting to a session's Claude Code process after navigating away
- **Template variables** — Support placeholder substitution in system prompts (e.g., `{{projectName}}`)
- **Template categories/tags** — Organize templates as the collection grows
