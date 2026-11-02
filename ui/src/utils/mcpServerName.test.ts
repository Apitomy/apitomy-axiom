import { describe, expect, it } from "vitest";
import { MCP_SERVER_NAME_MAX, mcpServerNameError } from "./mcpServerName";

describe("mcpServerNameError", () => {
    it("accepts valid names and an empty name", () => {
        for (const name of ["", "github", "a", "my-server_2", "A1"]) {
            expect(mcpServerNameError(name)).toBeNull();
        }
    });

    it("rejects invalid characters and edges", () => {
        for (const name of ["bad name!", "-start", "end-", "_x", "a.b"]) {
            expect(mcpServerNameError(name)).toMatch(/letters, digits/);
        }
    });

    it("rejects double underscores", () => {
        expect(mcpServerNameError("a__b")).toMatch(/__/);
    });

    it("rejects names that are too long", () => {
        expect(mcpServerNameError("a".repeat(MCP_SERVER_NAME_MAX + 1))).toMatch(/at most/);
    });
});
