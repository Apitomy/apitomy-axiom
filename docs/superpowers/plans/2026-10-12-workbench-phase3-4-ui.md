# Workbench Phases 3 and 4: Flow 3.0.1 UI Upgrade and Workbench UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Upgrade the UI to `@apitomy/flow-ui` 3.0.1 and build the Workflow Workbench page. The page has a
three-pane layout:
- a composition tree with revisions and publish;
- tabbed editors for the workflow, action types and tools;
- an AI chat.

You and the AI take turns editing the same live drafts. AI workflow changes arrive as Flow change sets
that the editor applies (live) or stages (review), and an AI turn can be undone.

**Architecture:**
- **Data:** a thin API layer (`config/compositions.ts`) over the Phase 2 REST routes, plus a typed SSE
  helper.
- **Pure logic in testable modules** (`pages/workbench/*.ts`):
  - tree status
  - publish selection
  - turn and lock state
  - undo decision
  - preferences
- **Page:** `WorkbenchPage` assembles a resizable three-pane layout from small components.
- **Workflow tab:** the Flow `WorkflowEditor`, driven through its imperative handle.
- **Action type and tool tabs:** controlled editors extracted from the existing detail pages, which are
  refactored to use them, so there is one editor implementation.
- **Chat:** the existing `AssistantChatPanel`, with send and interrupt redirected to the composition routes.

**Tech Stack:** React 19, TypeScript, PatternFly 6, react-router 7, `@apitomy/flow-ui` 3.0.1, Vitest with
jsdom and Testing Library. The backend is Java/Quarkus, contract-first; this plan adds one endpoint.

**Spec:** `docs/superpowers/specs/2026-10-08-workflow-workbench-design.md`, especially §2 (UI), §3 (REST)
and §4 (errors). Flow reference: `apitomy-flow/docs/user-guide/ai-assisted-editing.md`.

## Global Constraints

- UI commands, run in `ui/`: `npx tsc -b --noEmit`, `npx vitest run <file>`, `npx vitest run`.
- **Node modules:** in a git worktree, run `npm ci` in the worktree's own `ui/`. Never symlink to, or
  modify, the main checkout's `node_modules`. Only Task 1 changes dependencies.
- **Backend:** never run `mvn clean`, `./build.sh` or any `mvn install`. Use
  `mvn -q -pl app -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`. REST changes start in
  `openapi.json`.
- **Style:** TypeScript and Java use 4-space indentation. Add JSDoc or Javadoc on exported or public
  functions and components. Use explicit types for exported APIs. Use `git grep`, not `rg`.
- **Commits:** conventional commit messages, no AI attribution, no push.
- **localStorage keys:** prefix with `axiom.workbench.`. Wrap every access in try/catch, as `useTheme.ts`
  does.
- **Flow handle use:**
  - Pending change set, apply mode `live`: call `handle.apply(cs)`.
  - Pending change set, apply mode `review`: call `handle.propose(cs)`.
  - A `stale` rejection or `onProposalResolved(id, 'stale')` resolves as `stale`.
  - Accept or apply resolves as `applied`. Reject resolves as `rejected`.
- **Resolving change sets:** every pending change set the UI receives must be resolved exactly once with
  `POST /compositions/{id}/changeset-resolution {turnId, outcome, fromPending}`.
  - `fromPending` is `true` when the set came from `GET /compositions/{id}` while the workbench was opening.
  - It is `false` when the set came from the live SSE `composition-changeset` event.
- **Saving the workflow:**
  - Debounce workflow saves by 500 ms.
  - Flush any pending save before sending a chat message.
  - A save caused by an agent-origin change (`meta.origin` starts with `agent:`) carries `turnId` = the
    turn that produced the change set.
- **SSE event types:**
  - `composition-item-changed` `{compositionId, itemId, contentRevision, author, turnId}`
  - `composition-turn` `{compositionId, turnId, state}`
  - `composition-changeset` `{compositionId, turnId, changeSet}`

  All three are top-level events. Consume them with `sseClient.subscribe`.
- **Mocking flow-ui in tests:** component tests that render the editor mock `@apitomy/flow-ui` with
  `vi.mock`, as `WorkflowRunDetailPage.test.tsx` does. jsdom cannot render React Flow.

## File Map

| File | Change |
|---|---|
| `ui/package.json`, `ui/package-lock.json` | `@apitomy/flow-ui` `^3.0.1` |
| `ui/src/hooks/useWorkflowEditorSpi.ts` | `openActionType` support; `buildActionTypeCatalog` |
| `ui/src/pages/WorkflowDefinitionDetailPage.tsx` | `onChange` meta, Open/Create action types, **Open in workbench** |
| `ui/src/config/compositions.ts` (+ test) | Composition API client and SSE helper |
| `ui/src/pages/workbench/workbenchLogic.ts` (+ test) | Pure logic |
| `ui/src/pages/workbench/workbenchPrefs.ts` (+ test) | localStorage preferences |
| `ui/src/components/ActionTypeEditor.tsx` (+ test), `ui/src/pages/ActionTypeDetailPage.tsx` | Extract the controlled editor |
| `ui/src/components/ToolEditor.tsx` (+ test), `ui/src/pages/ToolDetailPage.tsx` | Extract the controlled editor |
| `ui/src/components/assistant/AssistantChatPanel.tsx` | Optional `onSend` / `onInterrupt` |
| `ui/src/pages/WorkbenchListPage.tsx` (+ test) | `/workbench` |
| `ui/src/pages/workbench/WorkbenchPage.tsx`, `.css` (+ test) | `/workbench/:id` shell, panes, focus, break-out |
| `ui/src/pages/workbench/CompositionTree.tsx`, `PublishDialog.tsx`, `RevisionList.tsx` (+ tests) | Left pane |
| `ui/src/pages/workbench/WorkflowTab.tsx` (+ test) | Workflow editor tab |
| `ui/src/pages/workbench/ItemTab.tsx` (+ test) | Action type and tool tabs |
| `ui/src/pages/workbench/ChatPane.tsx` (+ test) | Right pane with Undo AI turn |
| `ui/src/pages/workbench/ProblemsStrip.tsx` | Bottom strip |
| `ui/src/App.tsx`, `ui/src/components/AppSidebar.tsx` | Routes, `?chrome=none`, nav |
| `common/api/.../openapi.json`, `CompositionsResourceImpl.java` (+ test) | `POST /compositions/{id}/session` |

---

### Task 1: Upgrade to `@apitomy/flow-ui` 3.0.1 (Phase 3)

**Files:**
- Modify: `ui/package.json` and `ui/package-lock.json`
- Modify: `ui/src/hooks/useWorkflowEditorSpi.ts`
- Modify: `ui/src/pages/WorkflowDefinitionDetailPage.tsx`
- Test: `ui/src/hooks/useWorkflowEditorSpi.test.ts`

**Interfaces:**
- Produces:
  - `useWorkflowEditorSpi(options?: { openActionType?: (request: { value: string; resolved: boolean }) => void }): EditorSpi`.
    It stays memoized on `openActionType`.
  - `findActionTypeIdByName(name: string): Promise<number | undefined>`. It looks the name up through
    `fetchActionTypes(1, 1000)`.

- [ ] **Step 1: Upgrade the dependency**

In the worktree's `ui/`, run `npm install @apitomy/flow-ui@^3.0.1`. Commit the lock file with the change.
Then run `npx tsc -b --noEmit` and fix any type errors at the existing usage sites listed below. The 3.0
`onChange` signature `(workflow, meta)` is backwards compatible.
- `WorkflowDefinitionDetailPage.tsx:467` and `:797`
- `components/assistant/WorkflowDetailModal.tsx:91`
- `components/WorkflowTab.tsx:381`
- `pages/WorkflowRunDetailPage.tsx:254`

- [ ] **Step 2: Write the failing test**

```ts
import { describe, expect, it, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { findActionTypeIdByName, useWorkflowEditorSpi } from "./useWorkflowEditorSpi";
import * as api from "../config/api";

describe("useWorkflowEditorSpi", () => {
    beforeEach(() => vi.restoreAllMocks());

    it("exposes openActionType only when given", () => {
        const open = vi.fn();
        expect(renderHook(() => useWorkflowEditorSpi()).result.current.openActionType).toBeUndefined();
        const spi = renderHook(() => useWorkflowEditorSpi({ openActionType: open })).result.current;
        spi.openActionType?.({ value: "x", resolved: true });
        expect(open).toHaveBeenCalledWith({ value: "x", resolved: true });
    });

    it("finds action type ids by name", async () => {
        vi.spyOn(api, "fetchActionTypes").mockResolvedValue({
            items: [{ id: 7, name: "triage", executionMode: "agent" }], totalCount: 1,
        } as never);
        expect(await findActionTypeIdByName("triage")).toBe(7);
        expect(await findActionTypeIdByName("nope")).toBeUndefined();
    });
});
```

- [ ] **Step 3: Run the test and confirm it fails**

Run: `npx vitest run src/hooks/useWorkflowEditorSpi.test.ts`. Expected: FAIL, because
`findActionTypeIdByName` does not exist yet.

- [ ] **Step 4: Implement**

- `useWorkflowEditorSpi(options)` returns
  `useMemo(() => ({ actionTypes: loadEditorActionTypes, ...(options?.openActionType ? { openActionType: options.openActionType } : {}) }), [options?.openActionType])`.
- In `WorkflowDefinitionDetailPage`:
  - Change `handleEditorChange` to accept `(updated: Workflow, _meta?: ChangeMeta)`. Import `ChangeMeta`
    from `@apitomy/flow-ui`.
  - Pass `openActionType` to the hook:
    - For a resolved value, `findActionTypeIdByName(value)` and then `navigate(\`/action-types/${id}\`)`.
    - For an unresolved value, `navigate("/action-types")`.
    - Each navigation must respect the existing unsaved-changes guard. If `dirty`, ask with `window.confirm`
      ("Leave without saving workflow changes?") before navigating.

