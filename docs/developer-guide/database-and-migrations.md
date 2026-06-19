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
Quarkus `PanacheEntity`, which provides an auto-generated `id` field and the active
record pattern.

### Key Entities

| Entity | Purpose |
|--------|---------|
| `ProjectEntity` | Long-lived project tracking work for an issue |
| `TaskEntity` | A unit of work assigned to an actor within a project |
| `EventEntity` | A normalized event from an external source |
| `EventQueueEntity` | Processing queue entry for pending events |
| `EventSourceEntity` | Configuration for a GitHub/Jira event source |
| `ActionTypeEntity` | Defines a type of work (prompt, tools, mode) |
| `ActorEntity` | A registered AI agent or human actor |
| `ToolDefinitionEntity` | A script-based tool definition |
| `ToolsetEntity` | A named collection of tools |
| `McpServerEntity` | An external MCP server configuration |
| `ReportDefinitionEntity` | A report template with schedule and prompt |
| `ReportEntity` | A generated report instance |
| `SecretEntity` | An encrypted secret (env var for subprocesses) |
| `ManagerConfigEntity` | Manager system prompt and prompt template |
| `AiUsageEntity` | Token cost and usage tracking per invocation |
| `ActivityLogEntity` | Unified activity log entries |
| `ThreadEntryEntity` | Chronological project thread entries |
| `EventSourceLogEntity` | Per-poll-cycle log for event sources |

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
long count = EventEntity.count("repository", "owner/repo");
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
    ```

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

---

## Development Tips

- In **dev mode** (default), Hibernate recreates the schema on every restart. You
  don't need Flyway migrations during development — just modify the entity class.
- Switch to **persist mode** (`--persist`) when you want to test migrations or keep
  data between restarts.
- When writing a migration, test it against a persistent database that already has
  data to verify it runs cleanly.
- The H2 console is available at `http://localhost:8080/q/h2` during development
  (Quarkus dev mode only).
