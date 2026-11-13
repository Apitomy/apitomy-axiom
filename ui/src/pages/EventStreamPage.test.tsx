import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { EventStreamPage, MatchIndicator } from "./EventStreamPage";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";
import type { StreamEvent, StreamEventProcessingStates } from "../config/api";

vi.mock("../config/api", () => ({ fetchStreamEvents: vi.fn(), fetchEventSources: vi.fn() }));
vi.mock("../config/sse", () => ({ sseClient: { subscribe: vi.fn(() => () => {}) } }));

const event = {
    id: "e1", source: "github", eventSourceId: "gh", type: "issue.created", ref: "https://github.com/o/r/issues/1",
    timestamp: "2026-10-14T10:00:00Z", actor: { login: "jsenko" }, payload: {}, matchedSubscriptions: 0,
};

beforeEach(() => {
    vi.mocked(api.fetchStreamEvents).mockReset().mockResolvedValue({ items: [event], totalCount: 1 } as never);
    vi.mocked(api.fetchEventSources).mockReset().mockResolvedValue({
        items: [{ id: "gh", name: "GitHub Main" }, { id: "jira", name: "Corporate Jira" }], totalCount: 2,
    } as never);
});

function lastActorFilter(): unknown {
    const calls = vi.mocked(api.fetchStreamEvents).mock.calls;
    return calls[calls.length - 1][5];
}

function lastEventSourceFilter(): unknown {
    const calls = vi.mocked(api.fetchStreamEvents).mock.calls;
    return calls[calls.length - 1][3];
}

describe("EventStreamPage", () => {
    it("filters by actor when the actor is clicked, as a chip", async () => {
        renderWithRouter(<EventStreamPage />);
        await userEvent.click(await screen.findByText("jsenko"));
        await waitFor(() => expect(lastActorFilter()).toBe("jsenko"));
        const chips = document.querySelector(".pf-v6-c-label-group") as HTMLElement;
        expect(chips.textContent).toContain("Actor: jsenko");
        await userEvent.click(screen.getByRole("button", { name: "Close label group Filters" }));
        await waitFor(() => expect(lastActorFilter()).toBeUndefined());
    });

    it("offers Actor as a filter type", async () => {
        renderWithRouter(<EventStreamPage />);
        await screen.findByText("jsenko");
        await userEvent.click(screen.getByRole("button", { name: "Event Type" }));
        expect(await screen.findByRole("option", { name: "Actor" })).toBeInTheDocument();
    });

    it("filters by event source chosen from a select of all event sources", async () => {
        renderWithRouter(<EventStreamPage />);
        await screen.findByText("jsenko");
        await waitFor(() => expect(api.fetchEventSources).toHaveBeenCalled());
        await userEvent.click(screen.getByRole("button", { name: "Event Type" }));
        await userEvent.click(await screen.findByRole("option", { name: "Event Source" }));
        expect(screen.queryByLabelText("search input")).not.toBeInTheDocument();
        await userEvent.click(screen.getByRole("button", { name: "Select Event Source" }));
        await userEvent.click(await screen.findByRole("option", { name: "Corporate Jira" }));
        await waitFor(() => expect(lastEventSourceFilter()).toBe("jira"));
        const chips = document.querySelector(".pf-v6-c-label-group") as HTMLElement;
        expect(chips.textContent).toContain("Event Source: Corporate Jira");
    });

    it("labels the event source chip by name when the source label is clicked", async () => {
        renderWithRouter(<EventStreamPage />);
        await waitFor(() => expect(api.fetchEventSources).toHaveBeenCalled());
        await userEvent.click(await screen.findByText("github"));
        await waitFor(() => expect(lastEventSourceFilter()).toBe("gh"));
        const chips = document.querySelector(".pf-v6-c-label-group") as HTMLElement;
        expect(chips.textContent).toContain("Event Source: GitHub Main");
    });

    it("falls back to a text input when event sources cannot be loaded", async () => {
        vi.mocked(api.fetchEventSources).mockRejectedValue(new Error("boom"));
        vi.spyOn(console, "error").mockImplementation(() => {});
        renderWithRouter(<EventStreamPage />);
        await screen.findByText("jsenko");
        await userEvent.click(screen.getByRole("button", { name: "Event Type" }));
        await userEvent.click(await screen.findByRole("option", { name: "Event Source" }));
        expect(screen.getByLabelText("search input")).toBeInTheDocument();
    });

    it("flags failed matches in the match indicator", async () => {
        vi.mocked(api.fetchStreamEvents).mockResolvedValue({
            items: [{ ...event, matchedSubscriptions: 3,
                processingStates: { completed: 1, pending: 1, failed: 1, exhausted: 0, skipped: 2, filterError: 0 } }],
            totalCount: 1,
        } as never);
        renderWithRouter(<EventStreamPage />);
        expect(await screen.findByRole("img",
            { name: "Matched 3 subscriptions: 1 completed, 1 pending, 1 failed" })).toBeInTheDocument();
    });

    it("shows no match indicator when nothing matched", async () => {
        renderWithRouter(<EventStreamPage />);
        await screen.findByText("jsenko");
        expect(screen.queryByRole("img", { name: /^Matched/ })).not.toBeInTheDocument();
    });
});

function states(overrides: Partial<StreamEventProcessingStates>): StreamEventProcessingStates {
    return { pending: 0, completed: 0, failed: 0, exhausted: 0, skipped: 0, filterError: 0, ...overrides };
}

function streamEvent(matched: number, processingStates?: StreamEventProcessingStates): StreamEvent {
    return {
        id: "e1", source: "github", eventSourceId: "c1", type: "issue.created",
        timestamp: "2026-01-01T00:00:00Z", matchedSubscriptions: matched, processingStates,
    } as StreamEvent;
}

describe("MatchIndicator", () => {
    it("shows an in-progress indicator while a matched subscription is pending", () => {
        render(<MatchIndicator event={streamEvent(2, states({ completed: 1, pending: 1 }))} />);
        const img = screen.getByRole("img", { name: "Matched 2 subscriptions: 1 completed, 1 pending" });
        expect(img).toHaveAttribute("data-state", "in-progress");
    });

    it("shows success when every matched subscription completed", () => {
        render(<MatchIndicator event={streamEvent(1, states({ completed: 1, skipped: 3 }))} />);
        const img = screen.getByRole("img", { name: "Matched 1 subscription: 1 completed" });
        expect(img).toHaveAttribute("data-state", "success");
    });

    it("shows danger for an event whose only ledger entries are filter errors", () => {
        render(<MatchIndicator event={streamEvent(0, states({ filterError: 2 }))} />);
        const img = screen.getByRole("img", { name: "2 filter errors" });
        expect(img).toHaveAttribute("data-state", "danger");
    });

    it("shows danger and lists filter errors separately for completed plus filter error", () => {
        render(<MatchIndicator event={streamEvent(1, states({ completed: 1, filterError: 2 }))} />);
        const img = screen.getByRole("img", { name: "Matched 1 subscription: 1 completed; 2 filter errors" });
        expect(img).toHaveAttribute("data-state", "danger");
    });

    it("renders nothing without matches or filter errors", () => {
        const { container } = render(<MatchIndicator event={streamEvent(0, states({ skipped: 1 }))} />);
        expect(container).toBeEmptyDOMElement();
    });
});
