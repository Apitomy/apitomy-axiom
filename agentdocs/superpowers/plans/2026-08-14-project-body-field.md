# Project Body Field Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `description` field on Projects with a `body` field (markdown content) that
auto-populates from the linked GitHub issue/PR body and can be edited via UI, REST API, and SDK
tools.

**Architecture:** API-first: update OpenAPI spec, regenerate beans via `mvn install`, then implement
the backend changes (entity, REST resource, pipeline orchestrator), then UI and SDK. A dedicated
`PUT /projects/{projectId}/body` endpoint with `text/markdown` content type makes it easy for
callers to set large markdown content without JSON wrapping.

**Tech Stack:** Java 25 / Quarkus 3.33, React 19 / PatternFly 6, Flyway, Jackson, Node.js MCP
server

**Spec:** Bounded design approved in conversation (no spec file for bounded tasks)

## Global Constraints

- API-first: all REST API changes start in `common/api/src/main/resources/openapi.json`
- Run `mvn install` (or `build.sh`) after OpenAPI changes to regenerate JAX-RS interfaces and beans
- REST impl classes must implement the generated interface — no `@Path` annotations on impl classes
- Use generated beans from `io.apitomy.axiom.api.beans` for request/response types
- Do NOT run tests or builds — the user handles compilation and testing

---

### Task 1: Database Migration and OpenAPI Spec

**Files:**
- Create: `app/src/main/resources/db/migration/V39__rename_description_to_body.sql`
- Modify: `common/api/src/main/resources/openapi.json`

**Interfaces:**
- Consumes: nothing
- Produces: Updated OpenAPI spec with `body` field on `Project`, `NewProject`, `UpdateProject`
  schemas and new `PUT /projects/{projectId}/body` endpoint. Generated beans will have `getBody()`
  / `setBody()` after `mvn install`.

- [ ] **Step 1: Create the Flyway migration**

Create `app/src/main/resources/db/migration/V39__rename_description_to_body.sql`:

```sql
ALTER TABLE project RENAME COLUMN description TO body;
```

- [ ] **Step 2: Update the OpenAPI spec — rename `description` to `body` on all three schemas**

In `common/api/src/main/resources/openapi.json`, replace the `"description"` property with `"body"`
in the following three schemas:

**`Project` schema** (~line 4982): Change `"description": { "type": "string" }` to
`"body": { "type": "string" }`.

**`NewProject` schema** (~line 5055): Change `"description": { "type": "string" }` to
`"body": { "type": "string" }`.

**`UpdateProject` schema** (~line 5098): Change `"description": { "type": "string" }` to
`"body": { "type": "string" }`.

- [ ] **Step 3: Add the dedicated body endpoint to the OpenAPI spec**

Add a new path entry in the `paths` section, after the `/projects/{projectId}/reopen` block
(~line 386). Insert the following path:

```json
"/projects/{projectId}/body": {
  "put": {
    "tags": ["Projects"],
    "summary": "Update a project's body",
    "description": "Sets the project body (markdown content).",
    "operationId": "updateProjectBody",
    "requestBody": {
      "content": {
        "text/markdown": {
          "schema": {
            "type": "string"
          }
        }
      },
      "required": true
    },
    "responses": {
      "204": {
        "description": "Body updated"
      },
      "404": {
        "$ref": "#/components/responses/NotFound"
      }
    }
  },
  "parameters": [
    {
      "$ref": "#/components/parameters/ProjectId"
    }
  ]
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/resources/db/migration/V39__rename_description_to_body.sql \
       common/api/src/main/resources/openapi.json
git commit -m "feat: add project body field — migration and OpenAPI spec"
```

---

### Task 2: Backend — Entity, REST Resource, and Pipeline Orchestrator

