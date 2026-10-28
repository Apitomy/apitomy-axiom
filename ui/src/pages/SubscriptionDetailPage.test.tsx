import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../config/api";
import { clearEventSchemaCache } from "../hooks/useEventSchemas";
import { renderWithRouter } from "../test/renderWithRouter";
import { SubscriptionDetailPage, completionTreeForExpression, getElCompletionTree, setElCompletionFields }
    from "./SubscriptionDetailPage";

vi.mock("../config/api", () => ({
    fetchSubscription: vi.fn(),
    fetchWorkflowDefinitions: vi.fn().mockResolvedValue({ items: [], totalCount: 0, page: 1, limit: 100 }),
    previewSubscriptionFilter: vi.fn().mockResolvedValue({ results: [], totalCount: 0, totalMatched: 0 }),
    updateSubscription: vi.fn(),
    fetchEventSchemas: vi.fn(),
    fetchEventSchema: vi.fn(),
}));
vi.mock("../components/EditableCodeEditor", () => ({
    EditableCodeEditor: ({ code }: { code: string }) =>
        <textarea aria-label="Filter expression editor" value={code} readOnly />,
}));
vi.mock("../components/StreamEventDetailModal", () => ({ StreamEventDetailModal: () => null }));
vi.mock("../hooks/useTheme", () => ({ useEffectiveTheme: () => "light" }));

beforeEach(() => {
    clearEventSchemaCache();
    setElCompletionFields([]);
    vi.mocked(api.fetchSubscription).mockResolvedValue({
        id: 1, name: "Issues", enabled: true, filterExpression: "event.type == 'issue.created' &&",
    } as never);
    vi.mocked(api.fetchEventSchemas).mockResolvedValue([{ type: "issue.closed" }, { type: "issue.created" }]);
    vi.mocked(api.fetchEventSchema).mockImplementation(async (t: string) => ({
        title: t,
        type: "object",
        properties: {
            payload: {
                type: "object",
                properties: { issue: { type: "object", properties: { state: { type: "string" } } } },
            },
        },
    }));
});

function renderPage() {
    renderWithRouter(
        <Routes><Route path="/subscriptions/:subscriptionId" element={<SubscriptionDetailPage />} /></Routes>,
        "/subscriptions/1");
}

describe("SubscriptionDetailPage filter tab", () => {
    it("shows the filter's event type and inserts a clicked path into the expression", async () => {
        renderPage();
        await userEvent.click(await screen.findByRole("tab", { name: "Filter" }));
        expect(await screen.findByRole("combobox", { name: "Event type" })).toHaveValue("issue.created");
        await userEvent.click(await screen.findByRole("button", { name: "Insert event.payload.issue.state" }));
        await waitFor(() => expect(screen.getByLabelText("Filter expression editor"))
            .toHaveValue("event.type == 'issue.created' && event.payload.issue.state"));
        expect(screen.getByRole("button", { name: "Save Changes" })).toBeEnabled();
    });

    it("keeps operators and examples in the help modal but no hard-coded field table", async () => {
        renderPage();
        await userEvent.click(await screen.findByRole("tab", { name: "Filter" }));
        await userEvent.click(screen.getByRole("button", { name: "Filter expression help" }));
        await waitFor(() => expect(screen.getByRole("grid", { name: "Examples" })).toBeInTheDocument());
        expect(screen.getByRole("grid", { name: "Operators" })).toBeInTheDocument();
        expect(screen.queryByRole("grid", { name: "Available fields" })).toBeNull();
        expect(screen.getByText("Only approved PR reviews")).toBeInTheDocument();
        expect(screen.getByText("Event fields", { selector: "strong" })).toBeInTheDocument();
    });
});

describe("EL completion tree", () => {
    it("offers envelope fields such as event.type before any schema data", () => {
        const event = getElCompletionTree().event;
        expect(typeof event).toBe("object");
        expect(event).toHaveProperty("type");
        expect(event).toHaveProperty("payload");
        expect(event).toHaveProperty("sourceData");
    });

    it("merges schema-derived fields into the envelope fields", () => {
        setElCompletionFields([
            { path: "event.payload.issue.state", type: "string", description: "State", examples: [] },
        ]);
        const event = getElCompletionTree().event as Record<string, unknown>;
        expect(event).toHaveProperty("type");
        expect(event.payload).toEqual({ issue: { state: "State" } });
    });

    it("uses the event type named in the expression, not the panel's previous type", async () => {
        setElCompletionFields([
            { path: "event.payload.pullRequest.state", type: "string", description: "PR state", examples: [] },
        ]);
        const tree = await completionTreeForExpression("event.type == 'issue.closed' && event.payload.");
        const event = tree?.event as Record<string, unknown>;
        expect(event.payload).toEqual({ issue: { state: "string" } });
        expect(api.fetchEventSchema).toHaveBeenCalledWith("issue.closed");
    });

    it("falls back to the panel's type when the expression names no known type", async () => {
        expect(await completionTreeForExpression("event.type == 'nope' && event.")).toBeNull();
        expect(await completionTreeForExpression("event.source == 'github'")).toBeNull();
    });

    it("still offers envelope fields when the schema request fails", async () => {
        vi.mocked(api.fetchEventSchemas).mockRejectedValue(new Error("boom"));
        renderPage();
        await userEvent.click(await screen.findByRole("tab", { name: "Filter" }));
        await waitFor(() => expect(api.fetchEventSchemas).toHaveBeenCalled());
        expect(getElCompletionTree().event).toHaveProperty("type");
    });
});
