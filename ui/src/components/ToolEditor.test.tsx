import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ToolEditor } from "./ToolEditor";

vi.mock("@patternfly/react-code-editor", () => ({
    CodeEditor: ({ code, isReadOnly }: { code: string; isReadOnly?: boolean }) =>
        <pre data-testid="code-editor" data-readonly={String(!!isReadOnly)}>{code}</pre>,
    Language: { shell: "shell", json: "json" },
}));
vi.mock("../config/api", async (orig) => ({
    ...(await orig<typeof import("../config/api")>()),
    validateTool: vi.fn().mockResolvedValue({ valid: true, errors: [], warnings: [] }),
}));

describe("ToolEditor", () => {
    it("is controlled", () => {
        const onChange = vi.fn();
        render(<ToolEditor value={{ name: "t", description: "old", parameters: [], scriptTemplate: "echo" }} onChange={onChange} />);
        fireEvent.change(screen.getByDisplayValue("old"), { target: { value: "new" } });
        expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ name: "t", description: "new" }));
    });

    it("hides the Test tab without a saved tool id", () => {
        const { rerender } = render(<ToolEditor value={{ name: "t", parameters: [] }} onChange={() => {}} />);
        expect(screen.queryByText("Test")).toBeNull();
        rerender(<ToolEditor value={{ name: "t", parameters: [] }} onChange={() => {}} toolId={3} />);
        expect(screen.getByText("Test")).toBeTruthy();
    });

    it("is fully read-only when readOnly", () => {
        const onChange = vi.fn();
        render(<ToolEditor readOnly value={{ name: "t", description: "d", parameters: [], scriptTemplate: "echo" }}
            onChange={onChange} />);
        expect((screen.getByDisplayValue("d") as HTMLTextAreaElement).disabled).toBe(true);
        expect(screen.queryByLabelText("Edit labels")).toBeNull();
        expect(screen.queryByText("AI Assistant")).toBeNull();
        fireEvent.click(screen.getByText("Script Template"));
        expect(screen.getByTestId("code-editor").getAttribute("data-readonly")).toBe("true");
        fireEvent.change(screen.getByDisplayValue("d"), { target: { value: "x" } });
        expect(onChange).not.toHaveBeenCalled();
    });

    it("shows given problems", () => {
        render(<ToolEditor value={{ name: "t", parameters: [] }} onChange={() => {}}
            problems={[{ severity: "error", field: "scriptTemplate", message: "required" }]} />);
        expect(screen.getByText(/Problems/)).toBeTruthy();
    });
});
