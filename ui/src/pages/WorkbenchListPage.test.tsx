import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { WorkbenchListPage } from "./WorkbenchListPage";
import * as compositions from "../config/compositions";

const modalProps: Record<string, unknown>[] = [];
vi.mock("@patternfly/react-core", async (orig) => {
    const actual: Record<string, unknown> = await orig();
    const React = await import("react");
    const RealModal = actual.Modal as React.ComponentType<Record<string, unknown>>;
    // jsdom has no layout, so focus-trap rejects the selector target; record the prop and strip it.
    return { ...actual, Modal: (p: Record<string, unknown>) => {
        modalProps.push(p);
        const { elementToFocus: _ignored, ...rest } = p;
        return React.createElement(RealModal, rest);
    } };
});

describe("WorkbenchListPage", () => {
    it("opens a composition by clicking anywhere on its row, but not via the delete button", async () => {
        vi.spyOn(compositions, "listCompositions").mockResolvedValue([
            { id: 1, name: "Triage", goal: "triage issues", turnState: "idle", applyMode: "live",
                createdAt: "", updatedAt: "" },
        ]);
        render(<MemoryRouter initialEntries={["/workbench"]}><Routes>
            <Route path="/workbench" element={<WorkbenchListPage />} />
            <Route path="/workbench/:compositionId" element={<div>opened</div>} />
        </Routes></MemoryRouter>);
        fireEvent.click(await screen.findByRole("button", { name: "Delete Triage" }));
        expect(screen.queryByText("opened")).toBeNull();
        fireEvent.click(screen.getByText("triage issues"));
        expect(await screen.findByText("opened")).toBeTruthy();
    });

    it("lists compositions and creates one with a blank workflow", async () => {
        vi.spyOn(compositions, "listCompositions").mockResolvedValue([
            { id: 1, name: "Triage", goal: "triage issues", turnState: "idle", applyMode: "live",
                createdAt: "2026-10-12T00:00:00Z", updatedAt: "2026-10-12T00:00:00Z" },
        ]);
        const create = vi.spyOn(compositions, "createComposition").mockResolvedValue({ id: 2, name: "New", turnState: "idle",
            applyMode: "live", createdAt: "", updatedAt: "" });
        const addItem = vi.spyOn(compositions, "addItem").mockResolvedValue({} as never);
        render(<MemoryRouter initialEntries={["/workbench"]}><Routes>
            <Route path="/workbench" element={<WorkbenchListPage />} />
            <Route path="/workbench/:compositionId" element={<div>opened</div>} />
        </Routes></MemoryRouter>);
        expect(await screen.findByText("Triage")).toBeTruthy();
        fireEvent.click(screen.getByRole("button", { name: /new composition/i }));
        fireEvent.change(screen.getByLabelText(/name/i), { target: { value: "New" } });
        fireEvent.click(screen.getByRole("button", { name: /^create$/i }));
        await waitFor(() => expect(screen.getByText("opened")).toBeTruthy());
        expect(create).toHaveBeenCalledWith(expect.objectContaining({ name: "New" }));
        expect(addItem).toHaveBeenCalledWith(2, { type: "workflow", name: "New" });
    });

    it("focuses the name field and submits on Enter", async () => {
        vi.spyOn(compositions, "listCompositions").mockResolvedValue([]);
        const create = vi.spyOn(compositions, "createComposition").mockResolvedValue({ id: 4, name: "New",
            turnState: "idle", applyMode: "live", createdAt: "", updatedAt: "" });
        vi.spyOn(compositions, "addItem").mockResolvedValue({} as never);
        render(<MemoryRouter initialEntries={["/workbench"]}><Routes>
            <Route path="/workbench" element={<WorkbenchListPage />} />
            <Route path="/workbench/:compositionId" element={<div>opened</div>} />
        </Routes></MemoryRouter>);
        fireEvent.click(await screen.findByRole("button", { name: /new composition/i }));
        const input: HTMLElement = screen.getByLabelText(/name/i);
        expect(input.id).toBe("composition-name");
        expect(modalProps.some((p) => p.isOpen && p.elementToFocus === "#composition-name")).toBe(true);
        fireEvent.change(input, { target: { value: "New" } });
        fireEvent.submit(input.closest("form") as HTMLFormElement);
        fireEvent.submit(input.closest("form") as HTMLFormElement);
        await waitFor(() => expect(screen.getByText("opened")).toBeTruthy());
        expect(create).toHaveBeenCalledTimes(1);
    });

    it("creates only once when Create is clicked twice", async () => {
        vi.spyOn(compositions, "listCompositions").mockResolvedValue([]);
        let release: (v: never) => void = () => {};
        const create = vi.spyOn(compositions, "createComposition")
            .mockReturnValue(new Promise((r) => { release = r; }));
        vi.spyOn(compositions, "addItem").mockResolvedValue({} as never);
        render(<MemoryRouter initialEntries={["/workbench"]}><Routes>
            <Route path="/workbench" element={<WorkbenchListPage />} />
            <Route path="/workbench/:compositionId" element={<div>opened</div>} />
        </Routes></MemoryRouter>);
        fireEvent.click(await screen.findByRole("button", { name: /new composition/i }));
        fireEvent.change(screen.getByLabelText(/name/i), { target: { value: "New" } });
        const button = screen.getByRole("button", { name: /^create$/i });
        fireEvent.click(button);
        fireEvent.click(button);
        expect(create).toHaveBeenCalledTimes(1);
        release({ id: 3, name: "New", turnState: "idle", applyMode: "live", createdAt: "", updatedAt: "" } as never);
        await waitFor(() => expect(screen.getByText("opened")).toBeTruthy());
        expect(create).toHaveBeenCalledTimes(1);
    });
});
