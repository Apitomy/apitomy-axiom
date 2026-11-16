import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { WorkflowDefinitionsPage } from "./WorkflowDefinitionsPage";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

vi.mock("../config/api", () => ({
    fetchWorkflowDefinitions: vi.fn(),
}));

beforeEach(() => {
    vi.mocked(api.fetchWorkflowDefinitions).mockReset().mockResolvedValue({
        items: [], totalCount: 0, page: 1, limit: 20,
    } as never);
});

describe("WorkflowDefinitionsPage", () => {
    it("loads filters and page from the URL", async () => {
        renderWithRouter(<WorkflowDefinitionsPage />, "/?name=wf&page=2");
        await waitFor(() => expect(api.fetchWorkflowDefinitions).toHaveBeenCalledWith(2, 20, "wf"));
        expect(screen.getByText("Filters")).toBeInTheDocument();
    });
});
