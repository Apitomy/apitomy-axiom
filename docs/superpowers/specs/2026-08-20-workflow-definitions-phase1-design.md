# Workflow Definitions — Phase 1 Design

**Date:** 2026-08-20
**Epic:** Custom Workflow Feature for Axiom

## Summary

Add workflow definition management and a visual editor to Axiom, powered by
[Apitomy Flow](~/git/apitomy/apitomy-flow). Users create workflow definitions using a
drag-and-drop editor, save drafts, and publish immutable versioned snapshots. This is Phase 1
of a multi-phase effort — later phases add execution, human tasks, event correlation, and wait
node support.

Apitomy Flow provides:
- **Java engine** (`io.apitomy:apitomy-flow-engine`) — stateless workflow execution engine with
  validation
- **React UI** (`@apitomy/flow-ui`) — `WorkflowEditor` (drag-and-drop editor) and
  `WorkflowViewer` (read-only instance viewer)

Phase 1 uses the engine for server-side validation on publish, and the `WorkflowEditor`
component for the visual editor UI.

## Data Model

### `workflow_definition` table

Stores the mutable draft and metadata for each workflow definition. Workflow definitions are
global to Axiom (not project-scoped).

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | `bigint` | PK, auto-generated | |
| `name` | `varchar` | NOT NULL, UNIQUE | Human-readable name |
| `description` | `text` | | Optional description |
| `content` | `text` | | Current draft workflow JSON (nodes, edges, positions) |
| `current_version` | `int` | | Latest published version number; null if never published |
| `created_on` | `timestamp` | NOT NULL | |
| `updated_on` | `timestamp` | NOT NULL | |

### `workflow_definition_version` table

Stores immutable published snapshots. All versions are kept indefinitely.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | `bigint` | PK, auto-generated | |
| `definition_id` | `bigint` | FK → workflow_definition, NOT NULL | |
| `version` | `int` | NOT NULL | Version number (1, 2, 3...) |
| `content` | `text` | NOT NULL | Frozen workflow JSON at publish time |
| `created_on` | `timestamp` | NOT NULL | When published |

**Constraints:** Unique index on `(definition_id, version)`.

### Versioning model

- Each workflow definition has a single mutable **draft** (the `content` column on
  `workflow_definition`).
- The user can **publish** the draft at any time, which copies the current `content` to a new
  row in `workflow_definition_version` with an incremented version number.
- Published versions are **immutable** — they cannot be edited or deleted.
- Running workflow instances (Phase 2) will be pinned to the version they started with.

## API Design

All endpoints follow the contract-first approach: defined in `openapi.json` first, then
generated interfaces are implemented.

### Workflow Definition endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/workflow-definitions` | List all definitions (paginated, searchable by name) |
| `POST` | `/api/v1/workflow-definitions` | Create a new definition |
| `GET` | `/api/v1/workflow-definitions/{id}` | Get a definition (returns draft content) |
| `PUT` | `/api/v1/workflow-definitions/{id}` | Update definition metadata (name, description) |
| `DELETE` | `/api/v1/workflow-definitions/{id}` | Delete a definition (reject if instances exist) |
| `PUT` | `/api/v1/workflow-definitions/{id}/content` | Update draft workflow content |
| `POST` | `/api/v1/workflow-definitions/{id}/publish` | Publish current draft as a new immutable version |

### Workflow Definition Version endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/workflow-definitions/{id}/versions` | List all published versions |
| `GET` | `/api/v1/workflow-definitions/{id}/versions/{version}` | Get a specific version's content |

### Request/Response schemas

**WorkflowDefinition** (response):
```json
{
  "id": 1,
  "name": "CVE Triage",
  "description": "Standard CVE triage and remediation workflow",
  "content": { "id": "...", "name": "...", "nodes": [...], "edges": [...] },
  "currentVersion": 3,
  "createdOn": "2026-08-20T12:00:00Z",
  "updatedOn": "2026-08-20T14:30:00Z"
}
```

- `content` is the draft workflow JSON (the Apitomy Flow `Workflow` object).
- `currentVersion` is null if never published.

**NewWorkflowDefinition** (create request):
```json
{
  "name": "CVE Triage",
  "description": "Standard CVE triage and remediation workflow"
}
```

- `content` is not provided on create — the backend initializes an empty workflow containing a
  single start node and a single end node connected by a default edge, with sensible default
  positions.

**UpdateWorkflowDefinition** (update metadata request):
```json
{
  "name": "CVE Triage v2",
  "description": "Updated description"
}
```

**WorkflowDefinitionContent** (update content request — `PUT .../content`):

The request body is the raw Apitomy Flow `Workflow` JSON object:
```json
{
  "id": "wf-1",
  "name": "CVE Triage",
  "nodes": [...],
  "edges": [...]
}
```

**WorkflowDefinitionVersion** (response):
```json
{
  "id": 10,
  "definitionId": 1,
  "version": 3,
  "content": { "id": "...", "name": "...", "nodes": [...], "edges": [...] },
  "createdOn": "2026-08-20T14:30:00Z"
}
```

### Publish behavior

`POST /api/v1/workflow-definitions/{id}/publish`:

