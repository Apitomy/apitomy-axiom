import { describe, expect, it } from "vitest";
import * as provider from "./PlaceholderCompletionProvider";
import { inputPlaceholderItems, SCRIPT_ENV_VARS } from "./PlaceholderCompletionProvider";

describe("PlaceholderCompletionProvider", () => {
    it("builds inputs.<name> completions for declared inputs", () => {
        expect(inputPlaceholderItems([{ name: "ref", description: "Issue URL" }]))
            .toEqual([{ name: "inputs.ref", description: "Issue URL" }]);
    });

    it("skips inputs without a name", () => {
        expect(inputPlaceholderItems([{ name: "", description: "x" }])).toEqual([]);
    });

    it("no longer exports built-in placeholder lists", () => {
        expect("ACTION_TYPE_PLACEHOLDERS" in provider).toBe(false);
        expect("SCRIPT_PLACEHOLDERS" in provider).toBe(false);
    });

    it("documents the script plumbing env vars", () => {
        expect(SCRIPT_ENV_VARS.map(v => v.name))
            .toEqual(["AXIOM_API_URL", "AXIOM_TASK_ID", "AXIOM_WORK_DIR"]);
    });
});
