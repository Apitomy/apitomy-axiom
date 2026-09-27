# Action Type Inputs/Outputs for Flow Workflows — Design

**Date:** 2026-08-31
**Status:** Approved (design); pending implementation plan
**Area:** Action Types, Apitomy Flow workflow integration

## Summary

Axiom Action Types can be used as **action nodes** in an Apitomy Flow workflow, but a Flow action node
is defined in terms of typed **inputs** and **outputs**, and an Action Type currently has no first-class
way to declare either. Today the only machine-readable I/O contract on an Action Type is a single
free-text `inputSchema` string that is stored and round-tripped but never parsed, validated, or
enforced anywhere; there is no output concept at all. As a result Axiom's Flow SPI provider supplies
only `value`/`label`/`description` to the editor, and workflow authors must hand-author each node's
input bindings and output rows with no guidance.

This design gives Action Types an optional, structured way to declare inputs and outputs that aligns
1:1 with Flow's node contract, wires those declarations into the Flow editor, and plumbs them through
execution end-to-end (named input template binding on the way in, a validated JSON output contract on
the way out). It also adds a `workflowEnabled` opt-in flag so authors curate which Action Types appear
in the Flow palette.

## Goals

- Let an Action Type optionally declare typed **inputs** and **outputs**.
- Feed those declarations into the Flow editor so node input-binding and output rows are auto-generated.
- Enforce the declared contract at runtime: validate resolved inputs before running, validate produced
  outputs after.
- Make each named input individually addressable in prompt/script templates.
- Auto-append the output contract to the agent prompt so authors do not hand-write "return JSON like…".
- Let authors curate which Action Types are eligible for use as workflow nodes.
- Preserve full backward compatibility: Action Types that declare nothing behave exactly as today.

## Non-Goals

- No change to how Flow itself renders nodes, ports, or edges — we only supply data it already consumes.
- No dedicated MCP `set-output` tool; output capture uses a JSON result convention (see §3).
- No nested/complex schema language (enums, nested objects) beyond Flow's flat type union.
- No retroactive breaking of published workflow definitions when a flag is later toggled off.

## Background: current state

Established during design exploration (file references current as of this date):

- **Action Type model** — OpenAPI schemas `ActionType` / `NewActionType`
  (`common/api/src/main/resources/openapi.json`), entity
  `core/src/main/java/io/apitomy/axiom/core/entities/ActionTypeEntity.java` (`@Table("action_type")`),
  validator `core/src/main/java/io/apitomy/axiom/core/services/ActionTypeValidator.java`, REST impl
  `app/src/main/java/io/apitomy/axiom/app/rest/ActionResourceImpl.java`. The entity has `inputSchema`
  (TEXT, `input_schema`) but no output field. `labels` are persisted via an `@ElementCollection`
  child table `action_type_label` (migration `V35`) — the precedent this design follows.
- **Flow contract** — `@apitomy/flow-ui` `ActionTypeDescriptor` carries optional
  `inputs?: ActionTypeField[]` and `outputs?: ActionTypeField[]`, where
  `ActionTypeField = { name, type: 'string'|'number'|'boolean'|'object', required?, description? }`.
- **SPI gap** — `ui/src/pages/WorkflowDefinitionDetailPage.tsx` implements the `EditorSpi.actionTypes`
  provider by mapping each Axiom Action Type to `{ value, label, description }` only; `inputs`/`outputs`
  are left empty, so the editor falls back to free-form authoring.
- **Execution bridge** — `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java` turns a
  waiting action node into a `TaskEntity` whose `actionType` is the node's action type name and whose
  `input` is the JSON of the engine-resolved `config.inputs` map;
  `app/src/main/java/io/apitomy/axiom/app/TaskExecutionService.java` runs the task against the matching
  `ActionTypeEntity` (agent prompt via `promptTemplate` / `{{managerInput}}`, or script via
  `scriptTemplate`). On completion `task.output` (a string) is parsed into a map and returned as
  `NodeResult.output`, which the engine merges into workflow context for downstream nodes.

## Design

### 1. Declaration model & API

Add two optional, ordered collections and one flag to the Action Type.

**Fields**

- `inputs: ActionTypeField[]` — ordered list of declared inputs.
- `outputs: ActionTypeField[]` — ordered list of declared outputs.
- `workflowEnabled: boolean` — opt-in eligibility as a Flow workflow node (default `false`).

**`ActionTypeField` shape** (1:1 with Flow, so the SPI mapping is a passthrough):

| Field | Type | Notes |
|-------|------|-------|
| `name` | string (required) | Must be a valid identifier (usable as a template key / EL identifier). |
| `type` | enum (required) | `string` \| `number` \| `boolean` \| `object`. Mirrors Flow exactly. |
| `required` | boolean | Defaults to `false`. |
| `description` | string | Optional. |

