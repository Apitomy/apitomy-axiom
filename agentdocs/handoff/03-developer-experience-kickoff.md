# Kickoff Notes: Integrated AI Developer Experience (DX)

**Goal (from the user):** a new **interactive, integrated, AI-based Developer Experience in the Axiom UI**
that makes it much easier to create **workflows together with the action types and tools that support them**.
It will do *similar* things to the Configuration Assistant (see `02-configuration-assistant.md`), but
integrated into the building experience rather than as a separate chat that writes files and is applied at the
end.

Nothing has been designed yet. This document lists what we can build on, the gaps, lessons learned and the
questions to settle first. Start the next session with the **brainstorming** skill.

## 1. Building blocks that already exist

| Need | Existing piece | Where |
|------|----------------|-------|
| Long-running AI conversation with tools and permissions | `AssistantSession` + `InteractiveSessionDriver` (Claude Code, OpenCode) with normalized events | `app/.../assistant/`, `assistant/runtime/` |
| One-shot AI call (no conversation) | `AgentRegistry.getAgent(engine).execute(...)` / `executeWithSchema(request, jsonSchema)`; used by `ActionTypeAiService`, `ScriptAiService`, `ToolAiService` | `agents/spi`, `app/.../*AiService.java` |
| Streaming events to the UI | Global SSE channel (`assistant-session-event`) + `GET /history` replay with `eventIndex` dedupe | `core/.../events/SseEvent`, `ui/src/config/sse.ts`, `AssistantChatPanel.tsx` |
| Chat UI pieces | Message list, input, tool-use blocks, todos, subagent cards, permission prompts, AskUserQuestion | `ui/src/components/assistant/` |
| Teaching the agent Axiom's formats | Generated reference + validated examples | `AssistantReferenceGenerator`, `templates/assistant-reference/examples/` |
| Agent access to Axiom data | `axiom-assistant` MCP server (list/get/validate) | `templates/axiom-assistant-mcp/server.js` |
| Validating a draft item, including against other drafts | `ConfigItemValidationService` + `ValidationContextFactory.forDirectory/forPack` | `app/.../validation/` |
| Transactional multi-item create/update | `ImportExportService.importOrUpdatePack` | `app/.../ImportExportService.java` |
| Visual workflow authoring | `@apitomy/flow-ui` `WorkflowEditor` (editable or `readOnly`, `EditorSpi` for the action-type picker, `onValidationChange`), `WorkflowViewer`, workflow diff | `WorkflowDefinitionDetailPage.tsx`, `hooks/useWorkflowEditorSpi.ts` |
| Action type editing | `ActionTypeDetailPage` tabs (Prompt/Script/Code), `NodeCodeTab` with previews, placeholder completion | `ui/src/pages`, `ui/src/components` |
| Running code to test it | Node runner, script execution, tool test endpoint (`ToolTestEndpointTest`), SDK calls | `NodeExecutionService`, `ScriptExecutionService` |

## 2. Gaps and constraints to design around

1. **Assistant features are tied to the template ID `axiom-config-assistant`** in the validation listener, the
   folder layout, the item, validate and apply endpoints, and the UI. A DX that reuses them needs a generic
   capability model, or its own service.
2. **State lives in files in a temporary working directory and is applied at the end.** An integrated DX
   probably wants drafts the user can see and edit live, for example in the workflow canvas, and incremental
   or partial apply. Decide where draft state lives: the database, files, or the client.
3. **Validation feedback arrives as fake user messages** after every tool call. An integrated UI could show
   problems in place (the canvas Problems panel, form fields) and pass them to the agent in a more structured
   way.
4. **History is in memory only.** Sessions do not survive a server restart, and dev mode restarts often.
5. **No AI helper exists for node `codeTemplate`.** Helpers exist for agent prompts, scripts and tools.
6. **Engines:** interactive sessions work only with claude-code and opencode, not copilot.
7. **MCP calls to REST are unauthenticated.** The `axiom-assistant` server is installed with `npm install` per
   server restart.
8. **The Flow editor is a third-party component** (Apitomy Flow, same maintainer). Features the DX needs in
   the editor, such as highlighting nodes the AI changed, applying AI edits as undoable revisions, or
   callbacks when a node is selected, may have to be added to Flow first and then consumed by upgrading.
   Example of that path: the `readOnly` editor prop.

## 3. Lessons learned (keep these)

- **Generate the AI's reference from code** (enums, records, validators, registries) and **test the examples
  with the real validators**. Prose drifts; generated reference does not.
- **Examples are copied verbatim by the model.** Every example must model best practice: declared inputs,
  correlation, the right execution mode.
- **Short, explicit prompt rules work** (for example "prefer `node` for non-AI steps" and "receive-event nodes
  must correlate"). Pair each rule with an assertion in `AssistantReferenceTest`.
- **Validate against drafts plus the database** (`forDirectory`), so dependent items can be created together.
- **Fail loudly on suspicious success** (for example Apply with 0 items). Silent success hid a data-loss bug.
- **Isolate tests from user state** (`axiom.home` in Surefire). Never let tests touch `~/.axiom`.
- **Data reaches actions only through declared inputs**, mapped on the workflow node. This is the contract the
  DX must make easy to see and wire up.
- **Previews should use the real components** (the Flow `WorkflowEditor` with `readOnly`) so that what the
  user sees is what gets applied.
- **Host responsibilities grow with Flow features:** correlation, look-back, timeouts and titles all needed
  Axiom code. When upgrading Flow, read the PRs for host-side work, not only the API changes.

## 4. Open questions for the design session

1. Where does the DX live? A new page (a "workbench"), or AI panels embedded in the existing workflow, action
   type and tool editors, or both?
2. Is the unit of work a workflow with its supporting items, created and edited together? How does the user
   move between the canvas, an action type's code and a tool's script without losing the AI context?
3. Draft model: database drafts, versioned items, or the assistant's file-based pack? How does "apply" or
   "publish" work, and can it be partial?
4. Conversation model: one long interactive session (as in the Config Assistant), task-scoped one-shot calls
   (as in the AI edit helpers), or a mix?
5. How does the AI change the canvas: structured edits through an API or MCP tool (preferred, validated,
   undoable) or by rewriting JSON?
6. Testing in the loop: should the DX run an action type or tool with sample inputs, and simulate a workflow
   (flow-ui has a simulator), so the AI can iterate on real results?
7. Should the existing Configuration Assistant be refactored onto the same foundation, kept as is, or
   replaced?
8. Does anything need to change in Apitomy Flow (the editor SPI, events, programmatic edits)? If so, plan a
   Flow release followed by an Axiom upgrade.

## 5. How we work (user preferences, observed)

- Work on a feature branch. **Do not merge, push or open a PR unless asked.** Commit with conventional commit
  messages and no AI attribution. Use annotated local tags as checkpoints.
- REST is contract-first (`openapi.json`). Edit the single Flyway baseline in place.
- Java style: Quarkus, Javadoc on public methods, 4-space indent, explicit types, JUnit 5. Wrap markdown at
  110 columns.
- The user tests manually with `quarkus:dev` on port 9090, so avoid `mvn clean` or `./build.sh` while it runs.
  `rg` is not installed; use `git grep`.
- Ask before significant design choices; present options with a recommendation. Brainstorm, then spec, then
  plan for new features (specs and plans go in `docs/superpowers/specs|plans/`).
- Report honestly what was and was not verified (tests run, browser not checked).
