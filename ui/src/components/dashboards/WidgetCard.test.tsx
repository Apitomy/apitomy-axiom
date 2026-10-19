import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { SseListener } from "../../config/sse";
import { registerWidget, type WidgetProps } from "./widget-registry";
import { WidgetCard } from "./WidgetCard";

const listeners: SseListener[] = [];
vi.mock("../../config/sse", () => ({
    sseClient: {
        subscribe: vi.fn((l: SseListener) => { listeners.push(l); return () => {}; }),
        onReconnect: vi.fn(() => () => {}),
    },
}));

function Probe({ refreshToken }: WidgetProps) {
    return <div>token {refreshToken}</div>;
}

registerWidget({
    type: "test-filtered", name: "Filtered Probe", description: "", category: "Runs",
    scope: "subject-labels", liveEvents: ["workflow-updated"], defaultSize: { w: 2, h: 2 }, component: Probe,
});
registerWidget({
    type: "test-global", name: "Global Probe", description: "", category: "System",
    scope: "global", defaultSize: { w: 2, h: 2 }, component: Probe,
});

function renderCard(type: string, labels: string[]) {
    render(<WidgetCard widgetType={type} config={{}} labels={labels} isEditing={false}
                       onConfigChange={vi.fn()} onRemove={vi.fn()} />);
}

describe("WidgetCard", () => {
    beforeEach(() => { listeners.length = 0; vi.useFakeTimers(); });
    afterEach(() => { vi.useRealTimers(); });

    it("shows the label scope only when the dashboard has labels", () => {
        renderCard("test-filtered", ["team:a"]);
        expect(screen.getByText("Filtered")).toBeInTheDocument();
    });

    it("marks global widgets", () => {
        renderCard("test-global", ["team:a"]);
        expect(screen.getByText("Global")).toBeInTheDocument();
    });

    it("hides the indicator without dashboard labels", () => {
        renderCard("test-global", []);
        expect(screen.queryByText("Global")).not.toBeInTheDocument();
    });

    it("increments refreshToken after a declared live event", () => {
        renderCard("test-filtered", []);
        expect(screen.getByText("token 0")).toBeInTheDocument();
        act(() => {
            listeners.forEach((l) => l({ type: "workflow-updated", data: {} }));
            vi.advanceTimersByTime(2000);
        });
        expect(screen.getByText("token 1")).toBeInTheDocument();
    });
});
