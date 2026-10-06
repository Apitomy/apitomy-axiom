# Workflow Input Contract

When Axiom starts a workflow run on a subject, it injects a set of built-in inputs into the workflow's
initial context. A workflow's Start node may declare these built-in inputs and may also declare its own
**custom inputs** (for example `environment`). Values supplied when a run is started (from the "Run
Workflow" dialog, the REST API, or a schedule trigger's static `inputs`) join the initial context and are
validated against the Start node's declared inputs.

## Built-in inputs

| name          | type   | presence                                          | may be `required`? |
|---------------|--------|----------------------------------------------------|--------------------|
| `subjectId`   | number | always                                            | yes |
| `ref`         | string | always                                            | yes |
| `event`       | object | only when the run was started by a subscription's `create-workflow` routing rule | no — must be optional |
| `subjectName` | string | never populated automatically (see below)         | no — must be optional |
| `repository`  | string | never populated automatically (see below)         | no — must be optional |

`event` is populated with the triggering event's envelope (`type`, `source`, `eventSourceId`, `ref`,
`timestamp`, `actor`, `payload`) only when the workflow instance was started by a subscription's
`create-workflow` routing rule. Runs started manually (from the subject detail page's "Run Workflow"
dialog) or by a schedule trigger do not populate `event`.

`subjectName` and `repository` are not injected by Axiom; they can only be supplied as run inputs.
Because they are not always present, they must be declared as optional.

## Protected inputs

`subjectId`, `ref` and `event` are always set by Axiom. Supplied run inputs with these names are ignored,
so a run's subject, ref and triggering event cannot be overridden by the caller or a schedule trigger.

## Custom inputs

Any other input name declared on the Start node is a custom input. Custom inputs are provided only through
supplied run inputs, and may be marked `required`. A custom input name must be a valid identifier: it
starts with a letter or underscore and contains only letters, digits and underscores.

## Rules enforced at publish

A workflow definition's Start node declares its inputs under `config.inputs` (a list of
`{ name, type, required, description }`). At publish time Axiom rejects a definition when:

1. The Start node declares an input whose `name` is not a valid identifier.
2. The Start node marks `event`, `subjectName` or `repository` as `required`.

New definitions are scaffolded with three inputs already declared: `subjectId` (required, number), `ref`
(required, string), and `event` (optional, object, described as "present when created by subscription
routing").

## Run-time behavior

A run whose initial context does not satisfy a required Start-node input (for example a required custom
input that was not supplied) fails to start. Manual and REST triggers return HTTP 400 with a message naming
the missing input, which the UI surfaces in the Run Workflow dialog; a schedule trigger records the attempt
with a `lastOutcome` of `failed: <reason>`.
