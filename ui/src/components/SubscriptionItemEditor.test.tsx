import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { SubscriptionItemEditor } from "./SubscriptionItemEditor";
import * as api from "../config/api";

vi.mock("../config/api", () => ({ previewSubscriptionFilter: vi.fn() }));

const base = { name: "on-pr", description: "", filterExpression: "event.type == 'pr.created'",
    routing: [{ type: "create-workflow", workflowDefinitionName: "wf" }] };

function renderEditor(over: Partial<Parameters<typeof SubscriptionItemEditor>[0]> = {}) {
    const props = { value: base, onChange: vi.fn(), workflowName: "wf", published: false, workflowPublished: false,
        enabled: false, onToggleEnabled: vi.fn(), ...over };
    render(<MemoryRouter><SubscriptionItemEditor {...props} /></MemoryRouter>);
    return props;
}

beforeEach(() => {
    vi.mocked(api.previewSubscriptionFilter).mockReset().mockResolvedValue({
        results: [
            { matched: true, event: { id: "e1", type: "pr.created", ref: "https://x/pull/1", timestamp: "2026-10-15T10:00:00Z" } },
            { matched: false, event: { id: "e2", type: "issue.created", ref: "https://x/issues/2", timestamp: "2026-10-15T09:00:00Z" } },
        ], totalMatched: 1, totalCount: 2, page: 1, limit: 200, errorCount: 0,
    } as never);
});

describe("SubscriptionItemEditor", () => {
    it("shows the fields and that start rules target the composition's workflow", () => {
        renderEditor();
        expect(screen.getByLabelText("Name")).toHaveValue("on-pr");
        expect(screen.getByLabelText("Filter expression")).toHaveValue("event.type == 'pr.created'");
        expect(screen.getByText(/Starts workflow/)).toHaveTextContent("Starts workflow wf");
    });

    it("previews matching recent events", async () => {
        renderEditor();
        expect(await screen.findByText("1 of 2 recent events match")).toBeInTheDocument();
        expect(screen.getByRole("link", { name: /https:\/\/x\/pull\/1/ })).toHaveAttribute("href", "/events/stream/e1");
        expect(api.previewSubscriptionFilter).toHaveBeenCalledWith({ filterExpression: base.filterExpression, limit: 200 });
    });

    it("adds a dispatch rule and a start rule bound to the workflow", async () => {
        const props = renderEditor({ value: { ...base, routing: [] } });
        await userEvent.click(screen.getByRole("button", { name: "Add start rule" }));
        expect(props.onChange).toHaveBeenLastCalledWith(expect.objectContaining({
            routing: [{ type: "create-workflow", workflowDefinitionName: "wf" }] }));
        await userEvent.click(screen.getByRole("button", { name: "Add dispatch rule" }));
        expect(props.onChange).toHaveBeenCalledWith(expect.objectContaining({
            routing: [{ type: "workflow-dispatch" }] }));
    });

    it("hides the Enabled switch until published and disables it while the workflow is unpublished", () => {
        renderEditor();
        expect(screen.queryByRole("switch", { name: "Enabled" })).toBeNull();
    });

    it("does not crash when routing is not an array", () => {
        renderEditor({ value: { ...base, routing: {} } });
        expect(screen.getByLabelText("Name")).toHaveValue("on-pr");
        expect(screen.queryByRole("button", { name: /Remove rule/ })).toBeNull();
    });

    it("disables the Enabled switch while a toggle is saving", () => {
        renderEditor({ published: true, workflowPublished: true, enabledBusy: true });
        expect(screen.getByRole("switch", { name: "Enabled" })).toBeDisabled();
    });

    it("toggles enabled when published", async () => {
        const props = renderEditor({ published: true, workflowPublished: true });
        await userEvent.click(screen.getByRole("switch", { name: "Enabled" }));
        expect(props.onToggleEnabled).toHaveBeenCalledWith(true);
    });

    it("keeps the switch disabled while the workflow is unpublished", () => {
        renderEditor({ published: true, workflowPublished: false });
        expect(screen.getByRole("switch", { name: "Enabled" })).toBeDisabled();
    });
});
