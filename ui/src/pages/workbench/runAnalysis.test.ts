import { describe, expect, it } from "vitest";
import { runAnalysisPrefill } from "./runAnalysis";

describe("runAnalysisPrefill", () => {
    it("includes node and reason", () => {
        expect(runAnalysisPrefill({ id: 9, status: "failed", currentNodeName: "classify", reason: "boom" }, "triage"))
            .toBe("Analyze run #9 of workflow `triage` (status: failed at node `classify`): boom. "
                + "Find the cause and fix the workflow or its components.");
    });

    it("omits the node and the reason when unknown", () => {
        expect(runAnalysisPrefill({ id: 9, status: "failed" }, "triage"))
            .toBe("Analyze run #9 of workflow `triage` (status: failed). Find the cause and fix the workflow or its components.");
    });

    it("uses the first non-empty line of the reason", () => {
        expect(runAnalysisPrefill({ id: 2, status: "failed", reason: "\n  first  \nsecond" }, "w"))
            .toBe("Analyze run #2 of workflow `w` (status: failed): first. Find the cause and fix the workflow or its components.");
    });
});
