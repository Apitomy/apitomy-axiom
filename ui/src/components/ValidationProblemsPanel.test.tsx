import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ValidationProblemsPanel } from "./ValidationProblemsPanel";

const messages = [{ severity: "error", field: "codeTemplate", message: "codeTemplate is required" }] as never;

describe("ValidationProblemsPanel", () => {
    it("has no Ask AI action unless a handler is given", () => {
        render(<ValidationProblemsPanel messages={messages} />);
        expect(screen.queryByRole("button", { name: /ask ai/i })).toBeNull();
    });

    it("asks the AI about a problem, and can be disabled", () => {
        const onAskAi = vi.fn();
        const { rerender } = render(<ValidationProblemsPanel messages={messages} onAskAi={onAskAi} />);
        fireEvent.click(screen.getByRole("button", { name: "Ask AI to fix this" }));
        expect(onAskAi).toHaveBeenCalledWith(expect.objectContaining({ field: "codeTemplate" }));
        rerender(<ValidationProblemsPanel messages={messages} onAskAi={onAskAi} askAiDisabled />);
        expect((screen.getByRole("button", { name: "Ask AI to fix this" }) as HTMLButtonElement).disabled).toBe(true);
    });
});
