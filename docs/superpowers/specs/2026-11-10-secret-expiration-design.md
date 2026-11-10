# Secret Expiration Design

## Summary

Secrets are often API keys or auth tokens with an expiration date. This feature lets users record an optional
expiration on a secret. Axiom then:

- shows expiry status in the UI (Secrets page column, app-wide banner, sidebar badge);
- emits `secret.expiring` and `secret.expired` stream events that workflows and pipelines can react to;
- blocks runtime resolution of expired secrets with a clear, actionable error.

## Goals

- Optional expiration date per secret, with a global warning window and an optional per-secret override.
- Each expiry event fires exactly once per expiry date.
- Expired secrets fail fast with an error that names the secret and the fix.
- Rotating a secret cannot leave it stuck in an expired state.

## Non-Goals

- A general notification center (bell, persistence, read/unread).
- Automatic expiry detection (e.g. JWT `exp` claims, provider headers).
- A dashboard widget for expiring secrets.
- Multiple escalating reminders (e.g. 30/7/1 days).

## Data Model

New Flyway migration `app/src/main/resources/db/migration/V10__secret_expiration.sql` adds nullable columns to
`secret`:

| Column                   | Type                     | Purpose                                     |
|--------------------------|--------------------------|---------------------------------------------|
| `expires_on`             | TIMESTAMP WITH TIME ZONE | When the secret expires (null = never)      |
| `warn_days_before`       | INTEGER                  | Per-secret warning window override          |
| `expiry_warning_sent_on` | TIMESTAMP WITH TIME ZONE | When `secret.expiring` was emitted (dedupe) |
| `expired_event_sent_on`  | TIMESTAMP WITH TIME ZONE | When `secret.expired` was emitted (dedupe)  |

`SecretEntity` gets matching fields. Both `*_sent_on` columns are reset to null whenever the secret value or
`expires_on` changes.

## Configuration

| Property                               | Default | Meaning                                           |
|----------------------------------------|---------|---------------------------------------------------|
| `axiom.secrets.expiry-warning-window`  | `7d`    | Warning window when `warnDaysBefore` is null      |
| `axiom.secrets.expiry-check-interval`  | `15m`   | How often the expiry checker runs                 |

## REST API (contract-first)

All changes start in `common/api/src/main/resources/openapi.json`.

- `Secret` (response) adds:
  - `expiresOn` (string, date-time, nullable)
  - `warnDaysBefore` (integer, nullable)
  - `expiryStatus` (enum `NONE`, `VALID`, `EXPIRING`, `EXPIRED`), computed server-side
- `NewSecret` (request) adds optional `expiresOn` and `warnDaysBefore`.

Expiry status rules, using the effective warning window `w` (per-secret override or global default):

- `NONE`: `expiresOn` is null
- `EXPIRED`: `now >= expiresOn`
- `EXPIRING`: `now >= expiresOn - w`
- `VALID`: otherwise

Validation (`createSecret` and `updateSecret` return 400):

1. `expiresOn` is in the past.
2. `warnDaysBefore` is negative.
3. On update: the stored secret is expired and the request neither supplies a future `expiresOn` nor clears it
   (explicit null). This applies to every update, including value rotation.

During implementation, confirm whether `PUT /secrets/{secretId}` currently requires `value`. Rule 3 applies
regardless.

`ImportExportService` imports and exports `expiresOn` and `warnDaysBefore`. The `*_sent_on` columns are not
exported. Imported secrets with a past `expiresOn` are imported as-is (they will be reported as expired) rather
than failing the whole import.

## Expiry Checker and Events

### SystemEventPublisher

Extract the stream-event publishing logic from `SdkCallService.fireEvent()` into a reusable
`SystemEventPublisher` bean. It builds and persists a `StreamEventEntity` and fires `StreamEventPersisted`.
`SdkCallService` is refactored to use it, with no behaviour change.

Confirm during implementation that free-form event types are accepted on this path (as they are for the SDK). If
`EventType` or validation restricts types, add `secret.expiring` and `secret.expired`.

