# Workflow Input Contract & Trigger Failure Visibility — Design

**Date:** 2026-08-27
**Status:** Approved (design)
**Related:** `2026-08-26-workflow-execution-phase2-design.md` (Phase 2 execution)

## Problem

When a user runs a workflow from the "Run Workflow" modal, the trigger silently fails if the workflow
definition's Start node requires an input that Axiom does not provide. Two distinct gaps cause this:

1. **No input contract.** Axiom injects only four hardcoded project fields as workflow context
   (`WorkflowExecutionService.java:111-119`). There is no documented, enforced agreement about which inputs a
   workflow definition may depend on, so a definition can require an input that can never be satisfied.
2. **No failure visibility.** The engine signals a missing required input with a raw
   `IllegalArgumentException`, which has no exception mapper and surfaces as an opaque HTTP 500. The UI api
   client discards the response body, and `WorkflowTab.handleTrigger` only does `.catch(console.error)` — so
   the user sees nothing at all.

## Decisions

Confirmed during brainstorming:

- **Input model:** a *fixed, Axiom-provided set* of inputs. The Run Workflow flow does **not** collect
  user-supplied inputs, and the `TriggerWorkflow` API bean is unchanged (still just `workflowDefinitionId`).
- **Canonical set:** the current four fields (`projectId`, `projectName`, `repository`, `ref`).
- **Enforcement:** validate at publish time **and** guard at run time; pre-seed the Start node on new
  definitions.

## The canonical input contract

Axiom guarantees to inject exactly these inputs when starting a workflow for a project. Workflow definitions
are constrained to this set.

| name          | type    | presence                        | may be `required`? |
|---------------|---------|---------------------------------|--------------------|
| `projectId`   | number  | always                          | yes                |
| `projectName` | string  | always                          | yes                |
| `repository`  | string  | only if the project has one     | no — must be optional |
| `ref`         | string  | only if the project has one     | no — must be optional |

`repository` and `ref` are injected only when the project defines them
(`WorkflowExecutionService.java:115-118`), so they can legitimately be absent at run time. Marking them
`required` would be a latent failure and is therefore rejected at publish.

**Contract rule.** A Start node's declared inputs (`config.inputs`) must satisfy:

1. Every declared input `name` must be one of the canonical four.
2. Only `projectId` and `projectName` (the always-present inputs) may be marked `required: true`.

Any declared input that also appears in the canonical set should use the type in the table above. A definition
may declare a subset (or none) of the canonical inputs; it may not declare anything outside the set.

## Design

### 1. Publish-time validation (backend)

**Where:** `WorkflowDefinitionsResourceImpl.publishWorkflowDefinition` (`:165-198`), immediately after the
existing `WorkflowValidator.validate()` block (`:181-189`) and before the new version entity is persisted
(`:191-198`).

Add a `validateStartInputs(Workflow)` check that enforces the two contract rules above. On violation, throw a
`400 WebApplicationException` whose entity names the offending input(s) and the reason (e.g. `"Start node
input 'issueNumber' is not part of the workflow input contract"` or `"Start node input 'repository' cannot be
marked required"`). Contract violations return a `{"message": ...}` JSON body (the same shape the run-time
guard uses), which differs from the array-of-problems body that `WorkflowValidator` failures return.

The Start node inputs are read from `startNode.config().get("inputs")` as a `List<Map>` of
`{name, type, required, description}` — the same shape the engine's `validateInputs` reads
(`WorkflowEngine.java:630-646`).

### 2. Run-time guard (backend)

**Where:** `WorkflowExecutionService.triggerWorkflow`, around the `workflowEngine.startWorkflow(...)` call.

Catch the engine's `IllegalArgumentException` ("Missing required input: X" / "Required input is null: X") and
`WorkflowValidationException`, and rethrow as a `400 WebApplicationException` carrying the readable message.
This matches the deliberate 400/404/409 pattern already present in that class and is defense-in-depth for
hand-edited or legacy definitions that predate publish-time validation.

### 3. UI failure visibility

- **`ui/src/config/api.ts` `triggerWorkflow` (`:2236-2250`)** — on a non-OK response, read the response body
  and include the backend message in the thrown `Error` instead of discarding it and reporting only the status
  code.
- **`ui/src/components/WorkflowTab.tsx` `handleTrigger` (`:91-104`)** — replace `.catch(console.error)` with
  an error state rendered as a PatternFly `Alert` (variant `danger`) inside the trigger modal. The modal stays
  open on failure so the user can read the reason and correct the definition. Clear the error when the modal
  is reopened or a new definition is selected.

### 4. Start-node scaffolding

**Where:** `WorkflowDefinitionsResourceImpl.createEmptyWorkflowContent` (`:306-335`), specifically the Start
node's `config` (currently `Map.of()` at `:311`).

Seed the Start node config with `inputs` set to the canonical four:

```json
"inputs": [
  { "name": "projectId",   "type": "number", "required": true,  "description": "The Axiom project id" },
  { "name": "projectName", "type": "string", "required": true,  "description": "The Axiom project name" },
  { "name": "repository",  "type": "string", "required": false, "description": "The project git repository, if any" },
  { "name": "ref",         "type": "string", "required": false, "description": "The project git ref, if any" }
]
```

This makes new definitions conform to the contract by default and shows authors what context is available.

### 5. Documentation

- Add a "Workflow input contract" section to the workflow docs (the canonical table and the two contract
  rules).
- Update the Phase 2 spec's trigger error-response list
  (`2026-08-26-workflow-execution-phase2-design.md:107-110`) to include the required-input `400`.

## Out of scope

- Any dynamic/user-supplied input collection in the Run Workflow modal.
- Changes to the `TriggerWorkflow` API bean or the OpenAPI contract for triggering.
- Expanding the canonical input set beyond the current four.

## Testing

- **Backend (JUnit 5):**
  - `publishWorkflowDefinition` rejects (400) a definition whose Start node declares a non-canonical input.
  - `publishWorkflowDefinition` rejects (400) a definition marking `repository` or `ref` as required.
  - `publishWorkflowDefinition` succeeds for a Start node declaring a valid subset of the canonical inputs.
  - `triggerWorkflow` returns 400 (not 500) with a readable message when the engine reports a missing
    required input.
  - `createEmptyWorkflowContent` produces a Start node whose `config.inputs` is the canonical four with the
    correct `required` flags.
- **UI:** `triggerWorkflow` surfaces the backend message on failure; `WorkflowTab` renders the danger `Alert`
  and keeps the modal open on a failed trigger.
