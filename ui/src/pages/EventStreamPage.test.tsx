import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { EventStreamPage } from "./EventStreamPage";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

vi.mock("../config/api", () => ({ fetchStreamEvents: vi.fn() }));
vi.mock("../config/sse", () => ({ sseClient: { subscribe: vi.fn(() => () => {}) } }));

const event = {
    id: "e1", source: "github", eventSourceId: "gh", type: "issue.created", ref: "https://github.com/o/r/issues/1",
    timestamp: "2026-10-14T10:00:00Z", actor: { login: "jsenko" }, payload: {}, matchedSubscriptions: 0,
};

beforeEach(() => {
    vi.mocked(api.fetchStreamEvents).mockReset().mockResolvedValue({ items: [event], totalCount: 1 } as never);
});

function lastActorFilter(): unknown {
    const calls = vi.mocked(api.fetchStreamEvents).mock.calls;
    return calls[calls.length - 1][5];
}

describe("EventStreamPage", () => {
    it("filters by actor when the actor is clicked, as a chip", async () => {
        renderWithRouter(<EventStreamPage />);
        await userEvent.click(await screen.findByText("jsenko"));
        await waitFor(() => expect(lastActorFilter()).toBe("jsenko"));
        expect(screen.getByText("Actor")).toBeInTheDocument();
        await userEvent.click(screen.getByRole("button", { name: "Close label group Filters" }));
        await waitFor(() => expect(lastActorFilter()).toBeUndefined());
    });

    it("offers Actor as a filter type", async () => {
        renderWithRouter(<EventStreamPage />);
        await screen.findByText("jsenko");
        await userEvent.click(screen.getByRole("button", { name: "Event Type" }));
        expect(await screen.findByText("Actor")).toBeInTheDocument();
    });
});
