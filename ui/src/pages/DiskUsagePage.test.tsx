import { waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { DiskUsagePage } from "./DiskUsagePage";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

vi.mock("../config/api", () => ({
    fetchDiskUsage: vi.fn(),
    formatBytes: (n: number) => `${n} B`,
}));

beforeEach(() => {
    vi.mocked(api.fetchDiskUsage).mockReset().mockResolvedValue({
        items: [], totalCount: 0, totalDiskUsageBytes: 0, subjectCount: 0,
    } as never);
});

describe("DiskUsagePage URL state", () => {
    it("loads filter, sort and page from the URL", async () => {
        renderWithRouter(<DiskUsagePage />, "/?name=x&sort=diskUsageBytes&dir=desc&page=2");
        await waitFor(() => expect(api.fetchDiskUsage).toHaveBeenCalledWith(2, 20, "x", "diskUsageBytes", "desc"));
    });
});
