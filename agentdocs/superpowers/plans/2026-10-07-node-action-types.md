# Node.js Action Type Execution Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Add `executionMode: "node"` — an ES module in `codeTemplate` exporting `run(inputs, ctx)`, run by a
bundled runner with typed JSON inputs and an object result.

**Architecture:** (1) contract + persistence for the new mode/field; (2) a bundled `axiom-node-runner.mjs` plus a
`NodeExecutionService` that reuses `ScriptExecutionService`'s process plumbing (working directory, capture,
output extraction, completion); (3) validator rules for node modules; (4) UI editor/preview; (5) assistant
reference, example and docs.

**Tech Stack:** Java 21 / Quarkus / Panache; Node.js 22 (built-ins only); React + PatternFly + Monaco; JUnit 5,
Vitest.

**Spec:** `docs/superpowers/specs/2026-10-07-node-action-types-design.md`

## Global Constraints

- Contract-first: edit `common/api/src/main/resources/openapi.json` (targeted text edits), regenerate with
  `mvn -q -pl common/api install -DskipTests`; impls implement generated interfaces; generated beans only.
- Single Flyway file: edit `app/src/main/resources/db/migration/V1__baseline.sql` in place
  (`MigrationSchemaValidationTest` guards it).
- Built-ins only: imports must be `node:*` or a Node built-in name; no npm.
- No `{{…}}` placeholders in `codeTemplate`.
- Runner error lines (exact): `Action failed: <message>`, `Action module does not export run()`,
  `run() must return an object, got <type>`. Missing node: `Node.js is required to run node action types but
  was not found on PATH.`
- Same process rules as script mode: subject workspace working dir (temp dir if no subject), timeout
  `axiom.script.timeout-seconds`, process-tree kill, stderr separate, outputs = last JSON object on stdout,
  blank required inputs = missing, secrets/`environment` in `process.env`.
- 4-space indent (Java/TS/JS), Javadoc on public methods, JUnit 5, Vitest. `rg` not installed (use `git grep`).
  No browser testing. Each task ends with `./build.sh` (`-Pui` when `ui/` changes) passing and a conventional
  commit without AI attribution.

## Reference: existing code

- `ActionTypeEntity` (`core/.../entities/ActionTypeEntity.java`): `executionMode` (String, :29-30),
  `promptTemplate` (:35-36), `scriptTemplate` (:42-43). DDL `V1__baseline.sql:65-80` (`execution_mode`
  varchar, no check constraint).
- `openapi.json`: `ActionType` (:5688, required `id,name,executionMode`), `NewActionType` (:5781, required
  `name,executionMode`); `executionMode` enum `["agent","script"]` (:5706, :5794); `scriptTemplate` (:5724,
  :5812).
- `ActionResourceImpl`: `applyFields` (:202, :209), `toBean` (:236, :243), list filter (:79).
- `ImportExportService`: action type import (:509, :514), upsert (:619, :626), export `serializeActionType`
  (:759, :770).
- `ActionTypeValidator.validate` (:86-93) calls per-mode validators; `validateScriptTemplate` (:144-176).
- `TaskExecutionService.doExecuteTask` (:167): `if (actionTypeEntity != null && "script".equals(...)) {
  scriptExecutionService.executeScript(task); return; }`.
- `ScriptExecutionService`: `executeScript(TaskEntity)` (:92), `executeScript(TaskEntity, SubjectEntity)` (:106,
  marks in progress, runs async in request context, `completeTask`/`failTask`); package-private
  `CapturedOutput`, `capture(Process,long)`, `extractScriptOutput(String)`, `buildBaseEnvironment(TaskEntity,
  Path)`, `WorkingDirectory`/`workingDirectory(SubjectEntity)`, `markTaskInProgress`, `completeTask`,
  `failTask`; private `runScript` (:128), `buildExecutionLog` (:305), `RunResult` (:613); inline secret/env
  injection (:155-176); `timeoutSeconds` from `axiom.script.timeout-seconds`.