### SecretExpiryChecker

A new `@Scheduled` bean in `app/`, running every `axiom.secrets.expiry-check-interval` after a short startup
delay. It uses an injectable `java.time.Clock`.

For each secret with a non-null `expiresOn`:

- If `now >= expiresOn - w` and `expiry_warning_sent_on` is null, emit `secret.expiring` and set the flag.
  Skip this if the secret is already expired and no warning was ever sent; only `secret.expired` is emitted.
- If `now >= expiresOn` and `expired_event_sent_on` is null, emit `secret.expired` and set the flag.

Each secret is processed in its own transaction (persist event + set flag together). A failure on one secret
is logged and does not stop the batch.

### Emitted events

1. **Stream event** via `SystemEventPublisher`:
   - `eventSourceId = "system"`, `source = "axiom"`, `actor = {"login":"system"}`
   - type `secret.expiring` or `secret.expired`
   - payload `{ "secretId", "secretName", "expiresOn", "daysRemaining" }` — never the secret value
2. **SSE event** `SseEvent.secretExpiry(secretId, status)` with type `secret-expiry`, used by the UI to refresh.

Create, update, and delete of a secret also fire `secret-expiry` so the UI stays current.

## Runtime Blocking

- New `ExpiredSecretException` in `core/src/main/java/io/apitomy/axiom/core/services/`. Message:
  `Secret '<name>' expired on <date>. Update it on the Secrets page.`
- Introduce a common parent `SecretResolutionException` for `MissingSecretException` and
  `ExpiredSecretException`, if that keeps caller changes minimal.
- `EnvironmentResolver` throws `ExpiredSecretException` when it resolves a secret with `now >= expiresOn`, at the
  same point `MissingSecretException` is thrown today.
- Audit every `catch (MissingSecretException)` site and make sure expired secrets are handled the same way (MCP
  server start, tool calls, workflow environments).

### Validation

`ValidationContext*`: a config item referencing an expired secret produces a validation error. A reference to an
`EXPIRING` secret produces a warning.

## UI

- **Secrets page** (`ui/src/pages/SecretsPage.tsx`):
  - New sortable "Expires" column: date plus status label ("Expires in 5d" in warning colour, "Expired" in
    danger colour).
  - Create/edit modal: optional "Expires on" date-time picker and optional "Warn days before" field (placeholder
    shows the global default).
  - Editing an expired secret requires a new future date or clearing the date before saving.
- **App-wide banner**, modelled on `ConfigurationWarning`: "N secrets are expiring soon / M have expired.
  [View secrets]". Dismissible; reappears when the counts change.
- **Sidebar badge** on the Secrets nav item in `AppSidebar.tsx`, showing the count of expiring + expired secrets.
- **Live updates:** listen for `secret-expiry` via `sseClient.subscribe` and refetch the secrets list.

The global warning window must be available to the UI (for the placeholder). Expose it through the existing
system/config endpoint if one exists; otherwise add it to the OpenAPI spec.

## Testing

All backend tests use JUnit 5; time-dependent tests use a controllable `Clock`.

- **Checker** (`@QuarkusTest`): no expiry; entering the warning window; per-secret override; already expired at
  startup; each event fires only once; flags reset on rotation or date change.
- **REST:** 400 for past `expiresOn`, negative `warnDaysBefore`, and updating an expired secret without a new
  date; `expiryStatus` computed correctly.
- **EnvironmentResolver:** expired secrets throw `ExpiredSecretException`; valid and non-expiring secrets
  resolve.
- **Validation:** error for expired references, warning for expiring references.
- **SdkCallService:** existing tests still pass after the `SystemEventPublisher` refactor.
- **Import/export:** round-trip of the new fields.
- **UI:** type-check and build.
- **Live check:** an isolated instance, e.g.
  `AXIOM_HOME=/tmp/axiom-test/home ./dev.sh --skip-ui --portOffset=500 --dataDir=/tmp/axiom-test/data`, never
  the production instance on 9191.
