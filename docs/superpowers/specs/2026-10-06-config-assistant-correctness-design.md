# Configuration Assistant Correctness Design

**Date:** 2026-10-06
**Status:** Approved (pending spec review)

## Goal

Make the Axiom Configuration Assistant produce syntactically and semantically correct Tools, Action Types,
Toolsets, Session Templates, Workflows, Event Sources and Subscriptions — and let items created in one session
reference each other — by making the server's real validators the single source of truth for the API, the
assistant's feedback, a new pre-write validation tool, and Apply.

## Problems Being Solved

1. The system prompt documents the wrong workflow format (uppercase node types, `sourceNodeId`/`targetNodeId`
   edges, missing `content.id`/`name`, wrong `human-task` config, no Start `inputs`, no graph rules).
2. `AssistantItemValidator` enforces that same wrong format (`"START"`/`"END"`) and never uses the flow
   engine's model or `WorkflowValidator`; live feedback pushes the agent toward the wrong format.
3. Apply writes entities directly: workflows become published versions without `WorkflowValidator` or Start
   input checks; subscriptions skip filter and routing checks.
4. No way for the agent to validate a draft before writing; only tools and action types have REST validate
   endpoints.
5. Prompt errors: wrong action-type placeholders (`{{subjectId}}` is rejected), wrong event type examples
   (`issues`), wrong `enabled` default for subscriptions, tools named in the prompt missing from
   `allowedTools` (`axiom_list_secrets`, `axiom_list_subjects`) or nonexistent (`axiom_list_agents`).
6. Same-session cross-references fail: toolsets created in the session are unknown to action-type validation;
   subscriptions need a numeric `workflowDefinitionId` that does not exist before Apply.

## Non-Goals

- AI-assisted workflow node layout (prompt keeps simple spacing guidance).
- Validation of `EventSourceFilters` include/exclude rules (unused in production).
- Changes to the general-purpose assistant.

## 1. Shared Validation Service

`ConfigItemValidationService` (app module, `@ApplicationScoped`) is the only place that decides whether a
configuration item is valid.

**API:**

- `List<ConfigProblem> validate(ConfigItemType type, JsonNode item, ValidationContext context)`
- `ConfigItemType`: `TOOL`, `ACTION_TYPE`, `TOOLSET`, `SESSION_TEMPLATE`, `WORKFLOW`, `EVENT_SOURCE`,
  `SUBSCRIPTION` (JSON names: `tool`, `action-type`, `toolset`, `session-template`, `workflow`,
  `event-source`, `subscription`).
- `ConfigProblem(severity, path, code, message)` — severity `error|warning`; `path` is a JSON path such as
  `content.edges[2].source`.
- `ValidationContext` resolves names of existing tools, toolsets, action types, workflow definitions, event
  sources and secrets from the database **plus** items pending in the current assistant session.
  `ValidationContext.databaseOnly()` for REST calls; `ValidationContext.forSession(sessionDir)` for the
  assistant.

**Per-type checks (reusing existing code):**

| Type | Checks |
|---|---|
| Tool | Existing `ToolValidator` |
| Action type | Existing `ActionTypeValidator`; known tools/toolsets come from the context (DB + session) |
| Toolset / session template | Required fields; referenced tools/toolsets exist |
| Workflow | Requires `name` and `content`; deserialize `content` into the flow engine's `Workflow`; run `WorkflowValidator`; Start-input rules (extracted from `WorkflowDefinitionsResourceImpl.validateStartInputs` into a shared method); each `action` node's `actionType` exists in context or is a registered `sdk:` function; `receive-event` `eventType` is a known `EventType` (warning if unknown) |
| Event source | Id matches `[a-z0-9-]+`; `sourceType` is `github` or `jira`; GitHub `configuration.repositories` is a non-empty array of `owner/repo`; Jira `configuration.projects` is a non-empty array of keys; `secretName` exists (warning) |
| Subscription | `filterExpression` parses as EL; each routing rule `type` is `create-workflow` or `workflow-dispatch`; `create-workflow` requires exactly one of `workflowDefinitionId` (must exist) or `workflowDefinitionName` (must exist in context); filter comparing `event.type` to a string that is not a known `EventType` → warning |

**Reuse by REST:** subscription create/update, workflow definition publish, tool and action-type validate/
create endpoints call the service (`databaseOnly()` context) instead of their inline checks. Error responses
keep their current status codes and shapes.

`AssistantItemValidator` becomes a thin adapter: walks the session directories, detects types, and delegates
to the service with `forSession`. Its hand-written workflow check is deleted.

## 2. Pre-Write Validation Tool and Live Feedback

