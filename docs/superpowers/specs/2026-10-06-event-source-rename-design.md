# Event Source Rename Design

**Date:** 2026-10-06
**Status:** Approved (pending spec review)

## Goal

Replace the "Connection" naming for event sources with "EventSource" naming across the REST API, database,
backend, SDK/assistant templates, UI, and docs, matching the "Event Sources" label already used in the UI
navigation.

## Non-Goals

- Backward compatibility: no API aliases, no data migration (the schema is a single editable baseline).
- Renaming non-domain uses of "connection": JDBC/database connections, HTTP/OpenCode client connections, MCP
  connection code, and Jira's own `projects` configuration.
- Behavior changes of any kind.

## Naming Map

| Area | From | To |
|---|---|---|
| REST paths | `/connections`, `/connections/{connectionId}`, `…/status`, `…/logs` | `/event-sources`, `/event-sources/{eventSourceId}`, `…/status`, `…/logs` |
| OpenAPI tag (generated interface) | `Connections` (`ConnectionsResource`) | `EventSources` (`EventSourcesResource`) |
| Schemas | `Connection`, `NewConnection`, `ConnectionStatus`, `ConnectionPollLog`, `ConnectionSearchResults`, `ConnectionPollLogSearchResults` | `EventSource`, `NewEventSource`, `EventSourceStatus`, `EventSourcePollLog`, `EventSourceSearchResults`, `EventSourcePollLogSearchResults` |
| Operation IDs | `listConnections`, `getConnection`, `createConnection`, `updateConnection`, `deleteConnection`, `getConnectionStatus`, `listConnectionPollLogs` | `listEventSources`, `getEventSource`, `createEventSource`, `updateEventSource`, `deleteEventSource`, `getEventSourceStatus`, `listEventSourcePollLogs` |
| Query params / properties | `filterConnectionId`, `connectionId` | `filterEventSourceId`, `eventSourceId` |
| Tables | `event_source_connection`, `connection_poll_log` (+ `_SEQ`) | `event_source`, `event_source_poll_log` (+ `_SEQ`) |
| Columns | `connection_id` (poll log, stream event, any other) | `event_source_id` |
| Indexes | `idx_cpl_connection`, `idx_cpl_created`, `idx_stream_event_connection` | `idx_espl_event_source`, `idx_espl_created`, `idx_stream_event_event_source` |
| Java classes | `EventSourceConnectionEntity`, `ConnectionPollLogEntity`, `ConnectionsResourceImpl`, `GitHubConnectionPoller`, `JiraConnectionPoller` (+ tests, incl. `ConnectionsResourceTest`) | `EventSourceEntity`, `EventSourcePollLogEntity`, `EventSourcesResourceImpl`, `GitHubEventSourcePoller`, `JiraEventSourcePoller` (+ tests, `EventSourcesResourceTest`) |
| Event field | `connectionId` on `StreamEventEntity`, event model, event JSON in workflow context; `event.connectionId` in EL | `eventSourceId`; `event.eventSourceId` |
| Config assistant | `connections/` directory, `axiom_list_connections`, `axiom_get_connection`, "Connections" prose | `event-sources/`, `axiom_list_event_sources`, `axiom_get_event_source`, "Event Sources" |
| Import/export | `connections` section | `eventSources` section |
| UI | `ConnectionsPage`, `ConnectionDetailPage`, `api.ts` types/functions, routes `/events/connections…` | `EventSourcesPage`, `EventSourceDetailPage`, matching `api.ts` names, routes `/events/sources…` |
| Docs | "connection(s)" meaning event source | "event source(s)" |

Fields named after the event-source *kind* (`event.source`, `sourceType`) are unchanged.

## Approach

Single full rename, contract-first, in this order: `openapi.json` → regenerate → `V1__baseline.sql` → core →
`events/*` modules → app → templates/assistant → UI → docs. Delivered as a few reviewable commits on
`feat/workflow-first`. The baseline migration is edited in place; `MigrationSchemaValidationTest` guards
entity/schema consistency.

## Testing

- Existing tests renamed with their subjects.
- New regression tests:
  - `GET /api/v1/connections` returns 404; `GET /api/v1/event-sources` returns 200.
  - A subscription filter expression using `event.eventSourceId` matches an event from that event source.
  - Sidebar "Event Sources" item navigates to `/events/sources` (Vitest).
- `./build.sh -Pui` passes.
- Final `git grep -i connection` (excluding historical `docs/superpowers`, `agentdocs`) shows only the
  non-domain uses listed under Non-Goals.