**Files:**
- Modify: `core/src/main/java/io/apitomy/axiom/core/entities/ProjectEntity.java:26-27`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/ProjectsResourceImpl.java:138,168-170,499`
- Modify: `app/src/main/java/io/apitomy/axiom/app/PipelineOrchestrator.java:522-557,586-603`

**Interfaces:**
- Consumes: Generated `Project`, `NewProject`, `UpdateProject` beans with `getBody()` / `setBody()`
  from Task 1 (after `mvn install`)
- Produces: Working REST API with `body` field on projects, dedicated `PUT /projects/{id}/body`
  endpoint, and auto-population of body from GitHub issue/PR events

**Important:** Run `mvn install` (or `build.sh`) before starting this task so the generated beans
reflect the OpenAPI changes from Task 1.

- [ ] **Step 1: Rename the entity field**

In `core/src/main/java/io/apitomy/axiom/core/entities/ProjectEntity.java`, rename the field at
line 27 from `description` to `body`:

```java
@Column(columnDefinition = "TEXT")
public String body;
```

- [ ] **Step 2: Update `createProject()` in `ProjectsResourceImpl`**

In `app/src/main/java/io/apitomy/axiom/app/rest/ProjectsResourceImpl.java`, change line 138 from:

```java
entity.description = data.getDescription();
```

to:

```java
entity.body = data.getBody();
```

- [ ] **Step 3: Update `updateProject()` in `ProjectsResourceImpl`**

Change lines 168-170 from:

```java
if (data.getDescription() != null) {
    entity.description = data.getDescription();
}
```

to:

```java
if (data.getBody() != null) {
    entity.body = data.getBody();
}
```

- [ ] **Step 4: Update `toProjectBean()` in `ProjectsResourceImpl`**

Change line 499 from:

```java
project.setDescription(entity.description);
```

to:

```java
project.setBody(entity.body);
```

- [ ] **Step 5: Implement the dedicated body endpoint**

Add the following method to `ProjectsResourceImpl`, after the `reopenProject` method (~line 228):

```java
/**
 * {@inheritDoc}
 */
@Override
@Transactional
public void updateProjectBody(long projectId, String body) {
    ProjectEntity entity = findProjectOrThrow(projectId);
    entity.body = body;
    entity.updatedOn = Instant.now();
}
```

Note: the exact method signature will be determined by the generated `ProjectsResource` interface
after `mvn install`. Match whatever signature was generated — it should accept `long projectId`
and `String body` based on the OpenAPI spec.

- [ ] **Step 6: Add `extractIssueBody()` to `PipelineOrchestrator`**

Add a new method after the existing `extractIssueTitle()` method (~line 603):

```java
/**
 * Extracts the issue or pull request body from the event payload.
 * Returns {@code null} if the body cannot be found or is blank.
 */
private String extractIssueBody(EventEntity event) {
    if (event.payload != null) {
        try {
            JsonNode root = objectMapper.readTree(event.payload);
            String body = root.path("issue").path("body").asText(null);
            if (body != null && !body.isBlank()) {
                return body;
            }
            body = root.path("pull_request").path("body").asText(null);
            if (body != null && !body.isBlank()) {
                return body;
            }
        } catch (Exception e) {
            LOG.debugf("Could not parse event payload for issue body: %s", e.getMessage());
        }
    }
    return null;
}
```

- [ ] **Step 7: Wire `extractIssueBody()` into `createProjectFromEvent()`**

In `createProjectFromEvent()`, after the line `project.name = extractIssueTitle(event);`
(line 524), add:

```java
project.body = extractIssueBody(event);
```

- [ ] **Step 8: Commit**

```bash
git add core/src/main/java/io/apitomy/axiom/core/entities/ProjectEntity.java \
       app/src/main/java/io/apitomy/axiom/app/rest/ProjectsResourceImpl.java \
       app/src/main/java/io/apitomy/axiom/app/PipelineOrchestrator.java
