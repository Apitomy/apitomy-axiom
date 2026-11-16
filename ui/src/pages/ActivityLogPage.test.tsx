import { waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { ActivityLogPage } from "./ActivityLogPage";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

vi.mock("../config/api", () => ({
    fetchActivityLog: vi.fn(),
}));

beforeEach(() => {
    vi.mocked(api.fetchActivityLog).mockReset().mockResolvedValue({
        items: [], totalCount: 0, page: 1, limit: 20,
    } as never);
});

describe("ActivityLogPage", () => {
    it("loads filters and page from the URL, including the traceId deep link", async () => {
        renderWithRouter(<ActivityLogPage />, "/?traceId=t1&entryType=a&entryType=b&page=2");
        await waitFor(() => expect(api.fetchActivityLog).toHaveBeenCalledWith(
            2, 20, undefined, undefined, undefined, "a,b", undefined, "t1"));
    });
});
