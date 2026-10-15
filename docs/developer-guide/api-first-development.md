# API-First Development

Axiom follows a contract-first API design. The OpenAPI specification is the source of
truth — JAX-RS interfaces are generated from it at build time, and backend resource
classes implement those generated interfaces.

---

## The OpenAPI Contract

The API specification lives at:

```
common/api/src/main/resources/openapi.json
```

All REST endpoints, request/response schemas, and path parameters are defined here.
This file is the starting point for any API change.

---

## Code Generation

The `common/api` module uses the
[Apitomy Codegen](https://www.apitomy.io/projects/codegen/) Maven plugin to generate
JAX-RS interfaces from the OpenAPI spec at compile time.

Generated output goes to:

```
common/api/target/generated-sources/jaxrs/io/apitomy/axiom/api/
```

The generated code includes:

- **Resource interfaces** — one per API tag/group, with JAX-RS annotations
  (`@Path`, `@GET`, `@POST`, etc.)
- **Bean classes** (package `io.apitomy.axiom.api.beans`) — POJOs with Jackson `@JsonProperty` annotations
  for request/response bodies

Resource methods take and return these generated beans. Do not use raw `JsonNode` for request or response
bodies.

The plugin is configured in `common/api/pom.xml`:

```xml
<plugin>
    <groupId>io.apitomy</groupId>
    <artifactId>apitomy-codegen-maven-plugin</artifactId>
    <configuration>
        <projectSettings>
            <javaPackage>io.apitomy.axiom.api</javaPackage>
            <generatesOpenApi>false</generatesOpenApi>
        </projectSettings>
        <inputSpec>${project.basedir}/src/main/resources/openapi.json</inputSpec>
    </configuration>
</plugin>
```

---

## Implementing Endpoints

Backend implementations live in:

```
app/src/main/java/io/apitomy/axiom/app/rest/
```

Each resource class implements the corresponding generated interface:

```java
@ApplicationScoped
public class SubjectsResourceImpl implements SubjectsResource {

    @Override
    public SubjectSearchResults listSubjects(/* params */) {
        // implementation
    }
}
```

The generated interface provides the JAX-RS annotations — the implementation class
only needs to provide the business logic. Never put `@Path` on an implementation class; the paths come from
the generated interface.

---

## Adding a New Endpoint

### Step 1: Edit the OpenAPI Spec

Add the path, operation, and any new schemas to `common/api/src/main/resources/openapi.json`.

### Step 2: Regenerate Interfaces

```bash
mvn -q -pl common/api install
```

This regenerates the JAX-RS interfaces and beans and installs the module, so that `app` builds against the
new interface (`./build.sh` also does this). Check
`target/generated-sources/jaxrs/io/apitomy/axiom/api/` to see the new interface
method.

### Step 3: Implement the Endpoint

In `app/src/main/java/io/apitomy/axiom/app/rest/`, either update an existing
`*ResourceImpl.java` or create a new one implementing the generated interface.

### Step 4: Add UI API Functions

In `ui/src/config/api.ts` (or `ui/src/config/compositions.ts` for the workbench `/compositions` API), add:

1. TypeScript interfaces matching the request/response schemas
2. A `fetch()` function calling the new endpoint

Most API functions in `api.ts` follow the same pattern:

```typescript
const API = `${getApiBaseUrl()}/api/v1`;

export async function fetchWidgets(): Promise<Widget[]> {
    const resp = await fetch(`${API}/widgets`);
    if (!resp.ok) throw new Error(await extractErrorMessage(resp, "Failed to fetch widgets"));
    return resp.json();
}
```

---

## API Base URL

The API base URL is resolved differently in development and production:

| Mode | Base URL | Mechanism |
|------|----------|-----------|
| Development | (empty string) | Vite proxy forwards `/api` to the backend (`localhost:9090` by default) |
| Production | (empty string) | The backend serves the UI and the API from the same origin |

This is handled by `getApiBaseUrl()` in `api.ts`. If a global `window.AXIOM_API_URL` string is defined, it is
used as the base URL instead; the backend does not set it.

---

## SPA Routing

`SpaRoutingFilter.java` in the `app` module catches all non-API routes and forwards
them to `index.html`, allowing React Router to handle client-side navigation.

Routes that are **not** forwarded:

- `/api/*` — REST API endpoints
- `/q/*` — Quarkus dev UI and health endpoints
- Paths with file extensions (e.g. `.js`, `.css`, `.png`)

Everything else returns `index.html`, and React Router renders the correct page based
on the URL.
