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
import defsJson from "../../../core/src/main/resources/schemas/events/defs.json";
import envelopeJson from "../../../core/src/main/resources/schemas/events/envelope.json";
import issueClosedPayloadJson from "../../../core/src/main/resources/schemas/events/payloads/issue.closed.json";

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

/**
 * Builds the schema served by `GET /api/v1/schemas/events/issue.closed` from the real resource files,
 * mirroring `EventSchemaRegistry.assemble`.
 */
function assembleIssueClosed(): JsonSchema {
    const schema = structuredClone(envelopeJson) as JsonSchema;
    const payload = structuredClone(issueClosedPayloadJson) as JsonSchema;
    const properties = schema.properties as Record<string, JsonSchema>;
    schema.title = "issue.closed";
    schema.description = payload.description ?? "";
    schema.$defs = structuredClone(defsJson.$defs) as Record<string, JsonSchema>;
    properties.type = { ...properties.type, const: "issue.closed" };
    delete payload.$schema;
    properties.payload = payload;
    return schema;
}

describe("schemaToPaths on the real assembled issue.closed schema", () => {
    const rows = schemaToPaths(assembleIssueClosed());

    it("collapses [\"array\", \"null\"] arrays and expands object items as [0]", () => {
        expect(row(rows, "event.payload.issue.assignees")?.type).toBe("array<object>");
        expect(row(rows, "event.payload.issue.assignees[0].login")?.type).toBe("string");
        expect(row(rows, "event.payload.issue.labels")).toMatchObject({ type: "array<string>", examples: [["bug"]] });
        expect(rows.some((r) => r.path.startsWith("event.payload.issue.labels["))).toBe(false);
    });

    it("derives the type of an enum-only property from its values", () => {
        expect(row(rows, "event.payload.issue.state")).toEqual({
            path: "event.payload.issue.state",
            type: "string",
            description: "Normalized state.",
            examples: ["open"],
        });
    });

    it("follows $ref -> anyOf -> $ref chains for nullable timestamps", () => {
        expect(row(rows, "event.payload.issue.closedAt")).toEqual({
            path: "event.payload.issue.closedAt",
            type: "string",
            description: "Close (resolution) time.",
            examples: ["2026-10-21T14:53:07.835Z"],
        });
        expect(row(rows, "event.timestamp")?.type).toBe("string");
    });

    it("types const-only properties from the const value", () => {
        expect(row(rows, "event.payload.type")).toMatchObject({ type: "string", examples: ["issue.closed"] });
        expect(row(rows, "event.type")).toMatchObject({ type: "string", examples: ["issue.closed"] });
    });

    it("expands the nullable actor", () => {
        expect(row(rows, "event.actor")).toMatchObject({ type: "object", description: "Who performed the action." });
        expect(row(rows, "event.actor.login")?.type).toBe("string");
        expect(row(rows, "event.payload.issue.author.displayName")?.type).toBe("string");
    });

    it("shows untyped sourceData as one source-specific row", () => {
        expect(rows.filter((r) => r.path.startsWith("event.sourceData"))).toEqual([{
            path: "event.sourceData",
            type: SOURCE_SPECIFIC_TYPE,
            description: "Source-specific data. Not validated; contents vary by source.",
            examples: [],
        }]);
    });

    it("reports no row as any", () => {
        expect(rows.filter((r) => r.type === "any")).toEqual([]);
    });
});
