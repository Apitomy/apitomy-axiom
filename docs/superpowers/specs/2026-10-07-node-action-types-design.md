# Node.js Execution Mode for Action Types Design

**Date:** 2026-10-07
**Status:** Approved (pending spec review)

## Goal

Add a third Action Type execution mode, `node`, in which the action is a JavaScript ES module exporting
`run(inputs, ctx)`. Inputs arrive as typed JSON values and outputs are returned as an object, removing the shell
quoting, heredoc and `jq` friction that script-mode actions suffer when moving JSON around.

## Decisions

- Built-ins only: no npm packages.
- Code is an ES module that exports `run` (not a function body, not a free-form script).

## Non-Goals

npm dependencies; TypeScript; an AI-edit helper for code; sandboxing (node actions have the same trust level
as scripts); converting existing script actions.

## 1. Contract and Execution

### Authoring

- `executionMode: "node"`; code stored in a new `codeTemplate` field. No placeholders: any `{{…}}` is a
  validation error.
- The module exports `async function run(inputs, ctx)` (a synchronous `run` returning an object is also
  accepted).
- `inputs`: declared inputs as JSON values (string, number, boolean, object, array). Unmapped optional inputs are
  absent from the object.
- `ctx`:
  - `ctx.workDir` — the subject workspace (also the process working directory);
  - `ctx.apiUrl` — Axiom REST API base URL;
  - `ctx.taskId` — the task ID;
  - `ctx.log(...args)` — writes to stderr (shown in the execution log);
  - `ctx.exec(cmd, args, options?)` — runs a command without a shell; resolves to
    `{stdout, stderr, exitCode}`; rejects on non-zero exit unless `options.allowFailure === true`;
    `options.input` (string) is written to the command's stdin when given.
- Returns a plain object containing the declared outputs.
- Secrets and the action type's `environment` are in `process.env` (same rules as script mode).

### Execution

- Axiom writes the code to `action.mjs` in a fresh temporary directory together with the bundled runner
  `axiom-node-runner.mjs` (classpath resource), and runs `node <tmp>/axiom-node-runner.mjs <tmp>/action.mjs` with
  the subject workspace as working directory. The temporary directory is deleted afterwards.
- Inputs are written to the runner's stdin as one JSON object.
- The runner imports the module, checks `run` is a function, awaits `run(inputs, ctx)`, checks the result is a
  plain object (not null, not an array), and prints `JSON.stringify(result)` as the final stdout line.
- Output extraction (last JSON object on stdout), type and required-output validation are unchanged.
- Same process handling as script mode: timeout, killing the process tree, stderr captured separately into the
  execution log, subject workspace as working directory (temporary directory when there is no subject), and
  blank required inputs counting as missing.

## 2. Validation, API, UI and Assistant

### Validation (`ActionTypeValidator`, node mode)

- `codeTemplate` required.
- Any `{{…}}` → error: "Node actions receive inputs as run(inputs, ctx); placeholders are not supported."
- Static import specifiers (`import … from '…'`, `import '…'`, `import('…')`, `require('…')`) must be
  `node:*` or a Node built-in module name (from a fixed list of built-ins); otherwise error naming the
  specifier.
- Must export `run`: matches `export async function run`, `export function run`, `export const run`,
  `export let run`, or an `export { … run … }` list; otherwise error.
- Syntax: `node --check` on the code (as `.mjs`) with a 10 s timeout; failure → error with node's message;
  node unavailable → warning "Syntax not checked: Node.js not found".
- `inputs.<name>` property reads of undeclared names → warning.
- `promptTemplate` / `scriptTemplate` are not required in node mode.

### API (contract-first, `openapi.json`)

- `executionMode` enum gains `node` (in `ActionType` and `NewActionType`).
- `ActionType` / `NewActionType` gain `codeTemplate` (string).
- Database: `action_type.code_template` (TEXT) in `V1__baseline.sql`; import/export carries the field.

### UI

- Action Type editor: execution mode "Node.js"; a JavaScript code editor (Monaco, `javascript` language) replaces
  the script editor in that mode, seeded with a starter module exporting `run`; help text covers `inputs`,
  `ctx` and built-ins only.
- Assistant Action Type preview: shows the code with JavaScript highlighting.

### Assistant

- Generated reference documents node mode, the `run(inputs, ctx)` contract, `ctx`, built-ins only, and when to
  prefer node (JSON reshaping, HTTP calls) vs script (short shell pipelines).
- New bundled example `action-types/fetch-repo-labels.json` (node mode, uses `ctx.exec("gh", …)`), validated
  and applied end to end by `AssistantReferenceTest`.

## 3. Errors and Testing

### Errors

- Runner writes one leading stderr line, then the stack: `Action failed: <message>`;
  `Action module does not export run()`; `run() must return an object, got <type>`.
- `node` not on PATH at run time → task fails: "Node.js is required to run node action types but was not found
  on PATH."
- Timeout → process tree killed, task fails with the timeout message.

### Testing

- Runner (real node): typed inputs (array, object, number); returned object becomes outputs; `ctx.log` only on
  stderr; `ctx.exec("echo", ["a b"])` keeps one argument; `$(…)` in an exec argument is not executed; throw /
  missing `run` / non-object result fail with the messages above; working directory honoured.
- Validator: placeholder rejected; `import x from "lodash"` and `require("axios")` rejected; `node:fs` and `fs`
  accepted; missing `run` rejected; syntax error reported; valid module accepted.
- End to end: a workflow action node using a node action type completes with typed outputs.
- REST and import/export round-trip `codeTemplate`.
- Assistant example validates and applies.
- UI (Vitest): Node.js mode shows the code editor; the assistant preview shows the code.
