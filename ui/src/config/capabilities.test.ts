import { describe, expect, it } from "vitest";
import { hasCapability } from "./capabilities";

describe("hasCapability", () => {
    it("is true when the capability is listed", () => {
        expect(hasCapability({ capabilities: ["config-items"] }, "config-items")).toBe(true);
    });

    it("is false when the capability is absent", () => {
        expect(hasCapability({ capabilities: ["composition"] }, "config-items")).toBe(false);
    });

    it("is false when capabilities are missing or the target is null", () => {
        expect(hasCapability({}, "config-items")).toBe(false);
        expect(hasCapability(null, "config-items")).toBe(false);
        expect(hasCapability(undefined, "composition")).toBe(false);
    });
});
