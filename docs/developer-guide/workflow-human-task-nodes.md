# Workflow Human-Task Nodes

Axiom workflows support `human-task` nodes, which pause execution until a user completes a form through
the Inbox UI. When the engine reaches a human-task node, it parks the workflow instance in `WAITING` and
creates an inbox task with status `AwaitingInput`. A user completes the task via the unified completion
endpoint, and Axiom resumes the workflow with the answers merged into context.

---

## Authoring a Human-Task Node

A `human-task` node in a Flow workflow definition configures these things under `config`:

1. **Title** (`title`, optional) — an EL expression for the task title shown in the Inbox, for example
   `'Review ' += context.subject.ref`. When it is absent or fails, the node's name is used.
2. **Description** (`description`, optional) — text shown with the task.
3. **Inputs** — mapped to read-only `details` displayed to the user for context.
4. **Outputs** — define the completion form fields (`outputSchema`) that the user fills in.

### Inputs (Display Context)

A human-task node's `inputs` is a map from a display name to an EL expression. Each expression is
evaluated against the current workflow context, and the result is shown in the Inbox task's details section
as a read-only value under its name.

Example (from `templates/assistant-reference/examples/workflows/issue-triage.json`):

```json
{
  "inputs": {
    "issue": "context.subject.ref",
    "severity": "context.severity"
  }
}
```

At run time, if the workflow context contains `{ ref: "https://github.com/apitomy/axiom/issues/7", severity:
"high" }`, the inbox task's details show `issue` and `severity` with those values. Values that are not
strings are shown as their string form.

### Outputs (Completion Form)

A human-task node's `outputs` list declares the form fields the user must complete. Each output becomes a
field in the task's completion form, with its `type` determining the input widget and validation.

Each output has a `name` and a semantic `type` (`string`, `number`, `boolean` or `object`), and may be
marked `required`. Axiom also supports **optional rich metadata** on each output:

| property       | purpose                                                  | default if omitted           |
|----------------|----------------------------------------------------------|------------------------------|
| `label`        | Human-readable field label                               | The output's `name` verbatim |
| `description`  | Help text shown below the field                          | Empty                        |
| `widget`       | UI control (`text`, `textarea`, `number`, `checkbox`, `select`) | `text`                |
| `defaultValue` | Pre-filled value when the form loads                     | Empty                        |
| `options`      | List of `{ label, value }` choices (for the `select` widget only) | Empty array         |
| `contextKey`   | Context key the answer is stored under                   | The output's `name`          |

Example (in Flow authoring UI):

```json
{
  "outputs": [
    {
      "name": "approved",
      "type": "boolean",
      "label": "Approve this change?",
      "widget": "checkbox"
    },
    {
      "name": "comments",
      "type": "string",
      "label": "Review comments",
      "description": "Optional notes for the team",
      "widget": "textarea"
    }
  ]
}
```

When metadata is omitted, Axiom uses these defaults (`WorkflowHumanTaskMapper.toOutputSchema`):

- `label` defaults to the output's `name` verbatim (e.g., an output named `approvedByManager` shows the
  label `approvedByManager`).
- The form field's control comes from `widget` only; it is not inferred from `type`. An output without a
  `widget` is a `text` field, so set `widget` to `checkbox` for a `boolean` output and to `number` for a
  `number` output. The widget value `boolean` is also accepted as `checkbox`.
- A node with no outputs gets no `outputSchema`, and the task takes a free-form response.

---

## Run-Time Behavior

### Task Creation

When a running workflow reaches a human-task node:

1. The Flow engine transitions the workflow instance to `WAITING`.
2. Axiom creates an inbox task (`WorkflowExecutionService.createHumanTaskForNode`) with:
   - the task name (`actionType`) = the evaluated `title`, or the node's name when there is none
   - `humanContext.title` = the node's name, `humanContext.description` = the node's description (if
     provided), and `humanContext.details` = the node's resolved inputs
   - `outputSchema` = the completion form derived from the node's outputs
   - `status` = `AwaitingInput`
   - `traceId` = the run's trace; a `task` trace node with summary `Human task: <title>` is added to it

### Task Completion

A user completes the task through the unified completion endpoint:

The request body is a flat JSON object whose keys are the output names declared by the node (there is no
`answers` wrapper):

```
POST /api/v1/inbox/{taskId}/complete
Content-Type: application/json

{
  "approved": true,
  "comments": "Looks good to me"
}
```

This endpoint is used by:

- The Inbox page (global task list)
- The Subject detail page (subject-scoped task list)

On completion, Axiom:

Only a task with status `AwaitingInput` can be completed; any other task returns HTTP 404.

1. **Validates** the submission against the task's `outputSchema` before completion — missing required
   fields or wrong-typed values are rejected with HTTP 400.
2. **Coerces** the accepted answers to the node's declared semantic types (`string`, `number`,
   `boolean`, `object`) so downstream edge conditions compare correctly.
3. **Merges** the coerced answers into the workflow's context, each under its output's `contextKey` (or its
   `name` when no `contextKey` is set).
4. **Resumes** the workflow by calling the Flow engine's `completeNode` with the answers.

Downstream nodes and edge conditions can reference the answers via their keys (e.g., `approved`,
`comments`).

---

## Supported Node Types

Axiom workflows currently support the following node types:

- `start` — workflow entry point (see [Workflow Input Contract](workflow-input-contract.md))
- `end` — workflow termination
- `action` — runs an action type (AI agent, Node.js or script)
- `human-task` — pauses for user input (this doc)
- `wait` — pauses the instance for a fixed duration before resuming automatically
- `receive-event` — pauses the instance until a matching stream event is dispatched to it by a
  subscription's `workflow-dispatch` routing rule

For the full authoring and run-time reference across all node types, see
[Workflows](../user-guide/workflows.md).

---

## Example: Approval Workflow

A workflow that waits for manager approval before deploying:

1. **Start node** — declares the standard input `subject` (see
   [Workflow Input Contract](workflow-input-contract.md)).
2. **Action node** — runs a build script, outputs `buildArtifact`.
3. **Human-task node** (`Manager Approval`) —
   - Inputs: `{ "ref": "context.subject.ref", "buildArtifact": "context.buildArtifact" }` (shown as read-only
     context)
   - Outputs:
     ```json
     [
       { "name": "approved", "type": "boolean", "required": true, "label": "Approve deployment?",
         "widget": "checkbox" },
       { "name": "notes", "type": "string", "widget": "textarea", "description": "Optional comments" }
     ]
     ```
4. **Conditional edge** — if `approved == true`, advance to Deploy node; else, advance to Notify Owner
   node.
5. **Action node** (`Deploy`) — uses `buildArtifact` from step 2.

When the workflow reaches step 3, a manager sees a task titled "Manager Approval" with read-only details
showing the subject ref and build artifact, plus a checkbox for approval and a textarea for notes. After
they submit, the workflow continues based on their `approved` answer.
