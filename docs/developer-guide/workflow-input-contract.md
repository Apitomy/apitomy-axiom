# Workflow Input Contract

When Axiom starts a workflow for a project, it injects a fixed, canonical set of inputs into the workflow's
initial context. Workflow definitions may depend only on these inputs. This keeps a definition runnable by
construction — there is no way to supply arbitrary inputs from the "Run Workflow" dialog.

## Canonical inputs

| name          | type   | presence                    | may be `required`? |
|---------------|--------|-----------------------------|--------------------|
| `projectId`   | number | always                      | yes                |
| `projectName` | string | always                      | yes                |
| `repository`  | string | only if the project has one | no — must be optional |
| `ref`         | string | only if the project has one | no — must be optional |

`repository` and `ref` are injected only when the project defines them, so they can be absent at run time.

## Rules enforced at publish

A workflow definition's Start node declares its inputs under `config.inputs` (a list of
`{ name, type, required, description }`). At publish time Axiom rejects a definition when:

1. The Start node declares an input whose `name` is not one of the canonical inputs.
2. The Start node marks `repository` or `ref` (or any non-always-present input) as `required`.

New definitions are scaffolded with all four canonical inputs already declared (`projectId`/`projectName`
required, `repository`/`ref` optional).

## Run-time behavior

As defense-in-depth for legacy or hand-edited definitions, a trigger whose context does not satisfy a
required Start-node input fails with HTTP 400 and a message naming the missing input, which the UI surfaces in
the Run Workflow dialog.
