import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import * as c from "../../config/compositions";
import * as prefs from "./workbenchPrefs";
import type { WorkbenchState } from "./useWorkbench";

vi.mock("@apitomy/flow-ui", () => ({}));
const setContent = vi.fn();
const runTest = vi.fn();
let testRuns: c.CompositionTestRun[] = [];
vi.mock("./useWorkbench", () => ({
    useWorkbench: (): WorkbenchState => ({
        composition: { id: 1, name: "My comp", turnState: "idle", applyMode: "live", createdAt: "", updatedAt: "",
            session: { engine: "claude-code", engineLabel: "Claude Code" } },
        items: [{ id: 10, type: "workflow", name: "main-flow", contentRevision: "r", lastAuthor: "user", changed: false } as never,
            { id: 11, type: "tool", name: "my-tool", contentRevision: "r", lastAuthor: "user", changed: false } as never],
        contents: { 10: {}, 11: { name: "my-tool", parameters: [{ name: "who", required: true }],
            scriptTemplate: "echo {{who}}" } }, revisions: {}, problems: {}, pending: null, lastCompletedTurnId: undefined, sessionError: null,
        loadContent: async () => ({}), refreshItems: async () => {}, refreshComposition: async () => {},
        setContent, removeItem: async () => {}, markPendingHandled: () => {}, retrySession: async () => {},
        testRuns, runTest,
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
const itemFlush = vi.fn();
vi.mock("./ItemTab", async () => {
    const { useImperativeHandle } = await import("react");
    return {
        ItemTab: (props: Record<string, any>) => {
            useImperativeHandle(props.ref, () => ({ flush: itemFlush }));
            return <div data-testid="item-tab" />;
        },
    };
});
vi.mock("../../config/api", async (orig) => ({
    ...(await orig<Record<string, unknown>>()),
    fetchSubjects: vi.fn().mockResolvedValue({ items: [], count: 0 }),
}));
vi.mock("./PublishDialog", () => ({ PublishDialog: () => <div data-testid="publish-dialog" /> }));

import { act } from "@testing-library/react";
import { WorkbenchPage } from "./WorkbenchPage";

function Probe() {
    const loc = useLocation();
    return <div data-testid="loc">{loc.search}</div>;
}

function page() {
    return (
        <MemoryRouter initialEntries={["/workbench/1"]}>
            <Routes>
                <Route path="/workbench/:compositionId" element={<><WorkbenchPage /><Probe /></>} />
            </Routes>
        </MemoryRouter>
    );
}

function renderPage() {
    return render(page());
}

beforeEach(() => {
    vi.restoreAllMocks();
    setContent.mockReset();
    workflowFlush.mockReset();
    runTest.mockReset();
    testRuns = [];
    runTest.mockResolvedValue({});
    itemFlush.mockReset();
    itemFlush.mockResolvedValue(undefined);
    localStorage.clear();
});

describe("WorkbenchPage", () => {
    it("shows the composition name and the workflow tab", () => {
        renderPage();
        expect(screen.getByText("My comp")).toBeTruthy();
        expect(screen.getByRole("link", { name: "Workbench" }).getAttribute("href")).toBe("/workbench");
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

    it("shows the session's engine and model in the chat toolbar", () => {
        renderPage();
        expect(screen.getByText("Claude Code · default")).toBeTruthy();
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

    it("tests the active tool from the Test output strip", async () => {
        renderPage();
        fireEvent.click(screen.getByRole("button", { name: /^my-tool/ }));
        fireEvent.click(screen.getByRole("button", { name: "Test…" }));
        fireEvent.change(await screen.findByLabelText("who"), { target: { value: "ann" } });
        fireEvent.click(screen.getByRole("button", { name: "Run" }));
        await waitFor(() => expect(runTest).toHaveBeenCalledWith(11, { who: "ann" }, undefined));
        expect(itemFlush).toHaveBeenCalled();
    });

    it("shows an error when the test request fails", async () => {
        runTest.mockRejectedValue(new Error("boom"));
        renderPage();
        fireEvent.click(screen.getByRole("button", { name: /^my-tool/ }));
        fireEvent.click(screen.getByRole("button", { name: "Test…" }));
        fireEvent.change(await screen.findByLabelText("who"), { target: { value: "ann" } });
        fireEvent.click(screen.getByRole("button", { name: "Run" }));
        expect(await screen.findByText(/Could not run the test: boom/)).toBeTruthy();
        fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));
        expect(screen.queryByText(/Could not run the test/)).toBeNull();
    });

    it("disables Test… on the workflow tab", () => {
        renderPage();
        expect((screen.getByRole("button", { name: "Test…" }) as HTMLButtonElement).disabled).toBe(true);
    });

    it("saves the test strip collapsed state", () => {
        const spy = vi.spyOn(prefs, "savePrefs");
        renderPage();
        fireEvent.click(screen.getByRole("button", { name: "Expand test output" }));
        expect(spy).toHaveBeenCalledWith(expect.objectContaining({ testStripCollapsed: false }));
    });

    it("prefills the Test… dialog with the values typed last time", async () => {
        renderPage();
        fireEvent.click(screen.getByRole("button", { name: /^my-tool/ }));
        fireEvent.click(screen.getByRole("button", { name: "Test…" }));
        fireEvent.change(await screen.findByLabelText("who"), { target: { value: "ann" } });
        fireEvent.click(screen.getByRole("button", { name: "Run" }));
        await waitFor(() => expect(runTest).toHaveBeenCalled());
        fireEvent.click(screen.getByRole("button", { name: "Test…" }));
        expect(((await screen.findByLabelText("who")) as HTMLInputElement).value).toBe("ann");
    });

    it("expands the strip only for live-started runs, not loaded history", () => {
        const run = (id: string, status: c.TestRunStatus): c.CompositionTestRun => ({ id, itemId: 11,
            itemType: "tool", itemName: "my-tool", author: "user", status, startedAt: "2026-10-13T10:00:00Z" });
        const { rerender } = renderPage();
        testRuns = [run("old", "passed")];
        rerender(page());
        expect(screen.getByRole("button", { name: "Expand test output" })).toBeTruthy();
        testRuns = [run("new", "running"), run("old", "passed")];
        rerender(page());
        expect(screen.getByRole("button", { name: "Collapse test output" })).toBeTruthy();
    });
});
