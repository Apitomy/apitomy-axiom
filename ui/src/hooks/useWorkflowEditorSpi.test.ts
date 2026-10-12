import { describe, expect, it, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { findActionTypeIdByName, useWorkflowEditorSpi } from "./useWorkflowEditorSpi";
import * as api from "../config/api";

describe("useWorkflowEditorSpi", () => {
    beforeEach(() => vi.restoreAllMocks());

    it("exposes openActionType only when given", () => {
        const open = vi.fn();
        expect(renderHook(() => useWorkflowEditorSpi()).result.current.openActionType).toBeUndefined();
        const spi = renderHook(() => useWorkflowEditorSpi({ openActionType: open })).result.current;
        spi.openActionType?.({ value: "x", resolved: true });
        expect(open).toHaveBeenCalledWith({ value: "x", resolved: true });
    });

    it("finds action type ids by name", async () => {
        vi.spyOn(api, "fetchActionTypes").mockResolvedValue({
            items: [{ id: 7, name: "triage", executionMode: "agent" }], totalCount: 1,
        } as never);
        expect(await findActionTypeIdByName("triage")).toBe(7);
        expect(await findActionTypeIdByName("nope")).toBeUndefined();
    });
});
