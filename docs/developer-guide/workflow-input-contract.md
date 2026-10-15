# Workflow Input Contract

When Axiom starts a workflow run on a subject, it injects a set of built-in inputs into the workflow's
initial context. A workflow's Start node may declare these built-in inputs and may also declare its own
**custom inputs** (for example `environment`). Values supplied when a run is started (from the "Run
Workflow" dialog, the event detail page's "Run workflow…" dialog, the REST API, or a schedule trigger's
static `inputs`) join the initial context and are
validated against the Start node's declared inputs.

## Built-in inputs

| name          | type   | presence                                          | may be `required`? |
|---------------|--------|----------------------------------------------------|--------------------|
| `subjectId`   | number | always                                            | yes |
| `ref`         | string | always                                            | yes |
| `event`       | object | only when the run was started from a stream event | no — must be optional |
| `subjectName` | string | always (empty string when the subject has none)   | no — must be optional |
| `repository`  | string | always (empty string when the subject has none)   | no — must be optional |

`event` is populated with the triggering event's envelope (`id`, `type`, `source`, `eventSourceId`, `ref`,
`timestamp`, `actor`, `payload`) only when the run was started from a stream event. All of these use
`EventStreamOrchestrator.startRunFromEvent`:

- a subscription's `create-workflow` routing rule;
- a manual run from the event detail page ("Run workflow…", `POST /stream/events/{eventId}/runs`);
- a re-run of a failed run that was itself started from an event (`POST /workflow/runs/{runId}/rerun`).

Runs started manually on a subject (the subject detail page's "Run Workflow" dialog), by a schedule trigger,
or as a re-run of such a run do not populate `event`.

`subjectName` and `repository` are injected from the subject and are an empty string when the subject has
no value, so they must be declared as optional.

## Protected inputs

`subjectId`, `subjectName`, `ref`, `repository` and `event` are always set by Axiom. Supplied run inputs
with these names are ignored, so a run's subject data and triggering event cannot be overridden by the caller
or a schedule trigger.

## Passing context to action types

Action types never read the run context directly. Each `action` node maps values into the action type's
declared inputs, for example `context.ref` into a `ref` input. Every required action-type input must be
mapped, mapping an undeclared input name is an error, and an unmapped optional input is simply absent. See
[Inputs, Placeholders and Mappings](../user-guide/concepts.md#inputs-placeholders-and-mappings).

## Custom inputs

Any other input name declared on the Start node is a custom input. Custom inputs are provided only through
supplied run inputs, and may be marked `required`. A custom input name must be a valid identifier: it
starts with a letter or underscore and contains only letters, digits and underscores.

A Start input may declare a `defaultValue`. The Flow engine treats it as advisory, so
`WorkflowExecutionService.applyStartInputDefaults` applies it for every way a run is started: when the
supplied run inputs do not include the input, or set it to `null`, the run starts with the default. Defaults
are never applied to protected inputs.

## Rules enforced at publish

A workflow definition's Start node declares its inputs under `config.inputs` (a list of
`{ name, type, required, description, defaultValue }`). The checks are in
`WorkflowContentRules.startInputViolation`. At publish time Axiom rejects a definition when:

1. The Start node declares an input whose `name` is not a valid identifier.
2. The Start node marks `event`, `subjectName` or `repository` as `required`.

New definitions are scaffolded with three inputs already declared: `subjectId` (required, number), `ref`
(required, string), and `event` (optional, object, described as "The triggering event (present when
created by subscription routing)").

## Run-time behavior

Defaults are applied before the run starts, so a required input with a `defaultValue` is satisfied when it is
not supplied. A run whose initial context does not satisfy a required Start-node input (for example a required
custom input that was not supplied) fails to start. Manual and REST triggers return HTTP 400 with a message
naming the missing input, which the UI surfaces in the Run Workflow dialog; a schedule trigger records the
attempt with a `lastOutcome` of `failed: <reason>`.

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