- `InputBindingResolver.parseInputs(String, ObjectMapper)`.
- UI: `ui/src/config/api.ts:391-407` (`ActionType`), `ui/src/pages/ActionTypeDetailPage.tsx` (picker :470-477,
  Script tab :374-384, visible-tab logic :147-165, defaults :65), `ui/src/pages/ActionTypesPage.tsx` (defaults
  :50, :67, :140-142, label colour :247, picker :311-312),
  `ui/src/components/assistant/ActionTypeDetailModal.tsx` (:35, :40, :53-54); colour-only:
  `dashboards/widgets/ActionTypesWidget.tsx:45`, `pages/ConfigurationPacksPage.tsx:346,351,826,831`.
  Monaco is mocked in tests as in `AssistantGeneratedItems.test.tsx:21-24`.
- Assistant: `AssistantReferenceGenerator.SCRIPT_OUTPUT_RULE` (:31-38) used at :155; examples
  `templates/assistant-reference/examples/` with `index.txt`; `AssistantReferenceTest`
  (`everyExampleValidatesWithoutProblems`, `examplesApplyEndToEnd`).

---

### Task 1: Contract and persistence for `node` mode

**Files:**
- Modify: `common/api/src/main/resources/openapi.json` — add `"node"` to both `executionMode` enums; add to both
  `ActionType` and `NewActionType`:
  `"codeTemplate": {"description": "JavaScript ES module for node-mode action types. It must export async function run(inputs, ctx) and return an object with the declared outputs. Only Node.js built-in modules may be imported; no placeholders are supported.", "type": "string"}`
- Modify: `ActionTypeEntity` — `@Column(name = "code_template", columnDefinition = "TEXT") public String
  codeTemplate;` with Javadoc
- Modify: `V1__baseline.sql` — add `code_template TEXT,` to `action_type`
- Modify: `ActionResourceImpl.applyFields`/`toBean` — map `codeTemplate`
- Modify: `ImportExportService` — import, upsert and export of action types carry `codeTemplate`
- Modify: `ui/src/config/api.ts` — `codeTemplate?: string;` on `ActionType`
- Test: `app/src/test/java/io/apitomy/axiom/app/ActionResourceTest.java`, `ConfigPackApplyTest.java`