- [ ] **Step 5: Verify**

Run `npx tsc -b --noEmit && npx vitest run`. Expected: no type errors, and all tests pass, including the
existing ones.

- [ ] **Step 6: Commit**

```bash
git add ui/package.json ui/package-lock.json ui/src/hooks ui/src/pages/WorkflowDefinitionDetailPage.tsx
git commit -m "build(ui): upgrade @apitomy/flow-ui to 3.0.1 and open action types from the editor"
```

---

### Task 2: Backend `POST /compositions/{id}/session`, and the composition API client

**Files:**
- Modify: `common/api/src/main/resources/openapi.json` and
  `app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/CompositionTurnsResourceTest.java` (extend)
- Create: `ui/src/config/compositions.ts` and `ui/src/config/compositions.test.ts`

**Interfaces:**
- Backend:
  - `POST /compositions/{compositionId}/session` (operationId `ensureCompositionSession`) returns 200
    `Composition` after calling `WorkbenchSessions.ensure(id)`, which starts or restores the agent session.
  - It returns 503 (`AgentUnavailableException`) if the agent cannot start.
  - It is not `@Transactional`, because `ensure` refuses active transactions.
- UI (`compositions.ts`):
  - **Types:** `Composition`, `CompositionItem`, `CompositionItemType = "workflow" | "action-type" | "tool"`,
    `CompositionRevision`, `ConfigProblem`, `ChangeSet` (re-exported from `@apitomy/flow-ui`),
    `UndoResult`, `PublishResult`, and `ApiError extends Error { status: number; body: unknown }`.
  - **Functions:** each throws `ApiError` on a non-2xx response, using `extractErrorMessage` for the
    message and keeping the parsed body.
    - `listCompositions()`
    - `createComposition({name, goal?, seedWorkflowId?})`
    - `fetchComposition(id)`
    - `updateComposition(id, {name?, applyMode?})`
    - `deleteComposition(id)`
    - `ensureCompositionSession(id)`
    - `listItems(id)`
    - `addItem(id, {type, name?, existingItemId?})`
    - `fetchItem(id, itemId)`
    - `saveItem(id, itemId, {content, baseRevision, turnId?})`
    - `deleteItem(id, itemId)`
    - `listRevisions(id, itemId)`
    - `restoreRevision(id, itemId, revisionId)`
    - `validateItemContent(id, itemId, content)`, returning `{valid, problems}`
    - `validateComposition(id)`
    - `publishComposition(id, itemIds, force?)`
    - `sendCompositionMessage(id, {message, nodeIds?, edgeIds?})`
    - `interruptComposition(id)`
    - `resolveChangeSet(id, {turnId, outcome, fromPending})`
    - `undoTurn(id, turnId)`
  - **SSE:** `subscribeComposition(compositionId: number, handlers: { itemChanged?, turn?, changeSet? }): () => void`
    wraps `sseClient.subscribe`, filters by `data.compositionId === compositionId`, and returns the
    unsubscribe function.

- [ ] **Step 1: Backend, write the failing test** (append to `CompositionTurnsResourceTest`):

```java
    @Test
    void ensureSessionStartsTheAgent() {
        given().post(BASE + "/{id}/session", compositionId).then().statusCode(200).body("id", equalTo((int) compositionId));
        org.mockito.Mockito.verify(sessions).ensure(compositionId);
    }
```

- [ ] **Step 2:** Run it:
  `mvn -q -pl app -am test -Dtest=CompositionTurnsResourceTest -Dsurefire.failIfNoSpecifiedTests=false`.
  Expected: FAIL with 404 or 405.
- [ ] **Step 3:** Add the path to `openapi.json`:
  - tag `Compositions`, the full `compositionId` parameter;
  - responses `200` (Composition), `404` and `503` (`CompositionConflictError` shape).

  Regenerate with `mvn -q -pl app -am compile`. Implement the method as
  `workbenchSessions.ensure(id); return toBean(service.get(id));`, without `@Transactional`.
- [ ] **Step 4:** Run the test again. Expected: PASS.
- [ ] **Step 5: UI, write the failing test** `ui/src/config/compositions.test.ts`:

```ts
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, resolveChangeSet, saveItem, subscribeComposition } from "./compositions";
import { sseClient } from "./sse";

function mockFetch(status: number, body: unknown) {
    return vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify(body), {
        status, headers: { "Content-Type": "application/json" },
    }));
}

describe("compositions api", () => {
    afterEach(() => vi.restoreAllMocks());

    it("saves an item with base revision and turn id", async () => {
        const fetchSpy = mockFetch(200, { id: 2, name: "t", type: "tool", contentRevision: "sha256:b", lastAuthor: "ai", changed: true });
        await saveItem(1, 2, { content: { name: "t" }, baseRevision: "sha256:a", turnId: "turn-1" });
        const [url, init] = fetchSpy.mock.calls[0];
        expect(String(url)).toMatch(/\/api\/v1\/compositions\/1\/items\/2$/);
        expect((init as RequestInit).method).toBe("PUT");
        expect(JSON.parse(String((init as RequestInit).body))).toEqual({ content: { name: "t" }, baseRevision: "sha256:a", turnId: "turn-1" });
    });

    it("throws ApiError with status and body on 409", async () => {
        mockFetch(409, { message: "stale", currentRevision: "sha256:z" });
        const error = await saveItem(1, 2, { content: {}, baseRevision: "x" }).catch((e) => e);
        expect(error).toBeInstanceOf(ApiError);
        expect(error.status).toBe(409);
        expect((error.body as { currentRevision: string }).currentRevision).toBe("sha256:z");
    });

    it("posts resolutions with fromPending", async () => {
        const fetchSpy = mockFetch(204, {});
        await resolveChangeSet(1, { turnId: "t", outcome: "stale", fromPending: true });
        expect(JSON.parse(String((fetchSpy.mock.calls[0][1] as RequestInit).body)))
            .toEqual({ turnId: "t", outcome: "stale", fromPending: true });
    });

    it("routes SSE events by composition", () => {
        let listener: ((e: { type: string; data: Record<string, unknown> }) => void) | undefined;
        vi.spyOn(sseClient, "subscribe").mockImplementation((l) => { listener = l; return () => {}; });
        const changeSet = vi.fn();
        subscribeComposition(5, { changeSet });
        listener!({ type: "composition-changeset", data: { compositionId: 6, turnId: "x", changeSet: {} } });
        listener!({ type: "composition-changeset", data: { compositionId: 5, turnId: "y", changeSet: { id: "y" } } });
        expect(changeSet).toHaveBeenCalledTimes(1);
        expect(changeSet).toHaveBeenCalledWith({ compositionId: 5, turnId: "y", changeSet: { id: "y" } });
    });
});
```

A 204 response with a `Response` body is fine for the mock. The client must not parse JSON on a 204
response.

- [ ] **Step 6:** Run `npx vitest run src/config/compositions.test.ts`. Expected: FAIL (module missing).
- [ ] **Step 7:** Implement `compositions.ts`:
  - Follow the `api.ts` conventions: `const API = \`${getApiBaseUrl()}/api/v1\``, and use
    `extractErrorMessage`, exporting it from `api.ts` if it isn't already.
  - Use one private `request<T>(method, path, body?)` helper that throws `ApiError` and returns `undefined`
    for 204.
  - Types mirror the OpenAPI schemas; read field names from `openapi.json`.
  - Every export has JSDoc.
- [ ] **Step 8:** Run `npx tsc -b --noEmit && npx vitest run src/config/compositions.test.ts`. Expected:
  PASS.
- [ ] **Step 9: Commit**

```bash
git add common/api/src/main/resources/openapi.json app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java \
  app/src/test/java/io/apitomy/axiom/app/composition/CompositionTurnsResourceTest.java ui/src/config
git commit -m "feat(workbench): session endpoint and UI composition client"
```

---

### Task 3: Pure workbench logic and preferences

**Files:**
- Create: `ui/src/pages/workbench/workbenchLogic.ts` and `workbenchLogic.test.ts`
- Create: `ui/src/pages/workbench/workbenchPrefs.ts` and `workbenchPrefs.test.ts`

**Interfaces (`workbenchLogic.ts`):**
- `itemStatus(problems: ConfigProblem[] | undefined): "valid" | "warnings" | "errors" | "unknown"`
- `isAiTouchedInTurn(item: CompositionItem, turnId: string | undefined): boolean` returns
  `!!turnId && item.lastTurnId === turnId`.
- `isLockedForUser(item: CompositionItem, composition: Composition): boolean` is true when
  `turnState === "ai-working"`, the item is not the workflow, and `item.lastTurnId === composition.currentTurnId`.
- `referencedActionTypes(workflow: Workflow): string[]` returns the `config.actionType` values of
  `action` nodes, excluding `sdk:`, deduplicated and in order.
- `referencedTools(actionType: Record<string, unknown>): string[]` handles `mcp__axiom-tools__<name>`
  entries, where `allowedTools` is either a comma- or whitespace-separated string or an array.
- `publishSelection(items: CompositionItem[], contents: Record<number, Record<string, unknown>>, chosen: number[]): number[]`
  adds, transitively, the changed draft dependencies of chosen items: a workflow brings its action types,
  and an action type brings its tools. Only items in the composition whose `changed` is true are added. The
  result is sorted ascending.
- `defaultPublishChoice(items: CompositionItem[], statuses: Record<number, ReturnType<typeof itemStatus>>): number[]`
  returns the ids of items that are changed and not in `"errors"`.
