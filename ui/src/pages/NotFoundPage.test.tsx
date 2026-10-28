import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { NotFoundPage } from "./NotFoundPage";

describe("NotFoundPage", () => {
    it("names the unknown path", () => {
        render(
            <MemoryRouter initialEntries={["/tasks"]}>
                <Routes>
                    <Route path="/runs" element={<p>runs</p>} />
                    <Route path="*" element={<NotFoundPage />} />
                </Routes>
            </MemoryRouter>);
        expect(screen.getByText("404: Page not found")).toBeTruthy();
        expect(screen.getByText("/tasks")).toBeTruthy();
        expect(screen.getByRole("button", { name: "Go to Runs" })).toBeTruthy();
    });
});
