import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SubscriptionsPage } from "./SubscriptionsPage";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

vi.mock("../config/api", () => ({
    fetchSubscriptions: vi.fn(),
}));

beforeEach(() => {
    vi.mocked(api.fetchSubscriptions).mockReset().mockResolvedValue({
        items: [], totalCount: 0, page: 1, limit: 20,
    } as never);
});

describe("SubscriptionsPage", () => {
    it("loads filters and page from the URL", async () => {
        renderWithRouter(<SubscriptionsPage />, "/?name=sub&page=2");
        await waitFor(() => expect(api.fetchSubscriptions).toHaveBeenCalledWith(2, 20, "sub"));
        expect(screen.getByText("Filters")).toBeInTheDocument();
    });
});