- `undoPlan(result: UndoResult, lastWorkflowSaveTurnId: string | undefined, turnId: string): { replaceWorkflow: Workflow | null; message: string }`.
  It returns `replaceWorkflow = result.workflowBefore` only when `result.workflowRestorable &&
  lastWorkflowSaveTurnId === turnId && result.workflowBefore`. Otherwise it is `null`, with a message
  explaining that the workflow was left alone.
- `mergeCatalog(database: ActionTypeDescriptor[], drafts: { name: string; content: Record<string, unknown> }[]): ActionTypeDescriptor[]`.
  Drafts override database entries by `value`. A draft's inputs and outputs come from
  `content.inputs` / `content.outputs`, and `label` = name. Database order is kept, with new drafts
  appended.

**Interfaces (`workbenchPrefs.ts`):**
- `type PaneSizes = { left: number; right: number }`, defaulting to `{ left: 260, right: 380 }`.
- `loadPrefs(): { applyLayout: "place" | "tidy"; focus: boolean; panes: PaneSizes; leftCollapsed: boolean; rightCollapsed: boolean }`
- `savePrefs(partial)`, which merges into the stored object.
- The key is `axiom.workbench.prefs`. Invalid JSON or values fall back to the defaults.
- Pane widths are clamped to `[160, 800]`.

- [ ] **Step 1: Write the failing tests**

```ts
import { describe, expect, it } from "vitest";
import {
    defaultPublishChoice, isLockedForUser, itemStatus, mergeCatalog, publishSelection, referencedActionTypes,
    referencedTools, undoPlan,
} from "./workbenchLogic";
import type { Composition, CompositionItem } from "../../config/compositions";

const item = (id: number, type: CompositionItem["type"], name: string, extra: Partial<CompositionItem> = {}): CompositionItem =>
    ({ id, type, name, contentRevision: "r", lastAuthor: "user", changed: true, ...extra });

describe("workbenchLogic", () => {
    it("derives item status from problems", () => {
        expect(itemStatus(undefined)).toBe("unknown");
        expect(itemStatus([])).toBe("valid");
        expect(itemStatus([{ severity: "warning", code: "c", message: "m" }])).toBe("warnings");
        expect(itemStatus([{ severity: "warning", code: "c", message: "m" }, { severity: "error", code: "c", message: "m" }])).toBe("errors");
    });

    it("locks only non-workflow items touched by the running turn", () => {
        const composition = { turnState: "ai-working", currentTurnId: "t1" } as Composition;
        expect(isLockedForUser(item(1, "tool", "a", { lastTurnId: "t1" }), composition)).toBe(true);
        expect(isLockedForUser(item(2, "tool", "b", { lastTurnId: "t0" }), composition)).toBe(false);
        expect(isLockedForUser(item(3, "workflow", "w", { lastTurnId: "t1" }), composition)).toBe(false);
        expect(isLockedForUser(item(1, "tool", "a", { lastTurnId: "t1" }), { ...composition, turnState: "idle" })).toBe(false);
    });

    it("finds references", () => {
        expect(referencedActionTypes({ id: "w", name: "w", edges: [], nodes: [
            { id: "a", type: "action", name: "A", config: { actionType: "triage" } },
            { id: "b", type: "action", name: "B", config: { actionType: "sdk:x" } },
            { id: "c", type: "action", name: "C", config: { actionType: "triage" } },
        ] } as never)).toEqual(["triage"]);
        expect(referencedTools({ allowedTools: "Read, mcp__axiom-tools__gh mcp__axiom-tools__jq" })).toEqual(["gh", "jq"]);
        expect(referencedTools({ allowedTools: ["mcp__axiom-tools__x"] })).toEqual(["x"]);
    });

    it("selects changed draft dependencies transitively", () => {
        const items = [item(1, "workflow", "wf"), item(2, "action-type", "at"), item(3, "tool", "gh"),
            item(4, "tool", "unchanged", { changed: false }), item(5, "action-type", "other")];
        const contents = {
            1: { nodes: [{ id: "a", type: "action", config: { actionType: "at" } }], edges: [] },
            2: { name: "at", allowedTools: "mcp__axiom-tools__gh mcp__axiom-tools__unchanged" },
        };
        expect(publishSelection(items, contents, [1])).toEqual([1, 2, 3]);
        expect(defaultPublishChoice(items, { 1: "valid", 2: "errors", 3: "valid", 4: "valid", 5: "warnings" })).toEqual([1, 3, 5]);
    });

    it("plans undo of the workflow only when the turn's change is the latest save", () => {
        const before = { id: "w", name: "w", nodes: [], edges: [] };
        const result = { restored: [], removed: [], skipped: [], workflowBefore: before, workflowRestorable: true };
        expect(undoPlan(result as never, "t1", "t1").replaceWorkflow).toBe(before);
        expect(undoPlan(result as never, "t2", "t1").replaceWorkflow).toBeNull();
        expect(undoPlan({ ...result, workflowRestorable: false } as never, "t1", "t1").replaceWorkflow).toBeNull();
    });

    it("merges draft action types into the catalog", () => {
        const merged = mergeCatalog(
            [{ value: "a", label: "a" }, { value: "b", label: "b", description: "db" }],
            [{ name: "b", content: { name: "b", inputs: [{ name: "x", type: "string" }] } }, { name: "c", content: { name: "c" } }]);
        expect(merged.map((d) => d.value)).toEqual(["a", "b", "c"]);
        expect(merged[1].inputs).toEqual([{ name: "x", type: "string" }]);
    });
});
```

```ts
import { beforeEach, describe, expect, it } from "vitest";
import { loadPrefs, savePrefs } from "./workbenchPrefs";

describe("workbenchPrefs", () => {
    beforeEach(() => localStorage.clear());

    it("returns defaults and merges saved values", () => {
        expect(loadPrefs()).toEqual({ applyLayout: "place", focus: false, panes: { left: 260, right: 380 },
            leftCollapsed: false, rightCollapsed: false });
        savePrefs({ applyLayout: "tidy", panes: { left: 5000, right: 10 } });
        expect(loadPrefs().applyLayout).toBe("tidy");
        expect(loadPrefs().panes).toEqual({ left: 800, right: 160 });
    });

    it("ignores corrupt storage", () => {
        localStorage.setItem("axiom.workbench.prefs", "{nope");
        expect(loadPrefs().focus).toBe(false);
    });
});
```

