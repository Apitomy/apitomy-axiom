# Workbench Phase 5b: AI-Suggested Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** The AI may no longer run action types or tools. It can only *suggest* a test (draft + sample inputs);
the suggestion appears in the Test output strip with a **Run…** button that opens the Test dialog pre-filled,
and only the user decides whether anything executes.

**Architecture:** The MCP-only `POST /compositions/{id}/agent/test` route and the `test_action_type` /
`test_tool` MCP tools are removed. A new MCP-only `POST /compositions/{id}/agent/test-suggestions` route
stores a `CompositionTestRun` with the new status `suggested` (author `ai`, inputs, nothing executed) in the
existing in-memory run store and fires the existing `composition-test-run` SSE event. A new MCP tool
`suggest_test(type, name, inputs)` calls it. The UI renders suggested runs with a **Run…** button.

**Tech Stack:** as Phase 5 (Java 21/Quarkus, React/PatternFly, Node MCP server).

**Spec:** `docs/superpowers/specs/2026-10-08-workflow-workbench-design.md`, amended by the user's decision
(2026-10-13): only the user runs tests, because action types and tools can make permanent changes (GitHub
issues, PRs, …). Builds on `docs/superpowers/plans/2026-10-13-workbench-phase5-testing.md`.

## Global Constraints

- All Phase 5 Global Constraints still apply (contract-first REST, never `./build.sh`/`mvn clean`/`mvn
  install`, test commands, code style, commits directly on `feat/workflow-first`, never push).
- The AI must have **no** way to execute an action type or tool: no MCP tool, no MCP-only route, no allowed
  tool. `Bash(jq *)` stays the only shell permission.
- New run status value: `suggested`. A suggested run has `author: "ai"`, `turnId` (current turn), `inputs`,
  `startedAt`, and no `durationMs`, `exitCode`, `stdout`, `stderr`, `outputs`, `problems` or `message`.
- Suggestions do not take a concurrency-cap permit and never call `DraftTestRunner`.
- Exact texts:
  - MCP tool name: `suggest_test`; parameters `type` (`action-type` | `tool`, required), `name` (required),
    `inputs` (object, optional).
  - MCP tool result: `Suggested a test of <type> <name> with inputs <json>. The user can run it from the Test
    output strip; ask them to tell you the result.`
  - Template rule (replaces the Phase 5 "Test action types and tools you create…" rule): `- You cannot run
    action types or tools. After creating or changing a node or script action type or a tool, call
    suggest_test with realistic sample inputs so the user can run it. Never claim it works until the user
    reports a passing run.`
  - Working-loop step 3 becomes: `3. Create or update action types and tools with upsert_item and fix every
    INVALID result. For each node or script action type and each tool you create or change, call
    suggest_test with realistic sample inputs.`
  - Strip: suggested row label `Suggested by AI`, summary `Inputs: <json>`, button `Run…`.

---

### Task 1: Backend and MCP — replace AI test runs with suggestions

**Files:**
- Modify: `common/api/src/main/resources/openapi.json` (remove path `/compositions/{compositionId}/agent/test`
  and operation `testAgentItem`; add path `/compositions/{compositionId}/agent/test-suggestions`, operation
  `suggestAgentTest`; add `suggested` to the `CompositionTestRun.status` enum; rename schema
  `AgentTestRequest` → `AgentTestSuggestion` and drop its `subjectId` property)
