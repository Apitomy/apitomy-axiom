# Engine-Neutral Assistant Session UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Make the assistant chat UI work equally well for Claude Code and OpenCode sessions (GitHub issue #380,
epic #387):
- Show which engine a session uses.
- Remove Claude-specific wording.
- Understand OpenCode tool names and input fields in tool previews, colours and auto-approve suggestions.
- Show token counts when the cost is 0.
- Mark tool errors reliably.
- Don't end the "working" state on non-terminal notices.

**Architecture:** A small backend part and a UI part.
- **Backend:**
  - Add `engine` to `AssistantSessionInfo`. This is API-first: add it to the OpenAPI spec, regenerate, then set it
    in `AssistantResourceImpl`.
  - Add an explicit `isError` flag to `tool_result` events from both engines.
- **UI:**
  - A new pure module `ui/src/components/assistant/toolNames.ts` maps engine-specific tool names and input fields
    to one vocabulary. `AssistantToolUseBlock` uses it.
  - Engine labels go in the session header, the sessions list and the create modal.
  - Text changes, token label, error flag, and a set of non-terminal `session_error` names.

**Tech Stack:** Java 21 / Quarkus (OpenAPI-generated JAX-RS beans), React + TypeScript + PatternFly, Vite. There
is **no UI unit-test framework**, so UI changes are verified with `npm run build` (`tsc -b && vite build`) and a
manual/browser check.

**Spec:** GitHub issue #380, including the follow-up comment from #375.

## Background (verified facts)

- **`AssistantSessionInfo`** (OpenAPI `components.schemas.AssistantSessionInfo`) has these properties: `id`,
  `name`, `status`, `createdAt`, `lastActivityAt`, `errorMessage`, `templateId`, `totalCostUsd`,
  `totalInputTokens`, `totalOutputTokens`, `turnCount`, `projectId`, `projectName`, `allowAll`. There is **no
  engine**.
  - `AssistantResourceImpl` builds it at ~line 611 (`new AssistantSessionInfo()` … `setTotalCostUsd`).
  - `AssistantSession.getEngineType()` exists and returns e.g. `claude-code` or `opencode`.
  - The UI type is `AssistantSessionInfo` in `ui/src/config/api.ts` (~line 1260).
- **API-first rule** (`CLAUDE.md`):
  - Edit `common/api/src/main/resources/openapi.json`.
  - Run `mvn install` for `common/api` to regenerate `io.apitomy.axiom.api.beans`.
  - Implement the change in `app/.../rest/`.
- **Hard-coded Claude text:**
  - `AssistantMessageList.tsx:246`: `"Claude is working..."`
  - `AssistantAskUserQuestion.tsx:118`: `Claude has a question`
  - `thinkingMessages.ts:2`: `"Claude is working..."`
  - `thinkingMessages.ts:15`: `"Nobody puts Claude in a corner..."`
- **`AssistantToolUseBlock.tsx`:**
  - `getToolColor`: special names `AskUserQuestion`, `EnterPlanMode`, `Agent`; prefixes `mcp__axiom-sdk__`,
    `mcp__axiom__` (green), `mcp__axiom-tools__` (purple), `mcp__` (grey); anything else blue.
  - `getContextSummary(toolName, input)`: `Bash` → `command`; `Write`/`Edit`/`Read` → `file_path`; `Agent` →
    `description`/`prompt`; otherwise a JSON excerpt.
  - `getFieldInfo`: `Bash` → `command`; `Read`/`Write`/`Edit` → `file_path`.
  - `getSuggestedPatterns(toolName, input)` depends on the same names and fields.
  - Around line 94: `toolName === "Agent"` enables subagent click-through.
