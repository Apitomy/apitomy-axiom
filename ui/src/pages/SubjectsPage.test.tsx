import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SubjectsPage } from "./SubjectsPage";
import { renderWithRouter } from "../test/renderWithRouter";

vi.mock("../config/api", () => ({
    fetchSubjects: vi.fn().mockResolvedValue({
        items: [{
            id: 1,
            name: "Repo issue 42",
            type: "issue",
            ref: "42",
            createdOn: "2026-10-01T00:00:00Z",
            updatedOn: "2026-10-01T00:00:00Z",
            labels: [],
            active: true,
        }],
        totalCount: 1,
        page: 1,
        limit: 20,
    }),
    createSubject: vi.fn(),
    deleteSubject: vi.fn(),
}));

describe("SubjectsPage", () => {
    it("lists subjects with their active status", async () => {
        renderWithRouter(<SubjectsPage />, "/subjects");
        expect(await screen.findByText("Repo issue 42")).toBeInTheDocument();
        expect(screen.getByText("Active")).toBeInTheDocument();
    });
});