- [ ] **Step 2:** Run `npx vitest run src/pages/workbench`. Expected: FAIL (modules missing).
- [ ] **Step 3:** Implement both modules as pure functions with JSDoc. They must not import React.
- [ ] **Step 4:** Run `npx tsc -b --noEmit && npx vitest run src/pages/workbench`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add ui/src/pages/workbench
git commit -m "feat(workbench): pure workbench logic and preferences"
```

---
### Task 4: Extract `ActionTypeEditor`

**Files:**
- Create: `ui/src/components/ActionTypeEditor.tsx` and `ui/src/components/ActionTypeEditor.test.tsx`
- Modify: `ui/src/pages/ActionTypeDetailPage.tsx` (744 lines)

**Interfaces:**
- Produces `ActionTypeEditor(props: ActionTypeEditorProps)`:

```ts
export interface ActionTypeEditorProps {
    /** The action type as configuration-item JSON (NewActionType shape; allowedTools and environment included). */
    value: NewActionType;
    /** Called with the complete new value on every edit. */
    onChange: (value: NewActionType) => void;
    /** Disables every input and hides AI helpers and label editing. */
    readOnly?: boolean;
    /** Problems to show in the Problems tab instead of running the editor's own validation. */
    problems?: ValidationMessage[];
}
```

- **What moves into the component:**
  - all tabs (Info, Allowed Tools, Environment, Inputs, Outputs, Prompt Template, AI Config, Script, Code,
    Problems)
  - the tab-fallback effect
  - `ScriptAiModal`, `ActionTypeAiModal`, `EditLabelsModal`
  - available models and engines loading
  - debounced validation, but only when `problems` is not given
- **Folding the extra state into `value`:**
  - `tools` becomes `value.allowedTools`, using the existing string format, so check how `updateActionType`
    sends it.
  - `envVars` becomes `value.environment`.
  - The editor derives the arrays and records from `value` on every render, and writes them back through
    `onChange`.
- **What stays in `ActionTypeDetailPage`:** loading, the `actionType` record, `dirty`, saving, `saveError`,
  the page header, breadcrumbs and the Save button. It now renders
  `<ActionTypeEditor value={form} onChange={(v) => { setForm(v); setDirty(true); }} />` and saves `form`
  as is.
- **Local subcomponents:** `InfoTab`, `PromptTemplateTab` and `ScriptTab` move into `ActionTypeEditor.tsx`
  unchanged. If the file grows past about 600 lines, split them into `components/actionType/*.tsx` and note
  it in the report.
- **Behaviour must not change:** existing behaviour on `/action-types/:id` must be identical, including that
  changing `engine` resets `model`.

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ActionTypeEditor } from "./ActionTypeEditor";

vi.mock("@patternfly/react-code-editor", () => ({
    CodeEditor: ({ code }: { code: string }) => <pre data-testid="code-editor">{code}</pre>,
    Language: { json: "json", javascript: "javascript", shell: "shell", markdown: "markdown" },
}));
vi.mock("../config/api", async (orig) => ({
    ...(await orig<typeof import("../config/api")>()),
    fetchSystemConfig: vi.fn().mockResolvedValue({ engines: [] }),
    validateActionType: vi.fn().mockResolvedValue({ valid: true, errors: [], warnings: [] }),
}));

describe("ActionTypeEditor", () => {
    it("is controlled: edits call onChange with the whole value", () => {
        const onChange = vi.fn();
        render(<ActionTypeEditor value={{ name: "at", description: "old", executionMode: "node", inputs: [], outputs: [] }}
            onChange={onChange} />);
        fireEvent.change(screen.getByDisplayValue("old"), { target: { value: "new" } });
        expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ name: "at", description: "new", executionMode: "node" }));
    });

    it("shows the Code tab for node action types and disables inputs when read-only", () => {
        render(<ActionTypeEditor readOnly value={{ name: "at", description: "d", executionMode: "node",
            codeTemplate: "export async function run() { return {}; }", inputs: [], outputs: [] }} onChange={() => {}} />);
        expect(screen.getByText("Code")).toBeTruthy();
        expect((screen.getByDisplayValue("d") as HTMLInputElement).disabled || (screen.getByDisplayValue("d") as HTMLInputElement).readOnly).toBe(true);
    });

    it("shows given problems", () => {
        render(<ActionTypeEditor value={{ name: "at", executionMode: "node", inputs: [], outputs: [] }} onChange={() => {}}
            problems={[{ severity: "error", message: "codeTemplate is required" }]} />);
        expect(screen.getByText("Problems")).toBeTruthy();
    });
});
```

`ValidationMessage` is the type that `ValidationProblemsPanel` already takes. Import it from where it is
defined (find it with `git grep -n "ValidationMessage" ui/src`). Adjust the test's problem literal to that
type's real fields. Adjust the `fetchSystemConfig` mock to the function the page actually uses to load
engines and models.

- [ ] **Step 2:** Run `npx vitest run src/components/ActionTypeEditor.test.tsx`. Expected: FAIL.
- [ ] **Step 3:** Extract the editor as described. Keep every behaviour.
- [ ] **Step 4:** Run `npx tsc -b --noEmit && npx vitest run`. Expected: PASS, including
  `ActionTypeDetailModal.test.tsx` and the other existing tests.
- [ ] **Step 5: Commit**

```bash
git add ui/src/components/ActionTypeEditor.tsx ui/src/components/ActionTypeEditor.test.tsx ui/src/pages/ActionTypeDetailPage.tsx
git commit -m "refactor(ui): extract a controlled ActionTypeEditor"
```

---

### Task 5: Extract `ToolEditor`

**Files:**
- Create: `ui/src/components/ToolEditor.tsx` and `ui/src/components/ToolEditor.test.tsx`
- Modify: `ui/src/pages/ToolDetailPage.tsx` (635 lines)

**Interfaces:**

```ts
export interface ToolEditorProps {
    /** The tool as configuration-item JSON: name, description, parameters, scriptTemplate, labels. */
    value: NewToolDefinition;
    onChange: (value: NewToolDefinition) => void;
    readOnly?: boolean;
    problems?: ValidationMessage[];
    /** Saved tool id; the Test tab is shown only when set (testing drafts is Phase 5). */
    toolId?: number;
}
```

- **What moves into the component:** the tabs Info, Parameters, Script Template, Test (only when `toolId`
  is set) and Problems; the `ToolAiModal`; and `EditLabelsModal`.
- **Folding the extra state into `value`:** `params` becomes `value.parameters` and `labels` becomes
  `value.labels`.
- **What stays in `ToolDetailPage`:** loading, `dirty`, the `beforeunload` guard, save, and the header. It
  renders `<ToolEditor value={form} onChange={...} toolId={id} />`.
- **Behaviour must not change** on `/tools/:id`.

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ToolEditor } from "./ToolEditor";

vi.mock("@patternfly/react-code-editor", () => ({
    CodeEditor: ({ code }: { code: string }) => <pre data-testid="code-editor">{code}</pre>,
    Language: { shell: "shell", json: "json" },
}));
vi.mock("../config/api", async (orig) => ({
    ...(await orig<typeof import("../config/api")>()),
    validateTool: vi.fn().mockResolvedValue({ valid: true, errors: [], warnings: [] }),
}));

describe("ToolEditor", () => {
    it("is controlled", () => {
        const onChange = vi.fn();
        render(<ToolEditor value={{ name: "t", description: "old", parameters: [], scriptTemplate: "echo" }} onChange={onChange} />);
        fireEvent.change(screen.getByDisplayValue("old"), { target: { value: "new" } });
        expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ name: "t", description: "new" }));
    });

    it("hides the Test tab without a saved tool id", () => {
        const { rerender } = render(<ToolEditor value={{ name: "t", parameters: [] }} onChange={() => {}} />);
        expect(screen.queryByText("Test")).toBeNull();
        rerender(<ToolEditor value={{ name: "t", parameters: [] }} onChange={() => {}} toolId={3} />);
        expect(screen.getByText("Test")).toBeTruthy();
    });
});
```

- [ ] **Step 2:** Run `npx vitest run src/components/ToolEditor.test.tsx`. Expected: FAIL.
- [ ] **Step 3:** Extract as described.
- [ ] **Step 4:** Run `npx tsc -b --noEmit && npx vitest run`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add ui/src/components/ToolEditor.tsx ui/src/components/ToolEditor.test.tsx ui/src/pages/ToolDetailPage.tsx
git commit -m "refactor(ui): extract a controlled ToolEditor"
```

---

### Task 6: Chat panel overrides for send and interrupt

**Files:**
- Modify: `ui/src/components/assistant/AssistantChatPanel.tsx`
- Test: `ui/src/components/assistant/AssistantChatPanel.test.tsx` (create)

**Interfaces:**
- New optional props on `AssistantChatPanelProps`:
  - `onSend?: (message: string) => Promise<void>`. When given, it replaces
    `sendAssistantMessage(sessionId, message)`. The optimistic user message and the failure message work
    as before; the failure text uses the thrown error's message.
  - `onInterrupt?: () => Promise<void>`. When given, it replaces `interruptAssistantSession(sessionId)`.
  - `disabledReason?: string`. When set, the input is disabled and the reason is shown as its placeholder,
    for example "The AI is working…".
- No other behaviour changes. `/assistant/:sessionId` passes none of these props.

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { AssistantChatPanel } from "./AssistantChatPanel";
import * as api from "../../config/api";

vi.mock("../../config/sse", () => ({
    sseClient: { connect: vi.fn(), subscribe: vi.fn(() => () => {}), subscribeSession: vi.fn(() => () => {}),
        onReconnect: vi.fn(() => () => {}) },
}));

describe("AssistantChatPanel overrides", () => {
    it("uses onSend instead of the generic endpoint", async () => {
        vi.spyOn(api, "fetchAssistantSessionHistory").mockResolvedValue([]);
        const generic = vi.spyOn(api, "sendAssistantMessage").mockResolvedValue();
        const onSend = vi.fn().mockResolvedValue(undefined);
        render(<AssistantChatPanel sessionId="s1" onSend={onSend} />);
        const input = await screen.findByRole("textbox");
        fireEvent.change(input, { target: { value: "hello" } });
        fireEvent.keyDown(input, { key: "Enter" });
        await waitFor(() => expect(onSend).toHaveBeenCalledWith("hello"));
        expect(generic).not.toHaveBeenCalled();
    });

    it("disables input with a reason", async () => {
        vi.spyOn(api, "fetchAssistantSessionHistory").mockResolvedValue([]);
        render(<AssistantChatPanel sessionId="s1" disabledReason="The AI is working…" />);
        const input = await screen.findByPlaceholderText("The AI is working…");
        expect((input as HTMLTextAreaElement).disabled).toBe(true);
    });
});
```

The submit gesture (Enter versus a send button) and the input role must match the real input component.
Read `AssistantChatPanel` and its input child, and adapt the two interaction lines if needed. Keep the
assertions.

- [ ] **Step 2:** Run `npx vitest run src/components/assistant/AssistantChatPanel.test.tsx`. Expected:
  FAIL.
- [ ] **Step 3:** Implement the three props.
- [ ] **Step 4:** Run `npx tsc -b --noEmit && npx vitest run`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add ui/src/components/assistant
git commit -m "feat(ui): let the chat panel delegate send and interrupt"
```

---

### Task 7: Workbench list page, routes, navigation, chrome-less mode and "Open in workbench"

**Files:**
- Create: `ui/src/pages/WorkbenchListPage.tsx` and `ui/src/pages/WorkbenchListPage.test.tsx`
- Create: `ui/src/pages/workbench/WorkbenchPage.tsx`. In this task it is a placeholder that renders the
  composition name; Task 8 replaces it.
- Modify: `ui/src/App.tsx`, `ui/src/components/AppSidebar.tsx`, `ui/src/components/AppSidebar.test.tsx`,
  `ui/src/pages/WorkflowDefinitionDetailPage.tsx`

**Interfaces and behaviour:**
- **Routes:** `/workbench` renders `WorkbenchListPage`, and `/workbench/:compositionId` renders
  `WorkbenchPage`.
- **`App.tsx`:** treat `?chrome=none` like `?breakout=true`, so there is no masthead and no sidebar. Add
  `chrome === "none"` to the existing `isBreakout` expression. Also hide the sidebar when the workbench is
  in focus mode: `WorkbenchPage` adds `?focus=1` to the URL. Hide the masthead and sidebar when
  `location.pathname.startsWith("/workbench/")` and the `focus` query parameter is `"1"`.
- **Sidebar:** add a **Workbench** `NavItem` as the first item in the **Components** group, and add
  `"/workbench"` to `COMPONENT_PATHS`.
- **`WorkbenchListPage`:**
  - A table of compositions with columns Name, Goal, State (`idle` / `AI working`) and Updated. Each row
    links to `/workbench/:id`.
  - A **New composition** button opens a modal with Name (required) and Goal (optional). It calls
    `createComposition` then `addItem(id, { type: "workflow", name })`, so every new composition starts with
    a blank workflow, and navigates to the new workbench.
  - A Delete action per row, with a confirmation.
  - An empty state.
- **`WorkflowDefinitionDetailPage`:** add an **Open in workbench** button in the page header. It calls
  `createComposition({ name: definition.name, seedWorkflowId: id })` and navigates to `/workbench/:newId`.
  It is disabled while `dirty`, with a tooltip "Save first".

- [ ] **Step 1: Write the failing tests**

```tsx
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { WorkbenchListPage } from "./WorkbenchListPage";
import * as compositions from "../config/compositions";

