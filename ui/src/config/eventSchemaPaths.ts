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