**OpenAPI first** (per the repo's contract-first rule): define an `ActionTypeField` schema and add
`inputs`, `outputs`, and `workflowEnabled` to both `ActionType` and `NewActionType`. Regenerate the
JAX-RS interfaces and beans. Remove `inputSchema` from both schemas.

**Persistence** — follow the `action_type_label` precedent:

- Map `inputs` and `outputs` as `@ElementCollection` of an `@Embeddable` `ActionTypeField`, into child
  tables `action_type_input` and `action_type_output`, each with columns
  `(action_type_id, ordinal, name, type, required, description)`. Use `@OrderColumn(name = "ordinal")`
  to preserve author order. `@JoinColumn(name = "action_type_id")` with `ON DELETE CASCADE`.
- Add `workflow_enabled BOOLEAN NOT NULL DEFAULT FALSE` to `action_type`.
- Drop the `input_schema` column.

Child tables (rather than a JSON TEXT column like `environment`) were chosen because author order is
meaningful and the fields benefit from being real, queryable rows; it also matches the existing label
pattern.

**Validation** — extend `ActionTypeValidator`:

- Within each of `inputs` and `outputs`: field `name` required and a valid identifier; `name`s unique
  within the list; `type` is one of the allowed values.
- `inputSchema` validation (currently absent) is removed along with the field.

### 2. Flow editor wiring & palette curation

**SPI provider** (`WorkflowDefinitionDetailPage.tsx`): extend the `ActionTypeDescriptor` mapping to
populate `inputs` and `outputs` from the Action Type's new fields. Because the Axiom `ActionTypeField`
shape matches Flow's, this is a near-passthrough map (`{ name, type, required, description }`), not a
parse. Update the frontend `ActionType` interface in `ui/src/config/api.ts` to add `inputs`,
`outputs`, and `workflowEnabled`, and remove `inputSchema`.

**Behavioral effect**: once a node's `config.actionType` matches a descriptor that carries `inputs`,
Flow's `PropertiesPanel` auto-generates the per-input EL-expression binding fields and renders the
declared `outputs` read-only. Action Types that declare no inputs/outputs still fall back gracefully to
the free-form panel, so existing definitions are unaffected.

**Palette curation** (`workflowEnabled`): add a `workflowEnabled` filter to the existing
`listActionTypes` query (server-side, consistent with how `executionMode` and labels are already
filtered in `ActionResourceImpl.listActionTypes`). The SPI provider requests only workflow-enabled
Action Types, so the Flow palette shows only the curated subset.

**Scope of the flag**: `workflowEnabled` governs **palette visibility only**. A published workflow
definition that already references an Action Type by name keeps working even if the flag is later
turned off — the flag is not consulted at execution time and does not retroactively invalidate
existing definitions.

### 3. Execution: inputs in, outputs out

The data path already exists (`resolvedInputs` → `task.input` → prompt/script; `task.output` → map →
`NodeResult`). This design makes it **named and validated**. All new behavior is gated on the Action
Type actually declaring inputs/outputs; when it declares none, execution is byte-for-byte as today.

**Inputs (into the action):**

- The engine resolves the node's `config.inputs` (name → EL expression) into a `resolvedInputs` map,
  which Axiom serializes to `task.input`. This stays as-is on the wire.
- In `TaskExecutionService`, **before running**, validate `task.input` against the Action Type's
  declared `inputs`: every `required` input must be present; values are best-effort checked/coerced
  against the declared `type`. A missing required input (or type mismatch that cannot be coerced) fails
  the task fast with a clear message, which surfaces as a `FAILED` node and fails the run.
- **Template binding**: expose each named input to `promptTemplate` / `scriptTemplate` as
  `{{inputs.NAME}}`. The legacy whole-payload `{{input}}` / `{{managerInput}}` placeholders remain
  intact for user- and manager-triggered tasks that do not originate from a workflow.

**Outputs (out of the action):**

- Axiom **auto-appends the output contract** to the run instructions: for agents, an
  Axiom-generated section is appended to the prompt instructing the agent to emit, as its final result,
  a JSON object keyed by the declared output names; for scripts, the `scriptTemplate` is expected to
  print that JSON object to stdout (or a known result file). Authors do not hand-write these
  instructions.
- On completion, Axiom parses `task.output` as that JSON object and **validates** it against the
  declared `outputs`: required outputs present, types checked, extra keys allowed (lenient). A missing
  required output or malformed JSON fails the task (fail fast).
- The validated map becomes `NodeResult.output` exactly as today, so the engine merges it into workflow
  context and downstream nodes bind to it through the EL expressions Flow already supports.

**Failure strictness**: fail fast on a missing required input, an uncoercible input type, missing
required output, or malformed output JSON. No warn-and-continue mode.

**Backward compatibility**: Action Types with no declared `inputs`/`outputs` skip both validation steps
and behave exactly as today (whole-blob input, free-form string output). Non-workflow tasks are
unaffected.

### 4. Authoring UI

In the Action Type detail page (`ui/src/pages/ActionTypeDetailPage.tsx`, a PatternFly `<Tabs>`):

- Add the `workflowEnabled` checkbox on the **Info** tab, next to the existing `userTriggerable` /
  `managerTriggerable` checkboxes.
- Add two new tabs, **Inputs** and **Outputs**, inserted in JSX order *after* Environment and *before*
  the Prompt Template / Script tabs (PatternFly orders tabs by position; each gets its own `eventKey`).
  Titles show counts for consistency with "Allowed Tools ({n})": **Inputs ({n})** / **Outputs ({n})**.
- Both tabs render **only when `workflowEnabled` is checked**, using the same conditional-rendering
  pattern already applied to the mode-specific Prompt Template / Script / Allowed Tools tabs.
  Unchecking `workflowEnabled` hides them.
- Each tab hosts a repeatable row editor: `name`, `type` (dropdown: string/number/boolean/object),
  `required` (checkbox), `description`, with add / remove / reorder (order persisted via
  `@OrderColumn`).
- Client-side mirrors of the validator rules (unique names, valid identifier) provide immediate
  feedback; the server-side `ActionTypeValidator` remains the source of truth (HTTP 422 on violation,
  matching the existing pattern).
- Remove the old `inputSchema` field from the form.

### 5. Migration, import/export, seed data

- **Flyway migration** (next `V` number, H2-compatible and idempotent per the repo's migration
  constraints): create `action_type_input` and `action_type_output` child tables (with `ordinal`
  order columns and cascading FKs to `action_type`), add `workflow_enabled BOOLEAN NOT NULL DEFAULT
  FALSE`, and drop `input_schema`. No auto-conversion of `input_schema` data (it is unenforced,
  H2-only/dev data); confirm no seed/import fixture depends on it before dropping.
- **Import/export** (`ImportExportService`): replace `inputSchema` handling with `inputs`, `outputs`,
  and `workflowEnabled` so round-tripping stays complete.
- **Seed data** (`SeedDataInitializer`): audit for any Action Type that sets `inputSchema` and migrate
  those to the structured fields; opportunistically mark obviously workflow-suited seed Action Types as
  `workflowEnabled`.

## Data flow (end to end)

1. Author declares `inputs`/`outputs` on an Action Type and checks `workflowEnabled`.
2. Flow editor requests workflow-enabled Action Types; the SPI provider returns descriptors carrying
   the typed `inputs`/`outputs`; the editor auto-generates the node's input-binding fields and
   read-only output rows.
3. At runtime the engine resolves the node's input expressions to a `resolvedInputs` map → `task.input`.
4. `TaskExecutionService` validates `task.input` against declared inputs (fail fast), binds each named
   input into the template as `{{inputs.NAME}}`, and auto-appends the output contract.
5. The agent/script emits a JSON object keyed by declared output names → `task.output`.
6. On completion, Axiom validates the output JSON against declared outputs (fail fast) and returns it as
   `NodeResult.output`; the engine merges it into workflow context for downstream nodes.

## Backward compatibility

- Action Types with no declared inputs/outputs: unchanged execution and editor behavior.
- `workflowEnabled` defaults to `false`; existing Action Types are simply absent from the Flow palette
  until curated, and existing published workflows that reference them by name continue to run.
- Removal of `inputSchema` is safe because it was never enforced; import/export and seed paths are
  updated in the same change.

## Testing strategy

- **Validator** (JUnit 5): unique/valid input & output names, invalid `type` rejected, required-field
  rules.
- **Persistence**: round-trip an Action Type with ordered inputs/outputs; verify `@OrderColumn`
  ordering and cascade delete of child rows.
- **REST**: create/update/get an Action Type with the new fields; 422 on validation violations.
- **SPI mapping** (frontend): descriptor carries mapped inputs/outputs; palette excludes
  non-`workflowEnabled` types.
- **Execution**: input validation fail-fast on missing required input; `{{inputs.NAME}}` binding;
  auto-appended output contract present; output JSON validated; malformed/missing required output fails
  the task; Action Types with no declared I/O bypass validation unchanged.
- **Migration**: applies cleanly on H2; child tables and `workflow_enabled` present; `input_schema`
  dropped.

## Open questions

None outstanding — all design decisions resolved during exploration.