describe("WorkbenchListPage", () => {
    it("lists compositions and creates one with a blank workflow", async () => {
        vi.spyOn(compositions, "listCompositions").mockResolvedValue([
            { id: 1, name: "Triage", goal: "triage issues", turnState: "idle", applyMode: "live",
                createdAt: "2026-10-12T00:00:00Z", updatedAt: "2026-10-12T00:00:00Z" },
        ]);
        const create = vi.spyOn(compositions, "createComposition").mockResolvedValue({ id: 2, name: "New", turnState: "idle",
            applyMode: "live", createdAt: "", updatedAt: "" });
        const addItem = vi.spyOn(compositions, "addItem").mockResolvedValue({} as never);
        render(<MemoryRouter initialEntries={["/workbench"]}><Routes>
            <Route path="/workbench" element={<WorkbenchListPage />} />
            <Route path="/workbench/:compositionId" element={<div>opened</div>} />
        </Routes></MemoryRouter>);
        expect(await screen.findByText("Triage")).toBeTruthy();
        fireEvent.click(screen.getByRole("button", { name: /new composition/i }));
        fireEvent.change(screen.getByLabelText(/name/i), { target: { value: "New" } });
        fireEvent.click(screen.getByRole("button", { name: /^create$/i }));
        await waitFor(() => expect(screen.getByText("opened")).toBeTruthy());
        expect(create).toHaveBeenCalledWith(expect.objectContaining({ name: "New" }));
        expect(addItem).toHaveBeenCalledWith(2, { type: "workflow", name: "New" });
    });
});
```

Extend `AppSidebar.test.tsx` with a test that the **Workbench** item exists and navigates to `/workbench`,
in the style of that file's existing tests.

- [ ] **Step 2:** Run `npx vitest run src/pages/WorkbenchListPage.test.tsx src/components/AppSidebar.test.tsx`.
  Expected: FAIL.
- [ ] **Step 3:** Implement the pages, the routes, the sidebar item, the `App.tsx` chrome rules and the
  button.
- [ ] **Step 4:** Run `npx tsc -b --noEmit && npx vitest run`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add ui/src
git commit -m "feat(workbench): list page, routes, navigation and Open in workbench"
```

---
### Task 8: Workbench page shell (load, session, SSE, panes, focus, break-out, toolbar)

**Files:**
- Create: `ui/src/pages/workbench/useWorkbench.ts`, `ui/src/pages/workbench/useWorkbench.test.tsx`
- Replace: `ui/src/pages/workbench/WorkbenchPage.tsx`; create `WorkbenchPage.css` and `WorkbenchPage.test.tsx`

**Interfaces:**
- `useWorkbench(compositionId: number)` returns a `WorkbenchState`:

```ts
export interface WorkbenchState {
    composition: Composition | null;
    items: CompositionItem[];
    /** Item content by item id, loaded on demand (the workflow is loaded eagerly). */
    contents: Record<number, Record<string, unknown>>;
    /** Problems by item id from POST /validate (refreshed on load, after saves and after turns; debounced 800 ms). */
    problems: Record<number, ConfigProblem[]>;
    /** Pending change set to apply, with where it came from. Cleared by markPendingHandled. */
    pending: { turnId: string; changeSet: ChangeSet; fromPending: boolean } | null;
    /** The last AI turn that completed in this browser session (for Undo AI turn and AI badges). */
    lastCompletedTurnId: string | undefined;
    sessionError: string | null;
    loadContent(itemId: number): Promise<Record<string, unknown>>;
    refreshItems(): Promise<void>;
    refreshComposition(): Promise<void>;
    setContent(itemId: number, content: Record<string, unknown>, revision: string): void;
    markPendingHandled(): void;
    retrySession(): Promise<void>;
}
```

**Behaviour:**
- **On mount:**
  1. Fetch the composition and its items, and load the workflow's content.
  2. Call `ensureCompositionSession`. A 503 sets `sessionError` and does not block editing.
  3. Run `validateComposition`.
  4. If `composition.pendingChangeSet` and `pendingTurnId` are set, set `pending` with `fromPending: true`.
- **SSE, through `subscribeComposition`:**
  - `itemChanged`: refresh the items. If the item's content is loaded and the event's revision differs from
    the loaded revision, reload that content. Then debounce a re-validation.
  - `turn`: refresh the composition. When `state` is `idle`, set `lastCompletedTurnId = turnId` and
    re-validate.
  - `changeSet`: set `pending` with `fromPending: false`.
- **On SSE reconnect** (`sseClient.onReconnect`): refresh the composition and items, and adopt a pending
  change set from the server if one has not already been handled.

**`WorkbenchPage` layout** (CSS grid):
- **Toolbar:**
  - composition name
  - apply mode toggle (Apply live / Review changes; `updateComposition` with `applyMode`)
  - layout preference (Place new nodes / Tidy after AI changes; `workbenchPrefs`)
  - Focus (`Ctrl+Shift+F`; toggles `?focus=1`)
  - Break out: opens `/workbench/:id?chrome=none` in a new window and shows an inline notice "Opened in
    another window" with a **Bring back here** button that dismisses it
  - **Publish…**
- **Left pane:** `CompositionTree` (Task 9). **Centre:** tabs (Tasks 10 and 11) with `ProblemsStrip` below
  them (Task 12). **Right:** `ChatPane` (Task 12).
- **Splitters:** two draggable splitters, built like the resize handle in `AssistantSubagentPanel.tsx:70-90`.
  Widths come from and are saved to `workbenchPrefs`. Each side pane has a collapse control and shrinks to a
  32 px rail; the chat rail shows a pulsing dot while the AI is working. **Maximize canvas** collapses both.
- **Tabs:** the workflow tab is always open. Item tabs open on demand, close with ✕, and the open set is kept
  in component state.

In this task the left, centre and right panes may render minimal placeholders, which Tasks 9 to 12 replace,
**except** the workflow tab placeholder must show the workflow item's name.

- [ ] **Step 1: Write the failing tests**

```tsx
import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useWorkbench } from "./useWorkbench";
import * as c from "../../config/compositions";

let sse: Parameters<typeof c.subscribeComposition>[1] = {};

beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(c, "fetchComposition").mockResolvedValue({ id: 1, name: "C", turnState: "idle", applyMode: "live",
        createdAt: "", updatedAt: "", pendingTurnId: "t0", pendingChangeSet: { id: "t0", baseRevision: "r", author: "agent:axiom-workbench", summary: "s", ops: [] } } as never);
    vi.spyOn(c, "listItems").mockResolvedValue([{ id: 10, type: "workflow", name: "wf", contentRevision: "r", lastAuthor: "user", changed: true }]);
    vi.spyOn(c, "fetchItem").mockResolvedValue({ id: 10, type: "workflow", name: "wf", contentRevision: "r", lastAuthor: "user",
        changed: true, content: { id: "w", name: "wf", nodes: [], edges: [] } } as never);
    vi.spyOn(c, "ensureCompositionSession").mockResolvedValue({ id: 1, assistantSessionId: "s1" } as never);
    vi.spyOn(c, "validateComposition").mockResolvedValue({ items: [{ itemId: 10, type: "workflow", name: "wf", valid: true, problems: [] }] });
    vi.spyOn(c, "subscribeComposition").mockImplementation((_id, handlers) => { sse = handlers; return () => {}; });
});

describe("useWorkbench", () => {
    it("loads, ensures the session and adopts a stored pending change set", async () => {
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(result.current.contents[10]).toBeTruthy());
        expect(c.ensureCompositionSession).toHaveBeenCalledWith(1);
        expect(result.current.pending).toEqual(expect.objectContaining({ turnId: "t0", fromPending: true }));
        expect(result.current.problems[10]).toEqual([]);
    });

    it("takes live change sets from SSE and records completed turns", async () => {
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(result.current.contents[10]).toBeTruthy());
        act(() => result.current.markPendingHandled());
        act(() => sse.changeSet?.({ compositionId: 1, turnId: "t1", changeSet: { id: "t1" } as never }));
        expect(result.current.pending).toEqual(expect.objectContaining({ turnId: "t1", fromPending: false }));
        await act(async () => sse.turn?.({ compositionId: 1, turnId: "t1", state: "idle" }));
        expect(result.current.lastCompletedTurnId).toBe("t1");
    });

    it("reports a session start failure without blocking", async () => {
        vi.spyOn(c, "ensureCompositionSession").mockRejectedValue(Object.assign(new c.ApiError("agent down", 503, {}), {}));
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(result.current.sessionError).toContain("agent down"));
        expect(result.current.items).toHaveLength(1);
    });
});
```

`WorkbenchPage.test.tsx` mocks `./useWorkbench` and `@apitomy/flow-ui`. It renders the page inside
`MemoryRouter` at `/workbench/1` and asserts:
- the toolbar shows the composition name;
- clicking the apply mode toggle calls `updateComposition(1, { applyMode: "review" })`;
- clicking **Focus** sets `?focus=1` (check with a `useLocation` probe);
- collapsing the left pane saves `leftCollapsed: true` (spy on `savePrefs`).