- Modify: `app/src/main/java/io/apitomy/axiom/app/composition/CompositionTestRuns.java` (add
  `suggest(long compositionId, CompositionItemEntity item, Map<String, Object> inputs, String turnId)`
  returning the stored `CompositionTestRun`)
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java` (remove
  `testAgentItem`; add `suggestAgentTest(compositionId, AgentTestSuggestion)`: `requireToken`, look the draft
  up by type+name exactly as `testAgentItem` did (404 when missing), call `testRuns.suggest(...)` with
  `turns.currentTurnId(compositionId)`)
- Modify: `app/src/main/resources/templates/axiom-workbench-mcp/server.js` (remove `test_action_type`,
  `test_tool`, `runTest`, `formatTestRun`/`tail` and `testRunBody` if they become unused; add
  `suggest_test`; export a pure `suggestionBody(args)` that validates `type` is `action-type` or `tool`
  (else `Error("type must be action-type or tool")`) and returns `{type, name, inputs}`)
- Modify: `app/src/main/resources/templates/assistant-templates/axiom-workbench.json` (allowedTools: remove the
  two test tools, add `mcp__workbench__suggest_test`; prompt: rule + step 3 texts from Global Constraints)
- Tests: `CompositionTestRunsResourceTest` (replace the agent-route run tests: valid token → 200 with
  `status: "suggested"`, `author: "ai"`, the inputs echoed, and the run listed by `GET /test-runs`; nothing
  executed — use a script that would fail if run, e.g. `exit 7`, and assert no `exitCode`; unknown draft →
  404; missing token → 401), `WorkbenchReferenceTest` (rule text; `suggest_test` allowed; assert no allowed
  tool contains `test_action_type` or `test_tool`), `app/src/test/node/workbench-mcp.test.js`
  (`suggestionBody` validation; remove tests of deleted helpers), and remove/adjust any
  `CompositionTestRunsFailureTest` cases that used the agent run route.

- [ ] Write/adjust the failing tests; run them (RED).
- [ ] Implement; run `mvn -q -pl app -am test -Dtest='CompositionTestRunsResourceTest,CompositionTestRunsFailureTest,WorkbenchReferenceTest,WorkbenchMcpServerScriptTest,DraftTestRunnerTest' -Dsurefire.failIfNoSpecifiedTests=false` and `node --test app/src/test/node/workbench-mcp.test.js` (GREEN).
- [ ] `grep -rn "test_action_type\|test_tool\|testAgentItem\|agent/test\"" app/src common/api/src` returns
  nothing outside comments/docs.
- [ ] Commit: `feat(workbench): the AI suggests tests instead of running them`

### Task 2: UI — suggested runs with Run…

**Files:**
- Modify: `ui/src/config/compositions.ts` (`TestRunStatus` gains `"suggested"`)
- Modify: `ui/src/pages/workbench/TestOutputStrip.tsx` (+ test): for `status === "suggested"`: an
  `OutlinedLightbulbIcon` (or `LightbulbIcon`) with `aria-label="suggested"`, the label `Suggested by AI`
  (instead of `AI`), summary `Inputs: <JSON.stringify(inputs ?? {})>`, no time/duration/exit text except the
  start time, and a `Run…` button (`variant="secondary" size="sm"`) calling a new prop
  `onRunSuggestion(run: CompositionTestRun): void`. `runSummary` handles `suggested`.
- Modify: `ui/src/pages/workbench/WorkbenchPage.tsx` (+ test): `onRunSuggestion` loads the item's content if
  not loaded (`wb.loadContent`), flushes its open tab if any, and opens `TestRunDialog` for that item with
  `initialValues` built from the suggestion's inputs (strings as-is, other values `JSON.stringify`), merged
  over nothing else (the suggestion wins over remembered values) and no subject. If the item no longer exists
  in `wb.items`, show the existing danger Alert with `The suggested item no longer exists.`. The
  auto-expand effect also expands for a new, unseen `suggested` run.
- Helper (in `ui/src/pages/workbench/testRunLogic.ts` + test): `export function valuesFromInputs(inputs:
  Record<string, unknown> | undefined): Record<string, string>`.

- [ ] Write the failing tests (strip renders a suggested row with label, summary and Run… calling the prop;
  page: clicking Run… on a suggestion opens the dialog pre-filled and Run calls `runTest` with the parsed
  values; a new suggestion expands the collapsed strip; `valuesFromInputs`); run them (RED).
- [ ] Implement; `cd ui && timeout 250 npx tsc -b --noEmit && timeout 280 npx vitest run` (GREEN).
- [ ] Commit: `feat(workbench): run AI-suggested tests from the Test output strip`
