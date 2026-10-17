import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { MatchIndicator } from "./EventStreamPage";
import type { StreamEvent, StreamEventProcessingStates } from "../config/api";

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