Match the `ApiError` constructor signature to Task 2's implementation.

- [ ] **Step 2:** Run `npx vitest run src/pages/workbench`. Expected: the new tests FAIL.
- [ ] **Step 3:** Implement `useWorkbench` and the page shell.
- [ ] **Step 4:** Run `npx tsc -b --noEmit && npx vitest run`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add ui/src/pages/workbench ui/src/App.tsx
git commit -m "feat(workbench): page shell with live composition state, panes and focus mode"
```

---

### Task 9: Composition tree, revisions and publish dialog (left pane)

**Files:**
- Create in `ui/src/pages/workbench/`: `CompositionTree.tsx`, `RevisionList.tsx`, `PublishDialog.tsx`, and
  `CompositionTree.test.tsx`, `PublishDialog.test.tsx`
- Modify: `WorkbenchPage.tsx` (wire them in)

**Interfaces:**
- `CompositionTree` props:
  - `items`, `problems`, `composition`, `lastCompletedTurnId`, `selectedItemId`
  - `onOpen(itemId)`
  - `onAdd(type: "action-type" | "tool")`, which opens an add dialog: a **New** name, or **Existing**, a
    select filled from `fetchActionTypes(1, 1000)` / `fetchTools(...)` (reuse existing `api.ts` functions)
  - `onRestored(item)`
- **Row rendering:**
  - Group order: Workflow, Action types, Tools.
  - Each row shows a status icon from `itemStatus` (check, warning or error icon; nothing for `unknown`), an
    **AI** label when `isAiTouchedInTurn(item, lastCompletedTurnId)`, and a dot with the tooltip
    "Unpublished changes" when `item.changed`.
  - Items locked by `isLockedForUser` show a lock icon.
- **Revisions:** selecting an item shows `RevisionList` below the tree.
  - It loads `listRevisions` and shows author (You / AI), turn and time, newest first.
  - **Restore** calls `restoreRevision`, then `onRestored(item)`.
  - For the workflow, `WorkbenchPage` applies a restored workflow through the editor handle with
    `replace(content, 'host')` (Task 10), so the canvas matches.
- **`PublishDialog`** props: `compositionId`, `items`, `contents` (it loads missing contents itself for
  dependency checks), `problems`, `onPublished(result)` and `onClose`.
  - Checkboxes start from `defaultPublishChoice`. Each change runs `publishSelection` to add dependencies,
    and dependency-added rows show "required by …".
  - **Publish** calls `publishComposition(id, chosen, false)`:
    - **409:** show the message and a **Publish anyway** button, which calls again with `force = true`.
    - **422:** list `problems` by key.
    - **Success:** show "Created N, updated M", call `onPublished`, and refresh items.

- [ ] **Step 1: Write the failing tests**

```tsx
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { PublishDialog } from "./PublishDialog";
import * as c from "../../config/compositions";

const items = [
    { id: 1, type: "workflow", name: "wf", contentRevision: "r", lastAuthor: "user", changed: true },
    { id: 2, type: "action-type", name: "at", contentRevision: "r", lastAuthor: "ai", changed: true },
] as c.CompositionItem[];
const contents = { 1: { nodes: [{ id: "a", type: "action", config: { actionType: "at" } }], edges: [] }, 2: { name: "at" } };

describe("PublishDialog", () => {
    it("auto-selects dependencies and forces after a conflict", async () => {
        const publish = vi.spyOn(c, "publishComposition")
            .mockRejectedValueOnce(new c.ApiError("Changed outside the workbench since loaded: action-type/at", 409, {}))
            .mockResolvedValueOnce({ created: 1, updated: 1, items: [] });
        const onPublished = vi.fn();
        render(<PublishDialog compositionId={9} items={items} contents={contents} problems={{ 1: [], 2: [] }}
            onPublished={onPublished} onClose={() => {}} />);
        fireEvent.click(screen.getByLabelText("at"));
        fireEvent.click(screen.getByLabelText("at"));
        expect((screen.getByLabelText("at") as HTMLInputElement).checked).toBe(true);
        fireEvent.click(screen.getByRole("button", { name: /^publish$/i }));
        await screen.findByText(/changed outside the workbench/i);
        fireEvent.click(screen.getByRole("button", { name: /publish anyway/i }));
        await waitFor(() => expect(onPublished).toHaveBeenCalled());
        expect(publish).toHaveBeenLastCalledWith(9, [1, 2], true);
    });

    it("lists blocking problems on 422", async () => {
        vi.spyOn(c, "publishComposition").mockRejectedValue(new c.ApiError("Cannot publish", 422,
            { message: "Cannot publish", problems: { "workflow/wf": [{ severity: "error", code: "x", message: "broken node" }] } }));
        render(<PublishDialog compositionId={9} items={items} contents={contents} problems={{}} onPublished={() => {}} onClose={() => {}} />);
        fireEvent.click(screen.getByRole("button", { name: /^publish$/i }));
        expect(await screen.findByText(/broken node/)).toBeTruthy();
    });
});
```

In the first test, "at" is unchecked and then checked again. Because the workflow (checked by default)
requires it, it must stay checked (dependency-locked). Implement it so that a required dependency's
checkbox is checked and disabled; then the double click is harmless.

Write `CompositionTree.test.tsx` along the same lines. It must assert:
- group order;
- the status icons (query by `aria-label` "valid", "warnings", "errors");
- the AI label for the last turn;
- the unpublished dot;
- that clicking a row calls `onOpen`.

- [ ] **Step 2:** Run `npx vitest run src/pages/workbench`. Expected: FAIL.
- [ ] **Step 3:** Implement the components and wire them into `WorkbenchPage`.
- [ ] **Step 4:** Run `npx tsc -b --noEmit && npx vitest run`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add ui/src/pages/workbench
git commit -m "feat(workbench): composition tree, revisions and publish dialog"
```

---

### Task 10: Workflow tab (Flow editor, saves, change sets, context actions)

**Files:**
- Create: `ui/src/pages/workbench/WorkflowTab.tsx` and `WorkflowTab.test.tsx`
- Modify: `WorkbenchPage.tsx` (wire it in)

**Interfaces:**

```ts
export interface WorkflowTabHandle {
    /** Saves any pending debounced change immediately. */
    flush(): Promise<void>;
    /** Replaces the editor document (host origin), e.g. for Undo AI turn or a restored revision. */
    replace(workflow: Workflow): void;
    /** Current editor selection. */
    selection(): { nodeIds: string[]; edgeIds: string[] };
}

export interface WorkflowTabProps {
    compositionId: number;
    item: CompositionItem;
    initialContent: Workflow;
    composition: Composition;
    catalog: ActionTypeDescriptor[];
    applyLayout: "place" | "tidy";
    pending: { turnId: string; changeSet: ChangeSet; fromPending: boolean } | null;
    onPendingHandled: () => void;
    onSaved: (item: CompositionItem, content: Workflow, savedTurnId: string | undefined) => void;
    onOpenActionType: (value: string, resolved: boolean) => void;
    onAskAi: (prefill: string) => void;
    ref?: React.Ref<WorkflowTabHandle>;
}
```

**Behaviour:**
- **Editor:** render `WorkflowEditor` with:
  - `ref` = the editor handle;
  - `workflow={initialContent}`, which is seed-only;
  - `theme` from `useEffectiveTheme()`;
  - `highlightApplied`;
  - `appliedChangeLayout={applyLayout}`;
  - `spi` = `{ actionTypes: catalog, openActionType, validate, contextActions }` (memoized), where:
    - `validate(workflow)` calls `validateItemContent(compositionId, item.id, workflow)` and maps the
      problems to Flow `ValidationProblem` (`severity`, `message`, and node id from `path` when it matches
      `nodes[<id>]` or `nodes/<id>`; otherwise no target);
    - `contextActions(context)` returns one item, **Ask AI about this…** (or **Ask AI to fix this** for a
      `problem` target):
      - It is disabled when `composition.turnState === "ai-working"`, and hidden when `context.readOnly`.
      - `onSelect` builds a prefill and calls `onAskAi`.
      - Prefill for a node: ``About node `<id>` (<name>, <type>): ``
      - Prefill for an edge: ``About edge `<id>` (<source> → <target>): ``
      - Prefill for a selection: ``About nodes <ids> and edges <ids>: ``
      - Prefill for a problem: ``Please fix this problem: <message> ``
      - Prefill for the canvas: an empty string.
- **Saving:** `onChange(workflow, meta)` records the content and schedules a save 500 ms later. If
  `meta.origin` starts with `agent:`, it records `turnIdForNextSave = the turn being applied`.
  - The save calls `saveItem(compositionId, item.id, { content, baseRevision: lastSavedRevision, turnId })`.
  - **On success:** update `lastSavedRevision` and call `onSaved(item, content, turnId)`.
  - **On 409:** show an inline warning "This workflow was changed elsewhere." with **Reload**. Reload calls
    `fetchItem`, then `handle.replace(content, 'host')`, sets `lastSavedRevision`, and suppresses that
    replace's save.
  - Other errors show an inline danger alert.
  - `flush()` runs any scheduled save at once and awaits it.
- **Pending change sets:** when the `pending` prop changes to a set not yet seen (tracked by `turnId`):
  1. Await `flush()`.
  2. If `composition.applyMode === "review"`, call `handle.propose(cs)`; otherwise call `handle.apply(cs)`.
  3. Resolve the change set according to this table, then call `onPendingHandled()`:

  | Handle result | Resolution |
  |---|---|
  | `apply` returns `applied` | `resolveChangeSet(..., outcome "applied", fromPending)` |
  | `rejected` with code `stale` | outcome `stale` |
  | `rejected` with any other code | outcome `rejected`, and show an alert "The AI's workflow changes could not be applied: <reason>" |
  | `propose` returns `staged` | wait for `onProposalResolved(id, outcome)` |
  | proposal `accepted` | `applied` |
  | proposal `rejected` or `withdrawn` | `rejected` |
  | proposal `stale` | `stale` |