- [ ] **Step 1: Failing tests**
  - `ActionResourceTest.nodeActionTypeRoundTripsCodeTemplate`: POST (use the class's base path) a node action
    type `{"name":"node-rt-<uuid>","executionMode":"node","codeTemplate":"export async function run(){ return {}; }"}`
    → 200/201 (match sibling create tests), then GET by id → `executionMode == "node"` and `codeTemplate`
    equal to the posted code.
  - `ConfigPackApplyTest.nodeActionTypeImportsAndExports`: import a pack with that action type; read the entity
    → `codeTemplate` set; export it (find the export method used by `SystemResourceImpl`/`ImportExportService`)
    → exported JSON contains `codeTemplate`.
- [ ] **Step 2: Run — expect FAIL** (enum rejects `node`).
- [ ] **Step 3: Implement** the listed changes; regenerate API.
- [ ] **Step 4: Run** the two test classes and `MigrationSchemaValidationTest` — PASS.
- [ ] **Step 5: `./build.sh -Pui`; commit** — `feat(action-types): add node execution mode and codeTemplate field`

Note: until Task 3, the validator does not require `codeTemplate`; until Task 2, node tasks fall through to the
agent path. Do not add execution here.

---

### Task 2: Node runner and execution

**Files:**
- Create: `app/src/main/resources/node-runner/axiom-node-runner.mjs` (exact content below)
- Create: `app/src/main/java/io/apitomy/axiom/app/NodeExecutionService.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/ScriptExecutionService.java` — make reusable pieces
  package-private: `buildExecutionLog` (rename parameters generically; keep output identical for scripts),
  `RunResult` record; extract the secret/environment injection (:155-176 block) into
  `void applyActionEnvironment(ProcessBuilder pb, ActionTypeEntity actionType)`; script behaviour unchanged.
- Modify: `TaskExecutionService.doExecuteTask` — add `node` branch next to the script branch:
  `if (actionTypeEntity != null && "node".equals(actionTypeEntity.executionMode)) {
  nodeExecutionService.executeNode(task); return; }`
- Test: `app/src/test/java/io/apitomy/axiom/app/NodeRunnerTest.java` (plain JUnit, real `node`),
  `app/src/test/java/io/apitomy/axiom/app/NodeExecutionServiceTest.java` (`@QuarkusTest`)

**Runner (`axiom-node-runner.mjs`, exact):**

```js
// Runs a node-mode Axiom action: node axiom-node-runner.mjs <action.mjs>
// Reads the declared inputs as one JSON object on stdin, calls run(inputs, ctx), and prints the result
// as the final line on stdout. Diagnostics go to stderr.
import { spawn } from "node:child_process";
import { pathToFileURL } from "node:url";
import { format } from "node:util";

function fail(line, error) {
    process.stderr.write(line + "\n");
    if (error && error.stack) {
        process.stderr.write(error.stack + "\n");
    }
    process.exit(1);
}

async function readStdin() {
    const chunks = [];
    for await (const chunk of process.stdin) {
        chunks.push(chunk);
    }
    const text = Buffer.concat(chunks).toString("utf8").trim();
    return text ? JSON.parse(text) : {};
}

function exec(cmd, args = [], options = {}) {
    return new Promise((resolve, reject) => {
        const child = spawn(cmd, args, { cwd: process.cwd(), env: process.env, shell: false });
        let stdout = "";
        let stderr = "";
        child.stdout.on("data", (d) => { stdout += d; });
        child.stderr.on("data", (d) => { stderr += d; });
        child.on("error", reject);
        child.on("close", (exitCode) => {
            const result = { stdout, stderr, exitCode };
            if (exitCode !== 0 && !options.allowFailure) {
                const error = new Error(`${cmd} exited with code ${exitCode}: ${stderr.trim()}`);
                error.result = result;
                reject(error);
            } else {
                resolve(result);
            }
        });
        if (options.input !== undefined) {
            child.stdin.write(String(options.input));
        }
        child.stdin.end();
    });
}

const modulePath = process.argv[2];
let inputs;
try {
    inputs = await readStdin();
} catch (error) {
    fail("Action failed: could not read inputs: " + error.message, error);
}

const ctx = {
    workDir: process.env.AXIOM_WORK_DIR || process.cwd(),
    apiUrl: process.env.AXIOM_API_URL || "",
    taskId: process.env.AXIOM_TASK_ID || "",
    log: (...args) => process.stderr.write(format(...args) + "\n"),
    exec,
};

let mod;
try {
    mod = await import(pathToFileURL(modulePath).href);
} catch (error) {
    fail("Action failed: " + error.message, error);
}
if (typeof mod.run !== "function") {
    fail("Action module does not export run()");
}

let result;
try {
    result = await mod.run(inputs, ctx);
} catch (error) {
    fail("Action failed: " + (error && error.message ? error.message : String(error)), error);
}
const type = result === null ? "null" : Array.isArray(result) ? "array" : typeof result;
if (type !== "object") {
    fail(`run() must return an object, got ${type}`);
}
process.stdout.write("\n" + JSON.stringify(result) + "\n");
```

**`NodeExecutionService` (`@ApplicationScoped`):**
- `public void executeNode(TaskEntity task)` — mirrors `ScriptExecutionService.executeScript`: mark in progress
  (reuse `scriptExecutionService.markTaskInProgress`), run asynchronously in an activated request context,
  then `scriptExecutionService.completeTask(id, output, exitCode == 0, executionLog)`; exceptions →
  `failTask`.
- `ScriptExecutionService.RunResult runNode(TaskEntity task, SubjectEntity subject)`:
  1. Load action type; blank `codeTemplate` → `RunResult(1, "No code configured for action type: …", null)`.
  2. Create temp dir `axiom-node-`; write runner (copied from the classpath resource) and `action.mjs`.
  3. `WorkingDirectory workDir = scriptExecutionService.workingDirectory(subject)`.
  4. `ProcessBuilder("node", runner, actionFile).directory(workDir)`; env =
     `scriptExecutionService.buildBaseEnvironment(task, workDir)` + `applyActionEnvironment(pb, actionType)`.
  5. Inputs JSON = the declared inputs from `InputBindingResolver.parseInputs(task.input, objectMapper)`
     filtered to declared names (omit absent ones); write to process stdin, close it.
  6. `CapturedOutput c = ScriptExecutionService.capture(process, timeoutSeconds)`; timeout appends the same
     timeout line as scripts; `output = ScriptExecutionService.extractScriptOutput(stdout)`.
  7. `IOException` from `pb.start()` whose message indicates the program was not found → `RunResult(1,
     "Node.js is required to run node action types but was not found on PATH.", null)`.
  8. Execution log via `buildExecutionLog` with "Code" in place of "Script Template" and no resolved-script
     section (add a `kind` parameter or a dedicated builder; keep script logs byte-identical).
  9. Delete the temp dir and clean up `workDir` in `finally`.
- Expose `static Path writeRunner(Path dir)` (package-private) for tests.

- [ ] **Step 1: Failing tests — `NodeRunnerTest`** (helper: write runner via `NodeExecutionService.writeRunner`,
  write `action.mjs`, run `node runner action.mjs` with stdin JSON and a given working dir, capture via
  `ScriptExecutionService.capture`). Cases:
  - typed inputs: inputs `{"labels":["a","b"],"meta":{"n":1},"count":3}`, code returns
    `{ joined: inputs.labels.join(","), n: inputs.meta.n + inputs.count }` → extracted output JSON equals
    `{"joined":"a,b","n":4}`;
  - `ctx.log("hi {x}")` appears in stderr only and extraction still yields the returned object;
  - `ctx.exec("echo", ["a b"])` stdout trimmed is `a b`; `ctx.exec("echo", ["$(touch <marker>)"])` does not
    create the marker;
  - `ctx.exec("false")` rejects; with `{allowFailure:true}` resolves with `exitCode` 1;
  - throw `new Error("boom")` → exit 1, stderr first line `Action failed: boom`;
  - module without `run` → `Action module does not export run()`;
  - `return [1,2]` → `run() must return an object, got array`; `return "x"` → `… got string`;
  - `ctx.workDir` and `process.cwd()` equal the working dir passed.
  Skip the class (JUnit `Assumptions.assumeTrue`) if `node --version` fails.
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement runner, `NodeExecutionService`, `ScriptExecutionService` extractions, dispatch.**
- [ ] **Step 4: `NodeExecutionServiceTest` (`@QuarkusTest`)** — create subject, node action type with inputs
  `labels` (object, required) and output `count` (number, required) and code
  `export async function run(inputs){ return { count: inputs.labels.length }; }`, a workflow task
  (`workflowRunId` set) with input `{"labels":["x","y"]}`; call `runNode(task, subject)` → exit 0 and output
  `{"count":2}`; and code that throws → exit 1 with `Action failed:` in the execution log. Run all Node and
  Script tests (`ScriptExecutionServiceTest`, `ScriptOutputTest`) — PASS, script logs unchanged.
- [ ] **Step 5: `./build.sh`; commit** — `feat(tasks): run node-mode action types with a bundled runner`

---

### Task 3: Validation for node modules

**Files:**
- Create: `core/src/main/java/io/apitomy/axiom/core/services/NodeModuleChecks.java`
- Modify: `ActionTypeValidator` — `validateCodeTemplate(def, messages)` called from `validate`; prompt/script
  checks already skip non-matching modes
- Test: `core/src/test/java/io/apitomy/axiom/core/services/NodeModuleChecksTest.java`,
  `app/src/test/java/io/apitomy/axiom/core/services/ActionTypeValidatorTest.java`

**Interfaces (`NodeModuleChecks`, final utility class):**
- `static List<String> importSpecifiers(String code)` — specifiers from `import … from '…'`, `import '…'`,
  `import('…')`, `require('…')` (single or double quotes).
- `static boolean isBuiltin(String specifier)` — true for `node:*` or a name in a fixed set: `assert`,
  `async_hooks`, `buffer`, `child_process`, `cluster`, `console`, `constants`, `crypto`, `dgram`,
  `diagnostics_channel`, `dns`, `domain`, `events`, `fs`, `fs/promises`, `http`, `http2`, `https`,
  `inspector`, `module`, `net`, `os`, `path`, `path/posix`, `path/win32`, `perf_hooks`, `process`,
  `punycode`, `querystring`, `readline`, `readline/promises`, `repl`, `stream`, `stream/promises`,
  `stream/web`, `string_decoder`, `timers`, `timers/promises`, `tls`, `trace_events`, `tty`, `url`, `util`,
  `util/types`, `v8`, `vm`, `wasi`, `worker_threads`, `zlib`.
- `static boolean exportsRun(String code)` — regex for `export async function run`, `export function run`,
  `export const run`, `export let run`, or `export {…}` containing `run` as a whole word.
- `static Optional<String> syntaxError(String code)` — writes code to a temp `.mjs`, runs
  `node --check <file>` (10 s timeout); returns stderr on non-zero exit; `Optional.empty()` when valid;
  throws `IllegalStateException("node unavailable")` when node cannot be started (caller turns this into a
  warning).
- `static Set<String> inputReads(String code)` — names from `inputs.<identifier>` occurrences.

**Validator rules (node mode, field `codeTemplate`):** required (`"Code is required for node-mode action
types."`); any `{{` → error `"Node actions receive inputs as run(inputs, ctx); placeholders are not
supported."`; each non-builtin specifier → error `"Import '<spec>' is not a Node.js built-in module; only
built-ins (e.g. node:fs) are allowed."`; `!exportsRun` → error `"The module must export async function run(inputs,
ctx)."`; syntax error → error `"Syntax error: <first line of node output>"`; node unavailable → warning
`"Syntax not checked: Node.js not found."`; undeclared `inputs.X` → warning
`"Reads inputs.<X>, which is not a declared input."`.

- [ ] **Step 1: Failing tests** — `NodeModuleChecksTest` covering each function (imports of each syntax,
  builtin/non-builtin, the export forms, a syntax error, input reads). `ActionTypeValidatorTest`: valid node
  module accepted; missing code; placeholder; `import _ from "lodash"`; `require("axios")`; `import fs from
  "node:fs"` and `import fs from "fs"` accepted; no `run` export; syntax error; undeclared input read → warning.
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** both classes plus `ConfigItemValidationServiceTest` — PASS.
- [ ] **Step 5: `./build.sh`; commit** — `feat(validation): validate node-mode action type modules`

---

### Task 4: UI — editor and previews

**Files:**
- Modify: `ui/src/pages/ActionTypeDetailPage.tsx` — picker option `<FormSelectOption value="node" label="Node.js — runs a JavaScript module" />`;
  new tab (next unused eventKey) "Code" shown when `executionMode === "node"`, containing a Monaco
  `CodeEditor` (`Language.javascript`) bound to `codeTemplate`, with help text: "Export `async function
  run(inputs, ctx)` and return an object with the declared outputs. `inputs` holds the declared inputs;
  `ctx` provides `workDir`, `apiUrl`, `taskId`, `log(...)` and `exec(cmd, args, options)`. Only Node.js built-in
  modules may be imported."; include the new tab in the visible-tab logic (:147-165)
- Create: `ui/src/components/NodeCodeTab.tsx` (the tab body; props `value`, `onChange`)
- Modify: `ui/src/pages/ActionTypesPage.tsx` — picker option; `DEFAULT_NODE_TEMPLATE` used when creating a node
  action type; label colour for node (`"purple"`)
- Modify: `ui/src/components/assistant/ActionTypeDetailModal.tsx` — node mode shows `codeTemplate` with
  JavaScript highlighting (template tab title "Code")
- Modify: colour/label helpers in `dashboards/widgets/ActionTypesWidget.tsx` and
  `pages/ConfigurationPacksPage.tsx` so `node` gets `"purple"`
- Test: `ui/src/components/NodeCodeTab.test.tsx`,
  `ui/src/components/assistant/ActionTypeDetailModal.test.tsx` (mock Monaco as in
  `AssistantGeneratedItems.test.tsx:21-24`, adding `javascript: "javascript"` to `Language`)

`DEFAULT_NODE_TEMPLATE` (exact):

```js
// inputs: the declared inputs. ctx: { workDir, apiUrl, taskId, log(...), exec(cmd, args, options) }
// Only Node.js built-in modules may be imported (e.g. node:fs).
export async function run(inputs, ctx) {
    ctx.log("running");
    return {};
}
```

- [ ] **Step 1: Failing tests** — `NodeCodeTab` renders the code and the help text mentioning `run(inputs,
  ctx)`; `ActionTypeDetailModal` with `executionMode: "node"` shows a "Code" tab whose editor contains the
  `codeTemplate`.
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: `cd ui && npm test && npm run build`** — PASS.
- [ ] **Step 5: `./build.sh -Pui`; commit** — `feat(ui): node.js action type editor and previews`

---

### Task 5: Assistant reference, example and docs

**Files:**
- Modify: `AssistantReferenceGenerator` — action type section documents modes `agent`, `script`, `node`; node
  contract, `ctx` API, built-ins only, no placeholders; guidance "prefer `node` for reshaping JSON or calling
  HTTP APIs; use `script` for short shell pipelines"
- Create: `app/src/main/resources/templates/assistant-reference/examples/action-types/fetch-repo-labels.json`
  (node mode; input `repository` (string, required); output `labels` (object, required);
  code uses `ctx.exec("gh", ["label", "list", "--repo", inputs.repository, "--json", "name", "--limit",
  "200"])` and returns `{ labels: JSON.parse(stdout).map((l) => l.name) }`)
- Modify: `examples/index.txt` (add the file) and `examples/workflows/issue-triage.json` only if needed to keep
  examples coherent (optional: do not require the workflow to use it)
- Modify: `app/src/main/resources/templates/axiom-assistant-mcp/server.js` — include `codeTemplate` in action
  type summaries where `scriptTemplate`/`executionMode` are passed through
- Modify docs: `docs/user-guide/concepts.md` (new "Node.js action types" subsection), and the action-type part
  of `docs/user-guide/workflows.md` if it lists modes; wrap at 110 columns
- Test: `AssistantReferenceTest` (new assertions; examples still validate and apply)

- [ ] **Step 1: Failing test** — `AssistantReferenceTest.referenceDocumentsNodeMode`: reference contains
  `run(inputs, ctx)`, `ctx.exec`, and "built-in"; the example list contains `action-types/fetch-repo-labels.json`.
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement.** Examples must validate with no problems except the existing `UNKNOWN_SECRET`
  allowance (node syntax check runs; node is available in the build).
- [ ] **Step 4: Run** `AssistantReferenceTest` — PASS; `mkdocs build --strict` if installed (delete `site/`).
- [ ] **Step 5: `./build.sh`; commit** — `docs: document node.js action types for the assistant and users`

---

## Final Verification

- [ ] `./build.sh -Pui` — BUILD SUCCESS.
- [ ] A node action type created via REST, used in a workflow, completes with typed outputs (covered by
  `NodeExecutionServiceTest`).