- **OpenCode tool names and inputs** (from the #372 fixtures and the live sessions in this epic):
  - `bash {command, workdir?, description?}`
  - `read {filePath, offset?, limit?}`
  - `write {filePath, content}`
  - `edit {filePath, oldString, newString, replaceAll?}`
  - `glob {pattern, path?}`
  - `grep {pattern, path?, include?}`
  - `list {path?}`
  - `task {description, prompt, subagent_type}`
  - `webfetch {url, format?}`
  - `todowrite {todos}`
  - MCP tools are named `<server>_<tool>`, e.g. `axiom_axiom_list_tools`.
  - Guard permissions (`external_directory`, `doom_loop`) use their own names (see #373).
- **Auto-approve rules** (`AssistantSession.checkAutoApproval`) match `rule.toolName == permission toolName`
  exactly, and match fields by `toolInput.<fieldName>` regex. For OpenCode the permission `toolName` is the
  opencode tool name (`bash`, `write`, …) and the fields are `command` / `filePath`. Suggestions must therefore
  keep the **real** tool name and the **real** field key; only the logic is normalized.
- **Tool results:**
  - UI (`AssistantChatPanel.tsx:~123`): `isError: !!data.stderr && !data.stdout`.
  - OpenCode normalizer: an error part becomes `stdout ""`, `stderr <error>`, so errors are detected.
  - Claude parser (`AssistantEventParser.parseUser`): reads `tool_use_result.stdout/stderr` and ignores the
    `tool_result` block's `is_error`. Claude tool failures without stderr (e.g. a failing Read) therefore show as
    success.
- **Header** (`AssistantSessionPage.tsx:~309`): the cost label is shown only when `sessionCost > 0`, with tokens in
  a tooltip.
- **`session_error` handling** (`AssistantChatPanel.tsx:~474`): it adds a system message **and**
  `setIsProcessing(false)`. The non-terminal notices emitted by the backend in this epic are:
  - `McpServerUnavailable`
  - `ModelUnavailable`
  - `MessageNotDelivered`
  - `InterruptFailed`
  - `EventStreamReconnected`

  Terminal errors come from opencode `session.error` (names such as `ProviderAuthError`, `UnknownError`,
  `APIError`) or have no name.
- **Engine info:** `ui/src/config/api.ts` `SystemConfig` has `engine?`, `defaultEngine?` and `engines?`; templates
  have an optional `engine`. The create modal (`CreateSessionModal.tsx`) lists `SessionTemplate`s.
  `AiConfigTab.tsx:19-23` maps `claude-code` → "Claude Code", `opencode` → "OpenCode", `copilot` → "Copilot".

**Rulings:**
- **Allowed Tools autocomplete stays as it is.** Since #371, allowed tools are written in the engine-neutral
  (Claude-style) format and translated for OpenCode. Suggesting opencode-native names would produce entries the
  mapper doesn't understand. Only its doc comment changes to say the format is engine-neutral.
- **Tool names are not rewritten in the backend.** The UI normalizes for display and logic, and rules keep the
  real names.
- **No "queued" badge.** Optional and deferred. Only the processing-state fix.
- **Show tokens when cost is 0:** if `sessionCost > 0`, the label shows `$x.xxxx` with a tokens tooltip (as
  today). Otherwise, if any tokens exist, a label `N tokens` with an in/out tooltip.
- **One shared display-name map** for the engine label: `claude-code` → "Claude Code", `opencode` → "OpenCode",
  `copilot` → "Copilot", anything else → the raw id. `AiConfigTab` reuses it.

## Global Constraints

- Java: 4-space indentation; Javadoc on public members; explicit types; JUnit 5. TypeScript: follow the
  surrounding style (4-space indentation, named exports, no `any`).
- API-first: the `engine` property is added to `openapi.json` first. Generated beans are never edited by hand.
- Claude behaviour must not regress. Claude tool names, fields, plan mode, `AskUserQuestion` and `Agent` all keep
  working.
- Build checks: `mvn -q install -DskipTests` for `common/api`, `mvn -q -pl app test`, and `cd ui && npm run build`.
  Don't commit `ui/package-lock.json` or `dist`.
- Commit style: conventional commits. No AI attribution. No closing keywords directly before issue numbers.

---

### Task 1: Backend: session engine and tool error flag

**Files:**
- `common/api/src/main/resources/openapi.json`
- `app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java`
- `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantEventParser.java`
- `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventNormalizer.java`
- Tests:
  - `AssistantEventParserTest`
  - `OpenCodeEventNormalizerTest`
  - A REST test for session info. Find the existing assistant REST test; if there is none, test the mapping
    method directly.

**Steps:**
- [ ] **1. OpenAPI:** add to `AssistantSessionInfo.properties`:
  `"engine": {"description": "Interactive engine running the session (e.g. claude-code, opencode)", "type": "string"}`.
  Regenerate with `mvn -q -pl common/api install -DskipTests`, and check that the generated bean has
  `getEngine`/`setEngine`.
- [ ] **2. Failing tests:**
  - **Claude parser:** a `user` NDJSON line whose `message.content[0]` is
    `{"type":"tool_result","tool_use_id":"tu_1","is_error":true,"content":"File does not exist"}`, with
    `tool_use_result` = `"Error: File does not exist"` (a string, not an object), produces `tool_result` with
    `isError == true`, `toolUseId == "tu_1"` and stderr containing "File does not exist". Also, an existing
    success case has `isError == false`.
    - Check how `parseUser` currently handles a string `tool_use_result`. If it drops the text, put the block's
      `content` text in `stderr` when `is_error` is true.
  - **OpenCode normalizer:** the fixture error part → `isError == true`; a completed part → `isError == false`.
  - **Session info:** the mapping sets `engine` from `session.getEngineType()`.
- [ ] **3. Implement:**
  - Add `data.put("isError", …)` in both engines' `tool_result`. Keep `stdout`/`stderr` unchanged, except for
    the Claude string-result case above.
  - Add `info.setEngine(session.getEngineType())`.
- [ ] **4. Run:** `mvn -q -pl app test -Dtest='AssistantEventParserTest,OpenCode*Test,<rest test>'
  -Dsurefire.failIfNoSpecifiedTests=false`, then the full `mvn -q -pl app test`.
- [ ] **5. Commit:** `feat(assistant): expose session engine and explicit tool error flag (#380)`

---

### Task 2: UI: tool name normalization and engine-neutral text

**Files:**
- Create: `ui/src/components/assistant/toolNames.ts`
- Modify: `AssistantToolUseBlock.tsx`, `AssistantMessageList.tsx`, `AssistantAskUserQuestion.tsx`,
  `thinkingMessages.ts`, `ui/src/components/AddToolInput.tsx` (doc comment only)

**Interfaces** (`toolNames.ts`, all exported and pure):

```ts
/** Canonical, engine-neutral tool kinds used by the chat UI. */
export type ToolKind = "bash" | "read" | "write" | "edit" | "glob" | "grep" | "list" | "agent" | "webfetch"
    | "websearch" | "todo" | "askUser" | "enterPlanMode" | "exitPlanMode" | "mcp" | "other";

/** Returns the canonical kind for a Claude Code or OpenCode tool name. */
export function toolKind(toolName: string): ToolKind;

/** Returns the MCP server name for Claude (`mcp__server__tool`) or OpenCode (`server_tool`) names, else null. */
export function mcpServer(toolName: string, knownServers?: readonly string[]): string | null;

/** Returns the input key holding the file path (`file_path` for Claude, `filePath` for OpenCode), if present. */
export function filePathField(input?: Record<string, unknown>): "file_path" | "filePath" | undefined;
```

- **Kind mapping (exact names):**
  - `Bash`/`bash` → `bash`
  - `Read`/`read` → `read`
  - `Write`/`write` → `write`
  - `Edit`/`MultiEdit`/`edit` → `edit`
  - `Glob`/`glob` → `glob`
  - `Grep`/`grep` → `grep`
  - `LS`/`list` → `list`
  - `Agent`/`Task`/`task` → `agent`
  - `WebFetch`/`webfetch` → `webfetch`
  - `WebSearch`/`websearch` → `websearch`
  - `TodoWrite`/`todowrite` → `todo`
  - `AskUserQuestion` → `askUser`
  - `EnterPlanMode` → `enterPlanMode`
  - `ExitPlanMode` → `exitPlanMode`
  - names starting with `mcp__`, or where `mcpServer(...)` is non-null → `mcp`
  - otherwise → `other`
- **OpenCode MCP detection:** `knownServers` defaults to `["axiom", "axiom-tools", "axiom-sdk"]`. A name is
  treated as MCP when it starts with `<known>_`. Other `<x>_<y>` names count as MCP only when `x` is not a known
  built-in; it's safest to treat any name containing `_` that is not a built-in kind as `mcp`.

**Steps:**
- [ ] **1. Create `toolNames.ts`** as specified, with a JSDoc comment on each export.
- [ ] **2. `AssistantToolUseBlock.tsx`:**
  - **`getToolColor`:**
    - `askUser` → teal; `enterPlanMode` or `agent` → orange.
    - MCP server `axiom` or `axiom-sdk` → green (this covers the `mcp__axiom__…` and `axiom_…` forms).
    - `axiom-tools` → purple; other MCP → grey; else blue.
  - **`getContextSummary`**, by kind:
    - `bash` → `input.command`
    - `write` / `edit` / `read` → `Write to:` / `Edit:` / `Read:` + `input[filePathField(input)]`
    - `agent` → description/prompt
    - `glob` / `grep` → `input.pattern` (+ ` in ${input.path}` if present)
    - `webfetch` → `input.url`
    - else the existing JSON fallback
  - **`getFieldInfo`:** `bash` → `command`; `read`/`write`/`edit` → `filePathField(input)` (pass `input` in).
  - **`getSuggestedPatterns`:** use the kinds, and keep the **real** `toolName` in the labels. Use
    `filePathField(input)` as the `fieldName` of path patterns.
  - **Subagent click-through:** `toolKind(toolName) === "agent"` instead of `=== "Agent"`. Keep the other
    `AskUserQuestion` / `ExitPlanMode` checks **unchanged** (they are Claude-only permission UIs).
- [ ] **3. Text:**
  - `AssistantMessageList.tsx`: `"Claude is working..."` → `"Working..."`.
  - `AssistantAskUserQuestion.tsx`: `Claude has a question` → `The assistant has a question`.
  - `thinkingMessages.ts`: replace the two Claude-specific entries with neutral ones ("Working...", "Thinking it
    through...").
- [ ] **4. `AddToolInput.tsx`:** change the `BUILTIN_TOOLS` doc comment to say these are engine-neutral allowed
  tool names (Claude Code format), which are translated to OpenCode permissions for OpenCode sessions.
- [ ] **5. Build:** `cd ui && npm run build` passes. If `node_modules` is missing, run `npm ci` first and don't
  commit the lockfile.
- [ ] **6. Commit:** `feat(ui): engine-neutral tool previews and assistant wording (#380)`

---

### Task 3: UI: engine labels, token label, error flag, non-terminal notices

**Files:**
- `ui/src/config/api.ts` (`AssistantSessionInfo.engine?`)
- New `ui/src/components/engineNames.ts`
- `ui/src/components/AiConfigTab.tsx`
- `ui/src/pages/AssistantSessionPage.tsx`
- `ui/src/pages/AssistantPage.tsx`
- `ui/src/components/assistant/CreateSessionModal.tsx`
- `ui/src/components/assistant/AssistantChatPanel.tsx`

**Steps:**
- [ ] **1. `engineNames.ts`:** `export function engineDisplayName(engine?: string | null): string | null`
  implements the shared map from the Rulings (`null` when the engine is empty). Make `AiConfigTab.tsx` use it in
  place of its local map, keeping the same labels.
- [ ] **2. `api.ts`:** add `engine?: string` to `AssistantSessionInfo`.
- [ ] **3. Session header** (`AssistantSessionPage.tsx`): next to the model label, show
  `<Label color="blue" isCompact className="axiom-session-page__header-label">{engineDisplayName(session.engine)}</Label>`
  when it is non-null.
  - **Token label:** keep the `$` label when `sessionCost > 0`. Otherwise, if
    `sessionInputTokens + sessionOutputTokens > 0`, show a grey compact label with the total, e.g.
    `${(in+out).toLocaleString()} tokens`, and a tooltip `Tokens in: … / out: …`.
- [ ] **4. Sessions list** (`AssistantPage.tsx`): show the engine display name as a small grey compact label next
  to the status label for each session.
- [ ] **5. Create modal** (`CreateSessionModal.tsx`):
  - Each template item shows its engine: `template.engine`, or else the system default engine from the
    already-available system config.
  - If the modal doesn't have the system config, fetch it once with the existing config API call used elsewhere.
    Search `api.ts` for the config fetch function.
  - Label it "Default (<name>)" when the engine comes from the default.
- [ ] **6. Chat panel** (`AssistantChatPanel.tsx`):
  - **`tool_result`:** `isError: typeof data.isError === "boolean" ? data.isError : (!!data.stderr &&
    !data.stdout)`.
  - **`session_error`:** add `const NON_TERMINAL_SESSION_ERRORS = new Set(["McpServerUnavailable",
    "ModelUnavailable", "MessageNotDelivered", "InterruptFailed", "EventStreamReconnected"]);` at module level.
    Still add the system message, but only call `setIsProcessing(false)` when `!NON_TERMINAL_SESSION_ERRORS.has(
    data.name as string)`.
- [ ] **7. Build:** `cd ui && npm run build` passes.
- [ ] **8. Commit:** `feat(ui): show assistant session engine and token usage; keep working state on notices (#380)`
- [ ] **9. Manual/browser check** (controller or human):
  - **OpenCode session:** the header shows "OpenCode", the model, and a token or cost label. Tool blocks for
    `bash`, `read`, `glob` and `axiom_axiom_list_tools` show a context summary (command, file path, pattern) and
    the right colours. Allow Pattern on `bash ls` suggests `ls *` for field `command`.
  - **Claude session:** unchanged behaviour, and the header shows "Claude Code".
  - **Sessions list and create modal** show engines.
  - "Working..." replaces "Claude is working...".
