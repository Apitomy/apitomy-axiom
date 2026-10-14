import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import * as c from "../../config/compositions";
import * as prefs from "./workbenchPrefs";
import type { WorkbenchState } from "./useWorkbench";

vi.mock("@apitomy/flow-ui", () => ({}));
const setContent = vi.fn();
const runTest = vi.fn();
let testRuns: c.CompositionTestRun[] = [];
let testRunsLoaded: boolean = true;
vi.mock("./useWorkbench", () => ({
    useWorkbench: (): WorkbenchState => ({
        composition: { id: 1, name: "My comp", turnState: "idle", applyMode: "live", createdAt: "", updatedAt: "",
            session: { engine: "claude-code", engineLabel: "Claude Code" } },
        items: [{ id: 10, type: "workflow", name: "main-flow", baseItemId: 42, contentRevision: "r", lastAuthor: "user", changed: false } as never,
            { id: 11, type: "tool", name: "my-tool", contentRevision: "r", lastAuthor: "user", changed: false } as never],
        contents: { 10: {}, 11: { name: "my-tool", parameters: [{ name: "who", required: true }],
            scriptTemplate: "echo {{who}}" } }, revisions: {}, problems: {}, pending: null, lastCompletedTurnId: undefined, sessionError: null,
        loadContent: async () => ({}), refreshItems: async () => {}, refreshComposition: async () => {},
        setContent, removeItem: async () => {}, markPendingHandled: () => {}, retrySession: async () => {},
        testRuns, testRunsLoaded, runTest,
    }),
}));

let workflowTabProps: Record<string, any> = {};
const workflowFlush = vi.fn();
let chatPaneProps: Record<string, any> = {};
vi.mock("./ChatPane", async (orig) => {
    const real = await orig<typeof import("./ChatPane")>();
    return {
        ChatPane: (props: Record<string, any>) => {
            chatPaneProps = props;
            const RealChatPane = real.ChatPane as (p: Record<string, any>) => React.ReactNode;
            return <RealChatPane {...props} />;
        },
    };
});
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
    fetchWorkflowRuns: (...a: unknown[]) => fetchWorkflowRunsMock(...a),
}));
const fetchWorkflowRunsMock = vi.fn();
vi.mock("./RunDetailDialog", () => ({
    RunDetailDialog: (p: { runId: number }) => <div data-testid="run-dialog">run {p.runId}</div>,
}));
vi.mock("./PublishDialog", () => ({ PublishDialog: () => <div data-testid="publish-dialog" /> }));

import { act } from "@testing-library/react";
import { WorkbenchPage } from "./WorkbenchPage";
import { renderWorkbenchToolGroup } from "./WorkbenchToolCards";

function Probe() {
    const loc = useLocation();
    return <div data-testid="loc">{loc.search}</div>;
}

function GoTo2() {
    const navigate = useNavigate();
    return <button type="button" onClick={() => navigate("/workbench/2")}>go-2</button>;
}

