import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { EventSourcesPage } from "./EventSourcesPage";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

vi.mock("../config/api", () => ({
    fetchEventSources: vi.fn(),
    fetchSecrets: vi.fn(() => Promise.resolve([])),
}));

beforeEach(() => {
    vi.mocked(api.fetchEventSources).mockReset().mockResolvedValue({
        items: [], totalCount: 0, page: 1, limit: 20,
    } as never);
});

describe("EventSourcesPage", () => {
    it("loads filters and page from the URL", async () => {
        renderWithRouter(<EventSourcesPage />, "/?name=gh&type=github&page=2");
        await waitFor(() => expect(api.fetchEventSources).toHaveBeenCalledWith(2, 20, "gh", "github"));
        expect(screen.getByText("Filters")).toBeInTheDocument();
    });
});
