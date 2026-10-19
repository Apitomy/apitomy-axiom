# Built-in Session Template Overrides — Design

## Goal

Allow users to customize a limited set of settings on the built-in AI Session Templates without cloning
them, while still receiving upstream improvements (system prompts, tools, capabilities) on upgrade.

## Scope

Editable on built-in templates: `model`, `engine`, `environment`.

All other fields (`systemPrompt`, `mcpServers`, `allowedTools`, `workingDirectory`, `initScript`,
`initScriptType`, `welcomeMessage`, `initialMessage`, `name`, `description`, `templateId`,
`capabilities`) remain locked. Cloning remains the escape hatch for deeper customization.

## Data Model

- New Flyway migration `V2__session_template_overrides.sql` creating table `session_template_override`:
  - `id` (PK, sequence-backed, consistent with existing Panache entities)
  - `template_id` VARCHAR, unique, not null — the built-in template ID
  - `model` VARCHAR, nullable
  - `engine` VARCHAR, nullable
  - `environment` TEXT, nullable — JSON string, same format as `SessionTemplateEntity.environment`
- New entity `SessionTemplateOverrideEntity` in `core/.../entities/`.
- Semantics: a null field means "use the built-in default". The `environment` override **replaces** the
  default map (no merging).
- When all three fields are null/empty after a save, the row is deleted.
- Rows whose `template_id` no longer matches a built-in (e.g. a built-in removed in a later release) are
  ignored at read time.

## Service Layer (`SessionTemplateService`)

- `getTemplate(id)` and `listTemplates()` return built-ins with overrides merged in. Consumers such as
  `AssistantSessionManager.createSession` require no changes.
- The returned `SessionTemplate` record gains `overriddenFields` (`Set<String>`, values from
  `model`, `engine`, `environment`; empty for user templates and un-overridden built-ins).
- New methods:
  - `getOverrides(String templateId)` — returns current overrides (fields may be null).
  - `setOverrides(String templateId, SessionTemplateOverrides overrides)` — upserts; returns merged
    template.
  - `clearOverrides(String templateId)` — deletes the row if present.
- Errors: unknown ID → not-found exception; non-built-in ID → `IllegalArgumentException` (mapped to 400).

## REST API (contract-first, `common/api/src/main/resources/openapi.json`)

New path `/assistant/templates/{templateId}/overrides` (tag `Assistant`):

| Op | operationId | Request | Response | Errors |
|---|---|---|---|---|
| GET | `getSessionTemplateOverrides` | — | 200 `SessionTemplateOverrides` | 404 unknown, 400 not built-in |
| PUT | `updateSessionTemplateOverrides` | `SessionTemplateOverrides` | 200 `SessionTemplate` (merged) | 404, 400 |
| DELETE | `deleteSessionTemplateOverrides` | — | 204 | 404, 400 |

New schema `SessionTemplateOverrides`:

- `model`: string, nullable
- `engine`: string, nullable
- `environment`: object (string → string), nullable; values may use `${secret:NAME}`

`SessionTemplate` schema gains read-only `overriddenFields: string[]`.

The existing `PUT`/`DELETE /assistant/templates/{templateId}` continue returning 403 for built-ins.

Implementation in `AssistantResourceImpl` using generated beans from `io.apitomy.axiom.api.beans`.

## Import / Export

Overrides are **not** included in configuration packs. They are treated as deployment-local settings.
`ImportExportService` and `ConfigurationPacksPage` are unchanged.

## UI

- `ui/src/config/api.ts`: add `SessionTemplateOverrides` type, `overriddenFields` on `SessionTemplate`,
  and `getSessionTemplateOverrides` / `updateSessionTemplateOverrides` /
  `deleteSessionTemplateOverrides` functions.
- `SessionTemplateDetailPage.tsx`:
  - For built-ins, `model`, `engine` and the Environment tab are editable; all other fields stay
    read-only.
  - Save on a built-in calls `PUT .../overrides` with only the three fields.
  - Overridden fields show a "Modified" label.
  - "Reset to defaults" button (shown only when `overriddenFields` is non-empty) calls `DELETE` after
    confirmation, then reloads.
- `SessionTemplatesPage.tsx`: built-ins with non-empty `overriddenFields` show a "Customized" label next
  to "Built-in".

## Testing (JUnit 5)

- Service: merge logic (each field individually, all fields, none), empty save deletes row,
  `clearOverrides`, rejection of non-built-in IDs, orphaned override rows ignored.
- REST: GET/PUT/DELETE happy paths, 404 unknown ID, 400 user template, `overriddenFields` populated in
  `GET /assistant/templates` and `GET /assistant/templates/{id}`.
- Session creation: a session created from an overridden built-in uses the overridden model and engine.
