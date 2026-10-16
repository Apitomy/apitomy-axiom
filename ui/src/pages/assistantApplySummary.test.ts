import { describe, expect, it } from "vitest";
import { applySummaryLines } from "./assistantApplySummary";

describe("applySummaryLines", () => {
    it("lists every item type that was created or updated", () => {
        expect(applySummaryLines({
            actionTypesCreated: 4,
            eventSourcesCreated: 1,
            subscriptionsCreated: 1,
            workflowDefinitionsCreated: 1,
            toolsUpdated: 2,
        })).toEqual([
            "2 tool(s) updated",
            "4 action type(s) created",
            "1 workflow(s) created",
            "1 event source(s) created",
            "1 subscription(s) created",
        ]);
    });

    it("covers all seven item types", () => {
        const lines = applySummaryLines({
            toolsCreated: 1, toolsetsCreated: 1, actionTypesCreated: 1, sessionTemplatesCreated: 1,
            workflowDefinitionsUpdated: 1, eventSourcesUpdated: 1, subscriptionsUpdated: 1,
        });
        expect(lines).toHaveLength(7);
    });

    it("is empty when nothing changed", () => {
        expect(applySummaryLines({})).toEqual([]);
    });
});
