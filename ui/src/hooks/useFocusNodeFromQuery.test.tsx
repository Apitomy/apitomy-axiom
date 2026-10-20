import { describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import type { ReactNode } from "react";
import type { WorkflowEditorHandle } from "@apitomy/flow-ui";
import { useFocusNodeFromQuery } from "./useFocusNodeFromQuery";

const wrapper = (url: string) => ({ children }: { children: ReactNode }) =>
    <MemoryRouter initialEntries={[url]}>{children}</MemoryRouter>;

const handle = () => ({ select: vi.fn() }) as unknown as WorkflowEditorHandle;

describe("useFocusNodeFromQuery", () => {
    it("selects and centers the node named by ?node= once the editor is ready, then drops the param", () => {
        const h = handle();
        const { result, rerender } = renderHook(({ editor }) => {
            useFocusNodeFromQuery(editor);
            return useLocation();
        }, { wrapper: wrapper("/components/workflows/4?node=step-1&x=1"), initialProps: { editor: null as WorkflowEditorHandle | null } });
        expect(h.select).not.toHaveBeenCalled();

        rerender({ editor: h });
        expect(h.select).toHaveBeenCalledWith({ nodeId: "step-1" }, { center: true });
        expect(result.current.search).toBe("?x=1");

        rerender({ editor: h });
        expect(h.select).toHaveBeenCalledTimes(1);
    });

    it("does nothing without ?node=", () => {
        const h = handle();
        renderHook(() => useFocusNodeFromQuery(h), { wrapper: wrapper("/components/workflows/4") });
        expect(h.select).not.toHaveBeenCalled();
    });
});
