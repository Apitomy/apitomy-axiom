# Explicit Action Type Inputs Design

**Date:** 2026-10-06
**Status:** Approved (pending spec review)

## Goal

Make declared inputs the only way data reaches an Action Type, for both script and AI agent execution modes.
Remove the ambient placeholders, environment variables and system-prompt content that implicitly inject subject
and event data. Every data dependency of an action becomes visible in its declaration and explicitly mapped on
the workflow node that uses it.

## Background

Placeholders such as `{{ref}}` existed because the (now removed) AI Manager invoked action types with no declared
inputs. Action types now run only from workflow `action` nodes, which declare inputs and outputs. Today data still
reaches actions through four channels:

1. Template placeholders: prompt `{{actionType}}`, `{{ref}}`, `{{repository}}`, `{{subjectName}}`, `{{workDir}}`,
   `{{input}}`; script `{{subjectId}}`, `{{eventId}}`, `{{taskId}}`, `{{ref}}`, `{{repository}}`,
   `{{subjectName}}`, `{{apiBaseUrl}}`, `{{workDir}}` (`ActionTypeValidator` recognized sets;
   `TaskExecutionService.resolvePromptTemplate`; `ScriptExecutionService.substitutePlaceholders`).
2. Script environment variables `AXIOM_SUBJECT_ID`, `AXIOM_EVENT_ID`, `AXIOM_REF`, `AXIOM_REPOSITORY`,
   `AXIOM_SUBJECT_NAME` (always set, whether or not placeholders are used).
3. The agent system prompt (`TaskExecutionService.buildSystemPrompt`) always includes subject name, ref, body and
   action type name.
4. Declared inputs via `{{inputs.X}}`.

Only channel 4 is declared and visible. The Configuration Assistant, following our own examples, produced action
types with `"inputs": []` that silently depend on channels 1–2, which fail at runtime (not validation) when the
subject is not what the action assumes, and cannot be reused on a different target.

## Non-Goals

- Changes to output handling (the auto-appended output contract stays as is).
- Changes to human-task nodes, `sdk:` action nodes, or tool definitions (tool templates keep their own
  parameter substitution).
- Backward compatibility (pre-1.0; the single baseline schema and seed data are edited in place).

## Design

### 1. Declared inputs are the only template data channel

- In both `promptTemplate` and `scriptTemplate`, the only placeholder is `{{inputs.X}}`, where `X` is a declared
  input of the action type.
- Removed placeholders: `{{input}}`, `{{ref}}`, `{{repository}}`, `{{subjectName}}`, `{{subjectId}}`,
  `{{eventId}}`, `{{actionType}}`, `{{workDir}}`, `{{apiBaseUrl}}`, `{{taskId}}`.
- `ActionTypeValidator` errors on:
  - any `{{…}}` placeholder other than `{{inputs.X}}`, with a message naming the replacement:
    - data (`ref`, `repository`, `subjectName`, `subjectId`, `eventId`): "Declare an input `<name>` and map it
      from `context.<name>` on the workflow node" (`eventId` → map from `context.event.id`);
    - plumbing in scripts (`workDir`, `apiBaseUrl`, `taskId`): "Use `$AXIOM_WORK_DIR` / `$AXIOM_API_URL` /
      `$AXIOM_TASK_ID`";
    - `actionType`, `input`, and plumbing in prompts: "Not available; remove it" (agents already run in the
      workspace);
  - `{{inputs.X}}` where `X` is not a declared input.
- Script `{{inputs.X}}` keeps its current quote-aware, environment-variable-based substitution (`AXIOM_INPUT_X`).
  Prompt `{{inputs.X}}` keeps inline rendering (`InputBindingResolver`).

### 2. Runtime plumbing via environment variables only

- Script processes keep: `AXIOM_API_URL`, `AXIOM_TASK_ID`, `AXIOM_WORK_DIR`, resolved secrets, the action
  type's `environment`, and the `AXIOM_INPUT_*` variables used by script input substitution.
- Agent processes never had `AXIOM_API_URL`, `AXIOM_TASK_ID` or `AXIOM_WORK_DIR`; agents get data only through
  declared inputs.
- Removed from script and agent process environments: `AXIOM_SUBJECT_ID`, `AXIOM_EVENT_ID`, `AXIOM_REF`,
  `AXIOM_REPOSITORY`, `AXIOM_SUBJECT_NAME`.
