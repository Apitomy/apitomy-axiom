import { describe, expect, it } from "vitest";
import { activityTypePresentation } from "./activityTypes";

describe("activityTypePresentation", () => {
    it("maps 3.0 entry types to labels and colours", () => {
        expect(activityTypePresentation("workflow-failed")).toEqual({ label: "Workflow failed", color: "red" });
        expect(activityTypePresentation("workflow-completed").color).toBe("green");
        expect(activityTypePresentation("task-awaiting-input").label).toBe("Awaiting input");
        expect(activityTypePresentation("subject-created").color).toBe("purple");
        expect(activityTypePresentation("message").label).toBe("Message");
    });

    it("falls back to the raw type in grey", () => {
        expect(activityTypePresentation("pipeline-error")).toEqual({ label: "pipeline-error", color: "grey" });
    });
});