- **`replace(workflow)`:** calls `handle.replace(workflow, 'host')`. The `onChange` that follows saves as
  the user.
- **`selection()`:** returns the last `onSelectionChange` value.

- [ ] **Step 1: Write the failing test** `WorkflowTab.test.tsx`. Mock `@apitomy/flow-ui` with a fake editor:

```tsx
import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, render, waitFor } from "@testing-library/react";
import { useImperativeHandle } from "react";
import * as c from "../../config/compositions";
import { WorkflowTab } from "./WorkflowTab";

const handle = { apply: vi.fn(), propose: vi.fn(), replace: vi.fn(), withdraw: vi.fn(), clearHighlights: vi.fn(),
    getSnapshot: vi.fn() };
let editorProps: Record<string, any> = {};
vi.mock("@apitomy/flow-ui", () => ({
    WorkflowEditor: (props: Record<string, any>) => {
        editorProps = props;
        useImperativeHandle(props.ref, () => handle);
        return <div data-testid="editor" />;
    },
}));
vi.mock("../../hooks/useTheme", () => ({ useEffectiveTheme: () => "light" }));

const base = {
    compositionId: 1,
    item: { id: 10, type: "workflow", name: "wf", contentRevision: "r0", lastAuthor: "user", changed: true } as c.CompositionItem,
    initialContent: { id: "w", name: "wf", nodes: [], edges: [] },
    composition: { id: 1, name: "C", turnState: "idle", applyMode: "live", createdAt: "", updatedAt: "" } as c.Composition,
    catalog: [], applyLayout: "place" as const, onSaved: vi.fn(), onOpenActionType: vi.fn(), onAskAi: vi.fn(),
    onPendingHandled: vi.fn(),
};

describe("WorkflowTab", () => {
    beforeEach(() => { vi.restoreAllMocks(); Object.values(handle).forEach((f) => f.mockReset()); vi.useRealTimers(); });

    it("applies a live pending change set and resolves it as applied", async () => {
        handle.apply.mockReturnValue({ status: "applied" });
        const resolve = vi.spyOn(c, "resolveChangeSet").mockResolvedValue(undefined);
        const cs = { id: "t1", baseRevision: "r", author: "agent:axiom-workbench", summary: "s", ops: [] };
        render(<WorkflowTab {...base} pending={{ turnId: "t1", changeSet: cs as never, fromPending: true }} />);
        await waitFor(() => expect(resolve).toHaveBeenCalledWith(1, { turnId: "t1", outcome: "applied", fromPending: true }));
        expect(handle.apply).toHaveBeenCalledWith(cs);
        expect(base.onPendingHandled).toHaveBeenCalled();
    });

    it("resolves stale apply results as stale", async () => {
        handle.apply.mockReturnValue({ status: "rejected", error: { code: "stale", reason: "old" } });
        const resolve = vi.spyOn(c, "resolveChangeSet").mockResolvedValue(undefined);
        render(<WorkflowTab {...base} pending={{ turnId: "t2", changeSet: { id: "t2" } as never, fromPending: false }} />);
        await waitFor(() => expect(resolve).toHaveBeenCalledWith(1, { turnId: "t2", outcome: "stale", fromPending: false }));
    });

    it("proposes in review mode and maps the proposal outcome", async () => {
        handle.propose.mockReturnValue({ status: "staged" });
        const resolve = vi.spyOn(c, "resolveChangeSet").mockResolvedValue(undefined);
        render(<WorkflowTab {...base} composition={{ ...base.composition, applyMode: "review" }}
            pending={{ turnId: "t3", changeSet: { id: "t3" } as never, fromPending: false }} />);
        await waitFor(() => expect(handle.propose).toHaveBeenCalled());
        act(() => editorProps.onProposalResolved("t3", "accepted"));
        await waitFor(() => expect(resolve).toHaveBeenCalledWith(1, { turnId: "t3", outcome: "applied", fromPending: false }));
    });

    it("saves agent-origin changes with the turn id after the debounce", async () => {
        handle.apply.mockReturnValue({ status: "applied" });
        vi.spyOn(c, "resolveChangeSet").mockResolvedValue(undefined);
        const save = vi.spyOn(c, "saveItem").mockResolvedValue({ ...base.item, contentRevision: "r1" });
        render(<WorkflowTab {...base} pending={{ turnId: "t4", changeSet: { id: "t4" } as never, fromPending: false }} />);
        await waitFor(() => expect(handle.apply).toHaveBeenCalled());
        const next = { id: "w", name: "wf", nodes: [{ id: "n" }], edges: [] };
        act(() => editorProps.onChange(next, { contentRevision: "x", origin: "agent:axiom-workbench" }));
        await waitFor(() => expect(save).toHaveBeenCalledWith(1, 10, { content: next, baseRevision: "r0", turnId: "t4" }), { timeout: 2000 });
    });

    it("offers a reload on a 409 save", async () => {
        vi.spyOn(c, "saveItem").mockRejectedValue(new c.ApiError("stale", 409, { currentRevision: "r9" }));
        const { findByRole } = render(<WorkflowTab {...base} pending={null} />);
        act(() => editorProps.onChange({ id: "w", name: "wf", nodes: [], edges: [] }, { contentRevision: "x", origin: "user" }));
        expect(await findByRole("button", { name: /reload/i }, { timeout: 2000 })).toBeTruthy();
    });

    it("context actions prefill the chat and disable during AI turns", () => {
        render(<WorkflowTab {...base} pending={null} composition={{ ...base.composition, turnState: "ai-working" }} />);
        const actions = editorProps.spi.contextActions({ target: { kind: "node", nodeId: "a" }, readOnly: false,
            workflow: { nodes: [{ id: "a", name: "A", type: "action" }], edges: [] } });
        expect(actions[0].disabled).toBe(true);
        actions[0].onSelect({ target: { kind: "node", nodeId: "a" }, workflow: { nodes: [{ id: "a", name: "A", type: "action" }], edges: [] } });
        expect(base.onAskAi).toHaveBeenCalledWith("About node `a` (A, action): ");
    });
});
```

- [ ] **Step 2:** Run `npx vitest run src/pages/workbench/WorkflowTab.test.tsx`. Expected: FAIL.
- [ ] **Step 3:** Implement `WorkflowTab`.
  - Use a `useRef` for `lastSavedRevision`, the pending-save timer, `turnIdForNextSave` and the set of
    handled turn ids.
  - Wire it into `WorkbenchPage`:
    - `catalog` = `mergeCatalog(await loadEditorActionTypes(), draft action types from contents)`. Recompute
      it when an action-type item's content or name changes.
    - `onOpenActionType`: if a draft item with that name exists, open its tab. Otherwise, if resolved, add the
      existing database item (`addItem` with `existingItemId` from `findActionTypeIdByName`) and open it. If
      unresolved, create a blank draft with that name and open it.
    - `onSaved`: record `lastWorkflowSaveTurnId` for `undoPlan`.
- [ ] **Step 4:** Run `npx tsc -b --noEmit && npx vitest run`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add ui/src/pages/workbench
git commit -m "feat(workbench): workflow tab with AI change sets, saves and context actions"
```

---
### Task 11: Action type and tool tabs

**Files:**
- Create: `ui/src/pages/workbench/ItemTab.tsx` and `ItemTab.test.tsx`
- Modify: `WorkbenchPage.tsx` (wire it in)

**Interfaces:**

```ts
export interface ItemTabProps {
    compositionId: number;
    item: CompositionItem;
    content: Record<string, unknown>;
    composition: Composition;
    problems: ConfigProblem[] | undefined;
    onSaved: (item: CompositionItem, content: Record<string, unknown>) => void;
}
```

**Behaviour:**
- **Editor:** render `ActionTypeEditor` for `action-type` items and `ToolEditor` for `tool` items, with:
  - `value` = local state, initialised from `content`;
  - `readOnly = isLockedForUser(item, composition)`;
  - `problems` = the mapped `problems`;
  - `toolId` = `item.baseItemId`, for tools.
- **Locked banner:** while locked, show the inline banner "The AI is editing this item. It will be editable
  when the turn ends."
- **Saving:** `onChange` updates the local value and schedules a save 500 ms later with
  `saveItem(compositionId, item.id, { content, baseRevision })`. `baseRevision` starts at
  `item.contentRevision` and is updated from each save response. On success, call `onSaved`.
  - **On 409:** show the warning "Changed elsewhere" with **Reload**, which calls `fetchItem` and resets the
    local value and base.
- **External changes:** when the `content` prop changes from outside (an SSE refresh), adopt it only if no
  local save is pending. Otherwise keep the local value, and the next save's 409 offers the reload.
- **Renames:** the name comes from `content.name`. When a save renames an item, the tab title changes after
  `onSaved`, because `WorkbenchPage` refreshes the items.

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import * as c from "../../config/compositions";
import { ItemTab } from "./ItemTab";

vi.mock("../../components/ToolEditor", () => ({
    ToolEditor: ({ value, onChange, readOnly }: { value: { description?: string }; onChange: (v: unknown) => void; readOnly?: boolean }) => (
        <input aria-label="description" value={value.description ?? ""} disabled={readOnly}
            onChange={(e) => onChange({ ...value, description: e.target.value })} />),
}));
vi.mock("../../components/ActionTypeEditor", () => ({ ActionTypeEditor: () => <div /> }));

const item = { id: 5, type: "tool", name: "t", contentRevision: "r0", lastAuthor: "user", changed: true, lastTurnId: "t9" } as c.CompositionItem;
const composition = { id: 1, name: "C", turnState: "idle", applyMode: "live", createdAt: "", updatedAt: "" } as c.Composition;

describe("ItemTab", () => {
    it("saves edits with the base revision", async () => {
        const save = vi.spyOn(c, "saveItem").mockResolvedValue({ ...item, contentRevision: "r1" });
        render(<ItemTab compositionId={1} item={item} content={{ name: "t", description: "" }} composition={composition}
            problems={[]} onSaved={() => {}} />);
        fireEvent.change(screen.getByLabelText("description"), { target: { value: "new" } });
        await waitFor(() => expect(save).toHaveBeenCalledWith(1, 5, { content: { name: "t", description: "new" }, baseRevision: "r0" }), { timeout: 2000 });
    });

    it("is read-only while the AI edits it in the running turn", () => {
        render(<ItemTab compositionId={1} item={item} content={{ name: "t" }}
            composition={{ ...composition, turnState: "ai-working", currentTurnId: "t9" }} problems={[]} onSaved={() => {}} />);
        expect((screen.getByLabelText("description") as HTMLInputElement).disabled).toBe(true);
        expect(screen.getByText(/the ai is editing this item/i)).toBeTruthy();
    });
});
```

