# Workflow Input Contract

When Axiom starts a workflow run on a subject, it injects a fixed, canonical set of inputs into the
workflow's initial context. Workflow definitions may declare only these inputs. Values supplied when a run
is started (from the "Run Workflow" dialog, the REST API, or a schedule trigger's static `inputs`) join the
initial context and are validated against the Start node's declared inputs; `subjectId` and `ref` are always
set by Axiom and cannot be overridden.

## Canonical inputs

| name          | type   | presence                                          | may be `required`? |
|---------------|--------|----------------------------------------------------|--------------------|
| `subjectId`   | number | always                                            | yes |
| `ref`         | string | always                                            | yes |
| `event`       | object | only when the run was started by a subscription's `create-workflow` routing rule | no — must be optional |
| `subjectName` | string | never populated automatically (see below)         | no — must be optional |
| `repository`  | string | never populated automatically (see below)         | no — must be optional |

Only `subjectId` and `ref` are guaranteed to be present in every workflow run, and only these two may be
declared `required` on the Start node.

`event` is populated with the triggering event's envelope (`type`, `source`, `connectionId`, `ref`,
`timestamp`, `actor`, `payload`) only when the workflow instance was started by a subscription's
`create-workflow` routing rule. Runs started manually (from the subject detail page's "Run Workflow"
dialog) or by a schedule trigger do not populate `event`.

`subjectName` and `repository` are accepted as valid Start-node input names — so a definition may declare them
without failing publish validation — but Axiom does not inject values for them into any workflow's initial
context (they can only be supplied as run inputs). Declare them only if you plan to resolve them another way
(e.g. via an early `action` node that fetches subject details), and always as optional inputs, since a
`required` declaration for either name is rejected at publish time.

## Rules enforced at publish

A workflow definition's Start node declares its inputs under `config.inputs` (a list of
`{ name, type, required, description }`). At publish time Axiom rejects a definition when:

1. The Start node declares an input whose `name` is not one of the canonical inputs
   (`subjectId`, `ref`, `event`, `subjectName`, `repository`).
2. The Start node marks any input other than `subjectId` or `ref` as `required`.

New definitions are scaffolded with three inputs already declared: `subjectId` (required, number), `ref`
(required, string), and `event` (optional, object, described as "present when created by subscription
routing").

## Run-time behavior

As defense-in-depth for legacy or hand-edited definitions, a trigger whose context does not satisfy a
required Start-node input fails with HTTP 400 and a message naming the missing input, which the UI surfaces in
the Run Workflow dialog.
