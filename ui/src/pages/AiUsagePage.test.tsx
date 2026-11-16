import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { AiUsagePage } from "./AiUsagePage";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

vi.mock("../config/api", () => ({
    fetchUsage: vi.fn(),
}));

beforeEach(() => {
    vi.mocked(api.fetchUsage).mockReset().mockResolvedValue({
        items: [], totalCount: 0, page: 1, limit: 20,
        totalCostUsd: 0, totalInputTokens: 0, totalOutputTokens: 0,
    } as never);
});

describe("AiUsagePage", () => {
    it("loads filters, dates and page from the URL", async () => {
        renderWithRouter(<AiUsagePage />, "/?model=opus&from=2026-11-01&to=2026-11-15&page=4");
        await waitFor(() => expect(api.fetchUsage).toHaveBeenCalledWith(
            4, 20, undefined, undefined, undefined, undefined, "2026-11-01", "2026-11-15",
            undefined, undefined, "opus", undefined));
    });

    it("This Month sets from, clears to, and resets the page in the URL", async () => {
        renderWithRouter(<AiUsagePage />, "/?to=2026-01-31&page=3");
        await userEvent.click(await screen.findByRole("button", { name: "This Month" }));
        const now: Date = new Date();
        const from: string = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-01`;
        await waitFor(() => expect(api.fetchUsage).toHaveBeenLastCalledWith(
            1, 20, undefined, undefined, undefined, undefined, from, undefined,
            undefined, undefined, undefined, undefined));
    });
});
