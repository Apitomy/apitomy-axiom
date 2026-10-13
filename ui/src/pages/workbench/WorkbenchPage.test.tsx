import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import * as c from "../../config/compositions";
import * as prefs from "./workbenchPrefs";
import type { WorkbenchState } from "./useWorkbench";

vi.mock("@apitomy/flow-ui", () => ({}));
const setContent = vi.fn();
vi.mock("./useWorkbench", () => ({
    useWorkbench: (): WorkbenchState => ({
        composition: { id: 1, name: "My comp", turnState: "idle", applyMode: "live", createdAt: "", updatedAt: "" },
        items: [{ id: 10, type: "workflow", name: "main-flow", contentRevision: "r", lastAuthor: "user", changed: false } as never,
            { id: 11, type: "tool", name: "my-tool", contentRevision: "r", lastAuthor: "user", changed: false } as never],
        contents: { 10: {} }, revisions: {}, problems: {}, pending: null, lastCompletedTurnId: undefined, sessionError: null,
        loadContent: async () => ({}), refreshItems: async () => {}, refreshComposition: async () => {},
        setContent, removeItem: async () => {}, markPendingHandled: () => {}, retrySession: async () => {},
    }),
}));

let workflowTabProps: Record<string, any> = {};
const workflowFlush = vi.fn();
vi.mock("./WorkflowTab", async () => {
    const { useImperativeHandle } = await import("react");
    return {
        WorkflowTab: (props: Record<string, any>) => {
            workflowTabProps = props;
            useImperativeHandle(props.ref, () => ({ flush: workflowFlush, replace: () => {},
                selection: () => ({ nodeIds: [], edgeIds: [] }) }));
            return <div data-testid="wf" />;
        },
    };
});
vi.mock("./PublishDialog", () => ({ PublishDialog: () => <div data-testid="publish-dialog" /> }));

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
    setContent.mockReset();
    workflowFlush.mockReset();
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

    it("places panes with grid-column classes even when collapsed", () => {
        const { container } = renderPage();
        expect(container.querySelector(".axiom-workbench__splitter--left")).toBeTruthy();
        fireEvent.click(screen.getByRole("button", { name: "Collapse left pane" }));
        fireEvent.click(screen.getByRole("button", { name: "Collapse chat pane" }));
        const grid = container.querySelector(".axiom-workbench")!;
        const classes = Array.from(grid.children).map((el) => el.className);
        expect(classes).toEqual(expect.arrayContaining([
            expect.stringContaining("axiom-workbench__left"),
            expect.stringMatching(/axiom-workbench__splitter .*axiom-workbench__splitter--left/),
            expect.stringContaining("axiom-workbench__centre"),
            expect.stringMatching(/axiom-workbench__splitter .*axiom-workbench__splitter--right/),
            expect.stringContaining("axiom-workbench__right"),
        ]));
        expect(container.querySelectorAll("[role=separator]")).toHaveLength(0);
        expect(grid.parentElement?.className).toContain("axiom-workbench-page");
    });

    it("renders the active tab content as a direct child of the centre column", () => {
        const { container } = renderPage();
        const content = container.querySelector(".axiom-workbench__centre > .pf-v6-c-tab-content:not([hidden])");
        expect(content?.querySelector("[data-testid=wf]")).toBeTruthy();
    });

    it("flushes the workflow editor before opening the Publish dialog", async () => {
        let release: () => void = () => {};
        workflowFlush.mockReturnValue(new Promise<void>((r) => { release = r; }));
        renderPage();
        fireEvent.click(screen.getByRole("button", { name: /Publish/ }));
        expect(workflowFlush).toHaveBeenCalled();
        expect(screen.queryByTestId("publish-dialog")).toBeNull();
        await act(async () => release());
        expect(await screen.findByTestId("publish-dialog")).toBeTruthy();
    });

    it("records its own workflow saves as current content", () => {
        renderPage();
        const content = { id: "w", name: "wf", nodes: [], edges: [] };
        act(() => workflowTabProps.onSaved({ id: 10, contentRevision: "r2" }, content, undefined));
        expect(setContent).toHaveBeenCalledWith(10, content, "r2");
    });

    it("opens the revision history of the active item from the Components toolbar", async () => {
        vi.spyOn(c, "listRevisions").mockResolvedValue([
            { id: 1, contentRevision: "a", author: "user", createdAt: "2026-01-01T00:00:00Z" },
            { id: 2, contentRevision: "b", author: "ai", turnId: "t1", createdAt: "2026-01-02T00:00:00Z" },
        ]);
        renderPage();
        fireEvent.click(screen.getByRole("button", { name: "Revision history" }));
        expect(await screen.findByText(/Revision history: main-flow/)).toBeTruthy();
        expect(await screen.findAllByRole("button", { name: /restore/i })).toHaveLength(2);
    });

    it("opens the add dialog from the Create menu in the Components toolbar", async () => {
        renderPage();
        fireEvent.click(screen.getByRole("button", { name: "Create" }));
        fireEvent.click(await screen.findByRole("menuitem", { name: "Tool" }));
        expect(await screen.findByText("Add tool")).toBeTruthy();
    });

    it("closes a component tab on middle click", () => {
        renderPage();
        fireEvent.click(screen.getByRole("button", { name: /^my-tool/ }));
        const tab = screen.getByRole("tab", { name: "my-tool" });
        fireEvent(tab, new MouseEvent("auxclick", { bubbles: true, button: 1 }));
        expect(screen.queryByRole("tab", { name: "my-tool" })).toBeNull();
    });

    it("maximizes the canvas and restores both side panels on a second click", () => {
        renderPage();
        fireEvent.click(screen.getByRole("button", { name: "Maximize canvas" }));
        expect(screen.getByRole("button", { name: "Expand left pane" })).toBeTruthy();
        expect(screen.getByRole("button", { name: "Expand chat pane" })).toBeTruthy();
        fireEvent.click(screen.getByRole("button", { name: "Restore side panels" }));
        expect(screen.getByRole("button", { name: "Collapse left pane" })).toBeTruthy();
        expect(screen.getByRole("button", { name: "Collapse chat pane" })).toBeTruthy();
    });
});
