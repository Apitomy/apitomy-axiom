# Workflow Input Contract

When Axiom starts a workflow for a project, it injects a fixed, canonical set of inputs into the workflow's
initial context. Workflow definitions may depend only on these inputs. This keeps a definition runnable by
construction — there is no way to supply arbitrary inputs from the "Run Workflow" dialog.

## Canonical inputs

| name          | type   | presence                                          | may be `required`? |
|---------------|--------|----------------------------------------------------|--------------------|
| `projectId`   | number | always                                            | yes |
| `ref`         | string | always                                            | yes |
| `event`       | object | only when the run was started by a subscription's `create-workflow` routing rule | no — must be optional |
| `projectName` | string | never populated automatically (see below)         | no — must be optional |
| `repository`  | string | never populated automatically (see below)         | no — must be optional |

Only `projectId` and `ref` are guaranteed to be present in every workflow run, and only these two may be
declared `required` on the Start node.

`event` is populated with the triggering event's envelope (`type`, `source`, `connectionId`, `ref`,
`timestamp`, `actor`, `payload`) only when the workflow instance was started by a subscription's
`create-workflow` routing rule. Manually triggered runs (from the project detail page or the "Run Workflow"
dialog) do not populate `event`.

`projectName` and `repository` are accepted as valid Start-node input names — so a definition may declare
them without failing publish validation — but Axiom does not currently inject values for them into any
workflow's initial context. Declare them only if you plan to resolve them another way (e.g. via an early
`action` node that fetches project details), and always as optional inputs, since a `required` declaration
for either name is rejected at publish time.

## Rules enforced at publish

A workflow definition's Start node declares its inputs under `config.inputs` (a list of
`{ name, type, required, description }`). At publish time Axiom rejects a definition when:

1. The Start node declares an input whose `name` is not one of the canonical inputs
   (`projectId`, `ref`, `event`, `projectName`, `repository`).
2. The Start node marks any input other than `projectId` or `ref` as `required`.

New definitions are scaffolded with three inputs already declared: `projectId` (required, number), `ref`
(required, string), and `event` (optional, object, described as "present when created by subscription
routing").

## Run-time behavior

As defense-in-depth for legacy or hand-edited definitions, a trigger whose context does not satisfy a
required Start-node input fails with HTTP 400 and a message naming the missing input, which the UI surfaces in
the Run Workflow dialog.
