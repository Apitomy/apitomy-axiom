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
# or, with the database in another directory
./dev.sh --dataDir=/path/to/data
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
SubjectEntity subject = SubjectEntity.findById(id);

// Query with parameters
List<TaskEntity> tasks = TaskEntity.find("subjectId = ?1 and status = ?2",
    subjectId, "Pending").list();

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

### Migrations

Axiom 3 starts from a single baseline migration:

```
app/src/main/resources/db/migration/V1__baseline.sql
```

**`V1__baseline.sql` is frozen as of 3.0.0. Never edit it.** Every schema change is a new, incremental
migration, `V2__<description>.sql`, `V3__<description>.sql` and so on, which Flyway applies at startup to
existing databases. A migration must work against real data written by the previous release, and must keep
working on the H2 file database used by the `persist` and `prod` profiles.

### Databases From Earlier Major Versions

Axiom 3 cannot use a database created by Axiom 2.x or older. `LegacyDatabaseGuard` (a Flyway callback that
runs before validation) refuses to start with a clear message when the database's migration history contains
anything other than Axiom 3's migrations (a version 2 or later from 2.x, or a Flyway baseline marker), or when
the database has tables but no migration history. Flyway baselining (`baseline-on-migrate`) is therefore
disabled. See [Upgrading and Backups](upgrading-and-backups.md#upgrading-from-axiom-2x).

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

2. Add the table and its sequence to `V1__baseline.sql`:

    ```sql
    CREATE TABLE my_entity (
        id BIGINT NOT NULL,
        name VARCHAR(255) NOT NULL,
        description VARCHAR(1024),
        created_on TIMESTAMP NOT NULL,
        PRIMARY KEY (id)
    );
    CREATE SEQUENCE my_entity_SEQ START WITH 1 INCREMENT BY 50;
    ```

    **One sequence per entity.** Every `PanacheEntity` gets its id from a sequence named
    `<table>_SEQ` with `INCREMENT BY 50` (the Hibernate/Panache default `allocationSize`). The baseline
    must create that sequence. Forgetting this is invisible in tests, which use `drop-and-create`, but makes
    `prod` fail Hibernate validation with `missing sequence`.

3. If the entity is API-exposed, update the OpenAPI spec and create/update the
   corresponding REST resource (see
   [API-First Development](api-first-development.md)).

### Adding a Column to an Existing Entity

1. Add the field to the entity class:

    ```java
    @Column
    public String priority;
    ```

2. Add the column to the table's `CREATE TABLE` statement in `V1__baseline.sql`.

### Migration Guard Test

Tests run with Hibernate `drop-and-create`, so they never exercise the migrations. To catch drift between the
migrations and the entity model, `MigrationSchemaValidationTest` (in
`app/src/test/java/io/apitomy/axiom/app/db/`) runs in CI as a plain JUnit test (no Quarkus boot, a few
seconds):

1. Once per run, it applies the baseline to a fresh, private in-memory H2 database with Flyway's Java API.
2. It builds Hibernate metadata from every `@Entity` in the core module's Jandex index and starts a session
   factory with `hbm2ddl.auto=validate`, which checks tables, columns, column types and sequences.
3. It lists every sequence the entity model expects and reports all that are missing at once.

The guard covers H2 only; nothing in CI runs the migrations against PostgreSQL.

If you add or change an entity without updating `V1__baseline.sql`, this test fails. Fix the baseline; don't
change the test.

---

## Development Tips

- In **dev mode** (default), Hibernate recreates the schema on every restart. You
  don't need Flyway migrations during development — just modify the entity class.
- Switch to **persist mode** (`--persist`) when you want to test the baseline or keep
  data between restarts. After changing `V1__baseline.sql`, delete the persistent database first.
- The H2 console is available at `http://localhost:9090/q/h2` during development
  (Quarkus dev mode only).

See [Upgrading and Backups](upgrading-and-backups.md) for what a production upgrade
can change and what needs to be backed up alongside the database.