- [ ] **Step 2:** Run `npx vitest run src/pages/workbench/ItemTab.test.tsx`. Expected: FAIL.
- [ ] **Step 3:** Implement `ItemTab` and wire item tabs into `WorkbenchPage`. Opening an item loads its
  content with `loadContent`.
- [ ] **Step 4:** Run `npx tsc -b --noEmit && npx vitest run`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add ui/src/pages/workbench
git commit -m "feat(workbench): action type and tool tabs"
```

---

### Task 12: Chat pane, Undo AI turn and the Problems strip

**Files:**
- Create in `ui/src/pages/workbench/`: `ChatPane.tsx`, `ChatPane.test.tsx`, `ProblemsStrip.tsx`
- Modify: `ui/src/components/assistant/AssistantChatPanel.tsx`. Add the prop
  `prefill?: { text: string; nonce: number }`: when `nonce` changes, put `text` into the input and focus it.
  Extend `AssistantChatPanel.test.tsx` with a test.
- Modify: `WorkbenchPage.tsx` (wire them in)

**Interfaces:**

```ts
export interface ChatPaneProps {
    composition: Composition;
    sessionError: string | null;
    lastCompletedTurnId: string | undefined;
    prefill: { text: string; nonce: number } | undefined;
    /** Flushes pending saves and returns the current canvas selection. */
    beforeSend: () => Promise<{ nodeIds: string[]; edgeIds: string[] }>;
    onRetrySession: () => void;
    /** Applies the undo plan's workflow replacement in the editor. */
    onReplaceWorkflow: (workflow: Workflow) => void;
    lastWorkflowSaveTurnId: string | undefined;
    onUndone: () => void;
}
```

**Behaviour:**
- **No session:** if `sessionError` is set, show a danger alert with **Retry**. If
  `composition.assistantSessionId` is missing, show "Starting the AI…".
- **Chat:** otherwise render
  `<AssistantChatPanel sessionId={assistantSessionId} onSend=… onInterrupt=… disabledReason=… prefill=… />`:
  - `onSend(message)`: `const sel = await beforeSend(); await sendCompositionMessage(composition.id, { message, ...sel });`.
    On a 409, throw `Error("The AI is already working on a turn.")`.
  - `onInterrupt`: `interruptComposition(composition.id)`.
  - `disabledReason`: "The AI is working…" while `turnState === "ai-working"`.
- **Undo bar:** when `lastCompletedTurnId` is set and the turn state is `idle`, show a bar above the chat:
  "Last AI turn · **Undo AI turn**". Undo calls `undoTurn(composition.id, turnId)`, then
  `undoPlan(result, lastWorkflowSaveTurnId, turnId)`:
  - If the plan has `replaceWorkflow`, call `onReplaceWorkflow` with it.
  - Then show an info alert summarising what was restored, removed and skipped, plus the plan's message, and
    call `onUndone()`.
  - A 409 shows "Wait for the AI turn to finish before undoing."
- **`ProblemsStrip`:** a collapsible strip under the centre tabs with the title "Problems (N)". It lists the
  active item's problems as `severity · path · message`. When there are none it shows "No problems". The
  "Test output" area is deferred to Phase 5, so add no placeholder.

- [ ] **Step 1: Write the failing tests**

```tsx
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import * as c from "../../config/compositions";
import { ChatPane } from "./ChatPane";

let chatProps: Record<string, any> = {};
vi.mock("../../components/assistant/AssistantChatPanel", () => ({
    AssistantChatPanel: (props: Record<string, any>) => { chatProps = props; return <div data-testid="chat" />; },
}));

const composition = { id: 1, name: "C", turnState: "idle", applyMode: "live", createdAt: "", updatedAt: "",
    assistantSessionId: "s1" } as c.Composition;
const props = { composition, sessionError: null, lastCompletedTurnId: "t1", prefill: undefined,
    beforeSend: vi.fn().mockResolvedValue({ nodeIds: ["a"], edgeIds: [] }), onRetrySession: vi.fn(),
    onReplaceWorkflow: vi.fn(), lastWorkflowSaveTurnId: "t1", onUndone: vi.fn() };

describe("ChatPane", () => {
    it("sends through the composition with the selection after flushing", async () => {
        const send = vi.spyOn(c, "sendCompositionMessage").mockResolvedValue(composition);
        render(<ChatPane {...props} />);
        await chatProps.onSend("add a log step");
        expect(props.beforeSend).toHaveBeenCalled();
        expect(send).toHaveBeenCalledWith(1, { message: "add a log step", nodeIds: ["a"], edgeIds: [] });
    });

    it("undoes the last turn and replaces the workflow when allowed", async () => {
        const before = { id: "w", name: "wf", nodes: [], edges: [] };
        vi.spyOn(c, "undoTurn").mockResolvedValue({ restored: ["tool/t"], removed: [], skipped: [],
            workflowBefore: before, workflowRestorable: true } as never);
        render(<ChatPane {...props} />);
        fireEvent.click(screen.getByRole("button", { name: /undo ai turn/i }));
        await waitFor(() => expect(props.onReplaceWorkflow).toHaveBeenCalledWith(before));
        expect(await screen.findByText(/tool\/t/)).toBeTruthy();
    });

    it("disables the chat while the AI works and shows session errors", () => {
        const { rerender } = render(<ChatPane {...props} composition={{ ...composition, turnState: "ai-working" }} />);
        expect(chatProps.disabledReason).toBe("The AI is working…");
        rerender(<ChatPane {...props} sessionError="agent down" />);
        expect(screen.getByText(/agent down/)).toBeTruthy();
        expect(screen.getByRole("button", { name: /retry/i })).toBeTruthy();
    });
});
```

- [ ] **Step 2:** Run `npx vitest run src/pages/workbench/ChatPane.test.tsx src/components/assistant`.
  Expected: FAIL.
- [ ] **Step 3:** Implement and wire `ChatPane` and `ProblemsStrip` into `WorkbenchPage`:
  - `beforeSend` calls the workflow tab's `flush()` and `selection()`.
  - `onReplaceWorkflow` calls the workflow tab's `replace()`.
  - Context actions call `onAskAi`, which sets `prefill = { text, nonce: Date.now() }`.
- [ ] **Step 4:** Run `npx tsc -b --noEmit && npx vitest run`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add ui/src
git commit -m "feat(workbench): chat pane with undo AI turn and problems strip"
```

---

### Task 13: Verification, including the first live end-to-end run

**Files:** none, apart from fixes found here (each in its own commit).

- [ ] **Step 1: Automated**
  - Run `cd ui && npx tsc -b --noEmit && npx vitest run`. Expected: everything passes.
  - Run `mvn -q package` from the repository root. Expected: exit 0, and no `<failure` or `<error` in the
    Surefire reports. Report both totals.
- [ ] **Step 2: Live run.** This step needs a running Axiom and a real engine. Ask the user to start
  `quarkus:dev` from this branch's checkout, or get their approval to start it. Never run `./build.sh` or
  `mvn clean`. Then use Chrome DevTools MCP against `http://localhost:9090`. Record each check as passed or
  failed, with notes:
  1. Create a new composition on `/workbench`. The workbench opens with a blank workflow, and the chat starts
     with "Starting the AI…" followed by the welcome message.
  2. Ask: "Add a node action type that returns {greeting: 'hi'} and call it from the workflow between Start
     and End."
     - The AI uses its tools: watch `get_composition`, `upsert_item` and `propose_workflow_changes` in the
       chat.
     - When the turn ends, the canvas updates with highlights, the tree shows the AI badge, and the action
       type tab opens with its code.
  3. Edit the workflow by hand (rename a node). Then ask the AI to change something else. The preamble
     mentions your edit (check the session history or `raw-events`), and the AI does not undo your rename.
  4. Switch to **Review changes**, ask for another change, and Accept it in the review bar.
  5. **Undo AI turn** restores the previous state.
  6. **Stale path:** while the AI is working, rename a node that it is about to change. When the turn ends,
     the change set is stale, and the AI is re-prompted once.
  7. **Publish…**: publish the workflow together with its action type. It appears under Workflows and Action
     Types.
  8. **Focus mode** and **Break out** work, and the break-out window shows no masthead.
  9. Restart the dev server, reopen the composition, and send a message. The history is restored and the
     recap is sent.
- [ ] **Step 3: Report.** List what passed. List what failed, with screenshots or console errors. List what
  could not be checked, for example a missing engine. Do not claim a live pass that was not observed.
