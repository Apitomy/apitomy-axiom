# Database & Migrations

Axiom uses H2 as its database with Hibernate Panache for ORM and Flyway for schema
migrations.

---

## Database Profiles

Axiom has three database configurations, selected via Quarkus profiles:

| Profile | Storage | Schema Management | Use Case |
|---------|---------|-------------------|----------|
| `dev` (default) | H2 in-memory | Hibernate `drop-and-create` | Fast development — schema rebuilt on every restart |
| `persist` | H2 file (`~/.axiom/data/axiom`) | Flyway migrations | Development with persistent data |
| `prod` | H2 file (`~/.axiom/data/axiom`) | Flyway migrations | Production (uber-jar) |

Activate the `persist` profile during development:

```bash
./dev.sh --persist
# or
mvn quarkus:dev -Dquarkus.profile=persist
```

The `prod` profile is activated automatically when running from the release JAR.

---

## Entity Model

All entities are in `core/src/main/java/io/apitomy/axiom/core/entities/` and extend
Quarkus `PanacheEntity` (or `PanacheEntityBase` for entities with non-`Long` primary
keys, such as `TraceEntity`'s UUID key), which provides the active record pattern.
Read the entity classes directly for the current field list — that is the
authoritative reference, not this guide.

### Active Record Pattern

Entities use the Panache active record style — queries are static methods on the
entity class:

```java
// Find by ID
ProjectEntity project = ProjectEntity.findById(id);

// Query with parameters
List<TaskEntity> tasks = TaskEntity.find("projectId = ?1 and status = ?2",
    projectId, "Pending").list();

// Persist
entity.persist();

// Count
long count = StreamEventEntity.count("source", "github");
```

No separate DAO or repository classes are needed.

---

## Flyway Migrations

Schema migrations live in:

```
app/src/main/resources/db/migration/
```

Flyway runs automatically at startup when the `persist` or `prod` profile is active.

### Configuration

Flyway is configured with:

- `baseline-on-migrate=true` — existing databases created before Flyway was introduced
  are automatically baselined
- `baseline-version=1` — V1 (the full schema creation) is skipped for baselined
  databases; only V2+ migrations run

### Naming Convention

```
V<number>__<description>.sql
```

Migrations that need to compute values from data (for example, a sequence start value) can be written in
Java instead. Put them in `app/src/main/java/db/migration/` as a class named `V<number>__<description>`
that extends Flyway's `BaseJavaMigration` (see `V73__create_agent_sequence`). SQL and Java migrations share
one version sequence.

Examples:

```
V1__initial_schema.sql
V2__add_event_source_log.sql
V3__add_priority_to_task.sql
```

The version number must be unique and sequential. Use two underscores (`__`) between
the version and the description.

### Writing Migrations

Always use guards to make migrations idempotent:

```sql
-- Adding a column
ALTER TABLE task ADD COLUMN IF NOT EXISTS priority VARCHAR(255) DEFAULT 'normal';

-- Adding a table
CREATE TABLE IF NOT EXISTS my_table (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);

-- Adding an index
CREATE INDEX IF NOT EXISTS idx_task_status ON task (status);
```

### Adding a New Entity

1. Create the entity class in `core/src/main/java/io/apitomy/axiom/core/entities/`:

    ```java
    @Entity
    @Table(name = "my_entity")
    public class MyEntity extends PanacheEntity {
        @Column(nullable = false)
        public String name;

        @Column
        public String description;

        @Column(name = "created_on", nullable = false)
        public Instant createdOn;
    }
    ```

2. Create a Flyway migration in `app/src/main/resources/db/migration/`:

    ```sql
    -- V21__add_my_entity.sql
    CREATE TABLE IF NOT EXISTS my_entity (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        name VARCHAR(255) NOT NULL,
        description VARCHAR(1024),
        created_on TIMESTAMP NOT NULL
    );
    CREATE SEQUENCE IF NOT EXISTS my_entity_SEQ START WITH 1 INCREMENT BY 50;
    ```

    **One sequence per entity.** Every `PanacheEntity` gets its id from a sequence named
    `<table>_SEQ` with `INCREMENT BY 50` (the Hibernate/Panache default `allocationSize`). The migration
    that creates the table must also create that sequence. If you rename a table, create the new
    `<new_table>_SEQ`, start it above the existing ids (at least `max(id) + 50`) and drop the old one.
    Forgetting this is invisible in tests, which use `drop-and-create`, but makes `prod` fail Hibernate
    validation with `missing sequence` (issue #445: V45 renamed `actor` to `agent` but left `actor_SEQ`).

3. If the entity is API-exposed, update the OpenAPI spec and create/update the
   corresponding REST resource (see
   [API-First Development](api-first-development.md)).

### Adding a Column to an Existing Entity

1. Add the field to the entity class:

    ```java
    @Column
    public String priority;
    ```

2. Create a migration:

    ```sql
    -- V22__add_priority_to_task.sql
    ALTER TABLE task ADD COLUMN IF NOT EXISTS priority VARCHAR(255) DEFAULT 'normal';
    ```

### Migration Guard Test

Tests run with Hibernate `drop-and-create`, so they never exercise the migrations. To catch drift between the
migrations and the entity model, `MigrationSchemaValidationTest` (in
`app/src/test/java/io/apitomy/axiom/app/db/`) runs in CI as a plain JUnit test (no Quarkus boot, a few
seconds):

1. Once per run, it applies every migration to a fresh, private in-memory H2 database with Flyway's Java API.
2. It builds Hibernate metadata from every `@Entity` in the core module's Jandex index and starts a session
   factory with `hbm2ddl.auto=validate`, which checks tables, columns, column types and sequences.
3. It lists every sequence the entity model expects and reports all that are missing at once.
4. It checks the V73 upgrade path on a file H2 database: an existing `agent_SEQ` is never lowered.

The guard covers H2 only; nothing in CI runs the migrations against PostgreSQL.

If you add or change an entity without a matching migration, this test fails. Fix the migration; don't
change the test.

---

## Configuration Versions of Jobs and Reports

Scheduled job and report definitions are edited in place. To keep the configuration each run used (#426),
`V71` adds `scheduled_job_version` and `report_definition_version`. Each row stores a canonical JSON snapshot
(`config_snapshot`, sorted keys, unset fields omitted) of a definition's execution-relevant fields and its
SHA-256 `config_hash`. A row is unique per `(definition, config_hash)`, so runs with identical configuration
share one version. `scheduled_job_run.config_version_id` and `report.config_version_id` point at the version.

- `ConfigSnapshotService` builds the snapshot and finds or creates the version. `ScheduledJobScheduler` and
  `ReportScheduler` call it when they create the run or report row (scheduled and manual paths), because
  both paths load the definition there. The find-or-insert runs in its own transaction
  (`QuarkusTransaction.requiringNew()`); a unique violation from a concurrent insert is resolved by
  re-reading the version. The schedulers catch any failure, log a warning and leave `config_version_id`
  null, so a snapshot problem never blocks run or report creation.
- Snapshots are serialized with a private, fixed-configuration `ObjectMapper` (sorted keys) so application
  ObjectMapper customizers cannot change hashes; `maxBudgetUsd` is stored as stripped decimal text.
- Prompt and script templates are stored verbatim; only environment values are redacted.
- Environment values are redacted unless they are exactly a `${secret:NAME}` reference. Never add a field
  that can hold a secret value without redacting it.
- `GET /scheduled-jobs/runs/{runId}/config` and `GET /reports/{reportId}/config` return the snapshot,
  compared field by field with the current definition; `changed` compares the hashes. They return 404 with
  an `Error` body; the message starts with "No configuration was recorded" when the run or report exists but
  has no snapshot (rows created before V71, or a failed recording).
- Retention: versions belong to their definition. The delete endpoints remove runs/reports, then versions,
  then the definition. In Flyway-managed databases the FKs also cascade from the definition and set
  `config_version_id` to null if a version is removed. Deleting a single report keeps its version.
- Adding a field to the snapshot changes the hash of every definition, so the next run gets a new version
  and older runs show as changed.

---

## Development Tips

- In **dev mode** (default), Hibernate recreates the schema on every restart. You
  don't need Flyway migrations during development — just modify the entity class.
- Switch to **persist mode** (`--persist`) when you want to test migrations or keep
  data between restarts.
- When writing a migration, test it against a persistent database that already has
  data to verify it runs cleanly.
- The H2 console is available at `http://localhost:9090/q/h2` during development
  (Quarkus dev mode only).

See [Upgrading and Backups](upgrading-and-backups.md) for what a production upgrade
can change and what needs to be backed up alongside the database.
