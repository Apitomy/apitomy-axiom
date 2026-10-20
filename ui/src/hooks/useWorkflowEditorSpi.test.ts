import { describe, expect, it, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { findActionTypeIdByName, loadEditorActionTypes, useWorkflowEditorSpi } from "./useWorkflowEditorSpi";
import * as api from "../config/api";
import * as sdkTools from "../config/sdkTools";
import { AGENT_ACTION_COLOR, SDK_ACTION_COLOR } from "../config/actionTypeAppearance";

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

    it("styles SDK calls and agent action types, but not scripts", async () => {
        vi.spyOn(api, "fetchActionTypes").mockResolvedValue({
            items: [
                { id: 1, name: "triage", executionMode: "agent" },
                { id: 2, name: "cleanup", executionMode: "script" },
            ], totalCount: 2,
        } as never);
        vi.spyOn(sdkTools, "fetchSdkFunctions").mockResolvedValue([
            { name: "axiom_create_task", sdkCallSupported: true },
        ] as never);
        const byValue = new Map((await loadEditorActionTypes()).map((d) => [d.value, d]));
        expect(byValue.get("triage")?.color).toBe(AGENT_ACTION_COLOR);
        expect(byValue.get("triage")?.icon).toBeDefined();
        expect(byValue.get("sdk:axiom_create_task")?.color).toBe(SDK_ACTION_COLOR);
        expect(byValue.get("sdk:axiom_create_task")?.icon).toBeDefined();
        expect(byValue.get("cleanup")?.icon).toBeUndefined();
        expect(byValue.get("cleanup")?.color).toBeUndefined();
    });
});
