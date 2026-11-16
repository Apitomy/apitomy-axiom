import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TasksPage } from "./TasksPage";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

vi.mock("../config/api", () => ({
    fetchAllTasks: vi.fn(),
    cancelTask: vi.fn(),
}));
vi.mock("../components/LineageModal", () => ({
    LineageModal: (props: { id: string | null }) =>
        props.id ? <div data-testid="lineage-modal">{props.id}</div> : null,
}));
vi.mock("../components/ExecutionLogModal", () => ({ ExecutionLogModal: () => null }));

beforeEach(() => {
    vi.mocked(api.fetchAllTasks).mockReset().mockResolvedValue({
        items: [], totalCount: 0, page: 1, limit: 20,
    } as never);
});

describe("TasksPage URL state", () => {
    it("loads filters and page from the URL and keeps taskId", async () => {
        renderWithRouter(<TasksPage />, "/?status=failed&page=2&taskId=7");
        await waitFor(() => expect(api.fetchAllTasks).toHaveBeenCalledWith(2, 20, undefined, "failed"));
        expect(screen.getByTestId("lineage-modal")).toHaveTextContent("7");
    });
});
