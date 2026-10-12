import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ActionTypeEditor } from "./ActionTypeEditor";

vi.mock("@patternfly/react-code-editor", () => ({
    CodeEditor: ({ code, isReadOnly }: { code: string; isReadOnly?: boolean }) =>
        <pre data-testid="code-editor" data-readonly={String(!!isReadOnly)}>{code}</pre>,
    Language: { json: "json", javascript: "javascript", shell: "shell", markdown: "markdown" },
}));
vi.mock("../config/api", async (orig) => ({
    ...(await orig<typeof import("../config/api")>()),
    fetchEngines: vi.fn().mockResolvedValue([]),
    fetchModels: vi.fn().mockResolvedValue([]),
    validateActionType: vi.fn().mockResolvedValue({ valid: true, messages: [] }),
}));

describe("ActionTypeEditor", () => {
    it("is controlled: edits call onChange with the whole value", () => {
        const onChange = vi.fn();
        render(<ActionTypeEditor value={{ name: "at", description: "old", executionMode: "node", inputs: [], outputs: [] }}
            onChange={onChange} />);
        fireEvent.change(screen.getByDisplayValue("old"), { target: { value: "new" } });
        expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ name: "at", description: "new", executionMode: "node" }));
    });

    it("shows the Code tab for node action types and disables inputs when read-only", () => {
        render(<ActionTypeEditor readOnly value={{ name: "at", description: "d", executionMode: "node",
            codeTemplate: "export async function run() { return {}; }", inputs: [], outputs: [] }} onChange={() => {}} />);
        expect(screen.getByText("Code")).toBeTruthy();
        expect((screen.getByDisplayValue("d") as HTMLInputElement).disabled || (screen.getByDisplayValue("d") as HTMLInputElement).readOnly).toBe(true);
    });

    it("makes the code editor read-only and hides label editing when read-only", () => {
        render(<ActionTypeEditor readOnly value={{ name: "at", executionMode: "node", codeTemplate: "x",
            inputs: [], outputs: [] }} onChange={() => {}} />);
        fireEvent.click(screen.getByText("Code"));
        expect(screen.getByTestId("code-editor").getAttribute("data-readonly")).toBe("true");
        expect(screen.queryByLabelText("Edit labels")).toBeNull();
    });

    it("shows given problems", () => {
        render(<ActionTypeEditor value={{ name: "at", executionMode: "node", inputs: [], outputs: [] }} onChange={() => {}}
            problems={[{ severity: "error", field: "codeTemplate", message: "codeTemplate is required" }]} />);
        expect(screen.getByText(/Problems/)).toBeTruthy();
    });
});