1. Load the definition's current draft `content`.
2. Run Apitomy Flow's `WorkflowValidator` against the content.
3. If there are ERROR-level validation problems, return **400** with the validation errors.
4. Otherwise, create a new `workflow_definition_version` row with `version =
   currentVersion + 1` (or 1 if never published).
5. Update `workflow_definition.current_version` to the new version number.
6. Return the new `WorkflowDefinitionVersion`.

### Delete behavior

`DELETE /api/v1/workflow-definitions/{id}`:

- Returns **409 Conflict** if any workflow instances reference this definition (Phase 2 will
  add this check). For now, deletes are always allowed.
- Deleting a definition also deletes all its published versions (cascade).

## Backend Implementation

### Entities

**`WorkflowDefinitionEntity`** — Panache entity in the `core` module:
- Maps to `workflow_definition` table
- `content` field is a `String` storing the raw JSON

**`WorkflowDefinitionVersionEntity`** — Panache entity in the `core` module:
- Maps to `workflow_definition_version` table
- `content` field is a `String` storing the frozen JSON

### REST Resource

**`WorkflowDefinitionsResourceImpl implements WorkflowDefinitionsResource`** in the `app`
module's `rest` package. Follows the existing pattern: generated interface from OpenAPI, impl
class with no `@Path` annotations.

### Validation on publish

The `app` module depends on `io.apitomy:apitomy-flow-engine`. On publish:

1. Deserialize the draft `content` JSON into a Flow `Workflow` object using Jackson.
2. Call `WorkflowValidator.validate(workflow)`.
3. Filter for ERROR-severity problems.
4. If any exist, return 400 with the problems as the response body.

Draft saves (`PUT .../content`) do **not** run server-side validation. The `WorkflowEditor`
component runs client-side validation in real time.

### Maven dependency

Add to `app/pom.xml`:
```xml
<dependency>
    <groupId>io.apitomy</groupId>
    <artifactId>apitomy-flow-engine</artifactId>
    <version>${flow.version}</version>
</dependency>
```

## UI Implementation

### Navigation

Add "Workflows" as a new item under the **Components** expandable nav section, alongside
Action Types, Tools, Toolsets, etc.

### Workflow Definitions List Page

**Route:** `/components/workflows`

- Table with columns: Name, Description, Current Version (show "Draft" if never published),
  Updated
- "Create Workflow" button opens a modal with Name and Description fields
- Click a row to navigate to the detail page
- Search/filter by name

### Workflow Definition Detail Page

**Route:** `/components/workflows/{id}`

- **Breadcrumb:** Components > Workflows > {name}
- **Header area:**
  - Name and description (editable via an edit button/modal)
  - Current version badge (e.g., "v3" or "Draft")
  - **Save** button — disabled when content is clean, enabled when dirty. Visual indicator
    (e.g., dot or "Unsaved changes") when dirty.
  - **Publish** button — disabled when content is dirty (must save first) or when there are
    ERROR-level validation problems. Calls `POST .../publish`.
  - **Delete** button
- **Main content:** Embedded `WorkflowEditor` component from `@apitomy/flow-ui`
  - Fills the remaining viewport height (container with explicit dimensions)
  - `workflow` prop: the current draft content
  - `onChange` callback: stores updated content in component state and sets dirty flag
  - `onValidationChange` callback: tracks validation problems to control Publish button state
  - `spi.actionTypes`: async function that fetches Axiom action types from the existing
    `GET /api/v1/action-types` endpoint and maps them to `ActionTypeDescriptor` objects. In
    Phase 1, all action types are provided. In Phase 2, this will be filtered to only those
    with an `outputSchema`.
  - `theme`: matched to Axiom's current light/dark mode setting
- **Version history:** Collapsible section listing published versions with version number and
  timestamp.
- **Unsaved changes guard:** Browser `beforeunload` warning if navigating away with dirty
  content.

### npm dependencies

Add to `ui/package.json`:
- `@apitomy/flow-ui` — the visual editor and viewer components
- `@xyflow/react` — peer dependency required by `@apitomy/flow-ui`

Import in the application:
- `@apitomy/flow-ui/style.css`
- `@xyflow/react/dist/style.css`

## Testing

### Backend integration tests

**`WorkflowDefinitionsResourceTest`** — REST integration test (Quarkus `@QuarkusTest`),
following the same pattern as `ProjectsResourceTest`:

- Create a workflow definition — verify 200, returned fields
- Get a definition by ID — verify content matches
- List definitions — verify pagination and search by name
- Update metadata (name, description) — verify fields updated
- Update draft content — verify content persisted
- Publish a valid workflow — verify version created, `currentVersion` incremented
- Publish an invalid workflow — verify 400 with validation errors
- List versions — verify version history
- Get a specific version — verify frozen content matches
- Delete a definition — verify removed
- Delete cascades versions — verify version rows removed

### Manual browser testing

- Create workflow, add nodes/edges in the editor, save, publish
- Verify dirty indicator and Save/Publish button states
- Verify validation problems block publish
- Verify version history updates after publish
- Verify light/dark theme consistency

## Future phases

This spec covers Phase 1 only. Subsequent phases will add:

- **Phase 2:** Workflow execution — triggering, action node execution (async), instance viewer
- **Phase 3:** Human task nodes — inbox integration, task forms
- **Phase 4:** Event correlation — receive-event nodes, wait nodes
- **Phase 5:** Polish — audit/history view, error handling, edge cases
