# Workbench Subscriptions — Design

Date: 2026-10-15
Status: Approved in discussion; awaiting spec review

## Goal

Let a workbench composition define the subscriptions that trigger its workflow, so the workbench goes from a
goal to working automation. Publishing never turns automation on: the user enables each published
subscription explicitly.

## Decisions

| Topic | Decision |
|-------|----------|
| Item type | New composition item type `subscription`, any number per composition |
| Routing | `create-workflow` rules must target the composition's own workflow; `workflow-dispatch` rules allowed |
| Going live | Publish creates/updates subscriptions disabled; an explicit **Enabled** switch in the workbench enables |
| Draft `enabled` | Not part of the draft; ignored on publish, with a validation warning |
| Enabling | Sets `enabled = true` and `processEventsFrom = now` (no backlog) |
| Republish | Never changes a published subscription's enabled state |
| Removing a draft / deleting the composition | Published subscription untouched (same as other item types) |
| Filter preview | Endpoint over the 200 most recent stream events; AI tool `preview_subscription` and a UI panel |

## Current State (for reference)

- Composition item types are `workflow`, `action-type`, `tool` (`CompositionItems` supported set). Publishing
  builds a configuration pack and calls `ImportExportService.importOrUpdatePack`, which already imports
  subscriptions (`ConfigItemType.SUBSCRIPTION`, pack array `subscriptions`).
- Subscriptions (`EventSubscriptionEntity`): `name`, `description`, `enabled`, `filters` (EL filter),
  `routing` (JSON rules), `processEventsFrom` (defaults to creation time), `labels`. Pack/item JSON uses
  `filterExpression` for the filter.
- Routing rules: `create-workflow` (`workflowDefinitionName` or `workflowDefinitionId`) starts runs;
  `workflow-dispatch` (no target) delivers events to every run waiting at a matching `receive-event` node.
  Without a `workflow-dispatch` subscription, receive-event nodes never receive events.
- Subscription matching: `SubscriptionFilterEvaluator.matches` over `EventStreamOrchestrator.filterEventMap`
  (errors count as no match). `ExpressionService` already has a `subscription-filter` scope.

## 1. Subscription Items

- `subscription` joins the supported composition item types. Content is the pack form:
  `{name, description, filterExpression, routing, labels}`.
- The workbench AI creates and edits them with the existing `upsert_item` (`type: "subscription"`); the user
  edits them in a form editor (Section 6).
- An `enabled` field in a draft is ignored and reported as a warning `ENABLED_IGNORED`: "Subscriptions are
  published disabled; enable them in the workbench after publishing."
- New empty subscription items are created with `filterExpression: ""`, `routing: []`.

## 2. Validation (composition context, on top of existing subscription validation)

- Error `FOREIGN_WORKFLOW_ROUTE`: a `create-workflow` rule that does not use `workflowDefinitionName` equal
  to the name of the composition's workflow item (rules with `workflowDefinitionId`, or another name).
- `workflow-dispatch` rules are allowed without further checks.
- Warning `OVERLAPPING_SUBSCRIPTION`: another **enabled** published subscription, not owned by this
  composition, has a `create-workflow` rule for this composition's workflow.
- Publish dependency: a subscription item requires the composition's workflow to be published already or
  selected in the same publish (same mechanism as action types requiring tools).

## 3. Publishing and Enabling

- Publish passes subscriptions through the existing pack import, then forces `enabled = false` on newly
  created subscriptions and preserves the current `enabled` (and `processEventsFrom`) on updated ones.
- New endpoint (contract-first):
  `PUT /compositions/{compositionId}/items/{itemId}/enabled` with body `{"enabled": boolean}`.
  - 404 when the item is not in the composition; 409 when it is not a subscription or not yet published.
  - `true`: sets `enabled = true` and `processEventsFrom = now`. `false`: sets `enabled = false`.
  - Returns the item, including its published subscription's `enabled` state.
- Composition item responses gain `enabled` (boolean, only for published subscription items) so the UI can show
  the switch state.
- The Events → Subscriptions page is unchanged; changes made there are reflected in the switch.

## 4. Filter Preview

- New endpoint (contract-first): `POST /subscriptions/preview` with body `{"filterExpression": string}`.
  - Evaluates the filter against the 200 most recent stream events (by `createdOn`), each as
    `EventStreamOrchestrator.filterEventMap`, with `ConditionEvaluator` directly so errors are reported.
  - Response: `{evaluated, matchedCount, errorCount, firstError, matches: [{id, type, ref, actor, timestamp}]}`
    (`actor` = actor login when present; `matches` newest first, all matches within the 200).
  - A syntactically invalid filter returns 200 with `errorCount = evaluated` and `firstError` set.
- AI tool `preview_subscription(filterExpression)` in the workbench MCP server: formats the counts and up to
  20 matches, one line each.

## 5. Workbench AI

- Allowed tools gain `mcp__workbench__preview_subscription`.
- Workbench reference gains a **Subscriptions** section:
  - a composition may define subscriptions (`upsert_item` type `subscription`);
  - `create-workflow` only to this composition's workflow, by `workflowDefinitionName`;
  - add a `workflow-dispatch` subscription whenever the workflow has receive-event nodes;
  - check filters with `preview_subscription` (and single events with `evaluate_expression`, scope
    `subscription-filter`);
  - do not set `enabled`: publishing leaves subscriptions disabled and the user enables them.
- New prompt rule: "Never say the automation is live: published subscriptions stay disabled until the user
  enables them in the workbench."

## 6. UI

- Composition tree: a **Subscriptions** group listing subscription items; "add" creates an empty item.
- Subscription editor: name, description, filter expression (code editor), routing rules (type select;
  `create-workflow` fixed to the composition's workflow), labels; and a **Matching events** panel that calls
  the preview endpoint for the current filter (debounced) and shows "N of 200 recent events match", errors, and
  the matched events linking to `/events/stream/{id}`.
- An **Enabled** switch in the subscription editor header, shown only when the item is published; disabled with
  a tooltip when the composition's workflow is not published.

## 7. Testing

- Backend: item type supported; `FOREIGN_WORKFLOW_ROUTE`, `ENABLED_IGNORED`, `OVERLAPPING_SUBSCRIPTION`;
  publish dependency; publish creates disabled and preserves enabled on update; enable/disable endpoint
  (404/409, `processEventsFrom` set on enable); preview endpoint (matches, errors, limit 200).
- Workbench MCP: `preview_subscription` formatting (Node test); reference and prompt tests.
- UI: tree group, editor fields, Matching events panel, Enabled switch states.

## Out of Scope

- Event sources (credentials, webhooks) in the workbench.
- Disabling or deleting published subscriptions when drafts or compositions are removed.
- Workbench test runs of subscriptions.
