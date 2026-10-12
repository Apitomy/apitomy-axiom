import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import * as c from "../../config/compositions";
import * as prefs from "./workbenchPrefs";
import type { WorkbenchState } from "./useWorkbench";

vi.mock("@apitomy/flow-ui", () => ({}));
vi.mock("./useWorkbench", () => ({
    useWorkbench: (): WorkbenchState => ({
        composition: { id: 1, name: "My comp", turnState: "idle", applyMode: "live", createdAt: "", updatedAt: "" },
        items: [{ id: 10, type: "workflow", name: "main-flow", contentRevision: "r", lastAuthor: "user", changed: false } as never],
        contents: { 10: {} }, revisions: {}, problems: {}, pending: null, lastCompletedTurnId: undefined, sessionError: null,
        loadContent: async () => ({}), refreshItems: async () => {}, refreshComposition: async () => {},
        setContent: () => {}, markPendingHandled: () => {}, retrySession: async () => {},
    }),
}));

let workflowTabProps: Record<string, any> = {};
vi.mock("./WorkflowTab", () => ({
    WorkflowTab: (props: Record<string, any>) => { workflowTabProps = props; return <div data-testid="wf" />; },
}));

import { act } from "@testing-library/react";
import { WorkbenchPage } from "./WorkbenchPage";

function Probe() {
    const loc = useLocation();
    return <div data-testid="loc">{loc.search}</div>;
}

function renderPage() {
    return render(
        <MemoryRouter initialEntries={["/workbench/1"]}>
            <Routes>
                <Route path="/workbench/:compositionId" element={<><WorkbenchPage /><Probe /></>} />
            </Routes>
        </MemoryRouter>,
    );
}

beforeEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
});

describe("WorkbenchPage", () => {
    it("shows the composition name and the workflow tab", () => {
        renderPage();
        expect(screen.getByText("My comp")).toBeTruthy();
        expect(screen.getAllByText("main-flow").length).toBeGreaterThan(0);
    });

    it("toggles the apply mode", async () => {
        const spy = vi.spyOn(c, "updateComposition").mockResolvedValue({} as never);
        renderPage();
        fireEvent.click(screen.getByRole("button", { name: "Review changes" }));
        await waitFor(() => expect(spy).toHaveBeenCalledWith(1, { applyMode: "review" }));
    });

    it("enters focus mode", () => {
        renderPage();
        fireEvent.click(screen.getByRole("button", { name: /Focus/ }));
        expect(screen.getByTestId("loc").textContent).toContain("focus=1");
    });

    it("saves the collapsed left pane", () => {
        const spy = vi.spyOn(prefs, "savePrefs");
        renderPage();
        fireEvent.click(screen.getByRole("button", { name: "Collapse left pane" }));
        expect(spy).toHaveBeenCalledWith(expect.objectContaining({ leftCollapsed: true }));
    });

    it("expands a collapsed chat pane on Ask AI", () => {
        const spy = vi.spyOn(prefs, "savePrefs");
        renderPage();
        fireEvent.click(screen.getByRole("button", { name: "Collapse chat pane" }));
        expect(screen.getByRole("button", { name: "Expand chat pane" })).toBeTruthy();
        act(() => workflowTabProps.onAskAi("explain node a"));
        expect(screen.getByRole("button", { name: "Collapse chat pane" })).toBeTruthy();
        expect(spy).toHaveBeenLastCalledWith(expect.objectContaining({ rightCollapsed: false }));
    });
});
