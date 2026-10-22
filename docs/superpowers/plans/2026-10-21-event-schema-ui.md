# Event Schema UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Drive the Subscription Detail page's EL field reference from the server's event schemas, and show
schema validation warnings on the Event Detail page.

**Architecture:** A pure utility (`ui/src/config/eventSchemaPaths.ts`) flattens an assembled JSON Schema into
`{path, type, description, examples}` rows. A session-cached hook (`ui/src/hooks/useEventSchemas.ts`) loads
the type list and per-type schemas through new fetch wrappers in `ui/src/config/api.ts`. A new
`EventFieldsPanel` component renders the rows on the Filter tab, inserts clicked paths into the expression,
and feeds the Monaco completion provider, replacing both hard-coded field tables.

**Tech Stack:** React 19, PatternFly 6 (`@patternfly/react-core`, `@patternfly/react-table`), TypeScript,
Vite, Vitest 5 + Testing Library (jsdom).

**Spec:** `docs/superpowers/specs/2026-10-21-event-schemas-and-jira-ingestion-design.md` (section "Plan 3:
UI" and the user-guide bullet of "Documentation"). Plan 1 contract: `/tmp/axiom-test/plan1-interfaces.md`.

## Global Constraints

- Plan 1 is merged: `GET /api/v1/schemas/events` returns `EventSchemaSummary[]` (`{type, description}`) sorted
  by type; `GET /api/v1/schemas/events/{eventType}` returns a self-contained JSON Schema (only local
  `#/$defs/...` refs) or 404; `StreamEvent` has optional `validationErrors: string[]`.
- Optional schema fields are `{"type": ["string", "null"]}` or `{"anyOf": [{"$ref": "#/$defs/X"}, {"type":
  "null"}]}`; both collapse to the non-null type in the UI.
- The UI has **no generated TypeScript client**. All REST calls are hand-written `fetch` wrappers in
  `ui/src/config/api.ts` using `${API}` and `extractErrorMessage`. Follow that pattern; there is no client
  regeneration step. (Plan 1's `openapi.json` change regenerates Java JAX-RS code only.)
- Paths are prefixed `event.` (e.g. `event.payload.issue.state`); arrays appear as `[0]`; `event.sourceData`
  is a single row of type `any (source-specific)`; recursion is bounded.
- The "Examples" (common expressions) table and the "Operators" table in the filter help modal stay.
- Code style: 4-space indentation, TSDoc on every exported function/interface, explicit types where not
  obvious, double quotes.
- Markdown prose wrapped at 110 characters (tables exempt).
- All commands run from `ui/` unless stated: tests `npx vitest run <file>`, full suite `npm test`, typecheck
  and build `npm run build`.
- Commit messages: conventional style (`feat(ui): ...`, `docs: ...`), no AI attribution.

---

## File Structure

| File | Responsibility |
|---|---|
| `ui/src/config/eventSchemaPaths.ts` (create) | Pure functions: `schemaToPaths`, `typesFromFilterExpression`, `defaultEventType`, `appendPath`, `formatExample`, `pathsToCompletionTree`; `JsonSchema`, `EventFieldRow`, `CompletionNode` types |
| `ui/src/config/eventSchemaPaths.test.ts` (create) | Vitest unit tests for the above |
| `ui/src/config/api.ts` (modify) | `EventSchemaSummary`, `fetchEventSchemas`, `fetchEventSchema`, `StreamEvent.validationErrors` |
| `ui/src/hooks/useEventSchemas.ts` (create) | `useEventSchemas`, `useEventSchema`, `clearEventSchemaCache` (session cache) |
| `ui/src/hooks/useEventSchemas.test.ts` (create) | Hook tests |
| `ui/src/components/EventFieldsPanel.tsx` (create) | Type picker + clickable field table |
| `ui/src/components/EventFieldsPanel.test.tsx` (create) | Component tests |
| `ui/src/pages/SubscriptionDetailPage.tsx` (modify) | Mount panel on Filter tab, schema-driven Monaco completion, remove hard-coded tables |
| `ui/src/pages/SubscriptionDetailPage.test.tsx` (create) | Integration test for the Filter tab |
| `ui/src/pages/EventDetailPage.tsx` (modify) | Validation warning alert |
| `ui/src/pages/EventDetailPage.test.tsx` (modify) | Alert tests |
| `docs/user-guide/navigating-the-ui.md` (modify) | Event fields panel and validation warnings |

---

### Task 1: Schema-to-paths utility

**Files:**
- Create: `ui/src/config/eventSchemaPaths.ts`
- Test: `ui/src/config/eventSchemaPaths.test.ts`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `interface JsonSchema` (draft 2020-12 subset, index signature `[keyword: string]: unknown`)
  - `interface EventFieldRow { path: string; type: string; description: string; examples: unknown[] }`
  - `type CompletionNode = { [segment: string]: CompletionNode | string }`
  - `const MAX_SCHEMA_DEPTH = 8`, `const SOURCE_SPECIFIC_TYPE = "any (source-specific)"`,
    `const PREFERRED_DEFAULT_TYPES = ["issue.opened", "issue.created"]`
  - `schemaToPaths(schema: JsonSchema, maxDepth?: number): EventFieldRow[]`
  - `typesFromFilterExpression(expression: string, knownTypes: string[]): string[]`
  - `defaultEventType(types: string[]): string | undefined`
  - `appendPath(expression: string, path: string): string`
  - `formatExample(examples: unknown[]): string`
  - `pathsToCompletionTree(rows: EventFieldRow[]): CompletionNode`

- [ ] **Step 1: Write the failing tests**

Create `ui/src/config/eventSchemaPaths.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import {
    type EventFieldRow,
    type JsonSchema,
    SOURCE_SPECIFIC_TYPE,
    appendPath,
    defaultEventType,
    formatExample,
    pathsToCompletionTree,
    schemaToPaths,
    typesFromFilterExpression,
} from "./eventSchemaPaths";

/** A trimmed-down assembled schema shaped like Plan 1's output for issue.created. */
const ISSUE_CREATED: JsonSchema = {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    title: "issue.created",
    description: "An issue was created.",
    type: "object",
    $defs: {
        Actor: {
            type: "object",
            description: "A user account.",
            properties: {
                login: { type: "string", description: "Username or account ID", examples: ["octocat"] },
                displayName: { type: ["string", "null"], description: "Display name" },
            },
        },
        NormalizedIssue: {
            type: "object",
            properties: {
                number: { type: "string", description: "Issue number or key", examples: ["123", "PROJ-1"] },
                state: { type: "string", enum: ["open", "closed"], description: "Normalized state" },
                author: {
                    anyOf: [{ $ref: "#/$defs/Actor" }, { type: "null" }],
                    description: "Issue author",
                },
                assignees: {
                    type: "array",
                    description: "Current assignees",
                    items: { $ref: "#/$defs/Actor" },
                },
                labels: { type: "array", description: "Label names", items: { type: "string" } },
            },
        },
    },
    properties: {
        type: { type: "string", const: "issue.created", description: "Normalized event type" },
        actor: { $ref: "#/$defs/Actor", description: "Who triggered the event" },
        payload: {
            type: "object",
            description: "Type-specific payload",
            properties: {
                issue: { $ref: "#/$defs/NormalizedIssue", description: "The issue" },
            },
        },
        sourceData: { description: "Raw source-specific data" },
    },
};

function row(rows: EventFieldRow[], path: string): EventFieldRow | undefined {
    return rows.find((r) => r.path === path);
}

describe("schemaToPaths", () => {
    const rows = schemaToPaths(ISSUE_CREATED);

    it("prefixes every path with event.", () => {
        expect(rows.length).toBeGreaterThan(0);
        expect(rows.every((r) => r.path.startsWith("event."))).toBe(true);
    });

    it("emits nested payload paths with type, description and examples", () => {
        expect(row(rows, "event.payload.issue.number")).toEqual({
            path: "event.payload.issue.number",
            type: "string",
            description: "Issue number or key",
            examples: ["123", "PROJ-1"],
        });
    });

    it("uses enum values as examples when none are given", () => {
        expect(row(rows, "event.payload.issue.state")?.examples).toEqual(["open", "closed"]);
    });

    it("uses const as type and example", () => {
        expect(row(rows, "event.type")).toMatchObject({ type: "string", examples: ["issue.created"] });
    });

    it("follows local $refs and prefers the property's own description", () => {
        expect(row(rows, "event.actor")).toMatchObject({ type: "object", description: "Who triggered the event" });
        expect(row(rows, "event.actor.login")?.description).toBe("Username or account ID");
    });

    it("collapses type arrays and anyOf with null to the non-null type", () => {
        expect(row(rows, "event.actor.displayName")?.type).toBe("string");
        expect(row(rows, "event.payload.issue.author")).toMatchObject({ type: "object", description: "Issue author" });
        expect(row(rows, "event.payload.issue.author.login")?.type).toBe("string");
    });

    it("shows arrays as [0] and names the item type", () => {
        expect(row(rows, "event.payload.issue.assignees")?.type).toBe("array<object>");
        expect(row(rows, "event.payload.issue.assignees[0].login")?.type).toBe("string");
        expect(row(rows, "event.payload.issue.labels")?.type).toBe("array<string>");
        expect(rows.some((r) => r.path.startsWith("event.payload.issue.labels["))).toBe(false);
    });

    it("shows sourceData as one source-specific row", () => {
        expect(row(rows, "event.sourceData")).toEqual({
            path: "event.sourceData",
            type: SOURCE_SPECIFIC_TYPE,
            description: "Raw source-specific data",
            examples: [],
        });
        expect(rows.filter((r) => r.path.startsWith("event.sourceData"))).toHaveLength(1);
    });

    it("stops at self-referencing $refs", () => {
        const recursive: JsonSchema = {
            type: "object",
            $defs: {
                Node: {
                    type: "object",
                    properties: {
                        name: { type: "string" },
                        child: { $ref: "#/$defs/Node" },
                    },
                },
            },
            properties: { node: { $ref: "#/$defs/Node" } },
        };
        expect(schemaToPaths(recursive).map((r) => r.path)).toEqual([
            "event.node",
            "event.node.name",
            "event.node.child",
        ]);
    });

    it("stops at the maximum depth", () => {
        const deep: JsonSchema = {
            type: "object",
            properties: {
                a: { type: "object", properties: { b: { type: "object", properties: { c: { type: "string" } } } } },
            },
        };
        expect(schemaToPaths(deep, 2).map((r) => r.path)).toEqual(["event.a", "event.a.b"]);
    });

    it("reports unresolvable refs as any", () => {
        const broken: JsonSchema = { type: "object", properties: { x: { $ref: "#/$defs/Missing" } } };
        expect(schemaToPaths(broken)).toEqual([{ path: "event.x", type: "any", description: "", examples: [] }]);
    });
});

describe("typesFromFilterExpression", () => {
    const known = ["issue.closed", "issue.created", "pr.merged"];

    it("finds types compared with == in either order and either quote style", () => {
        expect(typesFromFilterExpression(
            "event.type == 'issue.closed' || \"pr.merged\" == event.type", known)).toEqual(["issue.closed", "pr.merged"]);
    });

    it("ignores != comparisons, unknown types and duplicates", () => {
        expect(typesFromFilterExpression(
            "event.type != 'issue.closed' && (event.type == 'nope' || event.type=='pr.merged' || event.type == 'pr.merged')",
            known)).toEqual(["pr.merged"]);
    });

    it("returns an empty list when the expression does not restrict event.type", () => {
        expect(typesFromFilterExpression("event.type.startsWith('issue.')", known)).toEqual([]);
        expect(typesFromFilterExpression("", known)).toEqual([]);
    });
});

describe("defaultEventType", () => {
    it("prefers issue.opened, then issue.created, then the first type", () => {
        expect(defaultEventType(["a", "issue.created", "issue.opened"])).toBe("issue.opened");
        expect(defaultEventType(["a", "issue.created"])).toBe("issue.created");
        expect(defaultEventType(["b", "a"])).toBe("b");
        expect(defaultEventType([])).toBeUndefined();
    });
});

describe("appendPath", () => {
    it("appends with a single space separator", () => {
        expect(appendPath("", "event.type")).toBe("event.type");
        expect(appendPath("a &&", "event.type")).toBe("a && event.type");
        expect(appendPath("a && ", "event.type")).toBe("a && event.type");
    });
});

describe("formatExample", () => {
    it("formats the first example as JSON or a dash", () => {
        expect(formatExample(["open", "closed"])).toBe("\"open\"");
        expect(formatExample([true])).toBe("true");
        expect(formatExample([])).toBe("—");
    });
});

describe("pathsToCompletionTree", () => {
    it("builds a nested tree with descriptions at the leaves and skips [0] paths", () => {
        const tree = pathsToCompletionTree(schemaToPaths(ISSUE_CREATED));
        const event = tree.event as Record<string, unknown>;
        expect(event.type).toBe("Normalized event type");
        expect((event.actor as Record<string, unknown>).login).toBe("Username or account ID");
        const issue = (event.payload as Record<string, Record<string, unknown>>).issue;
        expect(issue.assignees).toBe("Current assignees");
        expect(event.sourceData).toBe("Raw source-specific data");
    });

    it("falls back to the type when a row has no description", () => {
        expect(pathsToCompletionTree([{ path: "event.x", type: "string", description: "", examples: [] }]))
            .toEqual({ event: { x: "string" } });
    });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (in `ui/`): `npx vitest run src/config/eventSchemaPaths.test.ts`
Expected: FAIL — `Failed to resolve import "./eventSchemaPaths"`.

- [ ] **Step 3: Implement the utility**

Create `ui/src/config/eventSchemaPaths.ts`:

```ts
/**
 * Utilities that turn an assembled event JSON Schema (from `GET /schemas/events/{eventType}`) into the flat
 * list of EL paths shown in the subscription filter's Event fields panel and Monaco completion.
 */

/** A JSON Schema (draft 2020-12) node; only the keywords the UI reads are typed. */
export interface JsonSchema {
    $ref?: string;
    $defs?: Record<string, JsonSchema>;
    type?: string | string[];
    title?: string;
    description?: string;
    examples?: unknown[];
    const?: unknown;
    enum?: unknown[];
    properties?: Record<string, JsonSchema>;
    items?: JsonSchema;
    anyOf?: JsonSchema[];
    oneOf?: JsonSchema[];
    [keyword: string]: unknown;
}

/** One EL-addressable field of an event type. */
export interface EventFieldRow {
    /** EL path, e.g. `event.payload.issue.state`; array elements appear as `[0]`. */
    path: string;
    /** Display type, e.g. `string`, `object`, `array<object>`, `string | number`. */
    type: string;
    /** Description from the schema, or an empty string. */
    description: string;
    /** Example values from `examples`, `const` or `enum`; may be empty. */
    examples: unknown[];
}

/** Nested completion tree: objects are child nodes, leaves are their description text. */
export type CompletionNode = { [segment: string]: CompletionNode | string };

/** Maximum number of path segments below `event` that {@link schemaToPaths} descends. */
export const MAX_SCHEMA_DEPTH = 8;

/** Display type of `event.sourceData`, whose shape depends on the event source. */
export const SOURCE_SPECIFIC_TYPE = "any (source-specific)";

/**
 * Event types preferred as the panel's default selection, in order. The spec names `issue.opened`, but the
 * real `EventType` list uses `issue.created`, so both are listed.
 */
export const PREFERRED_DEFAULT_TYPES: readonly string[] = ["issue.opened", "issue.created"];

const MAX_REF_HOPS = 16;

interface Resolved {
    schema: JsonSchema;
    refs: string[];
    description?: string;
    examples?: unknown[];
}

function resolvePointer(root: JsonSchema, ref: string): JsonSchema | undefined {
    if (!ref.startsWith("#/")) {
        return undefined;
    }
    let node: unknown = root;
    for (const raw of ref.slice(2).split("/")) {
        const key = raw.replace(/~1/g, "/").replace(/~0/g, "~");
        if (node == null || typeof node !== "object") {
            return undefined;
        }
        node = (node as Record<string, unknown>)[key];
    }
    return node as JsonSchema | undefined;
}

function isNullSchema(schema: JsonSchema): boolean {
    return schema.type === "null";
}

/**
 * Follows `$ref` chains and collapses `anyOf`/`oneOf` that have exactly one non-null variant. The first
 * description and examples seen (outermost first) win.
 */
function resolve(root: JsonSchema, schema: JsonSchema): Resolved {
    let current: JsonSchema = schema;
    const refs: string[] = [];
    let description: string | undefined = schema.description;
    let examples: unknown[] | undefined = schema.examples;
    for (let hop = 0; hop < MAX_REF_HOPS; hop++) {
        if (current.$ref) {
            if (refs.includes(current.$ref)) {
                break;
            }
            refs.push(current.$ref);
            const target = resolvePointer(root, current.$ref);
            if (!target) {
                break;
            }
            current = target;
        } else {
            const variants = current.anyOf ?? current.oneOf;
            if (!variants) {
                break;
            }
            const nonNull = variants.filter((v) => !isNullSchema(v));
            if (nonNull.length !== 1) {
                break;
            }
            current = nonNull[0];
        }
        description ??= current.description;
        examples ??= current.examples;
    }
    return { schema: current, refs, description, examples };
}

function jsonTypeOf(value: unknown): string {
    if (value === null) {
        return "null";
    }
    if (Array.isArray(value)) {
        return "array";
    }
    return typeof value;
}

function typeName(root: JsonSchema, schema: JsonSchema): string {
    if (schema.$ref) {
        return "any";
    }
    if (schema.const !== undefined) {
        return jsonTypeOf(schema.const);
    }
    const declared: string[] = Array.isArray(schema.type)
        ? schema.type.filter((t) => t !== "null")
        : schema.type ? [schema.type] : [];
    if (declared.includes("array") || (declared.length === 0 && schema.items)) {
        const items = schema.items ? resolve(root, schema.items).schema : undefined;
        return `array<${items ? typeName(root, items) : "any"}>`;
    }
    if (declared.length > 0) {
        return declared.join(" | ");
    }
    if (schema.properties) {
        return "object";
    }
    if (schema.enum && schema.enum.length > 0) {
        return jsonTypeOf(schema.enum[0]);
    }
    const variants = schema.anyOf ?? schema.oneOf;
    if (variants) {
        const names = variants.filter((v) => !isNullSchema(v)).map((v) => typeName(root, resolve(root, v).schema));
        return [...new Set(names)].join(" | ") || "any";
    }
    return "any";
}

function examplesOf(resolved: Resolved): unknown[] {
    if (resolved.examples) {
        return resolved.examples;
    }
    if (resolved.schema.const !== undefined) {
        return [resolved.schema.const];
    }
    return resolved.schema.enum ?? [];
}

function walk(root: JsonSchema, node: JsonSchema, prefix: string, depth: number, refStack: string[],
    maxDepth: number, rows: EventFieldRow[]): void {
    for (const [name, propSchema] of Object.entries(node.properties ?? {})) {
        const path = `${prefix}.${name}`;
        if (path === "event.sourceData") {
            rows.push({ path, type: SOURCE_SPECIFIC_TYPE, description: propSchema.description ?? "", examples: [] });
            continue;
        }
        const resolved = resolve(root, propSchema);
        rows.push({
            path,
            type: typeName(root, resolved.schema),
            description: resolved.description ?? "",
            examples: examplesOf(resolved),
        });
        if (depth + 1 >= maxDepth || resolved.refs.some((r) => refStack.includes(r))) {
            continue;
        }
        const stack = [...refStack, ...resolved.refs];
        if (resolved.schema.properties) {
            walk(root, resolved.schema, path, depth + 1, stack, maxDepth, rows);
        } else if (resolved.schema.items) {
            const item = resolve(root, resolved.schema.items);
            if (item.schema.properties && !item.refs.some((r) => stack.includes(r))) {
                walk(root, item.schema, `${path}[0]`, depth + 1, [...stack, ...item.refs], maxDepth, rows);
            }
        }
    }
}

/**
 * Flattens an assembled event schema into EL field rows.
 *
 * Follows local `$ref`s, collapses nullable types to the non-null type, shows array elements as `[0]`,
 * reports `event.sourceData` as a single {@link SOURCE_SPECIFIC_TYPE} row, and never descends into a `$ref`
 * that is already being expanded or past `maxDepth` segments.
 *
 * @param schema the assembled schema for one event type
 * @param maxDepth maximum number of path segments below `event`
 * @returns rows in schema declaration order, parents before children
 */
export function schemaToPaths(schema: JsonSchema, maxDepth: number = MAX_SCHEMA_DEPTH): EventFieldRow[] {
    const rows: EventFieldRow[] = [];
    walk(schema, schema, "event", 0, [], maxDepth, rows);
    return rows;
}

/**
 * Finds the event types a filter expression compares `event.type` to with `==`.
 *
 * @param expression the EL filter expression
 * @param knownTypes the event types the server knows; others are ignored
 * @returns matching known types, in order of first appearance, without duplicates
 */
export function typesFromFilterExpression(expression: string, knownTypes: string[]): string[] {
    const found: string[] = [];
    const pattern = /event\.type\s*==\s*(['"])([^'"]+)\1|(['"])([^'"]+)\3\s*==\s*event\.type/g;
    for (const match of expression.matchAll(pattern)) {
        const type = match[2] ?? match[4];
        if (knownTypes.includes(type) && !found.includes(type)) {
            found.push(type);
        }
    }
    return found;
}

/**
 * Chooses the default event type for the Event fields panel.
 *
 * @param types the selectable types
 * @returns the first of {@link PREFERRED_DEFAULT_TYPES} present, else the first type, else undefined
 */
export function defaultEventType(types: string[]): string | undefined {
    return PREFERRED_DEFAULT_TYPES.find((t) => types.includes(t)) ?? types[0];
}

/**
 * Appends a field path to an expression, separated by one space when the expression is non-empty.
 *
 * @param expression the current expression
 * @param path the path to append
 * @returns the new expression
 */
export function appendPath(expression: string, path: string): string {
    const separator = expression && !expression.endsWith(" ") ? " " : "";
    return expression + separator + path;
}

/**
 * Formats the first example for display.
 *
 * @param examples example values
 * @returns the first example as JSON, or an em dash when there is none
 */
export function formatExample(examples: unknown[]): string {
    return examples.length > 0 ? JSON.stringify(examples[0]) : "—";
}

/**
 * Builds the Monaco completion tree from field rows. Rows containing `[0]` are skipped because EL completion
 * works on dotted identifiers only; the array row itself remains as a leaf.
 *
 * @param rows rows from {@link schemaToPaths}
 * @returns the tree rooted at `event`
 */
export function pathsToCompletionTree(rows: EventFieldRow[]): CompletionNode {
    const root: CompletionNode = {};
    for (const row of rows) {
        if (row.path.includes("[")) {
            continue;
        }
        const segments = row.path.split(".");
        let node: CompletionNode = root;
        segments.forEach((segment, index) => {
            const existing = node[segment];
            if (index === segments.length - 1) {
                if (existing === undefined) {
                    node[segment] = row.description || row.type;
                }
                return;
            }
            if (typeof existing !== "object") {
                node[segment] = {};
            }
            node = node[segment] as CompletionNode;
        });
    }
    return root;
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npx vitest run src/config/eventSchemaPaths.test.ts`
Expected: PASS (all tests green).

Note on the leaf/parent order in `pathsToCompletionTree`: `schemaToPaths` emits a parent (`event.actor`) as a
leaf string first; when its first child arrives, the `typeof existing !== "object"` branch replaces the leaf
with an object node. That is intended.

- [ ] **Step 5: Commit**

```bash
git add ui/src/config/eventSchemaPaths.ts ui/src/config/eventSchemaPaths.test.ts
git commit -m "feat(ui): flatten event schemas into EL field paths"
```

---

### Task 2: API wrappers and `useEventSchemas` hook

**Files:**
- Modify: `ui/src/config/api.ts` (the `StreamEvent` interface near line 2126; add wrappers after
  `fetchStreamEvent` near line 2171)
- Create: `ui/src/hooks/useEventSchemas.ts`
- Test: `ui/src/hooks/useEventSchemas.test.ts`

**Interfaces:**
- Consumes: `JsonSchema` from Task 1.
- Produces:
  - `interface EventSchemaSummary { type: string; description?: string }` (api.ts)
  - `fetchEventSchemas(): Promise<EventSchemaSummary[]>`, `fetchEventSchema(eventType: string):
    Promise<JsonSchema>` (api.ts)
  - `StreamEvent.validationErrors?: string[]` (api.ts)
  - `interface AsyncState<T> { data: T | undefined; loading: boolean; error: string | undefined }`
  - `useEventSchemas(): AsyncState<EventSchemaSummary[]>`
  - `useEventSchema(eventType: string | undefined): AsyncState<JsonSchema>`
  - `clearEventSchemaCache(): void` (tests only)

- [ ] **Step 1: Write the failing tests**

Create `ui/src/hooks/useEventSchemas.test.ts`:

```ts
import { renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../config/api";
import { clearEventSchemaCache, useEventSchema, useEventSchemas } from "./useEventSchemas";

vi.mock("../config/api", () => ({
    fetchEventSchemas: vi.fn(),
    fetchEventSchema: vi.fn(),
}));

const SUMMARIES = [{ type: "issue.closed", description: "Closed" }, { type: "issue.created", description: "Created" }];

beforeEach(() => {
    clearEventSchemaCache();
    vi.mocked(api.fetchEventSchemas).mockReset().mockResolvedValue(SUMMARIES);
    vi.mocked(api.fetchEventSchema).mockReset()
        .mockImplementation(async (type: string) => ({ title: type, type: "object" }));
});

describe("useEventSchemas", () => {
    it("loads the type list once per session", async () => {
        const first = renderHook(() => useEventSchemas());
        await waitFor(() => expect(first.result.current.data).toEqual(SUMMARIES));
        expect(first.result.current.loading).toBe(false);
        const second = renderHook(() => useEventSchemas());
        await waitFor(() => expect(second.result.current.data).toEqual(SUMMARIES));
        expect(api.fetchEventSchemas).toHaveBeenCalledTimes(1);
    });

    it("reports errors and retries on the next mount", async () => {
        vi.mocked(api.fetchEventSchemas).mockRejectedValueOnce(new Error("boom"));
        const failed = renderHook(() => useEventSchemas());
        await waitFor(() => expect(failed.result.current.error).toBe("boom"));
        failed.unmount();
        const retried = renderHook(() => useEventSchemas());
        await waitFor(() => expect(retried.result.current.data).toEqual(SUMMARIES));
        expect(api.fetchEventSchemas).toHaveBeenCalledTimes(2);
    });
});

describe("useEventSchema", () => {
    it("caches each type's schema and refetches only for new types", async () => {
        const { result, rerender } = renderHook(({ type }: { type: string }) => useEventSchema(type),
            { initialProps: { type: "issue.created" } });
        await waitFor(() => expect(result.current.data?.title).toBe("issue.created"));
        rerender({ type: "issue.closed" });
        await waitFor(() => expect(result.current.data?.title).toBe("issue.closed"));
        rerender({ type: "issue.created" });
        await waitFor(() => expect(result.current.data?.title).toBe("issue.created"));
        expect(api.fetchEventSchema).toHaveBeenCalledTimes(2);
    });

    it("returns no data and does not fetch when the type is undefined", () => {
        const { result } = renderHook(() => useEventSchema(undefined));
        expect(result.current).toEqual({ data: undefined, loading: false, error: undefined });
        expect(api.fetchEventSchema).not.toHaveBeenCalled();
    });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx vitest run src/hooks/useEventSchemas.test.ts`
Expected: FAIL — `Failed to resolve import "./useEventSchemas"`.

- [ ] **Step 3: Add the API wrappers**

In `ui/src/config/api.ts`, add as the first line of the file (above the JSDoc of `getApiBaseUrl`), followed
by a blank line:

```ts
import type { JsonSchema } from "./eventSchemaPaths";
```

In the `StreamEvent` interface, after `processingStates?: StreamEventProcessingStates;`, add:

```ts
    /** Schema validation errors recorded at ingestion; absent or empty when the event is valid. */
    validationErrors?: string[];
```

Directly after the `fetchStreamEvent` function, add:

```ts
/** One event type and its description, from `GET /schemas/events`. */
export interface EventSchemaSummary {
    type: string;
    description?: string;
}

/**
 * Lists every event type that has a schema.
 *
 * @returns summaries sorted by type
 */
export async function fetchEventSchemas(): Promise<EventSchemaSummary[]> {
    const response = await fetch(`${API}/schemas/events`);
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to fetch event schemas"));
    return response.json();
}

/**
 * Fetches the assembled, self-contained JSON Schema for one event type.
 *
 * @param eventType the normalized event type, e.g. `issue.created`
 * @returns the schema
 */
export async function fetchEventSchema(eventType: string): Promise<JsonSchema> {
    const response = await fetch(`${API}/schemas/events/${encodeURIComponent(eventType)}`);
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to fetch event schema"));
    return response.json();
}
```

- [ ] **Step 4: Implement the hook**

Create `ui/src/hooks/useEventSchemas.ts`:

```ts
import { useEffect, useState } from "react";
import { type EventSchemaSummary, fetchEventSchema, fetchEventSchemas } from "../config/api";
import type { JsonSchema } from "../config/eventSchemaPaths";

/** Loading state of a cached request. */
export interface AsyncState<T> {
    data: T | undefined;
    loading: boolean;
    error: string | undefined;
}

let listPromise: Promise<EventSchemaSummary[]> | undefined;
const schemaPromises = new Map<string, Promise<JsonSchema>>();

/** Clears the session cache. Intended for tests. */
export function clearEventSchemaCache(): void {
    listPromise = undefined;
    schemaPromises.clear();
}

function cachedList(): Promise<EventSchemaSummary[]> {
    if (!listPromise) {
        listPromise = fetchEventSchemas().catch((e: unknown) => {
            listPromise = undefined;
            throw e;
        });
    }
    return listPromise;
}

function cachedSchema(eventType: string): Promise<JsonSchema> {
    let promise = schemaPromises.get(eventType);
    if (!promise) {
        promise = fetchEventSchema(eventType).catch((e: unknown) => {
            schemaPromises.delete(eventType);
            throw e;
        });
        schemaPromises.set(eventType, promise);
    }
    return promise;
}

function useCached<T>(key: string | undefined, load: (key: string) => Promise<T>): AsyncState<T> {
    const [state, setState] = useState<AsyncState<T>>(
        { data: undefined, loading: key !== undefined, error: undefined });
    useEffect(() => {
        if (key === undefined) {
            setState({ data: undefined, loading: false, error: undefined });
            return;
        }
        let cancelled = false;
        setState((prev) => ({ data: prev.data, loading: true, error: undefined }));
        load(key)
            .then((data) => {
                if (!cancelled) setState({ data, loading: false, error: undefined });
            })
            .catch((e: unknown) => {
                if (!cancelled) {
                    setState({ data: undefined, loading: false, error: e instanceof Error ? e.message : String(e) });
                }
            });
        return () => {
            cancelled = true;
        };
    }, [key]); // eslint-disable-line react-hooks/exhaustive-deps
    return state;
}

/**
 * Loads the list of event types with schemas, cached for the browser session.
 *
 * @returns the loading state of the list
 */
export function useEventSchemas(): AsyncState<EventSchemaSummary[]> {
    return useCached("list", () => cachedList());
}

/**
 * Loads the assembled schema for one event type, cached per type for the browser session.
 *
 * @param eventType the event type, or undefined to load nothing
 * @returns the loading state of the schema
 */
export function useEventSchema(eventType: string | undefined): AsyncState<JsonSchema> {
    return useCached(eventType, cachedSchema);
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npx vitest run src/hooks/useEventSchemas.test.ts`
Expected: PASS.

- [ ] **Step 6: Typecheck**

Run: `npm run build`
Expected: exits 0 (no TypeScript errors).

- [ ] **Step 7: Commit**

```bash
git add ui/src/config/api.ts ui/src/hooks/useEventSchemas.ts ui/src/hooks/useEventSchemas.test.ts
git commit -m "feat(ui): load event schemas with a session-cached hook"
```

---

### Task 3: `EventFieldsPanel` component

**Files:**
- Create: `ui/src/components/EventFieldsPanel.tsx`
- Test: `ui/src/components/EventFieldsPanel.test.tsx`

**Interfaces:**
- Consumes: `useEventSchemas`, `useEventSchema`, `clearEventSchemaCache` (Task 2); `schemaToPaths`,
  `typesFromFilterExpression`, `defaultEventType`, `formatExample`, `EventFieldRow` (Task 1).
- Produces:
  - `interface EventFieldsPanelProps { filterExpression: string; onSelectPath: (path: string) => void;
    onRowsChange?: (rows: EventFieldRow[]) => void }`
  - `function EventFieldsPanel(props: EventFieldsPanelProps): JSX.Element`
  - Accessible names used by later tests: select `"Event type"`, table `"Event fields"`, path buttons
    `"Insert <path>"`.

- [ ] **Step 1: Write the failing tests**

Create `ui/src/components/EventFieldsPanel.test.tsx`:

```tsx
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../config/api";
import type { JsonSchema } from "../config/eventSchemaPaths";
import { clearEventSchemaCache } from "../hooks/useEventSchemas";
import { EventFieldsPanel } from "./EventFieldsPanel";

vi.mock("../config/api", () => ({
    fetchEventSchemas: vi.fn(),
    fetchEventSchema: vi.fn(),
}));

function schemaFor(type: string): JsonSchema {
    return {
        title: type,
        description: `Schema for ${type}`,
        type: "object",
        properties: {
            type: { type: "string", const: type, description: "Normalized event type" },
            payload: {
                type: "object",
                description: "Type-specific payload",
                properties: {
                    issue: {
                        type: "object",
                        description: "The issue",
                        properties: {
                            state: { type: "string", enum: ["open", "closed"], description: "Normalized state" },
                        },
                    },
                },
            },
            sourceData: { description: "Raw source data" },
        },
    };
}

beforeEach(() => {
    clearEventSchemaCache();
    vi.mocked(api.fetchEventSchemas).mockReset().mockResolvedValue([
        { type: "issue.closed" }, { type: "issue.created" }, { type: "pr.merged" },
    ]);
    vi.mocked(api.fetchEventSchema).mockReset().mockImplementation(async (t: string) => schemaFor(t));
});

describe("EventFieldsPanel", () => {
    it("offers all types and defaults to issue.created when the filter does not name a type", async () => {
        render(<EventFieldsPanel filterExpression="" onSelectPath={vi.fn()} />);
        const select = await screen.findByRole("combobox", { name: "Event type" });
        await waitFor(() => expect(select).toHaveValue("issue.created"));
        expect(screen.getAllByRole("option")).toHaveLength(3);
        expect(api.fetchEventSchema).toHaveBeenCalledWith("issue.created");
    });

    it("offers only the types named in the filter expression", async () => {
        render(<EventFieldsPanel filterExpression="event.type == 'pr.merged'" onSelectPath={vi.fn()} />);
        const select = await screen.findByRole("combobox", { name: "Event type" });
        await waitFor(() => expect(select).toHaveValue("pr.merged"));
        expect(screen.getAllByRole("option").map((o) => o.textContent)).toEqual(["pr.merged"]);
        expect(screen.getByText(/event types used in your filter expression/)).toBeInTheDocument();
    });

    it("lists path, type, description and example", async () => {
        render(<EventFieldsPanel filterExpression="" onSelectPath={vi.fn()} />);
        expect(await screen.findByText("event.payload.issue.state")).toBeInTheDocument();
        expect(screen.getByText("Normalized state")).toBeInTheDocument();
        expect(screen.getByText("\"open\"")).toBeInTheDocument();
        expect(screen.getByText("any (source-specific)")).toBeInTheDocument();
    });

    it("calls onSelectPath when a path is clicked", async () => {
        const onSelectPath = vi.fn();
        render(<EventFieldsPanel filterExpression="" onSelectPath={onSelectPath} />);
        await userEvent.click(await screen.findByRole("button", { name: "Insert event.payload.issue.state" }));
        expect(onSelectPath).toHaveBeenCalledWith("event.payload.issue.state");
    });

    it("loads the selected type's schema and reports its rows", async () => {
        const onRowsChange = vi.fn();
        render(<EventFieldsPanel filterExpression="" onSelectPath={vi.fn()} onRowsChange={onRowsChange} />);
        const select = await screen.findByRole("combobox", { name: "Event type" });
        await screen.findByText("event.payload.issue.state");
        await userEvent.selectOptions(select, "issue.closed");
        await waitFor(() => expect(api.fetchEventSchema).toHaveBeenCalledWith("issue.closed"));
        expect(await screen.findByText("Schema for issue.closed")).toBeInTheDocument();
        await waitFor(() => expect(onRowsChange).toHaveBeenLastCalledWith(
            expect.arrayContaining([expect.objectContaining({ path: "event.type", examples: ["issue.closed"] })])));
    });

    it("shows a warning when the type list cannot be loaded", async () => {
        vi.mocked(api.fetchEventSchemas).mockRejectedValue(new Error("offline"));
        render(<EventFieldsPanel filterExpression="" onSelectPath={vi.fn()} />);
        expect(await screen.findByText("Event field reference is unavailable")).toBeInTheDocument();
        expect(screen.getByText("offline")).toBeInTheDocument();
    });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx vitest run src/components/EventFieldsPanel.test.tsx`
Expected: FAIL — `Failed to resolve import "./EventFieldsPanel"`.

- [ ] **Step 3: Implement the component**

Create `ui/src/components/EventFieldsPanel.tsx`:

```tsx
import { useEffect, useMemo, useState } from "react";
import {
    Alert,
    Button,
    ExpandableSection,
    FormGroup,
    FormSelect,
    FormSelectOption,
    HelperText,
    HelperTextItem,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import {
    type EventFieldRow,
    defaultEventType,
    formatExample,
    schemaToPaths,
    typesFromFilterExpression,
} from "../config/eventSchemaPaths";
import { useEventSchema, useEventSchemas } from "../hooks/useEventSchemas";

/** Props of {@link EventFieldsPanel}. */
export interface EventFieldsPanelProps {
    /** Current filter expression; `event.type == '...'` comparisons restrict the type picker. */
    filterExpression: string;
    /** Called with a field path when the user clicks it. */
    onSelectPath: (path: string) => void;
    /** Called whenever the rows for the selected type change (used to feed editor completion). */
    onRowsChange?: (rows: EventFieldRow[]) => void;
}

/**
 * Schema-driven reference of the EL fields available for an event type. Shows the types named in the filter
 * expression, or every type when the expression names none.
 *
 * @param props the panel props
 * @returns the panel
 */
export function EventFieldsPanel({ filterExpression, onSelectPath, onRowsChange }: EventFieldsPanelProps) {
    const [expanded, setExpanded] = useState<boolean>(true);
    const [picked, setPicked] = useState<string | undefined>(undefined);
    const list = useEventSchemas();

    const allTypes: string[] = useMemo(() => (list.data ?? []).map((s) => s.type), [list.data]);
    const filterTypes: string[] = useMemo(
        () => typesFromFilterExpression(filterExpression, allTypes), [filterExpression, allTypes]);
    const choices: string[] = filterTypes.length > 0 ? filterTypes : allTypes;
    const selected: string | undefined = picked && choices.includes(picked) ? picked : defaultEventType(choices);

    const schema = useEventSchema(selected);
    const rows: EventFieldRow[] = useMemo(() => (schema.data ? schemaToPaths(schema.data) : []), [schema.data]);

    useEffect(() => {
        onRowsChange?.(rows);
    }, [rows, onRowsChange]);

    return (
        <ExpandableSection toggleText="Event fields" isExpanded={expanded}
            onToggle={(_e, value) => setExpanded(value)} style={{ marginTop: "16px" }}>
            {list.error ? (
                <Alert variant="warning" isInline title="Event field reference is unavailable">
                    {list.error}
                </Alert>
            ) : (
                <>
                    <FormGroup label="Event type" fieldId="event-fields-type" style={{ maxWidth: "400px" }}>
                        <FormSelect id="event-fields-type" aria-label="Event type" value={selected ?? ""}
                            onChange={(_e, value) => setPicked(value)} isDisabled={choices.length === 0}>
                            {choices.map((t) => <FormSelectOption key={t} value={t} label={t} />)}
                        </FormSelect>
                        <HelperText>
                            <HelperTextItem>
                                {filterTypes.length > 0
                                    ? "Showing the event types used in your filter expression."
                                    : "Your filter expression does not restrict event.type; pick a type to see its fields."}
                            </HelperTextItem>
                        </HelperText>
                    </FormGroup>
                    {schema.data?.description && (
                        <p style={{ marginTop: "8px" }}>{schema.data.description}</p>
                    )}
                    {schema.error && (
                        <Alert variant="warning" isInline title="Could not load the schema" style={{ marginTop: "8px" }}>
                            {schema.error}
                        </Alert>
                    )}
                    <div style={{ maxHeight: "320px", overflowY: "auto", marginTop: "8px" }}>
                        <Table aria-label="Event fields" variant="compact" isStickyHeader>
                            <Thead>
                                <Tr>
                                    <Th>Path</Th>
                                    <Th>Type</Th>
                                    <Th>Description</Th>
                                    <Th>Example</Th>
                                </Tr>
                            </Thead>
                            <Tbody>
                                {rows.map((row) => (
                                    <Tr key={row.path}>
                                        <Td>
                                            <Button variant="link" isInline aria-label={`Insert ${row.path}`}
                                                onClick={() => onSelectPath(row.path)}>
                                                <code>{row.path}</code>
                                            </Button>
                                        </Td>
                                        <Td>{row.type}</Td>
                                        <Td>{row.description}</Td>
                                        <Td><code>{formatExample(row.examples)}</code></Td>
                                    </Tr>
                                ))}
                            </Tbody>
                        </Table>
                    </div>
                </>
            )}
        </ExpandableSection>
    );
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npx vitest run src/components/EventFieldsPanel.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ui/src/components/EventFieldsPanel.tsx ui/src/components/EventFieldsPanel.test.tsx
git commit -m "feat(ui): add schema-driven Event fields panel"
```

---

### Task 4: Subscription Detail integration and user-guide docs

**Files:**
- Modify: `ui/src/pages/SubscriptionDetailPage.tsx` (imports at lines 1-60; module state near line 241;
  completion schema lines ~333-433; `FilterTab` editor ~line 581 and `StreamEventDetailModal` ~line 720;
  `FilterHelpModal` "Available Fields" ~lines 742-768)
- Create: `ui/src/pages/SubscriptionDetailPage.test.tsx`
- Modify: `docs/user-guide/navigating-the-ui.md` (Events section, after the Events table)

**Interfaces:**
- Consumes: `EventFieldsPanel` (Task 3); `appendPath`, `pathsToCompletionTree`, `CompletionNode`,
  `EventFieldRow` (Task 1); `fetchEventSchemas`, `fetchEventSchema` (Task 2, mocked in the test).
- Produces: nothing used by later tasks.

- [ ] **Step 1: Write the failing integration test**

Create `ui/src/pages/SubscriptionDetailPage.test.tsx`:

```tsx
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../config/api";
import { clearEventSchemaCache } from "../hooks/useEventSchemas";
import { renderWithRouter } from "../test/renderWithRouter";
import { SubscriptionDetailPage } from "./SubscriptionDetailPage";

vi.mock("../config/api", () => ({
    fetchSubscription: vi.fn(),
    fetchWorkflowDefinitions: vi.fn().mockResolvedValue({ items: [], totalCount: 0, page: 1, limit: 100 }),
    previewSubscriptionFilter: vi.fn().mockResolvedValue({ results: [], totalCount: 0, totalMatched: 0 }),
    updateSubscription: vi.fn(),
    fetchEventSchemas: vi.fn(),
    fetchEventSchema: vi.fn(),
}));
vi.mock("@patternfly/react-code-editor", () => ({
    CodeEditor: ({ code }: { code: string }) =>
        <textarea aria-label="Filter expression editor" value={code} readOnly />,
}));
vi.mock("../components/StreamEventDetailModal", () => ({ StreamEventDetailModal: () => null }));
vi.mock("../hooks/useTheme", () => ({ useEffectiveTheme: () => "light" }));

beforeEach(() => {
    clearEventSchemaCache();
    vi.mocked(api.fetchSubscription).mockResolvedValue({
        id: 1, name: "Issues", enabled: true, filterExpression: "event.type == 'issue.created' &&",
    } as never);
    vi.mocked(api.fetchEventSchemas).mockResolvedValue([{ type: "issue.closed" }, { type: "issue.created" }]);
    vi.mocked(api.fetchEventSchema).mockImplementation(async (t: string) => ({
        title: t,
        type: "object",
        properties: {
            payload: {
                type: "object",
                properties: { issue: { type: "object", properties: { state: { type: "string" } } } },
            },
        },
    }));
});

function renderPage() {
    renderWithRouter(
        <Routes><Route path="/subscriptions/:subscriptionId" element={<SubscriptionDetailPage />} /></Routes>,
        "/subscriptions/1");
}

describe("SubscriptionDetailPage filter tab", () => {
    it("shows the filter's event type and inserts a clicked path into the expression", async () => {
        renderPage();
        await userEvent.click(await screen.findByRole("tab", { name: "Filter" }));
        expect(await screen.findByRole("combobox", { name: "Event type" })).toHaveValue("issue.created");
        await userEvent.click(await screen.findByRole("button", { name: "Insert event.payload.issue.state" }));
        expect(screen.getByLabelText("Filter expression editor"))
            .toHaveValue("event.type == 'issue.created' && event.payload.issue.state");
        expect(screen.getByRole("button", { name: "Save Changes" })).toBeEnabled();
    });

    it("keeps operators and examples in the help modal but no hard-coded field table", async () => {
        renderPage();
        await userEvent.click(await screen.findByRole("tab", { name: "Filter" }));
        await userEvent.click(screen.getByRole("button", { name: "Filter expression help" }));
        await waitFor(() => expect(screen.getByRole("grid", { name: "Examples" })).toBeInTheDocument());
        expect(screen.getByRole("grid", { name: "Operators" })).toBeInTheDocument();
        expect(screen.queryByRole("grid", { name: "Available fields" })).toBeNull();
        expect(screen.getByText("Only approved PR reviews")).toBeInTheDocument();
        expect(screen.getByText(/Event fields/, { selector: "p" })).toBeInTheDocument();
    });
});
```

Note: PatternFly 6 `Table` renders `role="grid"`. If the run shows `role="table"` instead, change the three
`"grid"` queries to `"table"`; nothing else changes.

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx vitest run src/pages/SubscriptionDetailPage.test.tsx`
Expected: FAIL — `Unable to find role="combobox" and name "Event type"` (first test) and the "Available
fields" table still present (second test).

- [ ] **Step 3: Add imports and module-level completion state**

In `ui/src/pages/SubscriptionDetailPage.tsx`, after `import { StreamEventDetailModal } from
"../components/StreamEventDetailModal";` add:

```ts
import { EventFieldsPanel } from "../components/EventFieldsPanel";
import {
    type CompletionNode,
    type EventFieldRow,
    appendPath,
    pathsToCompletionTree,
} from "../config/eventSchemaPaths";
```

Replace:

```ts
const EL_LANGUAGE_ID = "axiom-el";
let elLanguageRegistered = false;
```

with:

```ts
const EL_LANGUAGE_ID = "axiom-el";
let elLanguageRegistered = false;

/** Completion tree for the EL editor, rebuilt from the Event fields panel's selected type. */
let elCompletionTree: CompletionNode = {};

/**
 * Replaces the EL editor's completion fields. Monaco providers are registered once per page load, so the
 * provider reads this module-level tree on every request.
 */
function setElCompletionFields(rows: EventFieldRow[]): void {
    elCompletionTree = pathsToCompletionTree(rows);
}
```

- [ ] **Step 4: Replace the hard-coded completion schema**

Inside `registerElLanguage`, delete everything from the line `type SchemaNode = { [key: string]: SchemaNode |
string };` through the closing `}` of the `resolveSchema` function (the `actorFields`, `issueFields`,
`pullRequestFields`, `commentFields`, `reviewFields`, `labelFields`, `changeFields`, `payloadFields`,
`completionSchema` constants and `resolveSchema`). Insert in their place:

```ts
    function resolveSchema(path: string[]): CompletionNode | null {
        let node: CompletionNode = elCompletionTree;
        for (const segment of path) {
            const child = node[segment];
            if (child == null || typeof child === "string") return null;
            node = child;
        }
        return node;
    }
```

Then in `provideCompletionItems` replace the two remaining references to `completionSchema`:

```ts
            const node = path.length > 0 ? resolveSchema(path) : elCompletionTree;
```

```ts
            const parent = parentPath.length > 0 ? resolveSchema(parentPath) : elCompletionTree;
```

Verify none remain: `grep -n "completionSchema\|SchemaNode" ui/src/pages/SubscriptionDetailPage.tsx` must
print nothing.

- [ ] **Step 5: Mount the panel and reuse `appendPath`**

In `FilterTab`, directly after the closing `/>` of the `<CodeEditor ... />` element (before the `{/* Summary
stats */}` comment), add:

```tsx
            <EventFieldsPanel
                filterExpression={filterExpression}
                onSelectPath={(path) => setFilterExpression(appendPath(filterExpression, path))}
                onRowsChange={setElCompletionFields}
            />
```

Replace the `onSelectPath` of `<StreamEventDetailModal ...>`:

```tsx
                onSelectPath={(path) => {
                    // Insert the path at the end of the current expression,
                    // adding a space separator if the expression is non-empty
                    const separator = filterExpression && !filterExpression.endsWith(" ") ? " " : "";
                    setFilterExpression(filterExpression + separator + path);
                }}
```

with:

```tsx
                onSelectPath={(path) => setFilterExpression(appendPath(filterExpression, path))}
```

- [ ] **Step 6: Replace the help modal's field table**

In `FilterHelpModal`, replace the `Available Fields` `<Title>` and the whole `<Table aria-label="Available
fields" ...>...</Table>` element with:

```tsx
                <Title headingLevel="h4" size="md" style={{ marginBottom: "12px" }}>
                    Available Fields
                </Title>
                <p>
                    The fields depend on the event type. Use the <strong>Event fields</strong> panel below the
                    expression editor to browse the fields of each type and click a path to insert it.
                </p>
```

Leave the "Operators" and "Examples" tables unchanged.

- [ ] **Step 7: Run the page test and the full suite**

Run: `npx vitest run src/pages/SubscriptionDetailPage.test.tsx`
Expected: PASS.

Run: `npm test`
Expected: all test files pass.

Run: `npm run build`
Expected: exits 0 (no unused-import errors; `ClipboardCopy`, `Table` etc. are still used by the modal).

- [ ] **Step 8: Document the panel in the user guide**

In `docs/user-guide/navigating-the-ui.md`, after the Events table (the line starting `| **Event Stream** |`)
and before the paragraph starting `The event detail page shows`, insert:

```markdown

On a subscription's **Filter** tab, the **Event fields** panel below the expression editor lists the fields
you can use in a filter expression, built from the event schemas Axiom validates events against. Each row
shows the EL path (for example `event.payload.issue.state`), its type, a description and an example value.
Click a path to append it to the expression; the editor's autocompletion uses the same fields.

The panel shows the event types your expression compares `event.type` to with `==` (for example
`event.type == 'issue.closed'`). If the expression names no type, pick one from the **Event type** list; it
starts on `issue.created`. Array elements appear as `[0]` (for example
`event.payload.issue.assignees[0].login`), and `event.sourceData` is listed once as source-specific because
its shape depends on the event source.
```

- [ ] **Step 9: Commit**

```bash
git add ui/src/pages/SubscriptionDetailPage.tsx ui/src/pages/SubscriptionDetailPage.test.tsx \
    docs/user-guide/navigating-the-ui.md
git commit -m "feat(ui): replace hard-coded EL field tables with the Event fields panel"
```

---

### Task 5: Validation warnings on Event Detail and user-guide docs

**Files:**
- Modify: `ui/src/pages/EventDetailPage.tsx` (after the `startedRun` alert, ~line 149)
- Test: `ui/src/pages/EventDetailPage.test.tsx`
- Modify: `docs/user-guide/navigating-the-ui.md` (the event detail paragraph in the Events section)

**Interfaces:**
- Consumes: `StreamEvent.validationErrors?: string[]` (Task 2).
- Produces: nothing.

- [ ] **Step 1: Write the failing tests**

In `ui/src/pages/EventDetailPage.test.tsx`, add inside `describe("EventDetailPage", () => { ... })`:

```tsx
    it("warns about schema validation errors", async () => {
        vi.mocked(api.fetchStreamEvent).mockResolvedValue({
            id: EVENT_ID, type: "issue.opened", source: "github", eventSourceId: 1,
            timestamp: "2026-10-01T00:00:00Z",
            validationErrors: ["$.payload.issue.state: must be one of [open, closed]", "$.timestamp: bad format"],
        } as never);
        renderPage();
        expect(await screen.findByText("This event does not match the issue.opened schema")).toBeInTheDocument();
        expect(screen.getByText("$.payload.issue.state: must be one of [open, closed]")).toBeInTheDocument();
        expect(screen.getByText("$.timestamp: bad format")).toBeInTheDocument();
    });

    it("shows no validation warning when there are no errors", async () => {
        vi.mocked(api.fetchStreamEvent).mockResolvedValue({
            id: EVENT_ID, type: "issue.opened", source: "github", eventSourceId: 1,
            timestamp: "2026-10-01T00:00:00Z", validationErrors: [],
        } as never);
        renderPage();
        await screen.findByRole("button", { name: "Run workflow…" });
        expect(screen.queryByText(/does not match the .* schema/)).toBeNull();
    });
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx vitest run src/pages/EventDetailPage.test.tsx`
Expected: FAIL — `Unable to find an element with the text: This event does not match the issue.opened
schema`; the second new test and the existing tests pass.

- [ ] **Step 3: Add the alert**

In `ui/src/pages/EventDetailPage.tsx`, directly after the closing `)}` of the `{startedRun && ( <Alert
variant="success" ...> ... )}` block, add:

```tsx
            {event.validationErrors && event.validationErrors.length > 0 && (
                <Alert variant="warning" isInline style={{ marginBottom: "16px" }}
                    title={`This event does not match the ${event.type} schema`}>
                    <p>
                        Axiom stored the event anyway. Subscription filters that use the fields below may not
                        behave as expected.
                    </p>
                    <ul>
                        {event.validationErrors.map((error) => (
                            <li key={error}><code>{error}</code></li>
                        ))}
                    </ul>
                </Alert>
            )}
```

(`Alert` is already imported in this file.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npx vitest run src/pages/EventDetailPage.test.tsx`
Expected: PASS (all tests, old and new).

Run: `npm run build`
Expected: exits 0.

- [ ] **Step 5: Document the warning in the user guide**

In `docs/user-guide/navigating-the-ui.md`, after the paragraph that starts `The event detail page shows the
full typed payload` (ending with `` `trigger:manual`. ``), add:

```markdown

Axiom checks every incoming event against the schema for its type. An event that does not match is still
stored and processed, and its detail page shows a warning listing each problem with the JSON path of the
field (for example `$.payload.issue.state: must be one of [open, closed]`). Subscription filters that use
those fields may not behave as expected for that event. Events recorded before schema validation existed
show no warning.
```

- [ ] **Step 6: Commit**

```bash
git add ui/src/pages/EventDetailPage.tsx ui/src/pages/EventDetailPage.test.tsx docs/user-guide/navigating-the-ui.md
git commit -m "feat(ui): show schema validation warnings on the event detail page"
```

---

## Final verification

- [ ] Run (in `ui/`): `npm test` — expected: all test files pass.
- [ ] Run (in `ui/`): `npm run build` — expected: exits 0.
- [ ] Optional live check (per `AGENTS.md`, never against port 9191/9090): from the repo root run
  `AXIOM_HOME=/tmp/axiom-test/home ./dev.sh --portOffset=500 --dataDir=/tmp/axiom-test/data`, open a
  subscription's Filter tab, confirm the Event fields panel lists `event.payload.issue.state` for
  `issue.created`, click a path, then stop the instance.

## Decisions and spec ambiguities resolved

- **No generated TS client.** The spec says "uses the generated API client"; the UI has none (all calls are
  hand-written in `ui/src/config/api.ts`). The plan adds wrappers there; no regeneration step exists.
- **`issue.opened` does not exist.** `EventType` has `issue.created`. The default is the first of
  `["issue.opened", "issue.created"]` present, else the first type, so it lands on `issue.created` today.
- **"The subscription's type filter".** `Subscription` has no type-filter field; types are extracted from the
  filter expression's `event.type == '...'` comparisons (either operand order, either quote style; `!=` and
  `startsWith` are ignored) and intersected with the server's type list. With several types, the picker is
  limited to them.
- **"Field tables around lines 338 and 763".** Line ~338 is the hard-coded Monaco completion schema, ~763 is
  the help modal's "Available Fields" table. Both are replaced: completion now reads the panel's selected type,
  and the modal points to the panel. Operators and Examples ("common expressions") stay.
- **Arrays in completion.** EL completion works on dotted identifiers, so `[0]` paths are panel-only; the
  array field itself remains a completion leaf.
- **Array rows.** An array field gets its own row (`array<T>`) plus `[0].child` rows when items are objects;
  arrays of scalars get just the one row.
- **Panel placement.** An expanded-by-default `ExpandableSection` below the editor with a 320px scrolling
  table, so the preview stays reachable.
