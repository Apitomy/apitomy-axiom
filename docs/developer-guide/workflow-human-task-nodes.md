# Workflow Human-Task Nodes

Axiom workflows support `human-task` nodes, which pause execution until a user completes a form through
the Inbox UI. When the engine reaches a human-task node, it parks the workflow instance in `WAITING` and
creates an inbox task with status `AwaitingInput`. A user completes the task via the unified completion
endpoint, and Axiom resumes the workflow with the answers merged into context.

---

## Authoring a Human-Task Node

A `human-task` node in a Flow workflow definition configures three things:

1. **Node name** — becomes the task title shown in the Inbox.
2. **Inputs** — mapped to read-only `details` displayed to the user for context.
3. **Outputs** — define the completion form fields (`outputSchema`) that the user fills in.

### Inputs (Display Context)

A human-task node's `inputs` list declares what workflow context values should be shown to the user as
read-only context. Each input is resolved from the current workflow context and displayed in the Inbox
task's details section.

Example (in Flow authoring UI):

```json
{
  "inputs": [
    { "name": "issueTitle", "type": "string" },
    { "name": "repository", "type": "string" }
  ]
}
```

At run time, if the workflow context contains `{ issueTitle: "Fix login bug", repository:
"apitomy/axiom" }`, the inbox task's details will show those values as read-only fields.

### Outputs (Completion Form)

A human-task node's `outputs` list declares the form fields the user must complete. Each output becomes a
field in the task's completion form, with its `type` determining the input widget and validation.

Axiom supports **optional rich metadata** on each output:

| property       | purpose                                                  | default if omitted           |
|----------------|----------------------------------------------------------|------------------------------|
| `label`        | Human-readable field label                               | Derived from `name` (title-cased) |
| `description`  | Help text shown below the field                          | Empty                        |
| `widget`       | UI control hint (text/textarea/number/checkbox/select)   | Inferred from semantic `type` |
| `defaultValue` | Pre-filled value when the form loads                     | Empty                        |
| `options`      | List of choices (for `select` widget only)               | Empty array                  |

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

When metadata is omitted, Axiom derives sensible defaults:

- `label` is generated from the output's `name` (e.g., `approvedByManager` becomes "Approved By
  Manager").
- `widget` is inferred from the semantic `type`: `boolean` defaults to `checkbox`, `number` to `number`,
  `string` to `text`.

---

## Run-Time Behavior

### Task Creation

When a running workflow reaches a human-task node:

1. The Flow engine transitions the workflow instance to `WAITING`.
2. Axiom creates an inbox task with:
   - `title` = the node's name
   - `description` = the node's description (if provided)
   - `details` = a read-only map of the node's resolved inputs
   - `outputSchema` = the completion form derived from the node's outputs
   - `status` = `AwaitingInput`

### Task Completion

A user completes the task through the unified completion endpoint:

```
POST /api/v1/inbox/{taskId}/complete
Content-Type: application/json

{
  "answers": {
    "approved": true,
    "comments": "Looks good to me"
  }
}
```

This endpoint is used by:

- The Inbox page (global task list)
- The Project detail page (project-scoped task list)

On completion, Axiom:

1. **Coerces** the submitted answers to match the node's declared semantic types (`string`, `number`,
   `boolean`, `object`). Type mismatches are rejected with HTTP 400.
2. **Merges** the coerced answers into the workflow's context (top-level keys).
3. **Resumes** the workflow by calling the Flow engine's `completeCurrentNode` with the answers.

Downstream nodes and edge conditions can reference the answers via their keys (e.g., `approved`,
`comments`).

---

## Supported and Unsupported Node Types

Axiom workflows currently support the following node types:

- `start` — workflow entry point (see [Workflow Input Contract](workflow-input-contract.md))
- `end` — workflow termination
- `action` — executes a tool or script
- `human-task` — pauses for user input (this doc)

**Unsupported node types** (rejected at workflow trigger time):

- `wait` — time-based delays
- `receive-event` — external event subscriptions

Attempting to run a workflow containing unsupported node types will fail with an error indicating which
node type is not yet implemented.

---

## Example: Approval Workflow

A workflow that waits for manager approval before deploying:

1. **Start node** — receives `projectId`, `projectName`, `repository`, `ref` (canonical inputs).
2. **Action node** — runs a build script, outputs `buildArtifact`.
3. **Human-task node** (`Manager Approval`) —
   - Inputs: `projectName`, `buildArtifact` (shown as read-only context)
   - Outputs:
     ```json
     [
       { "name": "approved", "type": "boolean", "label": "Approve deployment?" },
       { "name": "notes", "type": "string", "widget": "textarea", "description": "Optional comments" }
     ]
     ```
4. **Conditional edge** — if `approved == true`, advance to Deploy node; else, advance to Notify Owner
   node.
5. **Action node** (`Deploy`) — uses `buildArtifact` from step 2.

When the workflow reaches step 3, a manager sees a task titled "Manager Approval" with read-only details
showing the project name and build artifact, plus a checkbox for approval and a textarea for notes. After
they submit, the workflow continues based on their `approved` answer.
