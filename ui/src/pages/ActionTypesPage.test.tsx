import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ActionTypesPage } from "./ActionTypesPage";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

vi.mock("../config/api", () => ({
    fetchActionTypes: vi.fn(),
}));

beforeEach(() => {
    vi.mocked(api.fetchActionTypes).mockReset().mockResolvedValue({
        items: [], totalCount: 0, page: 1, limit: 20,
    } as never);
});

describe("ActionTypesPage", () => {
    it("loads filters and page from the URL", async () => {
        renderWithRouter(<ActionTypesPage />, "/?name=x&mode=agent&labels=a&labels=b&page=2");
        await waitFor(() => expect(api.fetchActionTypes).toHaveBeenCalledWith(2, 20, "x", "agent", "a,b"));
        expect(screen.getByText("Filters")).toBeInTheDocument();
    });
});
