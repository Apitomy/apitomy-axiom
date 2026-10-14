import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import * as api from "../../config/api";
import { TestRunDialog } from "./TestRunDialog";

vi.mock("@patternfly/react-core", async (orig) => {
    const actual: Record<string, unknown> = await orig();
    const React = await import("react");
    const RealModal = actual.Modal as React.ComponentType<Record<string, unknown>>;
    // jsdom has no layout, so focus-trap rejects the selector target; strip it.
    return { ...actual, Modal: (p: Record<string, unknown>) => {
        const { elementToFocus: _ignored, ...rest } = p;
        return React.createElement(RealModal, rest);
    } };
});

beforeEach(() => {
    vi.spyOn(api, "fetchSubjects").mockResolvedValue({ items: [{ id: 5, name: "repo-x" }], count: 1 } as never);
});

const content = { executionMode: "script", inputs: [
    { name: "who", type: "string", required: true }, { name: "times", type: "number", required: false }] };

describe("TestRunDialog", () => {
    it("runs with parsed inputs and the raw values", () => {
        const onRun = vi.fn();
        render(<TestRunDialog itemName="greet" itemType="action-type" content={content} initialValues={{ who: "ann" }}
            onRun={onRun} onClose={vi.fn()} />);
        fireEvent.change(screen.getByLabelText("times"), { target: { value: "3" } });
        fireEvent.click(screen.getByRole("button", { name: "Run" }));
        expect(onRun).toHaveBeenCalledWith({ who: "ann", times: 3 }, { who: "ann", times: "3" }, undefined);
    });

    it("blocks a run with a missing required input or an invalid value", () => {
        const onRun = vi.fn();
        render(<TestRunDialog itemName="greet" itemType="action-type" content={content} initialValues={{}}
            onRun={onRun} onClose={vi.fn()} />);
        fireEvent.change(screen.getByLabelText("times"), { target: { value: "x" } });
        fireEvent.click(screen.getByRole("button", { name: "Run" }));
        expect(onRun).not.toHaveBeenCalled();
        expect(screen.getByText("Required.")).toBeTruthy();
        expect(screen.getByText("Enter a number.")).toBeTruthy();
    });

    it("explains that agent action types cannot run", () => {
        render(<TestRunDialog itemName="a" itemType="action-type" content={{ executionMode: "agent" }}
            initialValues={{}} onRun={vi.fn()} onClose={vi.fn()} />);
        expect(screen.getByText("Test runs of AI Agent action types are not supported yet.")).toBeTruthy();
        expect(screen.queryByRole("button", { name: "Run" })).toBeNull();
    });

    it("invites the user to review an AI suggestion", () => {
        render(<TestRunDialog itemName="greet" itemType="action-type" content={content} initialValues={{ who: "ai" }}
            isSuggestion onRun={vi.fn()} onClose={vi.fn()} />);
        expect(screen.getByText("Suggested by AI")).toBeTruthy();
        expect((screen.getByLabelText("who") as HTMLInputElement).value).toBe("ai");
    });
});