- The working directory remains the subject workspace (runtime location, not data).

### 3. Agent prompts

Final prompt sent to the agent = rendered `promptTemplate` + generated **Inputs** section + existing output
contract (when outputs are declared).

- **Inputs section** (new, `InputBindingResolver.buildInputsSection(List<ActionTypeField> inputs, Map<String,
  Object> values)`): heading `## Inputs`, then one entry per declared input **that has a value**, in declaration
  order: name, type, description (if any), and the value. Strings are rendered as-is; numbers and booleans as
  text; objects and arrays as pretty-printed JSON in a fenced block. All declared inputs with values are listed,
  including ones also referenced inline (decision B). Omitted when no declared input has a value.
- **System prompt** (`buildSystemPrompt`): no subject data. Contains only: the action type name and description,
  and that the current working directory is the task's workspace.
- The automatic output contract (`InputBindingResolver.buildOutputContract`) is unchanged.

### 4. Workflow context and node input mappings

- Every run's initial context contains `subjectId`, `subjectName`, `ref`, `repository` (from the subject; empty
  string when null), plus `event` when started by an event. All five are protected from being overridden by
  supplied run inputs (extend `PROTECTED_INPUTS`).
- Node mapping rules for `action` nodes whose `actionType` is a declared action type (not `sdk:`):
  - every **required** declared input must have a key in the node's `config.inputs` → error
    `UNMAPPED_REQUIRED_INPUT` at `content.nodes[i].config.inputs`;
  - every key in `config.inputs` must be a declared input → error `UNKNOWN_INPUT_MAPPING` at
    `content.nodes[i].config.inputs.<key>`;
  - **optional** declared inputs may be left unmapped; at runtime they are absent: not listed in the Inputs
    section, and `{{inputs.X}}` for them renders as an empty string.
- Enforced in `ConfigItemValidationService` (needs action type declarations, so the context must expose them —
  see §6) and at publish time via the same shared rule (`WorkflowContentRules` gains a check that takes a lookup
  of action type declarations), and at run time defensively: a task whose required input resolved to nothing
  fails with a clear message.

### 5. Updating existing content

- Seed data (`SeedDataInitializer`): rewrite seeded action types (e.g. `close-subject`) to declare inputs.
- Assistant: bundled examples (`templates/assistant-reference/examples/`) use declared inputs mapped from
  `context.*`; `AssistantReferenceGenerator` documents the single placeholder, the plumbing environment
  variables, the context keys, the mapping rules and the generated Inputs section; the system prompt guidance
  adds "declare inputs for everything an action operates on" and "do not write your own output-format
  instructions — Axiom appends them".
- AI helpers (`ActionTypeAiService`, `ScriptAiService`) prompt text updated to the new model.
- UI: placeholder autocomplete (`PlaceholderCompletionProvider`) offers only `{{inputs.X}}` for the action type's
  declared inputs (and the plumbing env vars in script mode).
- Docs: user guide (action types, workflows) and developer guide updated.

### 6. Validation context

`ValidationContext` gains action type declarations (name → declared input names and required flags) from the
database and the pending pack/session, so workflow mapping rules can be checked before Apply. Publish-time
checking reads declarations from the database.

## Testing

- `ActionTypeValidator`: each removed placeholder is rejected with its replacement hint; undeclared
  `{{inputs.X}}` rejected; declared `{{inputs.X}}` accepted.
- Workflow validation: unmapped required input → `UNMAPPED_REQUIRED_INPUT`; unknown mapping key →
  `UNKNOWN_INPUT_MAPPING`; unmapped optional input accepted; same results at publish time.
- Agent prompt: contains the rendered template, an Inputs section listing all valued inputs (including inline-
  referenced ones), and the output contract; object values rendered as JSON; unmapped optional inputs absent;
  system prompt contains no subject name, ref or body.
- Script execution: `AXIOM_REF`, `AXIOM_REPOSITORY`, `AXIOM_SUBJECT_NAME`, `AXIOM_SUBJECT_ID`, `AXIOM_EVENT_ID`
  absent from the environment; `AXIOM_API_URL`, `AXIOM_TASK_ID`, `AXIOM_WORK_DIR` present; `{{inputs.X}}`
  substitution still shell-safe.
- Run context contains `repository` and `subjectName`; supplied inputs cannot override any of the five protected
  keys.
- Assistant examples still validate with no errors and apply end to end; seeded action types validate.