function page() {
    return (
        <MemoryRouter initialEntries={["/workbench/1"]}>
            <Routes>
                <Route path="/workbench/:compositionId" element={<><WorkbenchPage /><Probe /><GoTo2 /></>} />
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
    testRunsLoaded = true;
    runTest.mockResolvedValue({});
    itemFlush.mockReset();
    itemFlush.mockResolvedValue(undefined);
    localStorage.clear();
    fetchWorkflowRunsMock.mockReset();
    fetchWorkflowRunsMock.mockResolvedValue({ items: [], totalCount: 0 });
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
        await waitFor(() => expect(runTest).toHaveBeenCalledWith(11, { who: "ann" }, undefined, expect.any(String)));
        expect(itemFlush).toHaveBeenCalled();
    });

    it("keeps the dialog open when the server records the run under another id", async () => {
        runTest.mockResolvedValueOnce({ id: "server-id", itemId: 11, itemType: "tool", itemName: "my-tool", author: "user",
            status: "passed", startedAt: "2026-10-14T10:00:00Z", exitCode: 0 });
        const { rerender } = renderPage();
        fireEvent.click(screen.getByRole("button", { name: /^my-tool/ }));
        fireEvent.click(screen.getByRole("button", { name: "Test…" }));
        fireEvent.change(await screen.findByLabelText("who"), { target: { value: "ann" } });
        fireEvent.click(screen.getByRole("button", { name: "Run" }));
        await waitFor(() => expect(runTest).toHaveBeenCalled());
        testRuns = [{ id: "server-id", itemId: 11, itemType: "tool", itemName: "my-tool", author: "user",
            status: "passed", startedAt: "2026-10-14T10:00:00Z", exitCode: 0 }];
        rerender(page());
        expect(await screen.findByText("Test run: my-tool")).toBeTruthy();
        expect(screen.getByText("passed")).toBeTruthy();
    });

    it("opens the run's details at Run and shows it live as it finishes", async () => {
        const { rerender } = renderPage();
        fireEvent.click(screen.getByRole("button", { name: /^my-tool/ }));
        fireEvent.click(screen.getByRole("button", { name: "Test…" }));
        fireEvent.change(await screen.findByLabelText("who"), { target: { value: "ann" } });
        fireEvent.click(screen.getByRole("button", { name: "Run" }));
        await waitFor(() => expect(runTest).toHaveBeenCalled());
        const runId: string = runTest.mock.calls[runTest.mock.calls.length - 1][3];
        testRuns = [{ id: runId, itemId: 11, itemType: "tool", itemName: "my-tool", author: "user", status: "running",
            startedAt: "2026-10-14T10:00:00Z", inputs: { who: "ann" } }];
        rerender(page());
        expect(await screen.findByText("Test run: my-tool")).toBeTruthy();
        expect(screen.getByText("running")).toBeTruthy();
        testRuns = [{ ...testRuns[0], status: "passed", exitCode: 0, stdout: "hi ann\n" }];
        rerender(page());
        expect(screen.getByText("passed")).toBeTruthy();
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
        fireEvent.click(screen.getByRole("button", { name: "Expand bottom panel" }));
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
        expect(screen.getByRole("button", { name: "Expand bottom panel" })).toBeTruthy();
        testRuns = [run("new", "running"), run("old", "passed")];
        rerender(page());
        expect(screen.getByRole("button", { name: "Collapse bottom panel" })).toBeTruthy();
    });

    it("forgets seen runs when the composition changes", () => {
        const run = (id: string, status: c.TestRunStatus): c.CompositionTestRun => ({ id, itemId: 11,
            itemType: "tool", itemName: "my-tool", author: "user", status, startedAt: "2026-10-13T10:00:00Z" });
        const { rerender } = renderPage();
        testRuns = [run("x", "running")];
        rerender(page());
        fireEvent.click(screen.getByRole("button", { name: "Collapse bottom panel" }));
        expect(screen.getByRole("button", { name: "Expand bottom panel" })).toBeTruthy();
        fireEvent.click(screen.getByRole("button", { name: "go-2" }));
        expect(screen.getByRole("button", { name: "Collapse bottom panel" })).toBeTruthy();
    });

    it("runs an AI suggestion pre-filled from its inputs", async () => {
        testRuns = [{ id: "s", itemId: 11, itemType: "tool", itemName: "my-tool", author: "ai", status: "suggested",
            startedAt: "2026-10-13T10:00:00Z", inputs: { who: "bob" } }];
        renderPage();
        fireEvent.click(screen.getByRole("button", { name: "Expand bottom panel" }));
        fireEvent.click(screen.getByRole("button", { name: "Run…" }));
        expect(((await screen.findByLabelText("who")) as HTMLInputElement).value).toBe("bob");
        fireEvent.click(screen.getByRole("button", { name: "Run" }));
        await waitFor(() => expect(runTest).toHaveBeenCalledWith(11, { who: "bob" }, undefined, expect.any(String)));
    });

    it("shows an error when the suggested item no longer exists", async () => {
        testRuns = [{ id: "s", itemId: 99, itemType: "tool", itemName: "gone", author: "ai", status: "suggested",
            startedAt: "2026-10-13T10:00:00Z", inputs: {} }];
        renderPage();
        fireEvent.click(screen.getByRole("button", { name: "Expand bottom panel" }));
        fireEvent.click(screen.getByRole("button", { name: "Run…" }));
        expect(await screen.findByText("The suggested item no longer exists.")).toBeTruthy();
    });

    it("expands the strip for a new suggestion but not for suggestions in the loaded history", () => {
        const suggestion = (id: string): c.CompositionTestRun => ({ id, itemId: 11, itemType: "tool",
            itemName: "my-tool", author: "ai", status: "suggested", startedAt: "2026-10-13T10:00:00Z", inputs: {} });
        testRunsLoaded = false;
        const { rerender } = renderPage();
        testRuns = [suggestion("old")];
        testRunsLoaded = true;
        rerender(page());
        expect(screen.getByRole("button", { name: "Expand bottom panel" })).toBeTruthy();
        testRuns = [suggestion("new"), suggestion("old")];
        rerender(page());
        expect(screen.getByRole("button", { name: "Collapse bottom panel" })).toBeTruthy();
    });

    it("expands the strip for a new suggestion", () => {
        const { rerender } = renderPage();
        expect(screen.getByRole("button", { name: "Expand bottom panel" })).toBeTruthy();
        testRuns = [{ id: "s", itemId: 11, itemType: "tool", itemName: "my-tool", author: "ai", status: "suggested",
            startedAt: "2026-10-13T10:00:00Z", inputs: {} }];
        rerender(page());
        expect(screen.getByRole("button", { name: "Collapse bottom panel" })).toBeTruthy();
    });

    it("opens the tool's tab from an Updated tool card in the chat", () => {
        renderPage();
        const out = renderWorkbenchToolGroup([{ message: { id: "m", type: "tool_use",
            toolName: "mcp__workbench__upsert_item", toolInput: { type: "tool", name: "my-tool" },
            toolResult: "VALID: tool/my-tool (updated, revision r2)" }, genericBlock: null }], chatPaneProps.toolActions);
        render(<div data-testid="cards">{out}</div>);
        expect(screen.getByTestId("cards").textContent).toContain("Updated tool my-tool");
        fireEvent.click(screen.getByRole("button", { name: /Updated tool my-tool/ }));
        fireEvent.click(screen.getByRole("button", { name: "Open" }));
        expect(screen.getByTestId("item-tab")).toBeTruthy();
    });

    it("activates the workflow tab from a workflow card", () => {
        renderPage();
        act(() => chatPaneProps.toolActions.openItem("tool", "my-tool"));
        expect(screen.getByTestId("item-tab")).toBeTruthy();
        act(() => chatPaneProps.toolActions.openWorkflow("n1"));
        expect(screen.getByRole("tab", { name: "main-flow" }).getAttribute("aria-selected")).toBe("true");
    });

    it("lists the runs of the published workflow on the Runs tab", async () => {
        fetchWorkflowRunsMock.mockResolvedValue({ items: [{ runId: 5, status: "completed", title: "First",
            definitionVersion: 1, startedOn: "2026-10-14T10:00:00Z" }], totalCount: 1 });
        const spy = vi.spyOn(prefs, "savePrefs");
        renderPage();
        await waitFor(() => expect(fetchWorkflowRunsMock).toHaveBeenCalledWith(1, 50, { definitionId: 42 }));
        fireEvent.click(screen.getByRole("button", { name: "Expand bottom panel" }));
        fireEvent.click(screen.getByRole("tab", { name: /Runs/ }));
        expect(spy).toHaveBeenCalledWith(expect.objectContaining({ bottomPanelTab: "runs" }));
        expect(await screen.findByText("First")).toBeTruthy();
        expect(screen.getByRole("button", { name: "Refresh" })).toBeTruthy();
    });

    it("opens the run dialog when a run row is clicked", async () => {
        fetchWorkflowRunsMock.mockResolvedValue({ items: [{ runId: 5, status: "completed", title: "First",
            definitionVersion: 1, startedOn: "2026-10-14T10:00:00Z" }], totalCount: 1 });
        renderPage();
        await waitFor(() => expect(fetchWorkflowRunsMock).toHaveBeenCalled());
        fireEvent.click(screen.getByRole("button", { name: "Expand bottom panel" }));
        fireEvent.click(screen.getByRole("tab", { name: /Runs/ }));
        expect(screen.queryByTestId("run-dialog")).toBeNull();
        fireEvent.click(await screen.findByRole("button", { name: "Show run #5" }));
        expect(screen.getByTestId("run-dialog").textContent).toBe("run 5");
    });

    it("expands the panel on the Runs tab for a new failed run", async () => {
        const r = (runId: number, status: string) => ({ runId, status, title: `R${runId}`, definitionVersion: 1,
            startedOn: "2026-10-14T10:00:00Z" });
        fetchWorkflowRunsMock.mockResolvedValue({ items: [r(1, "failed")], totalCount: 1 });
        renderPage();
        await waitFor(() => expect(fetchWorkflowRunsMock).toHaveBeenCalled());
        await act(async () => {});
        expect(screen.getByRole("button", { name: "Expand bottom panel" })).toBeTruthy();
        fetchWorkflowRunsMock.mockResolvedValue({ items: [r(2, "failed"), r(1, "failed")], totalCount: 2 });
        // Refresh (Runs tab action) loads the new run while the panel stays collapsed; then go back to Tests.
        fireEvent.click(screen.getByRole("tab", { name: /Runs/ }));
        fireEvent.click(screen.getByRole("tab", { name: /Tests/ }));
        expect(screen.getByRole("button", { name: "Expand bottom panel" })).toBeTruthy();
        fireEvent.click(screen.getByRole("tab", { name: /Runs/ }));
        fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
        expect(await screen.findByRole("button", { name: "Collapse bottom panel" })).toBeTruthy();
        expect(screen.getByRole("tab", { name: /Runs/ }).getAttribute("aria-selected")).toBe("true");
    });
});
