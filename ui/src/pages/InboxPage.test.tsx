import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as api from "../config/api";
import { InboxPage } from "./InboxPage";

vi.mock("../config/api", async (importOriginal) => ({
    ...(await importOriginal<typeof import("../config/api")>()),
    fetchInboxItems: vi.fn(),
    fetchInboxItem: vi.fn(),
}));

vi.mock("../config/sse", () => ({
    sseClient: { subscribe: () => () => {}, onReconnect: () => () => {} },
}));

function renderAt(path: string) {
    render(<MemoryRouter initialEntries={[path]}><InboxPage /></MemoryRouter>);
}

afterEach(() => vi.clearAllMocks());

describe("InboxPage deep link", () => {
    it("opens the task given by ?task=", async () => {
        vi.mocked(api.fetchInboxItems).mockResolvedValue({ items: [], totalCount: 0 } as
            unknown as api.SearchResults<api.InboxItem>);
        vi.mocked(api.fetchInboxItem).mockResolvedValue({
            id: 5, subjectId: 1, actionType: "review", status: "pending", createdOn: "",
            humanContext: { title: "Approve release" },
        } as api.InboxItem);
        renderAt("/inbox?task=5");
        expect(await screen.findByRole("dialog")).toHaveTextContent("Approve release");
        expect(api.fetchInboxItem).toHaveBeenCalledWith(5);
    });

    it("tolerates an unknown task id", async () => {
        vi.mocked(api.fetchInboxItems).mockResolvedValue({ items: [], totalCount: 0 } as
            unknown as api.SearchResults<api.InboxItem>);
        vi.mocked(api.fetchInboxItem).mockRejectedValue(new Error("404"));
        renderAt("/inbox?task=77");
        expect(await screen.findByText(/Task #77 was not found/)).toBeInTheDocument();
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("tolerates a malformed task id", async () => {
        vi.mocked(api.fetchInboxItems).mockResolvedValue({ items: [], totalCount: 0 } as
            unknown as api.SearchResults<api.InboxItem>);
        renderAt("/inbox?task=abc");
        expect(await screen.findByText(/"abc" was not found/)).toBeInTheDocument();
        expect(api.fetchInboxItem).not.toHaveBeenCalled();
    });
});