git commit -m "feat: implement project body field in backend"
```

---

### Task 3: UI — API Types, Project Detail Page, and Create Modal

**Files:**
- Modify: `ui/src/config/api.ts:46-68`
- Modify: `ui/src/pages/ProjectDetailPage.tsx:1-451`
- Modify: `ui/src/pages/ProjectsPage.tsx:330-443`

**Interfaces:**
- Consumes: REST API from Task 2 — `body` field on project responses, `PUT /projects/{id}/body`
  with `text/markdown` content type
- Produces: Updated TypeScript types, `updateProjectBody()` API function, markdown viewer with edit
  toggle on project detail page, renamed field in create modal

- [ ] **Step 1: Update TypeScript types in `api.ts`**

In `ui/src/config/api.ts`, update the `Project` interface (line 46) — replace `description` with
`body`:

```typescript
export interface Project {
    id: number;
    name: string;
    body?: string;
    type: string;
    status: string;
    issueSource: string;
    issueRef: string;
    repository: string;
    createdOn: string;
    updatedOn: string;
    metadata?: Record<string, string>;
    labels?: string[];
}
```

Update the `NewProject` interface (line 61) — replace `description` with `body`:

```typescript
export interface NewProject {
    name: string;
    body?: string;
    type: string;
    issueSource: string;
    issueRef: string;
    repository: string;
}
```

- [ ] **Step 2: Add `updateProjectBody()` API function**

Add after the existing `updateProject` function (~line 237):

```typescript
export async function updateProjectBody(id: number, body: string): Promise<void> {
    const response = await fetch(`${API}/projects/${id}/body`, {
        method: "PUT",
        headers: { "Content-Type": "text/markdown" },
        body,
    });
    if (!response.ok) throw new Error(`Failed to update project body: ${response.status}`);
}
```

- [ ] **Step 3: Update the project detail page — replace description with markdown body viewer**

In `ui/src/pages/ProjectDetailPage.tsx`:

Add `updateProjectBody` to the imports from `../config/api` (line 66):

```typescript
import {
    // ... existing imports ...
    updateProjectBody,
} from "../config/api";
```

Add `PencilAltIcon` to the icon imports:

```typescript
import PencilAltIcon from "@patternfly/react-icons/dist/esm/icons/pencil-alt-icon";
import CheckIcon from "@patternfly/react-icons/dist/esm/icons/check-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
```

Add state variables for body editing, after the existing state declarations (~line 107):

```typescript
const [editingBody, setEditingBody] = useState(false);
const [bodyDraft, setBodyDraft] = useState("");
```

Remove the `description` `DescriptionListGroup` block (lines 309-316) that currently renders:

```tsx
{project.description && (
    <DescriptionListGroup>
        <DescriptionListTerm>Description</DescriptionListTerm>
        <DescriptionListDescription>
            {project.description}
        </DescriptionListDescription>
    </DescriptionListGroup>
)}
```

Replace it with a body section placed between the `DescriptionList` closing tag and the Tabs `div`.
Insert after the `</DescriptionList>` (line 324) and before `{/* Tabs */}` (line 326):

```tsx
{/* Project body */}
{(project.body || editingBody) && (
    <Card style={{ marginTop: "16px" }}>
        <CardBody>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "8px" }}>
                <FlexItem>
                    <Title headingLevel="h3" size="md">Body</Title>
                </FlexItem>
                <FlexItem>
                    {editingBody ? (
                        <>
                            <Button variant="plain" aria-label="Save body"
                                onClick={() => {
                                    updateProjectBody(id, bodyDraft)
                                        .then(() => {
                                            setProject({ ...project, body: bodyDraft });
                                            setEditingBody(false);
                                        })
                                        .catch(console.error);
                                }}>
                                <CheckIcon />
                            </Button>
                            <Button variant="plain" aria-label="Cancel editing"
                                onClick={() => setEditingBody(false)}>
                                <TimesIcon />
                            </Button>
                        </>
                    ) : (
                        <Button variant="plain" aria-label="Edit body"
                            onClick={() => {
                                setBodyDraft(project.body || "");
                                setEditingBody(true);
                            }}>
                            <PencilAltIcon />
                        </Button>
                    )}
                </FlexItem>
            </Flex>
            {editingBody ? (
                <TextArea
                    id="body-editor"
                    value={bodyDraft}
                    onChange={(_e, v) => setBodyDraft(v)}
                    rows={12}
                    style={{ fontFamily: "var(--pf-t--global--font--family--mono)" }}
                />
            ) : (
                <Content>
                    <Markdown remarkPlugins={[remarkGfm]}
                        components={markdownMermaidComponents}>
                        {project.body}
                    </Markdown>
                </Content>
            )}
        </CardBody>
    </Card>
)}
```

Note: `Card`, `CardBody`, `Content`, `TextArea`, `Title`, `Markdown`, `remarkGfm`, and
`markdownMermaidComponents` are all already imported in the file. The `CheckIcon` and `TimesIcon`
are new imports.

- [ ] **Step 4: Update the create project modal in `ProjectsPage.tsx`**

In `ui/src/pages/ProjectsPage.tsx`, update the create modal form (~lines 352-363). Rename the
"Description" form group to "Body" and update the field binding from `description` to `body`:

```tsx
<FormGroup label="Body" fieldId="body">
    <TextArea
        id="body"
        value={newProject.body || ""}
        onChange={(_e, v) =>
            setNewProject({
                ...newProject,
                body: v,
            })
        }
    />
