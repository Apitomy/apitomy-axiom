import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { ActionTypeDetailModal } from "./ActionTypeDetailModal";

vi.mock("@patternfly/react-code-editor", () => ({
    CodeEditor: ({ code, language }: { code: string; language: string }) =>
        <pre data-testid="code-editor" data-language={language}>{code}</pre>,
    Language: { json: "json", javascript: "javascript", markdown: "markdown", shell: "shell" },
}));

describe("ActionTypeDetailModal", () => {
    it("shows a Code tab with the codeTemplate for node mode", () => {
        const code = "export async function run(inputs, ctx) { return {}; }";
        render(<ActionTypeDetailModal isOpen onClose={() => {}} name="n"
            content={{ executionMode: "node", codeTemplate: code }} />);
        fireEvent.click(screen.getByRole("tab", { name: "Code" }));
        const editor = screen.getByTestId("code-editor");
        expect(editor.textContent).toBe(code);
        expect(editor.getAttribute("data-language")).toBe("javascript");
    });
});
