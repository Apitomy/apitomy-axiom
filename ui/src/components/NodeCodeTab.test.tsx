import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { NodeCodeTab } from "./NodeCodeTab";

vi.mock("@patternfly/react-code-editor", () => ({
    CodeEditor: ({ code }: { code: string }) => <pre data-testid="code-editor">{code}</pre>,
    Language: { json: "json", javascript: "javascript" },
}));

describe("NodeCodeTab", () => {
    it("renders the code and the run() help text", () => {
        render(<NodeCodeTab value={"export async function run() { return {}; }"} onChange={() => {}} />);
        expect(screen.getByTestId("code-editor").textContent).toContain("export async function run()");
        expect(screen.getByText(/run\(inputs, ctx\)/)).toBeTruthy();
        expect(screen.getByText(/Only Node.js built-in modules may be imported/)).toBeTruthy();
    });
});