</FormGroup>
```

Also update the `newProject` state initialization (find where `setNewProject` is called with the
initial/reset object) — change `description: ""` to `body: ""` if it exists, or ensure the initial
state uses `body` instead of `description`.

- [ ] **Step 5: Commit**

```bash
git add ui/src/config/api.ts \
       ui/src/pages/ProjectDetailPage.tsx \
       ui/src/pages/ProjectsPage.tsx
git commit -m "feat: add project body to UI with markdown viewer and editor"
```

---

### Task 4: SDK MCP Server and UI Tool Lists

**Files:**
- Modify: `app/src/main/resources/templates/axiom-mcp-server/sdk-server.js:278-292`
- Modify: `ui/src/components/AddToolInput.tsx:49-62`
- Modify: `ui/src/components/BrowseToolsModal.tsx:27-40`

**Interfaces:**
- Consumes: REST API from Task 2 — `PUT /projects/{id}/body` with `text/markdown`
- Produces: Updated `axiom_update_project` SDK tool (uses `body` instead of `description`), new
  `axiom_update_project_body` SDK tool, updated UI tool autocomplete lists

- [ ] **Step 1: Update `axiom_update_project` tool in the SDK server**

In `app/src/main/resources/templates/axiom-mcp-server/sdk-server.js`, update the
`axiom_update_project` tool definition (~lines 278-292):

Change the `description` parameter to `body`:

```javascript
{
    name: "axiom_update_project",
    description: "Update an Axiom project's metadata such as name, body, or labels.",
    parameters: [
        { name: "projectId", type: "number", description: "The project ID to update", required: true },
        { name: "name", type: "string", description: "New project name", required: false },
        { name: "body", type: "string", description: "New project body (markdown)", required: false },
        { name: "labels", type: "string", description: "Comma-separated list of labels to set on the project", required: false },
    ],
    handler: async (args) => {
        const data = {};
        if (args.name) data.name = args.name;
        if (args.body) data.body = args.body;
        if (args.labels) data.labels = args.labels.split(",").map(l => l.trim()).filter(Boolean);
        return await axiomApi("PUT", `/projects/${args.projectId}`, data);
    },
},
```

- [ ] **Step 2: Add `axiom_update_project_body` tool to the SDK server**

Add the following tool definition after the `axiom_update_project` entry (~line 293):

```javascript
{
    name: "axiom_update_project_body",
    description: "Update the markdown body of an Axiom project. Accepts raw markdown content.",
    parameters: [
        { name: "projectId", type: "number", description: "The project ID", required: true },
        { name: "body", type: "string", description: "The markdown body content", required: true },
    ],
    handler: async (args) => {
        const url = `${AXIOM_API_URL}/projects/${args.projectId}/body`;
        const resp = await fetch(url, {
            method: "PUT",
            headers: { "Content-Type": "text/markdown" },
            body: args.body,
        });
        const text = await resp.text();
        if (!resp.ok) {
            throw new Error(`Axiom API PUT /projects/${args.projectId}/body returned ${resp.status}: ${text.substring(0, 500)}`);
        }
        return text || "OK";
    },
},
```

Note: this tool calls `fetch` directly instead of `axiomApi()` because the dedicated endpoint uses
`text/markdown` content type, not `application/json`.

- [ ] **Step 3: Add the new tool to the `BrowseToolsModal` hardcoded list**

In `ui/src/components/BrowseToolsModal.tsx`, add the following entry to the `SDK_TOOLS` array
(~line 39, before the closing `];`):

```typescript
{ value: "mcp__axiom-sdk__axiom_update_project", label: "axiom_update_project", description: "Update an Axiom project's metadata", category: "sdk" },
{ value: "mcp__axiom-sdk__axiom_update_project_body", label: "axiom_update_project_body", description: "Update the markdown body of an Axiom project", category: "sdk" },
```

Note: `axiom_update_project` is also missing from this list currently — add both entries.

- [ ] **Step 4: Add the new tool to the `AddToolInput` hardcoded list**

In `ui/src/components/AddToolInput.tsx`, add the following entries to the `AXIOM_SDK_TOOLS` array
(~line 61, before the closing `];`):

```typescript
"mcp__axiom-sdk__axiom_update_project",
"mcp__axiom-sdk__axiom_update_project_body",
```

Note: `axiom_update_project` is also missing from this list currently — add both entries.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/resources/templates/axiom-mcp-server/sdk-server.js \
       ui/src/components/BrowseToolsModal.tsx \
       ui/src/components/AddToolInput.tsx
git commit -m "feat: add project body SDK tool and update UI tool lists"
```
