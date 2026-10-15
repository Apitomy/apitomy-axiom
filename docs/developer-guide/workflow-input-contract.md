# Workflow Input Contract

A workflow's Start node declares the inputs the workflow needs. Axiom provides a fixed set of **standard
inputs** and puts into a new run's context exactly the standard inputs the Start node declares. The registry
of standard inputs is `io.apitomy.axiom.app.StandardInputs`.

## Standard inputs

| name      | type   | presence | value |
|-----------|--------|----------|-------|
| `subject` | object | always | the run's subject: `id` (number), `name`, `type`, `ref`, `refSource`, `repository` (strings); a field with no value is `null` |
| `event`   | object | only when the run was started from a stream event | the event envelope: `id`, `type`, `source`, `eventSourceId`, `ref`, `timestamp`, `actor`, `payload` |

A run is started from a stream event (all through `EventStreamOrchestrator.startRunFromEvent`) by:

- a subscription's `create-workflow` routing rule;
- **Run workflow…** on the event detail page (`POST /stream/events/{eventId}/runs`);
- a re-run of a run that was itself started from an event (`POST /workflow/runs/{runId}/rerun`).

Runs started through the subject REST endpoint (`POST /api/v1/subjects/{subjectId}/workflow`), by a schedule
trigger, or as a re-run of such a run have no `event`.

## Declaring inputs

A Start node declares its inputs under `config.inputs` (a list of `{ name, type, required, description }`).
`WorkflowContentRules.startInputViolation` checks the declarations at publish and in workbench validation
(`propose_workflow_changes`). It rejects an input when:

1. its name is not a valid identifier;
2. its name is not a standard input name (the message lists the standard inputs);
3. its `type` is set and differs from the standard type (`object` for both);
4. it declares a default value — Axiom provides standard inputs itself.

Either input may be `required`. Requiring `event` is how a workflow says it must be started from an event.

New workflows (from the workbench and **Components → Workflows**) are scaffolded by
`WorkflowTemplates.emptyWorkflow` with `subject` and `event` both declared and required. A workflow run by a
schedule trigger must remove `event` or make it optional.

## Run creation

`WorkflowExecutionService.startRun` resolves the declared inputs with `StandardInputs.resolve`:

- only declared inputs are put into the initial context; undeclared standard inputs are not provided;
- a declared optional input that is not available is absent from the context;
- a declared required input that is not available stops the run before it is created, with the message
  `Cannot start workflow "<name>": required input(s) not available: <names>`.

How that failure surfaces depends on how the run was started:

| started by | outcome when a required input is unavailable |
|------------|----------------------------------------------|
| subject REST endpoint `POST /api/v1/subjects/{subjectId}/workflow` (API only) | HTTP 400 with the message |
| re-run (`POST /workflow/runs/{runId}/rerun`) | HTTP 400 with the message |
| schedule trigger | the firing records `lastOutcome` `failed: <message>`, for example `failed: Cannot start workflow "<name>": required input(s) not available: event` |
| subscription routing, **Run workflow…** on an event | cannot happen: both inputs are always available |

A re-run resolves its inputs again from the subject's current state and the original run's triggering event;
it does not copy the original run's inputs.

The run's stored `inputs` (shown in the **Inputs** field of the run's Overview tab and returned by
`axiom_get_workflow_run`) are the resolved standard inputs without `event`; the event is linked to the run as
its trigger instead.

## Reading inputs

Expressions read standard inputs from the run context, for example `context.subject.ref`,
`context.subject.repository` or `context.event.payload.pull_request.number`.

Reading a standard input that the Start node does not declare (for example `context.subject` without
declaring `subject`) is not reported, because a node output with that name may legitimately provide the key.
Standard input names are not reserved.

## Shadowing warning

Workflow validation (`ConfigItemValidationService.validateWorkflow`, which backs the workbench's
`propose_workflow_changes` problems and assistant item validation) reports a **warning** with code
`SHADOWS_WORKFLOW_INPUT` when a node would write a context key equal to the name of a declared Start input,
for example `Output 'event' of node 'Review' overwrites the workflow input 'event' for later nodes.` The
check is `WorkflowContentRules.shadowProblems` and does not block publish. It considers these output keys:

- an `action` node's `config.outputs` (by their context key);
- the declared outputs of the node's action type that the node does not rename;
- a `human-task` node's form fields (by their context key);
- a `receive-event` node's output mapping context keys.

`sdk:` node results are not declared anywhere and are not checked.

## Passing context to action types

Action types never read the run context directly. Each `action` node maps values into the action type's
declared inputs, for example `context.subject.ref` into a `ref` input. Every required action-type input must
be mapped, mapping an undeclared input name is an error, and an unmapped optional input is simply absent. See
[Inputs, Placeholders and Mappings](../user-guide/concepts.md#inputs-placeholders-and-mappings).

## Action type inputs and outputs at run time

When a workflow task runs, `TaskExecutionService` checks the task's resolved inputs before it runs the action
type:

1. `InputBindingResolver.missingRequiredInputs` lists required declared inputs with no value (absent, `null`
   or a blank string). If any are missing, the task fails with `Missing required input(s): ...`.
2. `ActionTypeIoValidator.validate` checks each supplied value against its declared type. A mismatch fails the
   task with `Input validation failed: ...`.

When the task completes, `WorkflowExecutionService` validates its outputs against the action type's declared
outputs with the same validator. A mismatch fails the task and the node with `Output validation failed: ...`.
Workbench test runs (`DraftTestRunner`) use the same validator for inputs and outputs.

`ActionTypeIoValidator` accepts a value that already has the declared type or can be converted without loss:

| Type      | Accepted values                                                                   |
|-----------|-----------------------------------------------------------------------------------|
| `string`  | strings, numbers and booleans                                                      |
| `number`  | numbers, and strings that parse as a number                                        |
| `boolean` | booleans, and the strings `true` / `false` (any case)                              |
| `object`  | any JSON structure: an object **or an array**, or a string that parses as one       |

Any other declared type is not checked. `InputBindingResolver` also substitutes `{{inputs.NAME}}` placeholders
in agent prompts and scripts: strings are inserted as-is, `null` becomes an empty string, and other values
are serialized as JSON.
