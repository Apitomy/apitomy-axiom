import type { ReactElement } from "react";
import { render, type RenderResult } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

/**
 * Renders a component inside a MemoryRouter for tests.
 *
 * @param ui the element to render
 * @param path the initial route (defaults to "/")
 * @returns the Testing Library render result
 */
export function renderWithRouter(ui: ReactElement, path: string = "/"): RenderResult {
    return render(<MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>);
}