**REST (contract-first, `openapi.json`):**
`POST /api/v1/assistant/sessions/{sessionId}/validate` — body `{ type, item }`; response
`{ valid: boolean, problems: ConfigProblem[] }`. Uses `ValidationContext.forSession` for that session; 404 for
unknown session.

**MCP tool** in `templates/axiom-assistant-mcp/server.js`: `axiom_validate_item(type, item)` calls the endpoint
above. The session ID is provided via an `AXIOM_ASSISTANT_SESSION_ID` env var in the per-session MCP config
generated by `AssistantSessionManager`. Returns problems with paths plus a one-line next-step hint.

**Template `allowedTools`:** add `mcp__axiom__axiom_validate_item`, `mcp__axiom__axiom_list_secrets`,
`mcp__axiom__axiom_list_subjects`; use the existing `axiom_list_actors` (remove references to the nonexistent
`axiom_list_agents`).

**Live feedback:** after each file-writing tool result, re-validation uses the service. Existing de-duplication
(`ValidationFeedbackTracker`) stays. When the set of problems becomes empty after having been non-empty, send one
"all items valid" message.

## 3. Apply and Cross-References

- Apply builds the configuration pack as today, then imports in dependency order: tools → toolsets → action
  types → session templates → workflows → event sources → subscriptions.
- Before any write, every item is validated with the session context; any error aborts Apply (no writes).
- `ImportExportService` create/update paths call the same service methods as REST rather than writing entities
  unchecked. This also covers configuration packs imported via the UI.
- Workflows: save draft content, then publish through the same publish method the REST endpoint uses
  (`WorkflowValidator` + Start-input rules). Failure rolls back the whole Apply transaction (verify Apply is a
  single transaction; make it one if not).
- `RoutingRule` gains optional `workflowDefinitionName` (openapi + `core/.../events/model/RoutingRule`). On
  subscription create/update/import, the server resolves the name to an ID and stores only
  `workflowDefinitionId`. Unknown name → validation error. Responses continue to expose `workflowDefinitionId`.
- Defaults unchanged: assistant-created event sources and subscriptions are `enabled: false` unless specified.

## 4. Prompt, Generated Reference, Examples

**Prompt structure (`axiom-config-assistant.json`):**

1. Role and the working loop: discover (list/get tools) → draft → `axiom_validate_item` → fix → write → read
   feedback. For workflows, ensure every referenced action type exists or is in the session.
2. Hand-written concepts: tool vs action type; how workflow, subscription and event source fit; action types
   run only from workflow `action` nodes; event sources/subscriptions are created disabled.
3. `{{reference}}` placeholder, replaced at session creation by `AssistantReferenceGenerator` output built from
   code:
   - Workflow: node types (`NodeType` values), per-type config fields (from the engine's `NodeConfig`),
     edge fields, `WorkflowValidator` rules in plain language, condition syntax (raw EL; variables `context`,
     `event`), Start-input rules.
   - Action type: prompt and script template placeholders (from `ActionTypeValidator`'s lists), field types,
     allowed-tool formats, valid `mcp__axiom-sdk__` names (from `SdkFunctionRegistry`).
   - Events: `EventType` values, subscription filter variables (`event.id`, `type`, `source`,
     `eventSourceId`, `ref`, `timestamp`, `actor`, `payload`), event-source configuration per source type.
   - Subscriptions: routing types; `workflowDefinitionName` option.
4. Examples: complete known-good JSON for each item type, plus a worked scenario "when a GitHub issue opens,
   run a triage workflow" (action type, workflow with a branch and a `receive-event` node, subscription
   referencing the workflow by name).

Examples are real files under `app/src/main/resources/templates/assistant-reference/examples/` loaded by
both the generator and tests.

## 5. Testing (JUnit 5)

- Every example file validates with zero errors; the scenario applies end to end (workflow published,
  subscription's `workflowDefinitionId` resolved from the name).
- Known-bad inputs rejected with useful paths: uppercase `START`; `sourceNodeId` edges; missing `content.id`;
  `{{subjectId}}` in a prompt template; filter `event.type == 'issues'` (warning).
- Apply: rolls back fully when a workflow fails to publish; resolves `workflowDefinitionName`; rejects an
  unknown name; same-session toolset referenced by a same-session action type is accepted.
- REST: validate endpoint with session context (and 404 for unknown session); subscription create with
  `workflowDefinitionName`; existing subscription and workflow-publish tests still pass unchanged.
- Reference generator output contains every `NodeType` and `EventType` value.
- `server.js` exposes `axiom_validate_item`, and the template allows it.
