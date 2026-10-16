import { describe, expect, it } from "vitest";
import { parseFieldValue, valuesFromInputs, testFields, testUnsupportedReason } from "./testRunLogic";

describe("testRunLogic", () => {
    it("reads action type inputs and tool parameters", () => {
        expect(testFields("action-type", { inputs: [{ name: "a", type: "number", required: true }, { type: "x" }] }))
            .toEqual([{ name: "a", type: "number", required: true, description: undefined }]);
        expect(testFields("tool", { parameters: [{ name: "p", description: "d" }] }))
            .toEqual([{ name: "p", type: "string", required: false, description: "d" }]);
    });

    it("refuses agent action types only", () => {
        expect(testUnsupportedReason("action-type", { executionMode: "agent" }))
            .toBe("Test runs of AI Agent action types are not supported yet.");
        expect(testUnsupportedReason("action-type", { executionMode: "node" })).toBeNull();
        expect(testUnsupportedReason("tool", {})).toBeNull();
    });

    it("parses values by type", () => {
        const f = (type: string) => ({ name: "x", type, required: false });
        expect(parseFieldValue(f("string"), "")).toEqual({});
        expect(parseFieldValue(f("string"), " hi ")).toEqual({ value: " hi " });
        expect(parseFieldValue(f("number"), "4.5")).toEqual({ value: 4.5 });
        expect(parseFieldValue(f("number"), "four").error).toBe("Enter a number.");
        expect(parseFieldValue(f("boolean"), "true")).toEqual({ value: true });
        expect(parseFieldValue(f("boolean"), "yes").error).toBe("Enter true or false.");
        expect(parseFieldValue(f("array"), "[1,2]")).toEqual({ value: [1, 2] });
        expect(parseFieldValue(f("array"), "{}").error).toBe("Enter a JSON array.");
        expect(parseFieldValue(f("object"), "{\"a\":1}")).toEqual({ value: { a: 1 } });
        expect(parseFieldValue(f("object"), "[").error).toBe("Enter a JSON object or array.");
        expect(parseFieldValue(f("object"), '["bug","ui"]')).toEqual({ value: ["bug", "ui"] });
        expect(parseFieldValue(f("object"), "42").error).toBe("Enter a JSON object or array.");
    });

    it("turns suggested inputs into form values", () => {
        expect(valuesFromInputs(undefined)).toEqual({});
        expect(valuesFromInputs({ a: "x", b: 2, c: true, d: [1], e: { k: "v" } }))
            .toEqual({ a: "x", b: "2", c: "true", d: "[1]", e: "{\"k\":\"v\"}" });
    });
});
