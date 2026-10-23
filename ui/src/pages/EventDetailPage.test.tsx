import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { EventDetailPage } from "./EventDetailPage";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

const EVENT_ID = "6b3f0d2e-1c1a-4c55-9f00-000000000001";

vi.mock("../config/api", () => ({
    fetchStreamEvent: vi.fn(),
    fetchEventProcessing: vi.fn().mockResolvedValue({ items: [] }),
    retryEventProcessing: vi.fn(),
    fetchWorkflowRuns: vi.fn(),
    fetchWorkflowDefinitions: vi.fn().mockResolvedValue({ items: [], totalCount: 0, page: 1, limit: 1000 }),
    fetchStreamEventWorkflowSuggestions: vi.fn().mockResolvedValue([]),
    triggerStreamEventWorkflow: vi.fn(),
}));
vi.mock("../config/sse", () => ({
    sseClient: { subscribe: vi.fn(() => () => {}) },
}));
vi.mock("../components/LineagePanel", () => ({
    LineagePanel: ({ expandable }: { expandable?: boolean }) =>
        <div data-testid="lineage-panel">{expandable === false ? "inline" : "expandable"}</div>,
}));
vi.mock("@patternfly/react-code-editor", () => ({
    CodeEditor: () => null,
    Language: { json: "json" },
}));

beforeEach(() => {
    vi.mocked(api.fetchStreamEvent).mockResolvedValue({
        id: EVENT_ID, type: "issue.opened", source: "github", eventSourceId: 1,
        timestamp: "2026-10-01T00:00:00Z",
    } as never);
    vi.mocked(api.fetchWorkflowRuns).mockReset().mockResolvedValue({
        items: [
            { runId: 11, title: "Manual run", subjectId: 7, definitionId: 2, definitionName: "Triage",
                definitionVersion: 1, status: "running", startedOn: "2026-10-01T00:00:00Z",
                labels: { trigger: "manual" } },
            { runId: 12, title: "Routed run", subjectId: 7, definitionId: 2, definitionName: "Triage",
                definitionVersion: 1, status: "completed", startedOn: "2026-10-01T00:00:00Z" },
        ], totalCount: 2, page: 1, limit: 100,
    } as never);
});

function renderPage() {
    renderWithRouter(
        <Routes><Route path="/events/stream/:eventId" element={<EventDetailPage />} /></Routes>,
        `/events/stream/${EVENT_ID}`);
}

describe("EventDetailPage", () => {
    it("warns about schema validation errors", async () => {
        vi.mocked(api.fetchStreamEvent).mockResolvedValue({
            id: EVENT_ID, type: "issue.opened", source: "github", eventSourceId: 1,
            timestamp: "2026-10-01T00:00:00Z",
            validationErrors: ["$.payload.issue.state: must be one of [open, closed]", "$.timestamp: bad format"],
        } as never);
        renderPage();
        expect(await screen.findByText("This event does not match the issue.opened schema")).toBeInTheDocument();
        expect(screen.getByText("$.payload.issue.state: must be one of [open, closed]")).toBeInTheDocument();
        expect(screen.getByText("$.timestamp: bad format")).toBeInTheDocument();
    });

    it("shows no validation warning when there are no errors", async () => {
        vi.mocked(api.fetchStreamEvent).mockResolvedValue({
            id: EVENT_ID, type: "issue.opened", source: "github", eventSourceId: 1,
            timestamp: "2026-10-01T00:00:00Z", validationErrors: [],
        } as never);
        renderPage();
        await screen.findByRole("button", { name: "Run workflow…" });
        expect(screen.queryByText(/does not match the .* schema/)).toBeNull();
    });

    it("shows a Run workflow… button that opens the modal", async () => {
        renderPage();
        await userEvent.click(await screen.findByRole("button", { name: "Run workflow…" }));
        expect(await screen.findByText(/with the event in context.event/)).toBeInTheDocument();
    });

    it("shows lineage only on its own Lineage tab, open without the expandable header", async () => {
        renderPage();
        const lineageTab = await screen.findByRole("tab", { name: "Lineage" });
        expect(screen.queryByTestId("lineage-panel")).toBeNull();
        await userEvent.click(lineageTab);
        expect(await screen.findByTestId("lineage-panel")).toHaveTextContent("inline");
    });

    it("lists the runs started from the event on the Runs tab with the manual label", async () => {
        renderPage();
        await userEvent.click(await screen.findByRole("tab", { name: "Runs" }));
        expect(await screen.findByRole("link", { name: "Manual run" })).toHaveAttribute("href", "/runs/11");
        expect(screen.getByRole("link", { name: "Routed run" })).toBeInTheDocument();
        expect(screen.getAllByText("manual")).toHaveLength(1);
        expect(api.fetchWorkflowRuns).toHaveBeenCalledWith(1, 100, { triggerEventId: EVENT_ID });
    });

    it("shows an empty state when no runs were started from the event", async () => {
        vi.mocked(api.fetchWorkflowRuns).mockResolvedValue({ items: [], totalCount: 0, page: 1, limit: 100 } as never);
        renderPage();
        await userEvent.click(await screen.findByRole("tab", { name: "Runs" }));
        await waitFor(() => expect(screen.getByText("No runs were started from this event.")).toBeInTheDocument());
    });
});
