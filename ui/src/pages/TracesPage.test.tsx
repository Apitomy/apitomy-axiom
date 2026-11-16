import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TracesPage } from "./TracesPage";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

vi.mock("../config/api", () => ({
    fetchTraces: vi.fn(),
}));

beforeEach(() => {
    vi.mocked(api.fetchTraces).mockReset().mockResolvedValue({
        items: [], totalCount: 0, page: 1, limit: 20,
    } as never);
});

describe("TracesPage", () => {
    it("loads filters and page from the URL", async () => {
        renderWithRouter(<TracesPage />, "/?traceType=agent&status=failed&status=running&page=2&perPage=50");
        await waitFor(() => expect(api.fetchTraces).toHaveBeenCalledWith(2, 50, "agent", "failed,running"));
        expect(screen.getByText("Filters")).toBeInTheDocument();
    });
});
