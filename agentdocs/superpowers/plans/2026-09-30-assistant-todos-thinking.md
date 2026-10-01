# Assistant Todos and Thinking Content Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Show the assistant's todo list and its thinking content in the session chat, for both Claude Code and
OpenCode sessions (GitHub issue #382, epic #387).

**Architecture:**
- **Backend:** both engines emit one new event type, `todos`, carrying the full current list. Claude's comes from
  its `TodoWrite` tool call; OpenCode's comes from its `todo.updated` event. `thinking` events gain optional `id`
  and `text` fields.
- **UI:** a collapsible todo panel at the top of the chat panel shows the latest list. Thinking events with text
  render as collapsible "Thinking" blocks in the conversation. Events without text keep today's spinner-only
  behaviour.

**Tech Stack:** Java 21, Jackson, JUnit 5; React + TypeScript + PatternFly 6 (no UI unit tests; verify with
`npm run build`).

**Spec:** GitHub issue #382.

## Background (verified facts)

**OpenCode capture** (opencode 1.18.33, `github-copilot/claude-sonnet-5`, 2026-09-30; script
`/tmp/opencode/capture-todo.py`, output `/tmp/opencode/todo-events.jsonl`):
- **`todo.updated`:** `{"type":"todo.updated","properties":{"sessionID":"ses_…","todos":[
  {"content":"plan","status":"completed","priority":"high"},
  {"content":"build","status":"pending","priority":"medium"},
  {"content":"test","status":"pending","priority":"medium"}]}}`.
  It is currently in `OpenCodeEventNormalizer.IGNORED_EVENT_TYPES`.
- **`todowrite`:** the tool part has the same `input.todos`. Use `todo.updated` only, so nothing is emitted twice.
- **OpenCode statuses** (per the opencode docs): `pending`, `in_progress`, `completed`, `cancelled`.
- **Reasoning parts** had **empty text** with this provider: `{"type":"reasoning","text":"","time":{start,end}}`.
  Thinking text therefore depends on the provider and may be absent.
- **Current reasoning handling:** `mapReasoningPart` emits `thinking {}` once per reasoning part id.

**Claude:**
- `AssistantEventParser.parseAssistant` turns content blocks into events:
  - `text` → `assistant_text`
  - `tool_use` → `tool_use {id, name, input}`
  - `thinking` → `thinking {}`. The block's `thinking` string field is dropped.
- `TodoWrite` input is `{"todos":[{"content":"…","status":"pending|in_progress|completed","activeForm":"…"}]}`.

**UI:**
- `AssistantChatPanel.tsx` `processEvent`: the `thinking` case only calls `setProcessingText(randomThinkingMessage())`.
- `AssistantMessageList.tsx`:
  - `ChatMessage.type` already includes `"thinking"`, and it renders as a plain "Thinking..." div. Nothing adds such
    messages today.
  - `ChatMessage` has `content?`.
- Messages are rebuilt from history replay, so the `todos` and `thinking` events replay naturally.

**Rulings:**
- **The `todos` event** is `{"todos":[{"content":string, "status":string, "priority"?:string,
  "activeForm"?:string}]}`. Each event replaces the whole list; it is not a diff.
- **Claude `TodoWrite`:** still emits its `tool_use` (no change), **plus** a `todos` event right after it.
- **`thinking` data:** `{ "id"?: string, "text"?: string }`.
  - Claude: `text` = the block's `thinking` field, when it is non-blank. Claude has no `id`.
  - OpenCode: the first sighting of a part emits `{id}` (spinner only, as today). When the part has `time.end` and
    non-blank `text`, it emits `{id, text}` **once**.
- **UI thinking blocks:**
  - Text with an `id` that is already shown → replace that block's text.
  - Text with no `id` → add a new block.
  - No text → spinner only (as today).
  - The block is collapsed by default: its header reads "Thinking" and expands to the text.
- **UI todo panel:** shown only once a `todos` event has arrived. The header reads "Todos (X/Y done)"; it is
  collapsible and expanded by default.
  - Items show a status icon: pending ○, in_progress ◐ (or a PatternFly in-progress icon), completed ✓ with
    strike-through, cancelled ✕ in muted style.
  - In-progress items show `activeForm`, if present, in place of `content`.

## Global Constraints

- Java: 4-space indentation; Javadoc on public members; explicit types; JUnit 5. TypeScript: the surrounding
  style; no `any`; JSDoc on exports.
- No REST/OpenAPI changes. The SSE events are not in the OpenAPI spec, so this is consistent with existing
  events.
- Existing behaviour is kept: `thinking` without text still only animates the spinner, and the `TodoWrite`
  `tool_use` block still appears.
- No `todos` event may come from opencode's `todowrite` tool part; only from `todo.updated`.
- Commit style: conventional commits. No AI attribution. No closing keywords directly before issue numbers. Don't
  commit `ui/package-lock.json` or `dist`.
- Tests: `mvn -q -pl app test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`; `cd ui && npm run build`
  (run `npm ci` first if `node_modules` is missing). A JBoss LogManager warning is pre-existing noise.

---

### Task 1: Backend `todos` event and thinking text

**Files:**
- `AssistantEventParser.java` and `AssistantEventParserTest.java`
- `OpenCodeEventNormalizer.java` and `OpenCodeEventNormalizerTest.java`
- Fixture: copy `/tmp/opencode/todo-events.jsonl` to
  `app/src/test/resources/opencode/events/1.18.33-todos.jsonl`, and add a row for it to the README table in that
  folder.

**Steps:**
- [ ] **1. Failing tests:**
  - **Claude:**
    - An assistant message with a `TodoWrite` tool_use whose input has 2 todos produces `[tool_use, todos]`, in
      that order. The `todos` data equals the input list, with `activeForm` kept.
    - A `thinking` block `{"type":"thinking","thinking":"Let me check the file."}` produces `thinking` with
      `text == "Let me check the file."` and no `id`.
    - A thinking block with an empty `thinking` field produces `thinking` with no `text` field.
  - **OpenCode:**
    - Replaying the `1.18.33-todos.jsonl` fixture produces exactly one `todos` event: 3 items, `plan` completed
      with priority `high`.
    - No `todos` event comes from the `todowrite` tool part.
    - No `unhandled_event` comes from any session-scoped event in the fixture.
    - A synthetic reasoning sequence produces exactly two events in total:
      - part `p1` with `text:""` and no `time.end` → `[thinking {id:"p1"}]`;
      - the same part with `time.end` and `text:"Reasoning about X"` → `[thinking {id:"p1", text:"Reasoning about X"}]`;
      - the same final part again → `[]`.
    - A reasoning part ending with empty text (the fixture case) produces only the first `{id}` event.
- [ ] **2. Implement:**
  - **Claude:** in `parseAssistant`, after adding a `tool_use` named `TodoWrite`, add
    `new SseEvent("todos", {"todos": input.todos})` when `input.todos` is an array. Copy each item's `content`,
    `status`, and `priority`/`activeForm` if present. For the `thinking` block, put `text` when `block.thinking` is
    non-blank.
  - **OpenCode:**
    - Remove `"todo.updated"` from `IGNORED_EVENT_TYPES`. Add a case that maps `properties.todos` to the `todos`
      event (same item fields).
    - `mapReasoningPart`: keep `reasoningPartsSeen` for the first `{id}` emit. Add a `reasoningTextEmitted` set for
      the final text emit (when `time.end` is present, `text` is non-blank, and the id hasn't had text emitted).
      One update can produce both: if it is the first sighting **and** final with text, emit only `{id, text}`.
- [ ] **3. Run:** `-Dtest='AssistantEventParserTest,OpenCode*Test'`, then the full `mvn -q -pl app test`.
- [ ] **4. Commit:** `feat(assistant): emit todo lists and thinking text for both engines (#382)`

---

### Task 2: UI todo panel and thinking blocks

**Files:**
- Create: `ui/src/components/assistant/AssistantTodoPanel.tsx` (+ `.css`)
- Modify: `AssistantChatPanel.tsx`, `AssistantMessageList.tsx` (+ its CSS)

**Interfaces:**
- `export interface AssistantTodo { content: string; status: string; priority?: string; activeForm?: string; }`
- `export function AssistantTodoPanel({ todos }: { todos: AssistantTodo[] }): JSX.Element | null`. Returns null
  for an empty array.
- `ChatMessage` gains `thinkingId?: string`. A thinking message uses `type: "thinking"` with `content` = the text.

**Steps:**
- [ ] **1. `AssistantTodoPanel`:**
  - It follows the rulings.
  - Use PatternFly `ExpandableSection` (or the existing collapse pattern in the code base) with the toggle text
    `Todos (${done}/${total} done)`, where done = completed. Expanded by default.
  - Render each item as a list row with a status icon. Use PatternFly icons if available
    (`CheckCircleIcon`, `InProgressIcon`, `OutlinedCircleIcon`, `TimesCircleIcon` from
    `@patternfly/react-icons`). Otherwise use the text glyphs from the rulings.
  - `cancelled` and `completed` are muted; `completed` also gets a strike-through.
- [ ] **2. `AssistantChatPanel`:**
  - **State:** `const [todos, setTodos] = useState<AssistantTodo[]>([])`. The `todos` case runs
    `setTodos(Array.isArray(data.todos) ? data.todos as AssistantTodo[] : [])`.
  - **Rendering:** render `<AssistantTodoPanel todos={todos} />` above the message list, inside the same scroll
    container or as a sticky header. Pick whichever fits the existing layout without breaking scrolling, and
    describe the choice in the report.
  - **`conversation_reset`:** also clears the todos.
  - **`thinking` case:** keep `setProcessingText(...)`. When `data.text` is a non-empty string:
    - if `data.id` is set and a message with `thinkingId === data.id` exists, replace its `content`;
    - otherwise append `{type:"thinking", content:data.text, thinkingId:data.id}`.
- [ ] **3. `AssistantMessageList`:** the `thinking` case renders a collapsible block with the header "Thinking",
  collapsed by default, whose content is the text, using muted, italic or smaller styling. A thinking message
  without content keeps the old "Thinking..." rendering.
- [ ] **4. Build:** `cd ui && npm run build`.
- [ ] **5. Commit:** `feat(ui): show assistant todo list and thinking content (#382)`
- [ ] **6. Manual (human or controller with browser):**
  1. **OpenCode session:** ask it to make a 3-item todo list with todowrite and complete the first item. The panel
     shows "Todos (1/3 done)". Reasoning produces no blocks with this provider (empty text); the spinner still
     changes.
  2. **Claude session:** ask it to plan a task with TodoWrite. The panel appears and updates. With a thinking
     model, "Thinking" blocks appear and expand.
  3. **Reload the page:** the todos and thinking blocks are restored from history.
