import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ToolsPage } from "./ToolsPage";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

vi.mock("../config/api", () => ({
    fetchTools: vi.fn(),
}));

beforeEach(() => {
    vi.mocked(api.fetchTools).mockReset().mockResolvedValue({
        items: [], totalCount: 0, page: 1, limit: 20,
    } as never);
});

describe("ToolsPage", () => {
    it("loads filters and page from the URL", async () => {
        renderWithRouter(<ToolsPage />, "/?name=git&labels=a&labels=b&page=2");
        await waitFor(() => expect(api.fetchTools).toHaveBeenCalledWith(2, 20, "git", "a,b"));
        expect(screen.getByText("Filters")).toBeInTheDocument();
    });
});
